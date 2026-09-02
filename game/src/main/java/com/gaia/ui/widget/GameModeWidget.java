package com.gaia.ui.widget;

import com.gaia.interaction.GameMode;
import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.TypographyRole;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Objects;
import java.util.Optional;

public final class GameModeWidget {
    private static final double MARGIN = 12;
    private static final double PADDING = 4;
    private static final double PERSISTENT_SCALE = 0.75;
    private static final double NOTICE_SCALE = 1;
    private static final double NOTICE_GAP = 4;
    private static final UiUvRect SOLID_UV = new UiUvRect(0, 0, 1, 1);

    private final TextRenderer text;

    public GameModeWidget(TextRenderer text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public void append(
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout,
            UiDrawList out) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        if (!snapshot.visibility().hudVisible()) {
            return;
        }

        String persistentLabel = label(snapshot.mode());
        UiColor persistentColor = color(snapshot.mode());
        double persistentBottom = appendPanelAndText(
                persistentLabel,
                persistentColor,
                GaiaUiTheme.VOID_BACKGROUND,
                MARGIN,
                PERSISTENT_SCALE,
                layout,
                out);

        snapshot.modeNotice().ifPresent(notice -> appendPanelAndText(
                label(notice.mode()),
                withAlpha(color(notice.mode()), notice.opacity()),
                withAlpha(GaiaUiTheme.VOID_BACKGROUND, notice.opacity()),
                persistentBottom + NOTICE_GAP,
                NOTICE_SCALE,
                layout,
                out));
    }

    private double appendPanelAndText(
            String label,
            UiColor textColor,
            UiColor panelColor,
            double top,
            double scale,
            UiLayoutContext layout,
            UiDrawList out) {
        double right = layout.logicalWidth() - MARGIN;
        double glyphHeight = text.lineHeight(TypographyRole.HUD, scale);
        double left = right - text.measure(label, TypographyRole.HUD, scale) - PADDING * 2;
        double bottom = top + glyphHeight + PADDING * 2;
        out.append(new UiDrawCommand(
                UiTextureId.SOLID,
                layout.toFramebuffer(new UiRect(left, top, right, bottom)),
                SOLID_UV,
                panelColor,
                Optional.empty()));
        text.append(
                label,
                TypographyRole.HUD,
                layout.snapX(left + PADDING),
                layout.snapY(top + PADDING + glyphHeight),
                scale * layout.contentScaleX(),
                scale * layout.contentScaleY(),
                textColor,
                Optional.empty(),
                out);
        return bottom;
    }

    private static String label(GameMode mode) {
        return mode == GameMode.CREATIVE ? "CREATIVE \u221e" : "SURVIVAL";
    }

    private static UiColor color(GameMode mode) {
        return mode == GameMode.CREATIVE
                ? GaiaUiTheme.CREATIVE_ACCENT
                : GaiaUiTheme.PRIMARY_TEXT;
    }

    private static UiColor withAlpha(UiColor color, double multiplier) {
        return new UiColor(
                color.red(),
                color.green(),
                color.blue(),
                color.alpha() * (float) multiplier);
    }
}
