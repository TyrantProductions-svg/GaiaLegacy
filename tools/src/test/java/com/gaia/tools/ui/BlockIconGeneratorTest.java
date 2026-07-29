package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

class BlockIconGeneratorTest {
    private static final int FIXTURE_TOP = 0xffe62814;
    private static final int FIXTURE_NORTH = 0xff14dc28;
    private static final int FIXTURE_EAST = 0xff1e3cf0;
    private static final List<String> REQUIRED_IDS = List.of(
            "gaia:grass", "gaia:dirt", "gaia:stone", "gaia:oak_log",
            "gaia:oak_leaves", "gaia:missing");

    @Test
    void canonicalCatalogProducesDeterministicGridMetadataAndPixels() throws Exception {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        BlockIconGenerator.GeneratedIcons first = new BlockIconGenerator().generate(assets);
        BlockIconGenerator.GeneratedIcons second = new BlockIconGenerator().generate(assets);

        assertArrayEquals(first.png(), second.png());
        assertArrayEquals(first.json(), second.json());
        assertEquals("f2748a3ba40e426c67855fa420f50607a6e7bc94c9415e22636d398cdfed8c41",
                sha256(first.png()));
        assertEquals("5cefdab102a062ebbf3fbd8a3bb1785bd22fcf8202c9eb2fd0f3ac49533015c5",
                sha256(first.json()));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first.png()));
        assertEquals(128, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(4, image.getColorModel().getNumComponents());

        JsonObject root = JsonParser.parseString(
                new String(first.json(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertEquals(128, root.getAsJsonObject("atlas").get("width").getAsInt());
        assertEquals(64, root.getAsJsonObject("atlas").get("height").getAsInt());
        assertEquals(32, root.getAsJsonObject("cell").get("width").getAsInt());
        assertEquals(32, root.getAsJsonObject("cell").get("height").getAsInt());
        assertEquals(100, root.getAsJsonObject("faceLight").get("top").getAsInt());
        assertEquals(82, root.getAsJsonObject("faceLight").get("north").getAsInt());
        assertEquals(68, root.getAsJsonObject("faceLight").get("east").getAsInt());

        JsonArray icons = root.getAsJsonArray("icons");
        assertEquals(REQUIRED_IDS, icons.asList().stream()
                .map(element -> element.getAsJsonObject().get("itemId").getAsString())
                .toList());
        assertEquals(List.of("Grass", "Dirt", "Stone", "Oak Log", "Oak Leaves", "Missing"),
                icons.asList().stream()
                        .map(element -> element.getAsJsonObject().get("displayName").getAsString())
                        .toList());
        assertEquals(1, icons.asList().stream()
                .filter(element -> element.getAsJsonObject().get("fallback").getAsBoolean())
                .count());
        assertEquals("gaia:missing", icons.get(5).getAsJsonObject().get("itemId").getAsString());

        JsonArray unassigned = root.getAsJsonArray("unassignedCells");
        assertEquals(2, unassigned.size());
        assertEquals(2, unassigned.get(0).getAsJsonObject().get("column").getAsInt());
        assertEquals(1, unassigned.get(0).getAsJsonObject().get("row").getAsInt());
        assertEquals(3, unassigned.get(1).getAsJsonObject().get("column").getAsInt());
        assertEquals(1, unassigned.get(1).getAsJsonObject().get("row").getAsInt());

        Set<String> cellHashes = new HashSet<>();
        List<String> expectedCellHashes = List.of(
                "7197cedbc14939bf0436ed736978a8b94333079318204ea129d2e0cc90455e93",
                "169b1a3cda43883fdcd2e03f59415800cb2eb8e0473b3826334a7db2ab9cb4ae",
                "595ffdc1065c42ad2f7bf1b0c83df707085000ab558027d3db5069f91cabad6a",
                "48eea7c6f67198e2eb29672d5de3d0183f1dc020379830d6e43fcaff4b8349cd",
                "07ecb48a53b6e3f75dc3d6fc9455071482c50ab69ea94e82dc1c0a53e2bc0ef6",
                "2c025b2b3c6d68bea799d5a938b841c0d204fae616a436167a65409c2e6af56e");
        for (int index = 0; index < 6; index++) {
            String hash = cellHash(image, index);
            assertEquals(expectedCellHashes.get(index), hash);
            cellHashes.add(hash);
        }
        assertEquals(6, cellHashes.size());
        assertNotEquals(cellHash(image, 0), cellHash(image, 5));
        assertEquals(blankCellHash(), cellHash(image, 6));
        assertEquals(blankCellHash(), cellHash(image, 7));
    }

    @Test
    void isometricFacesUseAuthoritativeRegionsAndFixedLighting() throws Exception {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        BufferedImage icons = ImageIO.read(new ByteArrayInputStream(
                new BlockIconGenerator().generate(assets).png()));
        BufferedImage blocks;
        try (InputStream input = assets.open(
                ResourceLocation.parse("gaia:textures/atlas.png"))) {
            blocks = ImageIO.read(input);
        }

        // These three interior pixels map to literal nearest-neighbour samples
        // from grass UP, NORTH, and EAST respectively.
        assertEquals(blocks.getRGB(9, 6), icons.getRGB(16, 8));
        assertEquals(shade(blocks.getRGB(24, 6), 82), icons.getRGB(9, 18));
        assertEquals(shade(blocks.getRGB(24, 6), 68), icons.getRGB(23, 18));
    }

    @Test
    void isolatedCanonicalResourcePackDistinguishesAllThreeAuthoritativeFaces(
            @TempDir Path temporary) throws Exception {
        try (URLClassLoader loader = fixtureLoader(temporary, "canonical", false)) {
            BufferedImage generated = generatedImage(loader);
            assertFixtureFaceSamples(generated);
        }
        try (URLClassLoader loader = fixtureLoader(temporary, "swapped", true)) {
            BufferedImage swapped = generatedImage(loader);
            AssertionFailedError killedMutation = assertThrows(
                    AssertionFailedError.class,
                    () -> assertFixtureFaceSamples(swapped));
            assertTrue(killedMutation.getMessage().contains("NORTH sample"));
            assertEquals(shade(FIXTURE_EAST, 82), swapped.getRGB(9, 18));
            assertEquals(shade(FIXTURE_NORTH, 68), swapped.getRGB(23, 18));
        }
    }

    @Test
    void transparencyHasWhiteNonPollutingRgbAndMissingIsNotARealBlockIcon() throws Exception {
        BlockIconGenerator.GeneratedIcons generated = new BlockIconGenerator().generate(
                new AssetManager(getClass().getClassLoader()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(generated.png()));

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0) {
                    assertEquals(0x00ffffff, argb);
                }
            }
        }
        assertFalse(cellHash(image, 5).equals(cellHash(image, 0)));
        assertTrue(hasOpaquePixel(image, 5));
    }

    private static int shade(int argb, int percent) {
        int alpha = argb >>> 24;
        int red = ((argb >>> 16) & 0xff) * percent / 100;
        int green = ((argb >>> 8) & 0xff) * percent / 100;
        int blue = (argb & 0xff) * percent / 100;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static void assertFixtureFaceSamples(BufferedImage generated) {
        assertEquals(FIXTURE_TOP, generated.getRGB(16, 8), "UP sample");
        assertEquals(shade(FIXTURE_NORTH, 82), generated.getRGB(9, 18), "NORTH sample");
        assertEquals(shade(FIXTURE_EAST, 68), generated.getRGB(23, 18), "EAST sample");
    }

    private static BufferedImage generatedImage(ClassLoader loader) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(
                new BlockIconGenerator().generate(new AssetManager(loader)).png()));
    }

    private URLClassLoader fixtureLoader(
            Path temporary, String name, boolean swapNorthEast) throws Exception {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        List<String> paths = List.of(
                "META-INF/gaialegacy/resource-indexes.list",
                "assets/gaia/resource-index.json",
                "assets/gaia/blocks/air.json",
                "assets/gaia/blocks/grass.json",
                "assets/gaia/blocks/dirt.json",
                "assets/gaia/blocks/stone.json",
                "assets/gaia/blocks/oak_log.json",
                "assets/gaia/blocks/oak_leaves.json",
                "assets/gaia/materials/opaque.json",
                "assets/gaia/materials/missing.json",
                "assets/gaia/atlases/blocks.json",
                "assets/gaia/textures/atlas.png",
                "assets/gaia/textures/effects/block_damage.png");
        for (String path : paths) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                resources.put(path, input.readAllBytes());
            }
        }

        JsonObject atlas = JsonParser.parseString(new String(
                resources.get("assets/gaia/atlases/blocks.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject regions = atlas.getAsJsonObject("regions");
        addRegion(regions, "gaia:fixture_up", 0, 32);
        addRegion(regions, "gaia:fixture_north", 16, 32);
        addRegion(regions, "gaia:fixture_east", 32, 32);
        resources.put("assets/gaia/atlases/blocks.json",
                atlas.toString().getBytes(StandardCharsets.UTF_8));

        JsonObject grass = JsonParser.parseString(new String(
                resources.get("assets/gaia/blocks/grass.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject textures = grass.getAsJsonObject("textures");
        textures.addProperty("top", "gaia:fixture_up");
        textures.addProperty("north", swapNorthEast
                ? "gaia:fixture_east" : "gaia:fixture_north");
        textures.addProperty("east", swapNorthEast
                ? "gaia:fixture_north" : "gaia:fixture_east");
        resources.put("assets/gaia/blocks/grass.json",
                grass.toString().getBytes(StandardCharsets.UTF_8));

        BufferedImage source;
        try (InputStream input = new ByteArrayInputStream(
                resources.get("assets/gaia/textures/atlas.png"))) {
            source = ImageIO.read(input);
        }
        fill(source, 0, 32, FIXTURE_TOP);
        fill(source, 16, 32, FIXTURE_NORTH);
        fill(source, 32, 32, FIXTURE_EAST);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));
        resources.put("assets/gaia/textures/atlas.png", png.toByteArray());

        Path jar = temporary.resolve(name + ".jar");
        writeJar(jar, resources);
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    private static void addRegion(JsonObject regions, String id, int x, int y) {
        JsonObject region = new JsonObject();
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("width", 16);
        region.addProperty("height", 16);
        regions.add(id, region);
    }

    private static void fill(BufferedImage image, int originX, int originY, int argb) {
        for (int y = originY; y < originY + 16; y++) {
            for (int x = originX; x < originX + 16; x++) {
                image.setRGB(x, y, argb);
            }
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

    private static boolean hasOpaquePixel(BufferedImage image, int cell) {
        int originX = cell % 4 * 32;
        int originY = cell / 4 * 32;
        for (int y = originY; y < originY + 32; y++) {
            for (int x = originX; x < originX + 32; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String blankCellHash() throws Exception {
        byte[] rgba = new byte[32 * 32 * 4];
        for (int offset = 0; offset < rgba.length; offset += 4) {
            rgba[offset] = (byte) 0xff;
            rgba[offset + 1] = (byte) 0xff;
            rgba[offset + 2] = (byte) 0xff;
        }
        return sha256(rgba);
    }

    private static String cellHash(BufferedImage image, int cell) throws Exception {
        byte[] rgba = new byte[32 * 32 * 4];
        int originX = cell % 4 * 32;
        int originY = cell / 4 * 32;
        int offset = 0;
        for (int y = originY; y < originY + 32; y++) {
            for (int x = originX; x < originX + 32; x++) {
                int argb = image.getRGB(x, y);
                rgba[offset++] = (byte) (argb >>> 16);
                rgba[offset++] = (byte) (argb >>> 8);
                rgba[offset++] = (byte) argb;
                rgba[offset++] = (byte) (argb >>> 24);
            }
        }
        return sha256(rgba);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
