package com.overlord.renderer.ui;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

public final class TextRenderer {
    private static final String ELLIPSIS = "...";

    private final BitmapFont font;
    private final IntConsumer missingGlyphDiagnostic;
    private final Set<Integer> diagnosedCodePoints = ConcurrentHashMap.newKeySet();

    public TextRenderer(BitmapFont font) {
        this(font, ignored -> {});
    }

    public TextRenderer(BitmapFont font, IntConsumer missingGlyphDiagnostic) {
        this.font = Objects.requireNonNull(font, "font");
        this.missingGlyphDiagnostic = Objects.requireNonNull(
                missingGlyphDiagnostic, "missingGlyphDiagnostic");
    }

    public double measure(String text, double scale) {
        Objects.requireNonNull(text, "text");
        requireScale(scale);

        double width = text.codePoints()
                .mapToDouble(codePoint -> glyph(codePoint).advance())
                .sum() * scale;
        if (!Double.isFinite(width)) {
            throw new IllegalArgumentException("measured text width must be finite");
        }
        return width;
    }

    public void append(
            String text,
            double x,
            double baselineY,
            double scale,
            UiColor color,
            Optional<UiRect> clip,
            UiDrawList out) {
        append(text, x, baselineY, scale, scale, color, clip, out);
    }

    public void append(
            String text,
            double x,
            double baselineY,
            double scaleX,
            double scaleY,
            UiColor color,
            Optional<UiRect> clip,
            UiDrawList out) {
        Objects.requireNonNull(text, "text");
        requireFinite(x, "text x origin");
        requireFinite(baselineY, "text baseline");
        requireScale(scaleX);
        requireScale(scaleY);
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(clip, "clip");
        Objects.requireNonNull(out, "out");

        double penX = x;
        int[] codePoints = text.codePoints().toArray();
        for (int codePoint : codePoints) {
            BitmapGlyph glyph = glyph(codePoint);
            double glyphWidth = (glyph.uv().right() - glyph.uv().left()) * font.atlasWidth();
            double glyphHeight = (glyph.uv().bottom() - glyph.uv().top()) * font.atlasHeight();
            UiRect bounds = new UiRect(
                    snap(penX + glyph.bearingX() * scaleX),
                    snap(baselineY - glyph.bearingY() * scaleY),
                    snap(penX + (glyph.bearingX() + glyphWidth) * scaleX),
                    snap(baselineY + (glyphHeight - glyph.bearingY()) * scaleY));
            out.append(new UiDrawCommand(
                    UiTextureId.FONT_ATLAS,
                    bounds,
                    glyph.uv(),
                    color,
                    clip));
            penX += glyph.advance() * scaleX;
            requireFinite(penX, "text pen position");
        }
    }

    public String truncateToFit(String text, double scale, double maxWidth) {
        Objects.requireNonNull(text, "text");
        requireScale(scale);
        if (!Double.isFinite(maxWidth) || maxWidth < 0.0d) {
            throw new IllegalArgumentException("maximum text width must be finite and non-negative");
        }
        if (measure(text, scale) <= maxWidth) {
            return text;
        }

        double ellipsisWidth = measure(ELLIPSIS, scale);
        if (ellipsisWidth > maxWidth) {
            throw new IllegalArgumentException("maximum text width cannot fit an ASCII ellipsis");
        }

        int prefixEnd = 0;
        double prefixWidth = 0.0d;
        while (prefixEnd < text.length()) {
            int codePoint = text.codePointAt(prefixEnd);
            double nextWidth = prefixWidth + glyph(codePoint).advance() * scale;
            if (nextWidth + ellipsisWidth > maxWidth) {
                break;
            }
            prefixWidth = nextWidth;
            prefixEnd += Character.charCount(codePoint);
        }
        return text.substring(0, prefixEnd) + ELLIPSIS;
    }

    private static double snap(double coordinate) {
        requireFinite(coordinate, "glyph edge");
        return Math.round(coordinate);
    }

    private BitmapGlyph glyph(int codePoint) {
        if (!font.glyphs().containsKey(codePoint)
                && codePoint != font.missingGlyph().codePoint()
                && diagnosedCodePoints.add(codePoint)) {
            missingGlyphDiagnostic.accept(codePoint);
        }
        return font.glyph(codePoint);
    }

    private static void requireScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0d) {
            throw new IllegalArgumentException("text scale must be finite and positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
