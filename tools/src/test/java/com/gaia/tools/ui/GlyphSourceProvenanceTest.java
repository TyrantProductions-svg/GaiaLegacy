package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlyphSourceProvenanceTest {
    private static final String DISPUTED_MODE_13H_PRINTABLE_ASCII_FINGERPRINT =
            "a2b901a9fc90cdfca0ae078ad12747fab8487713a8860d9e6ca0f2b235fd6b5c";
    private static final String QUIET_RUNE_V1_PRINTABLE_ASCII_FINGERPRINT =
            "d53cb032352c768e6ab17816d34a426ad376c2b08154c41072e404ae3498a662";

    @Test
    void printableAsciiRowsDoNotContainTheDiscardedMode13hTable() throws Exception {
        String actual = printableAsciiFingerprint(GlyphSource.projectGlyphs());

        assertNotEquals(DISPUTED_MODE_13H_PRINTABLE_ASCII_FINGERPRINT, actual,
                "the provenance-ambiguous Mode 13h/Allegro-style rows must not ship");
        assertEquals(QUIET_RUNE_V1_PRINTABLE_ASCII_FINGERPRINT, actual,
                "Quiet Rune source changes require an explicit versioned review");
    }

    @Test
    void generatedMetadataIdentifiesTheIndependentQuietRuneAlgorithm() throws Exception {
        BitmapFontGenerator.GeneratedFont generated =
                new BitmapFontGenerator().generate(GlyphSource.projectGlyphs());
        JsonObject root = JsonParser.parseString(
                new String(generated.json(), StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(root.has("source"), "font metadata must carry its source algorithm");
        JsonObject source = root.getAsJsonObject("source");
        assertEquals("Quiet Rune 5x7", source.get("name").getAsString());
        assertEquals("quiet-rune-5x7", source.get("algorithm").getAsString());
        assertEquals(1, source.get("version").getAsInt());
        assertEquals(QUIET_RUNE_V1_PRINTABLE_ASCII_FINGERPRINT,
                source.get("printableAsciiSha256").getAsString());
        assertEquals(printableAsciiFingerprint(GlyphSource.projectGlyphs()),
                source.get("printableAsciiSha256").getAsString());
    }

    @Test
    void quietRuneUsesOnlyTheCenteredFiveBySevenDrawingArea() {
        for (GlyphSource.Glyph glyph : GlyphSource.projectGlyphs()) {
            byte[] rows = glyph.rows();
            assertEquals(8, rows.length);
            assertEquals(0, Byte.toUnsignedInt(rows[7]),
                    () -> label(glyph) + " must leave row 7 transparent");
            for (int row = 0; row < 7; row++) {
                int checkedRow = row;
                assertEquals(0, Byte.toUnsignedInt(rows[row]) & 0x83,
                        () -> label(glyph)
                                + " escapes centered columns 1..5 at row " + checkedRow);
            }
        }
    }

    @Test
    void requiredAsciiClassesInfinityAndFallbackAreVisiblyDefined() {
        Map<Integer, byte[]> rows = rowsByCodePoint(GlyphSource.projectGlyphs());
        for (int codePoint = 33; codePoint <= 126; codePoint++) {
            int checkedCodePoint = codePoint;
            assertTrue(hasInk(rows.get(codePoint)),
                    () -> "printable " + label(checkedCodePoint) + " must be visible");
        }
        assertTrue(hasInk(rows.get(GlyphSource.INFINITY_CODE_POINT)));
        assertTrue(hasInk(rows.get(GlyphSource.MISSING_CODE_POINT)));
        assertFalse(hasInk(rows.get((int) ' ')), "space must remain transparent");
    }

    @Test
    void essentialHudLettersAndDigitsDoNotCollapseToTheSameBitmap() {
        Map<Integer, byte[]> rows = rowsByCodePoint(GlyphSource.projectGlyphs());
        String essential = "0123456789ABCDEFGHILMNOPQRSTUVWXYZ";
        Map<String, Integer> owners = new HashMap<>();
        essential.codePoints().forEach(codePoint -> {
            String fingerprint = HexFormat.of().formatHex(rows.get(codePoint));
            Integer previous = owners.putIfAbsent(fingerprint, codePoint);
            assertTrue(previous == null,
                    () -> label(previous) + " and " + label(codePoint)
                            + " must remain distinguishable in HUD labels");
        });

        assertFalse(Arrays.equals(rows.get((int) '0'), rows.get((int) 'O')));
        assertFalse(Arrays.equals(rows.get((int) '1'), rows.get((int) 'I')));
        assertFalse(Arrays.equals(rows.get((int) '5'), rows.get((int) 'S')));
        assertFalse(Arrays.equals(rows.get((int) '8'), rows.get((int) 'B')));
    }

    @Test
    void repeatedSourceReadsAreByteForByteDeterministicAndDefensive() {
        List<GlyphSource.Glyph> first = GlyphSource.projectGlyphs();
        byte[] attemptedMutation = first.get('A' - 32).rows();
        attemptedMutation[0] ^= 0x7c;
        List<GlyphSource.Glyph> second = GlyphSource.projectGlyphs();

        assertEquals(97, first.size());
        assertEquals(97, second.size());
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).codePoint(), second.get(index).codePoint());
            assertTrue(Arrays.equals(first.get(index).rows(), second.get(index).rows()));
        }
    }

    private static Map<Integer, byte[]> rowsByCodePoint(List<GlyphSource.Glyph> glyphs) {
        Map<Integer, byte[]> result = new HashMap<>();
        glyphs.forEach(glyph -> result.put(glyph.codePoint(), glyph.rows()));
        return result;
    }

    private static boolean hasInk(byte[] rows) {
        return rows != null && Arrays.stream(toUnsigned(rows)).anyMatch(row -> row != 0);
    }

    private static int[] toUnsigned(byte[] rows) {
        int[] result = new int[rows.length];
        for (int index = 0; index < rows.length; index++) {
            result[index] = Byte.toUnsignedInt(rows[index]);
        }
        return result;
    }

    private static String printableAsciiFingerprint(List<GlyphSource.Glyph> glyphs)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        glyphs.stream()
                .filter(glyph -> glyph.codePoint() >= 32 && glyph.codePoint() <= 126)
                .sorted((left, right) -> Integer.compare(left.codePoint(), right.codePoint()))
                .forEach(glyph -> digest.update(glyph.rows()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String label(GlyphSource.Glyph glyph) {
        return label(glyph.codePoint());
    }

    private static String label(Integer codePoint) {
        return codePoint == null
                ? "<none>"
                : "U+" + HexFormat.of().toHexDigits(codePoint).toUpperCase();
    }
}
