package com.overlord.renderer.ui;

import java.util.Objects;

public record BitmapGlyph(
        int codePoint,
        UiUvRect uv,
        int advance,
        int bearingX,
        int bearingY) {
    public BitmapGlyph {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException("glyph code point must be valid Unicode");
        }
        Objects.requireNonNull(uv, "uv");
        if (advance < 0) {
            throw new IllegalArgumentException("glyph advance must not be negative");
        }
    }
}
