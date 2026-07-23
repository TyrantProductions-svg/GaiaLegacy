package com.overlord.renderer.texture;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.BufferUtils;

public record TextureImage(
        int width, int height, ByteBuffer rgbaPixels) {
    private static final byte[] MISSING_RGBA = {
        (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF,
        0x00, 0x00, 0x00, (byte) 0xFF,
        0x00, 0x00, 0x00, (byte) 0xFF,
        (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF
    };

    public TextureImage {
        Objects.requireNonNull(rgbaPixels, "rgbaPixels");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive");
        }
        long pixelCount = (long) width * height;
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException(
                    "Texture dimensions exceed supported buffer size");
        }
        int expectedBytes = (int) (pixelCount * 4L);
        if (!rgbaPixels.isDirect()) {
            throw new IllegalArgumentException(
                    "Texture pixels must use a direct buffer");
        }
        if (rgbaPixels.remaining() != expectedBytes) {
            throw new IllegalArgumentException(
                    "Texture pixel buffer must contain exactly "
                            + expectedBytes
                            + " remaining RGBA bytes");
        }
        ByteBuffer owned = BufferUtils.createByteBuffer(expectedBytes);
        owned.put(rgbaPixels.slice()).flip();
        rgbaPixels = owned.asReadOnlyBuffer();
    }

    @Override
    public ByteBuffer rgbaPixels() {
        return rgbaPixels.asReadOnlyBuffer();
    }

    public static TextureImage missing() {
        ByteBuffer pixels =
                BufferUtils.createByteBuffer(MISSING_RGBA.length);
        pixels.put(MISSING_RGBA).flip();
        return new TextureImage(2, 2, pixels);
    }
}
