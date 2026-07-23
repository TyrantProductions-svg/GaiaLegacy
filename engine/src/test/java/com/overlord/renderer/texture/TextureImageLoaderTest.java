package com.overlord.renderer.texture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderAssets;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;

class TextureImageLoaderTest {
    private static final ResourceLocation LOCATION =
            ResourceLocation.parse("gaia:textures/test.png");
    private static final byte[] ONE_PIXEL_PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                    + "AAAADUlEQVR42mNk+M/wHwAF/gL+3MxZ5wAAAABJRU5ErkJggg==");

    @TempDir Path temp;

    @Test
    void createsPurpleBlackFallbackWhenImageIsMissing() {
        AssetManager assets =
                new AssetManager(ClassLoader.getPlatformClassLoader());
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        TextureImage image =
                new TextureImageLoader()
                        .load(
                                assets,
                                ResourceLocation.parse(
                                        "gaia:textures/missing.png"),
                                diagnostics::add);

        assertEquals(2, image.width());
        assertEquals(2, image.height());
        assertEquals(
                "ASSET_TEXTURE_FALLBACK",
                diagnostics.get(0).code());
        AssetDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(AssetSeverity.WARNING, diagnostic.severity());
        assertEquals(
                "assets/gaia/textures/missing.png",
                diagnostic.source());
        assertEquals(
                ResourceLocation.parse("gaia:textures/missing.png"),
                diagnostic.resource());
        assertEquals("texture", diagnostic.field());
        assertEquals(
                "Texture was missing or could not be decoded",
                diagnostic.message());
        assertEquals(
                ResourceLocation.parse("gaia:missing"),
                diagnostic.fallback());
        assertEquals(16, image.rgbaPixels().remaining());
        assertArrayEquals(
                new byte[] {
                    (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF,
                    0x00, 0x00, 0x00, (byte) 0xFF,
                    0x00, 0x00, 0x00, (byte) 0xFF,
                    (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF
                },
                bytes(image.rgbaPixels()));
    }

    @Test
    void decodesImageIntoOwnedDirectReadOnlyPixels() throws Exception {
        Path root = writeAsset(temp.resolve("root"), ONE_PIXEL_PNG);
        List<AssetDiagnostic> diagnostics = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {root.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            TextureImage image =
                    new TextureImageLoader()
                            .load(
                                    new AssetManager(loader),
                                    LOCATION,
                                    diagnostics::add);

            assertEquals(1, image.width());
            assertEquals(1, image.height());
            assertTrue(image.rgbaPixels().isDirect());
            assertTrue(image.rgbaPixels().isReadOnly());
            assertEquals(0, image.rgbaPixels().position());
            assertEquals(4, image.rgbaPixels().remaining());
            assertArrayEquals(
                    new byte[] {
                        0x00, (byte) 0xFF, 0x00, (byte) 0xFF
                    },
                    bytes(image.rgbaPixels()));
            assertTrue(diagnostics.isEmpty());
        }
    }

    @Test
    void returnsFreshReadOnlyViewsWithoutChangingInputPosition() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(10);
        pixels.position(2);
        pixels.put(new byte[8]);
        pixels.flip();
        pixels.position(2);

        TextureImage image = new TextureImage(1, 2, pixels);
        ByteBuffer first = image.rgbaPixels();
        first.position(first.limit());

        assertEquals(2, pixels.position());
        assertTrue(first.isReadOnly());
        assertTrue(first.isDirect());
        assertEquals(0, image.rgbaPixels().position());
        assertEquals(8, image.rgbaPixels().remaining());
        assertThrows(
                java.nio.ReadOnlyBufferException.class,
                () -> image.rgbaPixels().put(0, (byte) 1));
    }

    @Test
    void copiesPixelsAwayFromRetainedWritableInput() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(6);
        pixels.put(
                new byte[] {
                    9, 9, 1, 2, 3, 4
                });
        pixels.flip();
        pixels.position(2);

        TextureImage image = new TextureImage(1, 1, pixels);
        pixels.put(2, (byte) 99);

        assertEquals(2, pixels.position());
        assertArrayEquals(
                new byte[] {1, 2, 3, 4},
                bytes(image.rgbaPixels()));
    }

    @Test
    void rejectsInvalidPixelDimensionsAndStorage() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureImage(
                                0,
                                1,
                                BufferUtils.createByteBuffer(0)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureImage(
                                Integer.MAX_VALUE,
                                Integer.MAX_VALUE,
                                BufferUtils.createByteBuffer(4)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureImage(
                                1,
                                1,
                                ByteBuffer.allocate(4)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureImage(
                                1,
                                1,
                                BufferUtils.createByteBuffer(5)));
    }

    @Test
    void fallsBackWhenImageCannotBeDecoded() throws Exception {
        Path root =
                writeAsset(
                        temp.resolve("invalid"),
                        new byte[] {1, 2, 3, 4});
        List<AssetDiagnostic> diagnostics = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {root.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            TextureImage image =
                    new TextureImageLoader()
                            .load(
                                    new AssetManager(loader),
                                    LOCATION,
                                    diagnostics::add);

            assertEquals(TextureImage.missing(), image);
            assertEquals(1, diagnostics.size());
            assertEquals(
                    "ASSET_TEXTURE_FALLBACK",
                    diagnostics.get(0).code());
        }
    }

    @Test
    void rethrowsAmbiguousAssetOwnership() throws Exception {
        Path first = writeAsset(temp.resolve("first"), ONE_PIXEL_PNG);
        Path second = writeAsset(temp.resolve("second"), ONE_PIXEL_PNG);
        List<AssetDiagnostic> diagnostics = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {
                            first.toUri().toURL(),
                            second.toUri().toURL()
                        },
                        ClassLoader.getPlatformClassLoader())) {
            AssetLoadException exception =
                    assertThrows(
                            AssetLoadException.class,
                            () ->
                                    new TextureImageLoader()
                                            .load(
                                                    new AssetManager(loader),
                                                    LOCATION,
                                                    diagnostics::add));

            assertEquals(
                    "ASSET_AMBIGUOUS",
                    exception.report().errors().get(0).code());
            assertTrue(diagnostics.isEmpty());
        }
    }

    @Test
    void rethrowsAssetIoFailuresWithoutFallback() {
        ClassLoader broken =
                new ClassLoader(
                        ClassLoader.getPlatformClassLoader()) {
                    @Override
                    public Enumeration<URL> getResources(String name)
                            throws IOException {
                        throw new IOException("broken resource access");
                    }
                };
        List<AssetDiagnostic> diagnostics = new ArrayList<>();

        AssetLoadException exception =
                assertThrows(
                        AssetLoadException.class,
                        () ->
                                new TextureImageLoader()
                                        .load(
                                                new AssetManager(broken),
                                                LOCATION,
                                                diagnostics::add));

        assertEquals(
                "ASSET_IO",
                exception.report().errors().get(0).code());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void renderAssetsMissingUsesProceduralTexture() {
        RenderAssets assets = RenderAssets.missing();

        assertEquals(TextureImage.missing(), assets.blockAtlas());
    }

    private static Path writeAsset(Path root, byte[] content)
            throws Exception {
        Path path = root.resolve(LOCATION.toClasspathPath());
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        return root;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
