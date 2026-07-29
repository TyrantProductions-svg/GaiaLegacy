package com.gaia.ui.widget;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Objects;
import java.util.Optional;

public final class BreakProgressWidget {
    private static final double TRACK_WIDTH = 28;
    private static final double TRACK_HEIGHT = 2;
    private static final double TRACK_HALF_WIDTH = TRACK_WIDTH / 2;
    private static final double TRACK_TOP_OFFSET = 15;
    private static final UiColor TRACK_TINT = new UiColor(1, 1, 1, 0.22f);
    private static final UiColor WHITE = new UiColor(1, 1, 1, 1);
    private static final UiUvRect SOLID_UV = new UiUvRect(0, 0, 1, 1);

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        HudPresentationSnapshot.InteractionPresentation interaction = snapshot.interaction();
        if (!snapshot.visibility().hudVisible()
                || !snapshot.visibility().interactionEligible()
                || snapshot.mode() != GameMode.SURVIVAL
                || interaction.target().isEmpty()
                || interaction.hitFace().isEmpty()
                || interaction.mode() != InteractionMode.BREAKING
                || interaction.progress() <= 0
                || interaction.progress() >= 1) {
            return;
        }

        double centerX = layout.framebufferWidth() / 2.0;
        double centerY = layout.framebufferHeight() / 2.0;
        double left = centerX - TRACK_HALF_WIDTH;
        double top = centerY + TRACK_TOP_OFFSET;
        double right = left + TRACK_WIDTH;
        double bottom = top + TRACK_HEIGHT;
        appendSolid(new UiRect(left, top, right, bottom), TRACK_TINT, out);
        appendSolid(
                new UiRect(left, top, left + TRACK_WIDTH * interaction.progress(), bottom),
                WHITE,
                out);
    }

    private static void appendSolid(UiRect bounds, UiColor tint, UiDrawList out) {
        out.append(new UiDrawCommand(
                UiTextureId.SOLID,
                bounds,
                SOLID_UV,
                tint,
                Optional.empty()));
    }
}
