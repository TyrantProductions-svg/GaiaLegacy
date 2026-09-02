package com.gaia.ui.widget;

import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.TypographyRole;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.Objects;
import java.util.Optional;

/** Compact read-only current DETAIL tool state. */
public final class DetailToolWidget {
    private static final double SCALE = 0.75;
    private static final double BASELINE_OFFSET = 48;

    private final TextRenderer text;

    public DetailToolWidget(TextRenderer text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        HudPresentationSnapshot.DetailToolPresentation detail = snapshot.detailTool();
        if (!snapshot.visibility().hudVisible()
                || !snapshot.visibility().interactionEligible()
                || !detail.active()) {
            return;
        }

        StringBuilder label = new StringBuilder("DETAIL ")
                .append(detail.mode().name().replace('_', ' '));
        detail.selectedMaterial().ifPresent(material ->
                label.append(" | ").append(material.path()));
        if (detail.availableUnits().isPresent()) {
            label.append(" x").append(detail.availableUnits().getAsInt());
        }
        detail.localTarget().ifPresent(local -> label.append(" | [")
                .append(local.x()).append(',').append(local.y()).append(',')
                .append(local.z()).append(']'));
        detail.previewValidity().ifPresent(validity ->
                label.append(" | ").append(validity.name()));

        boolean invalid = detail.previewValidity()
                .map(validity -> validity != com.gaia.interaction.DetailPreviewValidity.VALID)
                .orElse(false) || detail.latestFailure().isPresent();
        double x = layout.framebufferWidth() / 2.0
                - text.measure(label.toString(), TypographyRole.HUD, SCALE) / 2.0;
        double baseline = layout.framebufferHeight() / 2.0 + BASELINE_OFFSET;
        text.append(
                label.toString(), TypographyRole.HUD, x, baseline, SCALE, SCALE,
                invalid ? GaiaUiTheme.FAILURE_TEXT : GaiaUiTheme.ACTIVE_PRIMARY_RIM,
                Optional.empty(), out);
    }
}
