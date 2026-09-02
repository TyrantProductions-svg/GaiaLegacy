package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UiFrameTest {
    @Test
    void frameDefensivelyCopiesCommandsAndExposesAnUnmodifiableList() {
        UiDrawCommand first = command(UiTextureId.SOLID, 0.0d);
        UiDrawCommand second = command(UiTextureId.ICON_ATLAS, 20.0d);
        List<UiDrawCommand> source = new ArrayList<>(List.of(first, second));

        UiFrame frame = new UiFrame(source);
        source.clear();

        assertEquals(List.of(first, second), frame.commands());
        assertThrows(UnsupportedOperationException.class, () -> frame.commands().clear());
        assertThrows(NullPointerException.class, () -> new UiFrame(null));
        assertThrows(
                NullPointerException.class,
                () -> new UiFrame(Arrays.asList(first, null)));
        assertEquals(List.of(), UiFrame.empty().commands());
    }

    @Test
    void drawListSealsExactlyOnceAndRejectsEveryLaterMutation() {
        UiDrawCommand first = command(UiTextureId.SOLID, 0.0d);
        UiDrawCommand second = command(UiTextureId.FONT_ATLAS, 20.0d);
        UiDrawList drawList = new UiDrawList();
        drawList.append(first);
        drawList.append(second);

        UiFrame frame = drawList.seal();

        assertEquals(List.of(first, second), frame.commands());
        assertThrows(IllegalStateException.class, () -> drawList.append(first));
        assertThrows(IllegalStateException.class, drawList::seal);
        assertThrows(NullPointerException.class, () -> new UiDrawList().append(null));
    }

    @Test
    void geometryRejectsNonFiniteOrInvertedEdges() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiRect(Double.NaN, 0.0d, 1.0d, 1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiRect(0.0d, Double.NEGATIVE_INFINITY, 1.0d, 1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiRect(2.0d, 0.0d, 1.0d, 1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiRect(0.0d, 2.0d, 1.0d, 1.0d));
        assertEquals(new UiRect(1.0d, 2.0d, 1.0d, 2.0d), new UiRect(1.0d, 2.0d, 1.0d, 2.0d));
    }

    @Test
    void uvBoundsRejectNonFiniteOutOfAtlasOrInvertedEdges() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiUvRect(Float.NaN, 0.0f, 1.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiUvRect(0.0f, 0.0f, 1.01f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiUvRect(-0.01f, 0.0f, 1.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiUvRect(0.8f, 0.0f, 0.2f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiUvRect(0.0f, 0.8f, 1.0f, 0.2f));
        assertEquals(
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f));
    }

    @Test
    void colourRejectsNonFiniteOrOutOfRangeChannels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiColor(Float.NaN, 0.0f, 0.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiColor(0.0f, Float.POSITIVE_INFINITY, 0.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiColor(-0.01f, 0.0f, 0.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UiColor(0.0f, 0.0f, 0.0f, 1.01f));
        assertEquals(new UiColor(0.0f, 0.5f, 1.0f, 1.0f), new UiColor(0.0f, 0.5f, 1.0f, 1.0f));
    }

    @Test
    void commandRejectsMissingIdentityGeometryColourUvOrClipContainer() {
        UiRect bounds = new UiRect(0.0d, 0.0d, 10.0d, 10.0d);
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        UiColor tint = new UiColor(1.0f, 1.0f, 1.0f, 1.0f);
        Optional<UiRect> clip = Optional.of(new UiRect(1.0d, 1.0d, 9.0d, 9.0d));

        assertThrows(NullPointerException.class, () -> new UiDrawCommand(null, bounds, uv, tint, clip));
        assertThrows(NullPointerException.class, () -> new UiDrawCommand(UiTextureId.SOLID, null, uv, tint, clip));
        assertThrows(NullPointerException.class, () -> new UiDrawCommand(UiTextureId.SOLID, bounds, null, tint, clip));
        assertThrows(NullPointerException.class, () -> new UiDrawCommand(UiTextureId.SOLID, bounds, uv, null, clip));
        assertThrows(NullPointerException.class, () -> new UiDrawCommand(UiTextureId.SOLID, bounds, uv, tint, null));
        assertEquals(
                clip,
                new UiDrawCommand(UiTextureId.SOLID, bounds, uv, tint, clip).clip());
    }

    @Test
    void framePreservesLegacyAndMultiPageTextureIdentitiesInSubmissionOrder() {
        List<UiTextureId> textures = List.of(UiTextureId.SOLID, UiTextureId.ICON_ATLAS,
                UiTextureId.FONT_ATLAS, UiTextureId.FONT_DISPLAY, UiTextureId.FONT_BODY,
                UiTextureId.HERO_BACKGROUND, UiTextureId.BRAND_EMBLEM);
        UiDrawList draw = new UiDrawList();
        textures.forEach(texture -> draw.append(command(texture, 0)));
        assertEquals(textures, draw.seal().commands().stream()
                .map(UiDrawCommand::texture).toList());
    }

    private static UiDrawCommand command(UiTextureId texture, double left) {
        return new UiDrawCommand(
                texture,
                new UiRect(left, 0.0d, left + 10.0d, 10.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                Optional.empty());
    }
}
