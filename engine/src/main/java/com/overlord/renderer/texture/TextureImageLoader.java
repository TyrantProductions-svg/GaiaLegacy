package com.overlord.renderer.texture;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

public final class TextureImageLoader {
    public TextureImage load(
            AssetManager assets,
            ResourceLocation location,
            Consumer<AssetDiagnostic> diagnostics) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(diagnostics, "diagnostics");

        try (InputStream input = assets.open(location)) {
            byte[] bytes = input.readAllBytes();
            ByteBuffer encoded = BufferUtils.createByteBuffer(bytes.length);
            encoded.put(bytes).flip();

            IntBuffer width = BufferUtils.createIntBuffer(1);
            IntBuffer height = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);
            ByteBuffer decoded =
                    STBImage.stbi_load_from_memory(
                            encoded, width, height, channels, 4);
            if (decoded == null) {
                return fallback(location, diagnostics);
            }

            try {
                int imageWidth = width.get(0);
                int imageHeight = height.get(0);
                long pixelCount =
                        (long) imageWidth * imageHeight;
                if (imageWidth <= 0
                        || imageHeight <= 0
                        || pixelCount > Integer.MAX_VALUE / 4L) {
                    return fallback(location, diagnostics);
                }
                int pixelBytes = (int) (pixelCount * 4L);
                if (decoded.remaining() != pixelBytes) {
                    return fallback(location, diagnostics);
                }
                return new TextureImage(
                        imageWidth, imageHeight, decoded);
            } finally {
                STBImage.stbi_image_free(decoded);
            }
        } catch (AssetLoadException exception) {
            if (!onlyRecoverableErrors(exception)) {
                throw exception;
            }
            return fallback(location, diagnostics);
        } catch (IOException exception) {
            return fallback(location, diagnostics);
        }
    }

    private static boolean onlyRecoverableErrors(
            AssetLoadException exception) {
        return !exception.report().errors().isEmpty()
                && exception.report().errors().stream()
                        .allMatch(
                                diagnostic ->
                                        diagnostic.code()
                                                        .equals(
                                                                "ASSET_NOT_FOUND")
                                                || diagnostic.code()
                                                        .equals(
                                                                "ASSET_IO"));
    }

    private static TextureImage fallback(
            ResourceLocation location,
            Consumer<AssetDiagnostic> diagnostics) {
        diagnostics.accept(
                new AssetDiagnostic(
                        AssetSeverity.WARNING,
                        "ASSET_TEXTURE_FALLBACK",
                        location.toClasspathPath(),
                        location,
                        "texture",
                        "Texture was missing or could not be decoded",
                        ResourceLocation.of(
                                location.namespace(), "missing")));
        return TextureImage.missing();
    }
}
