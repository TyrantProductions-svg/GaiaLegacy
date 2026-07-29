package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GaiaUiAssetLoaderTest {
    private static final List<String> PATHS = List.of(
            "assets/gaia/ui/ui-assets.json",
            "assets/gaia/ui/ui_font.png",
            "assets/gaia/ui/ui_font.json",
            "assets/gaia/ui/ui_icons.png",
            "assets/gaia/ui/ui_icons.json");

    @Test
    void loadsProductionImagesAndMetadataIntoImmutableRuntimeAssets() {
        GaiaUiAssets loaded = new GaiaUiAssetLoader(
                new AssetManager(getClass().getClassLoader())).load();

        assertEquals(128, loaded.renderAssets().icons().width());
        assertEquals(64, loaded.renderAssets().icons().height());
        assertEquals(128, loaded.renderAssets().font().width());
        assertEquals(64, loaded.renderAssets().font().height());
        assertTrue(loaded.renderAssets().icons().rgba().isReadOnly());
        assertTrue(loaded.renderAssets().font().rgba().isReadOnly());
        assertEquals(97, loaded.renderAssets().glyphs().glyphs().size());
        assertSame(loaded.renderAssets().glyphs().missingGlyph(),
                loaded.renderAssets().glyphs().glyph(0x1f642));

        assertEquals(List.of(
                        "gaia:grass", "gaia:dirt", "gaia:stone", "gaia:oak_log",
                        "gaia:oak_leaves", "gaia:missing"),
                loaded.icons().icons().keySet().stream().map(Object::toString).toList());
        assertEquals("Grass", loaded.icons().icons()
                .get(ResourceLocation.parse("gaia:grass")).displayName());
        assertEquals("Missing", loaded.icons().fallback().displayName());
        assertEquals(List.of(6, 7), loaded.icons().unassignedCells());

        ByteBuffer firstView = loaded.renderAssets().icons().rgba();
        ByteBuffer secondView = loaded.renderAssets().icons().rgba();
        firstView.position(4);
        assertEquals(0, secondView.position());
        assertThrows(java.nio.ReadOnlyBufferException.class,
                () -> firstView.put(0, (byte) 0));
    }

    @Test
    void loadsFromAClasspathJarWithoutFilesystemPaths(@TempDir Path temporary) throws Exception {
        Map<String, byte[]> resources = productionResources();
        Path jar = temporary.resolve("ui-assets.jar");
        writeJar(jar, resources);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, null)) {
            GaiaUiAssets loaded = new GaiaUiAssetLoader(new AssetManager(loader)).load();
            assertEquals("Oak Leaves", loaded.icons().icons()
                    .get(ResourceLocation.parse("gaia:oak_leaves")).displayName());
            assertEquals(128 * 64 * 4, loaded.renderAssets().icons().rgba().remaining());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "assets/gaia/ui/ui-assets.json",
        "assets/gaia/ui/ui_font.png",
        "assets/gaia/ui/ui_font.json",
        "assets/gaia/ui/ui_icons.png",
        "assets/gaia/ui/ui_icons.json"
    })
    void missingUiResourceNamesTheExactClasspathEntry(
            String missingPath, @TempDir Path temporary) throws Exception {
        GaiaUiAssetLoadException failure = loadFailure(
                temporary, "missing", resources -> resources.remove(missingPath));

        assertTrue(failure.getMessage().contains(missingPath));
        AssetLoadException missing = assertInstanceOf(
                AssetLoadException.class, failure.getCause());
        assertEquals("ASSET_NOT_FOUND", missing.report().errors().get(0).code());
        assertEquals(missingPath,
                missing.report().errors().get(0).resource().toClasspathPath());
    }

    @Test
    void malformedJsonNamesTheExactClasspathPathAndRetainsCause(@TempDir Path temporary)
            throws Exception {
        GaiaUiAssetLoadException failure = loadFailure(
                temporary, "malformed", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.json",
                        "{ invalid".getBytes(StandardCharsets.UTF_8)));

        assertTrue(failure.getMessage().contains("assets/gaia/ui/ui_icons.json"));
        assertNotNull(failure.getCause());
    }

    @Test
    void rejectsDuplicateJsonFieldsInsteadOfSilentlyTakingTheLastValue(
            @TempDir Path temporary) throws Exception {
        GaiaUiAssetLoadException failure = loadFailure(
                temporary, "duplicate-field", resources -> {
                    String json = new String(
                            resources.get("assets/gaia/ui/ui_icons.json"),
                            StandardCharsets.UTF_8);
                    resources.put(
                            "assets/gaia/ui/ui_icons.json",
                            json.replace(
                                            "\"version\": 1,",
                                            "\"version\": 1, \"version\": 1,")
                                    .getBytes(StandardCharsets.UTF_8));
                });

        assertTrue(failure.getMessage().contains("assets/gaia/ui/ui_icons.json"));
        assertTrue(failure.getCause().getMessage().contains("duplicate field"));
    }

    @Test
    void rejectsDuplicateIconIds(@TempDir Path temporary) throws Exception {
        GaiaUiAssetLoadException failure = loadFailure(
                temporary, "duplicate", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_icons.json", root -> root.getAsJsonArray("icons")
                                .get(1).getAsJsonObject().addProperty("itemId", "gaia:grass")));

        assertTrue(failure.getMessage().contains("assets/gaia/ui/ui_icons.json"));
        assertTrue(failure.getCause().getMessage().contains("duplicate icon id"));
    }

    @Test
    void rejectsOverlappingAndOutOfBoundsIconRegions(@TempDir Path temporary) throws Exception {
        GaiaUiAssetLoadException overlap = loadFailure(
                temporary, "overlap", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_icons.json", root -> {
                            JsonObject second = root.getAsJsonArray("icons")
                                    .get(1).getAsJsonObject();
                            second.add("cell", root.getAsJsonArray("icons")
                                    .get(0).getAsJsonObject().get("cell").deepCopy());
                            second.add("region", root.getAsJsonArray("icons")
                                    .get(0).getAsJsonObject().get("region").deepCopy());
                        }));
        GaiaUiAssetLoadException bounds = loadFailure(
                temporary, "bounds", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_icons.json", root -> root.getAsJsonArray("icons")
                                .get(0).getAsJsonObject().getAsJsonObject("region")
                                .addProperty("x", 128)));

        assertTrue(overlap.getCause().getMessage().contains("overlap"));
        assertTrue(bounds.getCause().getMessage().contains("bounds"));
    }

    @Test
    void rejectsMissingFallbackAndIncorrectUnassignedCells(@TempDir Path temporary)
            throws Exception {
        GaiaUiAssetLoadException fallback = loadFailure(
                temporary, "fallback", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_icons.json", root -> root.getAsJsonArray("icons")
                                .get(5).getAsJsonObject().addProperty("fallback", false)));
        GaiaUiAssetLoadException cells = loadFailure(
                temporary, "cells", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_icons.json", root -> root.getAsJsonArray(
                                "unassignedCells").remove(1)));

        assertTrue(fallback.getCause().getMessage().contains("exactly one fallback"));
        assertTrue(cells.getCause().getMessage().contains("two unassigned"));
    }

    @Test
    void rejectsImageDimensionAndGlyphMetadataMismatch(@TempDir Path temporary)
            throws Exception {
        GaiaUiAssetLoadException dimensions = loadFailure(
                temporary, "dimensions", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", png(64, 64)));
        GaiaUiAssetLoadException glyph = loadFailure(
                temporary, "glyph", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui_font.json", root -> root
                                .getAsJsonObject("fallback").getAsJsonObject("region")
                                .addProperty("x", 8)));

        assertTrue(dimensions.getMessage().contains("assets/gaia/ui/ui_icons.png"));
        assertTrue(dimensions.getCause().getMessage().contains("128x64"));
        assertTrue(glyph.getMessage().contains("assets/gaia/ui/ui_font.json"));
        assertTrue(glyph.getCause().getMessage().contains("fallback region"));
    }

    @Test
    void rejectsSameSizeRgbIndexedAndSixteenBitPngSources(@TempDir Path temporary)
            throws Exception {
        byte[] rgb = png(128, 64, BufferedImage.TYPE_3BYTE_BGR);
        byte[] indexed = png(128, 64, BufferedImage.TYPE_BYTE_INDEXED);
        byte[] sixteenBit = png(128, 64, BufferedImage.TYPE_USHORT_GRAY);
        assertEquals(8, Byte.toUnsignedInt(rgb[24]));
        assertEquals(2, Byte.toUnsignedInt(rgb[25]));
        assertEquals(8, Byte.toUnsignedInt(indexed[24]));
        assertEquals(3, Byte.toUnsignedInt(indexed[25]));
        assertEquals(16, Byte.toUnsignedInt(sixteenBit[24]));

        GaiaUiAssetLoadException rgbFailure = loadFailure(
                temporary, "rgb", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", rgb));
        GaiaUiAssetLoadException indexedFailure = loadFailure(
                temporary, "indexed", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", indexed));
        GaiaUiAssetLoadException sixteenBitFailure = loadFailure(
                temporary, "sixteen-bit", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", sixteenBit));

        for (GaiaUiAssetLoadException failure :
                List.of(rgbFailure, indexedFailure, sixteenBitFailure)) {
            assertTrue(failure.getMessage().contains("assets/gaia/ui/ui_icons.png"));
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("8-bit RGBA"));
        }
    }

    @Test
    void rejectsBadSignatureAndTruncatedIhdrWithPathAndCause(@TempDir Path temporary)
            throws Exception {
        byte[] valid = png(128, 64);
        byte[] badSignature = valid.clone();
        badSignature[0] = 0;
        byte[] truncated = java.util.Arrays.copyOf(valid, 20);

        GaiaUiAssetLoadException signatureFailure = loadFailure(
                temporary, "bad-signature", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", badSignature));
        GaiaUiAssetLoadException truncatedFailure = loadFailure(
                temporary, "truncated-ihdr", resources -> resources.put(
                        "assets/gaia/ui/ui_icons.png", truncated));

        assertTrue(signatureFailure.getMessage().contains("assets/gaia/ui/ui_icons.png"));
        assertNotNull(signatureFailure.getCause());
        assertTrue(signatureFailure.getCause().getMessage().contains("PNG signature"));
        assertTrue(truncatedFailure.getMessage().contains("assets/gaia/ui/ui_icons.png"));
        assertNotNull(truncatedFailure.getCause());
        assertTrue(truncatedFailure.getCause().getMessage().contains("IHDR"));
    }

    @Test
    void rejectsRedirectedOrAbsoluteManifestPaths(@TempDir Path temporary) throws Exception {
        GaiaUiAssetLoadException failure = loadFailure(
                temporary, "redirect", resources -> mutateJson(resources,
                        "assets/gaia/ui/ui-assets.json", root -> root
                                .getAsJsonObject("icons").addProperty(
                                        "image", "other:absolute/path.png")));

        assertTrue(failure.getMessage().contains("assets/gaia/ui/ui-assets.json"));
        assertFalse(failure.getMessage().contains(temporary.toString()));
        assertTrue(failure.getCause().getMessage().contains("required path"));
    }

    private GaiaUiAssetLoadException loadFailure(
            Path temporary, String name, Consumer<Map<String, byte[]>> mutation)
            throws Exception {
        Map<String, byte[]> resources = productionResources();
        mutation.accept(resources);
        Path jar = temporary.resolve(name + ".jar");
        writeJar(jar, resources);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, null)) {
            return assertThrows(GaiaUiAssetLoadException.class,
                    () -> new GaiaUiAssetLoader(new AssetManager(loader)).load());
        }
    }

    private Map<String, byte[]> productionResources() throws IOException {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (String path : PATHS) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                resources.put(path, input.readAllBytes());
            }
        }
        return resources;
    }

    private static void mutateJson(
            Map<String, byte[]> resources, String path, Consumer<JsonObject> mutation) {
        JsonObject root = JsonParser.parseString(
                new String(resources.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
        mutation.accept(root);
        resources.put(path, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] png(int width, int height) {
        return png(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] png(int width, int height, int imageType) {
        try {
            BufferedImage image = new BufferedImage(width, height, imageType);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void writeJar(Path jar, Map<String, byte[]> resources) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
