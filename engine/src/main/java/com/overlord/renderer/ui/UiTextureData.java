package com.overlord.renderer.ui;

import java.nio.ByteBuffer;
import java.util.Objects;

public record UiTextureData(
        int width,
        int height,
        ByteBuffer rgba,
        UiTextureSampling sampling) {
    public UiTextureData(int width, int height, ByteBuffer rgba) {
        this(width, height, rgba, UiTextureSampling.NEAREST);
    }

    public UiTextureData {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("UI texture dimensions must be positive");
        }
        Objects.requireNonNull(rgba, "rgba");
        Objects.requireNonNull(sampling, "sampling");

        long expectedBytes;
        try {
            expectedBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("UI texture RGBA byte count overflows", exception);
        }
        if (expectedBytes > Integer.MAX_VALUE || rgba.remaining() != (int) expectedBytes) {
            throw new IllegalArgumentException(
                    "UI texture must contain exactly width * height * 4 RGBA bytes");
        }

        ByteBuffer copy = ByteBuffer.allocateDirect((int) expectedBytes);
        copy.put(rgba.duplicate());
        copy.flip();
        rgba = copy.asReadOnlyBuffer();
    }

    @Override
    public ByteBuffer rgba() {
        return rgba.asReadOnlyBuffer();
    }
}
