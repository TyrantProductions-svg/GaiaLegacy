package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrueTypeFontRasterizerTest {
    private static final List<Integer> CODE_POINTS = List.of(
            (int) ' ', (int) 'A', (int) 'g', (int) '0', 0xfffd);

    @Test
    void rasterizesOrderedGlyphsWithTruthfulMetricsAndVisibleFallback() {
        FontSourceManifest.Entry source = source("inter-regular-400");

        TrueTypeFontRasterizer.RasterizedFace face =
                new TrueTypeFontRasterizer().rasterize(
                        getClass().getClassLoader(), source, 24, 2, CODE_POINTS);

        assertEquals("inter-regular-400", face.sourceId());
        assertEquals(24, face.pixelHeight());
        assertEquals(2, face.oversample());
        assertTrue(face.ascent() > 0);
        assertTrue(face.descent() <= 0);
        assertTrue(face.lineHeight() >= face.ascent() - face.descent());
        assertEquals(face.ascent(), face.baseline());
        assertEquals(CODE_POINTS,
                face.glyphs().stream()
                        .map(TrueTypeFontRasterizer.RasterizedGlyph::codePoint)
                        .toList());

        TrueTypeFontRasterizer.RasterizedGlyph space = face.glyph(' ');
        assertEquals(0, space.width());
        assertEquals(0, space.height());
        assertEquals(0, space.alpha().length);
        assertTrue(space.advance() > 0);

        assertVisible(face.glyph('A'));
        assertVisible(face.glyph('g'));
        assertVisible(face.glyph('0'));
        assertVisible(face.glyph(0xfffd));
    }

    @Test
    void repeatedRunsAreByteIdenticalAndReturnedAlphaCannotMutateTheFace() {
        FontSourceManifest.Entry source = source("pixelify-semibold-600");
        TrueTypeFontRasterizer rasterizer = new TrueTypeFontRasterizer();

        TrueTypeFontRasterizer.RasterizedFace first = rasterizer.rasterize(
                getClass().getClassLoader(), source, 28, 3, CODE_POINTS);
        TrueTypeFontRasterizer.RasterizedFace second = rasterizer.rasterize(
                getClass().getClassLoader(), source, 28, 3, CODE_POINTS);

        assertEquals(first, second);
        for (int index = 0; index < first.glyphs().size(); index++) {
            byte[] firstAlpha = first.glyphs().get(index).alpha();
            byte[] secondAlpha = second.glyphs().get(index).alpha();
            assertNotSame(firstAlpha, secondAlpha);
            assertArrayEquals(firstAlpha, secondAlpha);
        }

        byte[] exposed = first.glyph('A').alpha();
        byte original = exposed[0];
        exposed[0] = (byte) (original ^ 0xff);
        assertEquals(original, first.glyph('A').alpha()[0]);
    }

    @Test
    void rejectsInvalidRasterRequestsBeforeNativeWork() {
        FontSourceManifest.Entry source = source("inter-regular-400");
        TrueTypeFontRasterizer rasterizer = new TrueTypeFontRasterizer();

        assertThrows(IllegalArgumentException.class, () -> rasterizer.rasterize(
                getClass().getClassLoader(), source, 0, 2, CODE_POINTS));
        assertThrows(IllegalArgumentException.class, () -> rasterizer.rasterize(
                getClass().getClassLoader(), source, 24, 0, CODE_POINTS));
        assertThrows(IllegalArgumentException.class, () -> rasterizer.rasterize(
                getClass().getClassLoader(), source, 24, 2,
                List.of((int) 'A', (int) 'A')));
        assertThrows(IllegalArgumentException.class, () -> rasterizer.rasterize(
                getClass().getClassLoader(), source, 24, 2, List.of(0x11_0000)));
    }

    private FontSourceManifest.Entry source(String id) {
        return FontSourceManifest.load(getClass().getClassLoader()).entry(id);
    }

    private static void assertVisible(TrueTypeFontRasterizer.RasterizedGlyph glyph) {
        assertTrue(glyph.width() > 0);
        assertTrue(glyph.height() > 0);
        assertEquals(Math.multiplyExact(glyph.width(), glyph.height()), glyph.alpha().length);
        boolean visible = false;
        for (byte alpha : glyph.alpha()) {
            if ((alpha & 0xff) != 0) {
                visible = true;
                break;
            }
        }
        assertTrue(visible, () -> "glyph U+" + Integer.toHexString(glyph.codePoint())
                + " has no visible pixels");
    }
}
