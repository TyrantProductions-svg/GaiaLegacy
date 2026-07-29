package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextRendererTest {
    private static final UiColor WHITE = new UiColor(1.0f, 1.0f, 1.0f, 1.0f);
    private static final UiUvRect A_UV = new UiUvRect(0.0f, 0.0f, 0.5f, 1.0f);
    private static final UiUvRect INFINITY_UV = new UiUvRect(0.5f, 0.0f, 1.0f, 1.0f);
    private static final UiUvRect MISSING_UV = new UiUvRect(0.25f, 0.0f, 0.75f, 1.0f);

    @Test
    void measuresInfinityAndUsesMissingGlyphForUnsupportedCodePointsInCodePointOrder() {
        TextRenderer renderer = new TextRenderer(font());
        UiDrawList out = new UiDrawList();

        assertEquals(24.0d, renderer.measure("A\u221e\ud83d\ude03", 1.0d));

        renderer.append(
                "A\u221e\ud83d\ude03",
                0.0d,
                6.0d,
                1.0d,
                WHITE,
                Optional.empty(),
                out);

        List<UiDrawCommand> commands = out.seal().commands();
        assertEquals(List.of(A_UV, INFINITY_UV, MISSING_UV),
                commands.stream().map(UiDrawCommand::uv).toList());
        assertEquals(List.of(
                        new UiRect(1.0d, 0.0d, 9.0d, 8.0d),
                        new UiRect(9.0d, 0.0d, 17.0d, 8.0d),
                        new UiRect(17.0d, 0.0d, 25.0d, 8.0d)),
                commands.stream().map(UiDrawCommand::framebufferBounds).toList());
        assertTrue(commands.stream().allMatch(command -> command.texture() == UiTextureId.FONT_ATLAS));
    }

    @Test
    void diagnosesEachUnsupportedCodePointExactlyOnceAcrossAllLayoutOperations() {
        List<Integer> diagnostics = new ArrayList<>();
        TextRenderer renderer = new TextRenderer(font(), diagnostics::add);

        renderer.measure("A\ud83d\ude03\ud83d\ude03", 1.0d);
        renderer.append("\ud83d\ude03\u96ea", 0.0d, 6.0d, 1.0d, WHITE,
                Optional.empty(), new UiDrawList());
        renderer.truncateToFit("\u96eaAAAA", 1.0d, 32.0d);

        assertEquals(List.of(0x1f603, 0x96ea), diagnostics);
    }

    @Test
    void snapsEachScaledGlyphEdgeAndPreservesTheCallerClip() {
        TextRenderer renderer = new TextRenderer(font());
        UiRect clip = new UiRect(3.0d, 4.0d, 50.0d, 60.0d);
        UiDrawList out = new UiDrawList();

        renderer.append("AA", 10.4d, 20.6d, 1.25d, WHITE, Optional.of(clip), out);

        List<UiDrawCommand> commands = out.seal().commands();
        assertEquals(List.of(
                        new UiRect(12.0d, 13.0d, 22.0d, 23.0d),
                        new UiRect(23.0d, 13.0d, 33.0d, 23.0d)),
                commands.stream().map(UiDrawCommand::framebufferBounds).toList());
        assertEquals(List.of(Optional.of(clip), Optional.of(clip)),
                commands.stream().map(UiDrawCommand::clip).toList());
    }

    @Test
    void scalesGlyphGeometryIndependentlyAcrossAsymmetricAxes() {
        TextRenderer renderer = new TextRenderer(font());
        UiDrawList tallerPixels = new UiDrawList();
        UiDrawList widerPixels = new UiDrawList();

        renderer.append("AA", 10.0d, 20.0d, 1.25d, 1.5d,
                WHITE, Optional.empty(), tallerPixels);
        renderer.append("AA", 10.0d, 20.0d, 1.5d, 1.25d,
                WHITE, Optional.empty(), widerPixels);

        assertEquals(List.of(
                        new UiRect(11.0d, 11.0d, 21.0d, 23.0d),
                        new UiRect(23.0d, 11.0d, 33.0d, 23.0d)),
                tallerPixels.seal().commands().stream()
                        .map(UiDrawCommand::framebufferBounds).toList());
        assertEquals(List.of(
                        new UiRect(12.0d, 13.0d, 24.0d, 23.0d),
                        new UiRect(25.0d, 13.0d, 37.0d, 23.0d)),
                widerPixels.seal().commands().stream()
                        .map(UiDrawCommand::framebufferBounds).toList());
    }

    @Test
    void truncatesByCodePointToAnAsciiEllipsisWithinTheHudNameLimit() {
        TextRenderer renderer = new TextRenderer(monospaceAsciiFont());

        String truncated = renderer.truncateToFit("ABCDEFGHIJKLMNOPQRST", 1.0d, 144.0d);

        assertEquals("ABCDEFGHIJKLMNO...", truncated);
        assertEquals(144.0d, renderer.measure(truncated, 1.0d));
        assertEquals("SHORT", renderer.truncateToFit("SHORT", 1.0d, 144.0d));
    }

    @Test
    void rejectsMissingTextOutputsAndNonFiniteOrNonPositiveLayoutValues() {
        TextRenderer renderer = new TextRenderer(font());
        UiDrawList out = new UiDrawList();

        assertThrows(NullPointerException.class, () -> renderer.measure(null, 1.0d));
        assertThrows(IllegalArgumentException.class, () -> renderer.measure("A", 0.0d));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.measure("A", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.append("A", Double.NaN, 0.0d, 1.0d, WHITE, Optional.empty(), out));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.append("A", 0.0d, Double.NEGATIVE_INFINITY, 1.0d,
                        WHITE, Optional.empty(), out));
        assertThrows(NullPointerException.class,
                () -> renderer.append("A", 0.0d, 0.0d, 1.0d, WHITE, Optional.empty(), null));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.truncateToFit("TOO LONG", 1.0d, 8.0d));
    }

    private static BitmapFont font() {
        BitmapGlyph missing = new BitmapGlyph(0xfffd, MISSING_UV, 7, 0, 6);
        return new BitmapFont(
                16,
                8,
                Map.of(
                        (int) 'A', new BitmapGlyph('A', A_UV, 9, 1, 6),
                        (int) '.', new BitmapGlyph('.', A_UV, 7, 0, 6),
                        0x221e, new BitmapGlyph(0x221e, INFINITY_UV, 8, 0, 6)),
                missing);
    }

    private static BitmapFont monospaceAsciiFont() {
        BitmapGlyph missing = new BitmapGlyph(0xfffd, MISSING_UV, 8, 0, 8);
        Map<Integer, BitmapGlyph> glyphs = new LinkedHashMap<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            glyphs.put(codePoint, new BitmapGlyph(codePoint, A_UV, 8, 0, 8));
        }
        return new BitmapFont(16, 8, glyphs, missing);
    }
}
