package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FontAtlasPackerTest {
    private static final List<Integer> CODE_POINTS = List.of(
            (int) ' ', (int) 'A', (int) 'g', (int) '0', 0xfffd);

    @Test
    void compositePackingIsDeterministicPaddedAndTransparentWhite() {
        List<TrueTypeFontRasterizer.RasterizedFace> faces = faces();
        FontAtlasPacker.PageDefinition page = new FontAtlasPacker.PageDefinition(
                "composite",
                List.of("inter-regular-400", "pixelify-semibold-600"),
                FontAtlasPacker.SamplingMode.LINEAR);
        FontAtlasPacker packer = new FontAtlasPacker();

        FontAtlasPacker.GeneratedTypographyAtlas forward =
                packer.pack(faces, List.of(page), 2);
        List<TrueTypeFontRasterizer.RasterizedFace> reversed = new ArrayList<>(faces);
        Collections.reverse(reversed);
        FontAtlasPacker.GeneratedTypographyAtlas backward =
                packer.pack(reversed, List.of(page), 2);

        assertEquals(forward, backward);
        assertEquals(1, forward.pages().size());
        FontAtlasPacker.AtlasPage atlasPage = forward.page("composite");
        assertTrue(isPowerOfTwo(atlasPage.width()));
        assertTrue(isPowerOfTwo(atlasPage.height()));
        assertEquals(Math.multiplyExact(Math.multiplyExact(
                atlasPage.width(), atlasPage.height()), 4), atlasPage.byteCount());
        assertEquals(64, atlasPage.sha256().length());

        byte[] rgba = atlasPage.rgba();
        for (int pixel = 0; pixel < atlasPage.width() * atlasPage.height(); pixel++) {
            assertEquals(255, rgba[pixel * 4] & 0xff);
            assertEquals(255, rgba[pixel * 4 + 1] & 0xff);
            assertEquals(255, rgba[pixel * 4 + 2] & 0xff);
        }
        assertPaddingAndNoOverlap(forward);
    }

    @Test
    void separatePagesRetainPageSpecificSamplingAndFaceOwnership() {
        FontAtlasPacker.GeneratedTypographyAtlas atlas = new FontAtlasPacker().pack(
                faces(),
                List.of(
                        new FontAtlasPacker.PageDefinition(
                                "body-linear",
                                List.of("inter-regular-400"),
                                FontAtlasPacker.SamplingMode.LINEAR),
                        new FontAtlasPacker.PageDefinition(
                                "display-nearest",
                                List.of("pixelify-semibold-600"),
                                FontAtlasPacker.SamplingMode.NEAREST)),
                2);

        assertEquals(2, atlas.pages().size());
        assertEquals(FontAtlasPacker.SamplingMode.LINEAR,
                atlas.page("body-linear").samplingMode());
        assertEquals(FontAtlasPacker.SamplingMode.NEAREST,
                atlas.page("display-nearest").samplingMode());
        assertEquals("body-linear", atlas.placement("inter-regular-400", 'A').pageId());
        assertEquals("display-nearest",
                atlas.placement("pixelify-semibold-600", 'A').pageId());
        assertNotEquals(atlas.page("body-linear").sha256(),
                atlas.page("display-nearest").sha256());
    }

    @Test
    void rejectsIncompleteOrDuplicatedPhysicalPageOwnership() {
        List<TrueTypeFontRasterizer.RasterizedFace> faces = faces();
        FontAtlasPacker packer = new FontAtlasPacker();

        assertThrows(IllegalArgumentException.class, () -> packer.pack(
                faces,
                List.of(new FontAtlasPacker.PageDefinition(
                        "only-body", List.of("inter-regular-400"),
                        FontAtlasPacker.SamplingMode.LINEAR)),
                2));
        assertThrows(IllegalArgumentException.class, () -> packer.pack(
                faces,
                List.of(
                        new FontAtlasPacker.PageDefinition(
                                "one", List.of("inter-regular-400"),
                                FontAtlasPacker.SamplingMode.LINEAR),
                        new FontAtlasPacker.PageDefinition(
                                "two",
                                List.of("inter-regular-400", "pixelify-semibold-600"),
                                FontAtlasPacker.SamplingMode.LINEAR)),
                2));
        assertThrows(IllegalArgumentException.class, () -> packer.pack(
                faces,
                List.of(new FontAtlasPacker.PageDefinition(
                        "all",
                        List.of("inter-regular-400", "pixelify-semibold-600"),
                        FontAtlasPacker.SamplingMode.LINEAR)),
                -1));
    }

    private List<TrueTypeFontRasterizer.RasterizedFace> faces() {
        FontSourceManifest manifest = FontSourceManifest.load(getClass().getClassLoader());
        TrueTypeFontRasterizer rasterizer = new TrueTypeFontRasterizer();
        return List.of(
                rasterizer.rasterize(getClass().getClassLoader(),
                        manifest.entry("pixelify-semibold-600"), 28, 3, CODE_POINTS),
                rasterizer.rasterize(getClass().getClassLoader(),
                        manifest.entry("inter-regular-400"), 18, 3, CODE_POINTS));
    }

    private static void assertPaddingAndNoOverlap(
            FontAtlasPacker.GeneratedTypographyAtlas atlas) {
        List<FontAtlasPacker.GlyphPlacement> ink = atlas.placements().stream()
                .filter(placement -> placement.width() > 0)
                .toList();
        for (FontAtlasPacker.GlyphPlacement placement : ink) {
            assertEquals(2, placement.x() - placement.paddedX());
            assertEquals(2, placement.y() - placement.paddedY());
            assertEquals(placement.width() + 4, placement.paddedWidth());
            assertEquals(placement.height() + 4, placement.paddedHeight());
            for (FontAtlasPacker.GlyphPlacement other : ink) {
                if (placement == other || !placement.pageId().equals(other.pageId())) {
                    continue;
                }
                assertTrue(!overlaps(placement, other),
                        () -> placement + " overlaps " + other);
            }
        }
    }

    private static boolean overlaps(
            FontAtlasPacker.GlyphPlacement left,
            FontAtlasPacker.GlyphPlacement right) {
        return left.paddedX() < right.paddedX() + right.paddedWidth()
                && left.paddedX() + left.paddedWidth() > right.paddedX()
                && left.paddedY() < right.paddedY() + right.paddedHeight()
                && left.paddedY() + left.paddedHeight() > right.paddedY();
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
