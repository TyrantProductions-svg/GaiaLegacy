package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class BitmapFontGeneratorTest {
    private static final int INFINITY = 0x221e;
    private static final int MISSING = 0xfffd;

    @Test
    void metadataHasExactCoverageMetricsAndUniqueCellsInCodePointOrder() {
        BitmapFontGenerator.GeneratedFont generated =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());
        JsonObject metadata = parse(generated.json());

        assertEquals(128, metadata.getAsJsonObject("atlas").get("width").getAsInt());
        assertEquals(64, metadata.getAsJsonObject("atlas").get("height").getAsInt());
        assertEquals(8, metadata.getAsJsonObject("cell").get("width").getAsInt());
        assertEquals(8, metadata.getAsJsonObject("cell").get("height").getAsInt());
        assertTrue(metadata.get("tintable").getAsBoolean());
        JsonObject glyphColor = metadata.getAsJsonObject("glyphColor");
        assertEquals(255, glyphColor.get("red").getAsInt());
        assertEquals(255, glyphColor.get("green").getAsInt());
        assertEquals(255, glyphColor.get("blue").getAsInt());
        assertEquals(255, glyphColor.get("alpha").getAsInt());

        JsonArray glyphs = metadata.getAsJsonArray("glyphs");
        List<Integer> actualCodePoints = new ArrayList<>();
        Set<String> cells = new HashSet<>();
        for (int index = 0; index < glyphs.size(); index++) {
            JsonObject glyph = glyphs.get(index).getAsJsonObject();
            int codePoint = glyph.get("codePoint").getAsInt();
            actualCodePoints.add(codePoint);
            JsonObject cell = glyph.getAsJsonObject("cell");
            assertTrue(cells.add(cell.get("column").getAsInt()
                    + ":" + cell.get("row").getAsInt()));
            assertEquals(8, glyph.get("advance").getAsInt());
            assertEquals(0, glyph.getAsJsonObject("bearing").get("x").getAsInt());
            assertEquals(8, glyph.getAsJsonObject("bearing").get("y").getAsInt());
        }

        List<Integer> expectedCodePoints = new ArrayList<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            expectedCodePoints.add(codePoint);
        }
        expectedCodePoints.add(INFINITY);
        expectedCodePoints.add(MISSING);
        assertEquals(expectedCodePoints, actualCodePoints);
        assertEquals(97, cells.size());

        JsonObject fallback = metadata.getAsJsonObject("fallback");
        assertEquals(MISSING, fallback.get("codePoint").getAsInt());
        JsonObject region = fallback.getAsJsonObject("region");
        assertEquals(0, region.get("x").getAsInt());
        assertEquals(48, region.get("y").getAsInt());
        assertEquals(8, region.get("width").getAsInt());
        assertEquals(8, region.get("height").getAsInt());
    }

    @Test
    void rejectsAFontMissingAnyPrintableAsciiGlyph() {
        List<GlyphSource.Glyph> glyphs = new ArrayList<>(GlyphSource.projectGlyphs());
        glyphs.removeIf(glyph -> glyph.codePoint() == 'A');

        assertThrows(IllegalArgumentException.class,
                () -> new BitmapFontGenerator().generate(glyphs));
    }

    @Test
    void rejectsAFontMissingInfinity() {
        List<GlyphSource.Glyph> glyphs = new ArrayList<>(GlyphSource.projectGlyphs());
        glyphs.removeIf(glyph -> glyph.codePoint() == INFINITY);

        assertThrows(IllegalArgumentException.class,
                () -> new BitmapFontGenerator().generate(glyphs));
    }

    @Test
    void rejectsAnExtraCodePoint() {
        List<GlyphSource.Glyph> glyphs = new ArrayList<>(GlyphSource.projectGlyphs());
        GlyphSource.Glyph source = glyphs.get(0);
        glyphs.add(new GlyphSource.Glyph(
                0x2603, 97, source.advance(), source.bearingX(), source.bearingY(), source.rows()));

        assertThrows(IllegalArgumentException.class,
                () -> new BitmapFontGenerator().generate(glyphs));
    }

    @Test
    void rejectsTheWrongSetEvenWhenItStillContainsNinetySevenEntries() {
        List<GlyphSource.Glyph> glyphs = new ArrayList<>(GlyphSource.projectGlyphs());
        int index = 'A' - 32;
        GlyphSource.Glyph source = glyphs.get(index);
        glyphs.set(index, new GlyphSource.Glyph(
                0x2603,
                source.cellIndex(),
                source.advance(),
                source.bearingX(),
                source.bearingY(),
                source.rows()));

        assertThrows(IllegalArgumentException.class,
                () -> new BitmapFontGenerator().generate(glyphs));
    }

    @Test
    void atlasHasVisibleInfinityAndFallbackAndOnlyNeutralWhiteBinaryAlpha() throws Exception {
        BitmapFontGenerator.GeneratedFont generated =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(generated.png()));

        assertEquals(128, image.getWidth());
        assertEquals(64, image.getHeight());
        assertTrue(hasVisiblePixel(image, 15, 5));
        assertTrue(hasVisiblePixel(image, 0, 6));
        assertNeutralWhiteBinaryAlpha(image);
    }

    @Test
    void semanticPixelValidationRejectsNonWhiteAndPartialAlphaWithoutUsingAHash()
            throws Exception {
        BufferedImage nonWhite = generatedImage();
        nonWhite.setRGB(0, 0, 0xfffefeff);
        assertThrows(AssertionFailedError.class, () -> assertNeutralWhiteBinaryAlpha(nonWhite));

        BufferedImage partialAlpha = generatedImage();
        partialAlpha.setRGB(0, 0, 0x80ffffff);
        assertThrows(AssertionFailedError.class,
                () -> assertNeutralWhiteBinaryAlpha(partialAlpha));
    }

    private static void assertNeutralWhiteBinaryAlpha(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                assertTrue(argb == 0x00ffffff || argb == 0xffffffff,
                        () -> "unexpected RGBA pixel 0x" + Integer.toHexString(argb));
            }
        }
    }

    private static BufferedImage generatedImage() throws Exception {
        byte[] png = new BitmapFontGenerator()
                .generate(GlyphSource.projectGlyphs())
                .png();
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void pngAndJsonBytesDoNotDependOnInputIterationOrder() {
        List<GlyphSource.Glyph> reversed = new ArrayList<>(GlyphSource.projectGlyphs());
        Collections.reverse(reversed);
        BitmapFontGenerator generator = new BitmapFontGenerator();

        BitmapFontGenerator.GeneratedFont forward = generator.generate(GlyphSource.projectGlyphs());
        BitmapFontGenerator.GeneratedFont backward = generator.generate(reversed);

        assertArrayEquals(forward.png(), backward.png());
        assertArrayEquals(forward.json(), backward.json());
        String json = new String(forward.json(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\n  \"source\": {\n"));
        assertTrue(json.endsWith("\n}\n"));
        assertFalse(json.contains("\r"));
    }

    @Test
    void generatedBytesKeepTheApprovedQuietRuneVersionOneHashes() throws Exception {
        BitmapFontGenerator.GeneratedFont generated =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());

        assertEquals("a6a27be503ff26fd119cfe3ab74375faf7fbc22e13e1ed5670e6f2d56f5fd1ca",
                sha256(generated.png()));
        assertEquals("ec98df77b826b03df7fecfa2e77fadf540c47dc6e01e3b2664dd8aff35636ac4",
                sha256(generated.json()));
    }

    private static JsonObject parse(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean hasVisiblePixel(BufferedImage image, int column, int row) {
        for (int y = row * 8; y < row * 8 + 8; y++) {
            for (int x = column * 8; x < column * 8 + 8; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
