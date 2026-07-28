package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.texture.TextureRegion;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DamageAtlasResourceLoaderTest {
    private static final ResourceLocation DAMAGE_ATLAS =
            ResourceLocation.parse("gaia:textures/effects/block_damage.png");

    @TempDir Path temporaryDirectory;

    @Test
    void loadsExactTenStageHorizontalAtlasWithHalfTexelRegions() throws Exception {
        writePng(DAMAGE_ATLAS, 160, 16);
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        DamageAtlasLayout layout =
                new DamageAtlasResourceLoader()
                        .load(assetManager(), DAMAGE_ATLAS, diagnostics::add);

        assertEquals(10, layout.stageCount());
        assertEquals(160, layout.image().width());
        assertEquals(16, layout.image().height());
        assertEquals(0, diagnostics.size());
        for (int stage = 0; stage < 10; stage++) {
            TextureRegion region = layout.region(stage);
            assertEquals(stage * 16, region.x());
            assertEquals(0, region.y());
            assertEquals(16, region.width());
            assertEquals(16, region.height());
            assertEquals((stage * 16 + 0.5f) / 160.0f, region.uMin());
            assertEquals((stage * 16 + 15.5f) / 160.0f, region.uMax());
        }
        assertThrows(IllegalArgumentException.class, () -> layout.region(-1));
        assertThrows(IllegalArgumentException.class, () -> layout.region(10));
    }

    @Test
    void missingResourceReportsExactlyOnceAndUsesVisibleFallback() {
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        DamageAtlasLayout layout =
                new DamageAtlasResourceLoader()
                        .load(assetManager(), DAMAGE_ATLAS, diagnostics::add);

        assertEquals(1, diagnostics.size());
        assertEquals("DAMAGE_ATLAS_FALLBACK", diagnostics.get(0).code());
        assertEquals(DAMAGE_ATLAS, diagnostics.get(0).resource());
        assertEquals(
                "Damage atlas was missing or could not be decoded; cause: "
                        + "ASSET_TEXTURE_FALLBACK: Texture was missing or could not be decoded",
                diagnostics.get(0).message());
        assertEquals(
                ResourceLocation.parse("overlord:feedback/damage-atlas-fallback"),
                diagnostics.get(0).fallback());
        assertFallback(layout.image());
    }

    @Test
    void undecodableResourceReportsExactlyOnceAndUsesVisibleFallback() throws Exception {
        Path path = resourcePath(DAMAGE_ATLAS);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1, 2, 3, 4});
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        DamageAtlasLayout layout =
                new DamageAtlasResourceLoader()
                        .load(assetManager(), DAMAGE_ATLAS, diagnostics::add);

        assertEquals(1, diagnostics.size());
        assertEquals("DAMAGE_ATLAS_FALLBACK", diagnostics.get(0).code());
        assertEquals(DAMAGE_ATLAS, diagnostics.get(0).resource());
        assertEquals(
                "Damage atlas was missing or could not be decoded; cause: "
                        + "ASSET_TEXTURE_FALLBACK: Texture was missing or could not be decoded",
                diagnostics.get(0).message());
        assertEquals(
                ResourceLocation.parse("overlord:feedback/damage-atlas-fallback"),
                diagnostics.get(0).fallback());
        assertFallback(layout.image());
    }

    @Test
    @SuppressWarnings("deprecation")
    void unreadableResourceReportsExactlyOnceRetainsCauseAndUsesVisibleFallback()
            throws Exception {
        URL unreadableUrl =
                new URL(
                        null,
                        "memory:unreadable-damage-atlas",
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL url) {
                                return new URLConnection(url) {
                                    @Override
                                    public void connect() {}

                                    @Override
                                    public InputStream getInputStream() {
                                        return new InputStream() {
                                            @Override
                                            public int read() throws IOException {
                                                throw new IOException("unreadable damage atlas");
                                            }
                                        };
                                    }
                                };
                            }
                        });
        ClassLoader unreadableLoader =
                new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                    @Override
                    public Enumeration<URL> getResources(String name) {
                        assertEquals(DAMAGE_ATLAS.toClasspathPath(), name);
                        return Collections.enumeration(List.of(unreadableUrl));
                    }
                };
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        DamageAtlasLayout layout =
                new DamageAtlasResourceLoader()
                        .load(
                                new AssetManager(unreadableLoader),
                                DAMAGE_ATLAS,
                                diagnostics::add);

        assertEquals(1, diagnostics.size());
        AssetDiagnostic diagnostic = diagnostics.get(0);
        assertEquals("DAMAGE_ATLAS_FALLBACK", diagnostic.code());
        assertEquals(DAMAGE_ATLAS, diagnostic.resource());
        assertEquals(
                "Damage atlas was missing or could not be decoded; cause: "
                        + "ASSET_TEXTURE_FALLBACK: Texture was missing or could not be decoded",
                diagnostic.message());
        assertEquals(
                ResourceLocation.parse("overlord:feedback/damage-atlas-fallback"),
                diagnostic.fallback());
        assertFallback(layout.image());
    }

    @Test
    void wrongDimensionsReportExactlyOnceAndUseVisibleFallback() throws Exception {
        writePng(DAMAGE_ATLAS, 16, 16);
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        DamageAtlasLayout layout =
                new DamageAtlasResourceLoader()
                        .load(assetManager(), DAMAGE_ATLAS, diagnostics::add);

        assertEquals(1, diagnostics.size());
        assertEquals("DAMAGE_ATLAS_INVALID_DIMENSIONS", diagnostics.get(0).code());
        assertEquals(DAMAGE_ATLAS, diagnostics.get(0).resource());
        assertFallback(layout.image());
    }

    @Test
    void layoutRejectsInvalidShapeAndStageCount() {
        TextureImage valid = DamageAtlasResourceLoader.fallbackImage();
        assertThrows(IllegalArgumentException.class, () -> new DamageAtlasLayout(valid, 7));
        assertThrows(IllegalArgumentException.class, () -> new DamageAtlasLayout(valid, 11));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DamageAtlasLayout(TextureImage.missing(), 10));
    }

    private void assertFallback(TextureImage image) {
        assertEquals(160, image.width());
        assertEquals(16, image.height());
        ByteBuffer pixels = image.rgbaPixels();
        boolean black = false;
        boolean magenta = false;
        boolean transparent = false;
        while (pixels.hasRemaining()) {
            int red = pixels.get() & 0xff;
            int green = pixels.get() & 0xff;
            int blue = pixels.get() & 0xff;
            int alpha = pixels.get() & 0xff;
            black |= alpha == 255 && red == 0 && green == 0 && blue == 0;
            magenta |= alpha == 255 && red == 176 && green == 0 && blue == 176;
            transparent |= alpha == 0;
        }
        assertTrue(black);
        assertTrue(magenta);
        assertTrue(transparent);
    }

    private AssetManager assetManager() {
        try {
            URL root = temporaryDirectory.toUri().toURL();
            return new AssetManager(new URLClassLoader(new URL[] {root}, null));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void writePng(ResourceLocation location, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff112233);
            }
        }
        Path path = resourcePath(location);
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private Path resourcePath(ResourceLocation location) {
        return temporaryDirectory.resolve(location.toClasspathPath());
    }
}
