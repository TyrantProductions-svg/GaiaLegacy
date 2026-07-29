package com.overlord.renderer.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class BitmapFont {
    private final int atlasWidth;
    private final int atlasHeight;
    private final Map<Integer, BitmapGlyph> glyphs;
    private final BitmapGlyph missingGlyph;

    public BitmapFont(
            int atlasWidth,
            int atlasHeight,
            Map<Integer, BitmapGlyph> glyphs,
            BitmapGlyph missingGlyph) {
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalArgumentException("font atlas dimensions must be positive");
        }
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.missingGlyph = Objects.requireNonNull(missingGlyph, "missingGlyph");

        Objects.requireNonNull(glyphs, "glyphs");
        Map<Integer, BitmapGlyph> copiedGlyphs = new LinkedHashMap<>();
        glyphs.forEach((codePoint, glyph) -> {
            Objects.requireNonNull(codePoint, "glyph code point");
            Objects.requireNonNull(glyph, "glyph");
            if (!Character.isValidCodePoint(codePoint)) {
                throw new IllegalArgumentException("font map contains an invalid Unicode code point");
            }
            if (codePoint != glyph.codePoint()) {
                throw new IllegalArgumentException("font map key must match the glyph code point");
            }
            copiedGlyphs.put(codePoint, glyph);
        });
        this.glyphs = Collections.unmodifiableMap(copiedGlyphs);
    }

    public int atlasWidth() {
        return atlasWidth;
    }

    public int atlasHeight() {
        return atlasHeight;
    }

    public Map<Integer, BitmapGlyph> glyphs() {
        return glyphs;
    }

    public BitmapGlyph missingGlyph() {
        return missingGlyph;
    }

    public BitmapGlyph glyph(int codePoint) {
        return glyphs.getOrDefault(codePoint, missingGlyph);
    }
}
