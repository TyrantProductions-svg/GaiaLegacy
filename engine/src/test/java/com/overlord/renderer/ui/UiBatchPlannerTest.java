package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UiBatchPlannerTest {
    @Test
    void keepsNonConsecutiveTexturesInTheirExactOriginalRunOrder() {
        UiDrawCommand iconA = command(UiTextureId.ICON_ATLAS, 0.0d, Optional.empty());
        UiDrawCommand fontA = command(UiTextureId.FONT_ATLAS, 10.0d, Optional.empty());
        UiDrawCommand iconB = command(UiTextureId.ICON_ATLAS, 20.0d, Optional.empty());

        List<UiBatchRun> runs = new UiBatchPlanner().plan(List.of(iconA, fontA, iconB));

        assertEquals(List.of(List.of(iconA), List.of(fontA), List.of(iconB)),
                runs.stream().map(UiBatchRun::commands).toList());
        assertEquals(List.of(
                        UiTextureId.ICON_ATLAS,
                        UiTextureId.FONT_ATLAS,
                        UiTextureId.ICON_ATLAS),
                runs.stream().map(UiBatchRun::texture).toList());
    }

    @Test
    void mergesOnlyAdjacentCommandsWithEqualTextureAndClip() {
        UiRect clip = new UiRect(1.0d, 2.0d, 40.0d, 50.0d);
        UiDrawCommand first = command(UiTextureId.FONT_ATLAS, 0.0d, Optional.of(clip));
        UiDrawCommand second = command(
                UiTextureId.FONT_ATLAS,
                10.0d,
                Optional.of(new UiRect(1.0d, 2.0d, 40.0d, 50.0d)));
        UiDrawCommand unclipped = command(UiTextureId.FONT_ATLAS, 20.0d, Optional.empty());
        UiDrawCommand icon = command(UiTextureId.ICON_ATLAS, 30.0d, Optional.empty());

        List<UiBatchRun> runs = new UiBatchPlanner().plan(
                List.of(first, second, unclipped, icon));

        assertEquals(List.of(
                        List.of(first, second),
                        List.of(unclipped),
                        List.of(icon)),
                runs.stream().map(UiBatchRun::commands).toList());
        assertEquals(List.of(Optional.of(clip), Optional.empty(), Optional.empty()),
                runs.stream().map(UiBatchRun::clip).toList());
    }

    @Test
    void returnedRunsAndCommandsAreDefensiveAndUnmodifiable() {
        UiDrawCommand command = command(UiTextureId.SOLID, 0.0d, Optional.empty());
        List<UiDrawCommand> source = new ArrayList<>(List.of(command));

        List<UiBatchRun> runs = new UiBatchPlanner().plan(source);
        source.clear();

        assertEquals(List.of(command), runs.get(0).commands());
        assertThrows(UnsupportedOperationException.class, () -> runs.clear());
        assertThrows(UnsupportedOperationException.class, () -> runs.get(0).commands().clear());
        assertEquals(List.of(), new UiBatchPlanner().plan(List.of()));
        assertThrows(NullPointerException.class, () -> new UiBatchPlanner().plan(null));
    }

    private static UiDrawCommand command(
            UiTextureId texture,
            double left,
            Optional<UiRect> clip) {
        return new UiDrawCommand(
                texture,
                new UiRect(left, 0.0d, left + 8.0d, 8.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                clip);
    }
}
