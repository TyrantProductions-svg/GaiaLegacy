package com.gaia.ui;

import com.gaia.ui.widget.BodyInventoryHud;
import com.gaia.ui.widget.BreakProgressWidget;
import com.gaia.ui.widget.CrosshairWidget;
import com.gaia.ui.widget.DebugHud;
import com.gaia.ui.widget.GameModeWidget;
import com.gaia.ui.widget.InteractionFailureWidget;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.Objects;

public final class GaiaHudScreen {
    private final BodyInventoryHud bodyInventoryHud;
    private final CrosshairWidget crosshair;
    private final BreakProgressWidget breakProgress;
    private final GameModeWidget gameMode;
    private final InteractionFailureWidget failure;
    private final DebugHud debug;

    public GaiaHudScreen(UiIconResolver icons, TextRenderer text) {
        bodyInventoryHud = new BodyInventoryHud(
                Objects.requireNonNull(icons, "icons"),
                Objects.requireNonNull(text, "text"));
        crosshair = new CrosshairWidget();
        breakProgress = new BreakProgressWidget();
        gameMode = new GameModeWidget(text);
        failure = new InteractionFailureWidget(text);
        debug = new DebugHud(text);
    }

    public UiFrame compose(HudPresentationSnapshot snapshot, UiLayoutContext layout) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(layout, "layout");
        UiDrawList out = new UiDrawList();
        bodyInventoryHud.append(snapshot, layout, out);
        crosshair.append(snapshot, layout, out);
        breakProgress.append(snapshot, layout, out);
        gameMode.append(snapshot, layout, out);
        failure.append(snapshot, layout, out);
        debug.append(snapshot, layout, out);
        return out.seal();
    }
}
