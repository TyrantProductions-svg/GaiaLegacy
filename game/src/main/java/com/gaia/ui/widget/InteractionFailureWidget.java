package com.gaia.ui.widget;

import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.Objects;
import java.util.Optional;

public final class InteractionFailureWidget {
    private static final double SCALE = 1;
    private static final double BASELINE_OFFSET = 31;

    private final TextRenderer text;

    public InteractionFailureWidget(TextRenderer text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        if (!snapshot.visibility().hudVisible()
                || !snapshot.visibility().interactionEligible()) {
            return;
        }

        Optional<InteractionFailureReason> reason = snapshot.interaction().failureReason();
        if (reason.isEmpty()) {
            return;
        }
        String label = "FAILED: " + reason.orElseThrow().code();
        double x = layout.framebufferWidth() / 2.0 - text.measure(label, SCALE) / 2.0;
        double baseline = layout.framebufferHeight() / 2.0 + BASELINE_OFFSET;
        text.append(
                label,
                x,
                baseline,
                SCALE,
                SCALE,
                GaiaUiTheme.FAILURE_TEXT,
                Optional.empty(),
                out);
    }
}
