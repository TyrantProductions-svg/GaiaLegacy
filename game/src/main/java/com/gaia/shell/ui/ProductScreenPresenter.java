package com.gaia.shell.ui;

import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.settings.SettingsSnapshot;
import com.gaia.shell.ModalId;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenId;
import com.gaia.shell.save.SaveCatalog;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Pure product-screen presentation built from immutable route and save summaries. */
public final class ProductScreenPresenter {
    private static final UiUvRect SOLID_UV = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
    private static final UiColor BACKDROP = new UiColor(0.025f, 0.035f, 0.055f, 0.96f);
    private static final UiColor PANEL = new UiColor(0.08f, 0.10f, 0.15f, 0.98f);
    private static final UiColor BUTTON = new UiColor(0.16f, 0.20f, 0.29f, 1.0f);
    private static final UiColor SELECTED_BUTTON = new UiColor(0.24f, 0.34f, 0.50f, 1.0f);
    private static final UiColor DISABLED_BUTTON = new UiColor(0.09f, 0.11f, 0.15f, 1.0f);
    private static final UiColor TEXT = new UiColor(0.91f, 0.94f, 1.0f, 1.0f);
    private static final UiColor DISABLED_TEXT = new UiColor(0.42f, 0.45f, 0.52f, 1.0f);
    private static final double BUTTON_WIDTH = 300.0d;
    private static final double BUTTON_HEIGHT = 42.0d;
    private static final double BUTTON_GAP = 10.0d;
    private static final double SETTINGS_ROW_HEIGHT = 38.0d;
    private static final double SETTINGS_ROW_GAP = 4.0d;
    private static final double SETTINGS_CONTROL_WIDTH = 42.0d;
    private static final double SETTINGS_CONTROL_GAP = 6.0d;

    private final SaveCatalog saves;
    private final TextRenderer text;
    private final Supplier<SettingsDraftSnapshot> settings;

    public ProductScreenPresenter(SaveCatalog saves, TextRenderer text) {
        this(saves, text, ProductScreenPresenter::defaultSettings);
    }

    public ProductScreenPresenter(
            SaveCatalog saves,
            TextRenderer text,
            Supplier<SettingsDraftSnapshot> settings) {
        this.saves = Objects.requireNonNull(saves, "saves");
        this.text = Objects.requireNonNull(text, "text");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ProductUiLayout present(ProductShellSnapshot snapshot, UiLayoutContext context) {
        return present(snapshot, context, Optional.empty());
    }

    public ProductUiLayout present(
            ProductShellSnapshot snapshot,
            UiLayoutContext context,
            Optional<UiActionId> focusedAction) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(focusedAction, "focusedAction");

        if (snapshot.screen() == ScreenId.PLAYING && snapshot.modal().isEmpty()) {
            return new ProductUiLayout(UiFrame.empty(), List.of(), context);
        }

        UiDrawList draw = new UiDrawList();
        List<UiHitRegion> hitRegions = new ArrayList<>();
        appendSolid(context.safeArea(), BACKDROP, context, draw);

        if (snapshot.modal().isPresent()) {
            presentModal(
                    snapshot.modal().orElseThrow(),
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
        } else {
            presentScreen(snapshot.screen(), focusedAction, context, draw, hitRegions);
        }
        return new ProductUiLayout(draw.seal(), hitRegions, context);
    }

    private void presentScreen(
            ScreenId screen,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        switch (screen) {
            case MAIN_MENU -> presentMainMenu(focusedAction, context, draw, hitRegions);
            case PAUSED -> presentPause(focusedAction, context, draw, hitRegions);
            case CONTROLS -> presentControls(focusedAction, context, draw, hitRegions);
            case SETTINGS -> presentSettings(
                    Objects.requireNonNull(settings.get(), "settings snapshot"),
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
            case LOADING -> presentLoading(focusedAction, context, draw, hitRegions);
            case PLAYING -> {
                // Gameplay and its HUD are composed by the session rather than the product shell.
            }
        }
    }

    private void presentMainMenu(
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        List.copyOf(Objects.requireNonNull(saves.summaries(), "save summaries"));
        appendTitle("GAIA LEGACY", context, draw);
        appendButtons(
                List.of(
                        new Button(UiActionId.NEW_WORLD, "NEW WORLD", true),
                        new Button(
                                UiActionId.LOAD_WORLD,
                                "LOAD WORLD - AVAILABLE IN PHASE 14",
                                false),
                        new Button(UiActionId.SETTINGS, "SETTINGS", true),
                        new Button(UiActionId.CONTROLS, "CONTROLS", true),
                        new Button(UiActionId.QUIT, "QUIT", true)),
                226.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentPause(
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendTitle("PAUSED", context, draw);
        appendButtons(
                List.of(
                        new Button(UiActionId.RESUME, "RESUME", true),
                        new Button(UiActionId.SETTINGS, "SETTINGS", true),
                        new Button(UiActionId.CONTROLS, "CONTROLS", true),
                        new Button(UiActionId.RETURN_TO_MAIN_MENU, "RETURN TO MAIN MENU", true)),
                250.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentControls(
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendTitle("CONTROLS", context, draw);
        double centerY = context.logicalHeight() / 2.0d - 50.0d;
        appendCenteredText("WASD  MOVE", centerY, TEXT, context, draw);
        appendCenteredText("MOUSE  LOOK", centerY + 24.0d, TEXT, context, draw);
        appendCenteredText("ESC  BACK", centerY + 48.0d, TEXT, context, draw);
        appendButtons(
                List.of(new Button(UiActionId.BACK, "BACK", true)),
                context.logicalHeight() - 92.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentSettings(
            SettingsDraftSnapshot settingsSnapshot,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        SettingsSnapshot applied = settingsSnapshot.applied();
        SettingsSnapshot draft = settingsSnapshot.draft();
        boolean controlsEnabled = settingsSnapshot.blockingDiagnostic().isEmpty();
        appendCenteredText("SETTINGS", 32.0d, TEXT, context, draw);

        double rowTop = 48.0d;
        rowTop = appendSettingsRow(
                "VSYNC  APPLIED "
                        + onOff(applied.vsync())
                        + "  DRAFT "
                        + onOff(draft.vsync()),
                List.of(new Button(
                        UiActionId.VSYNC_TOGGLE, "TOGGLE", controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                String.format(
                        Locale.ROOT,
                        "FOV  APPLIED %.0f  DRAFT %.0f  RANGE 50-100 DEG",
                        applied.fovDegrees(),
                        draft.fovDegrees()),
                List.of(
                        new Button(
                                UiActionId.FOV_DECREMENT, "-", controlsEnabled),
                        new Button(
                                UiActionId.FOV_INCREMENT, "+", controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                String.format(
                        Locale.ROOT,
                        "MOUSE SENSITIVITY  APPLIED %.2f  DRAFT %.2f  RANGE 0.02-0.50",
                        applied.mouseSensitivity(),
                        draft.mouseSensitivity()),
                List.of(
                        new Button(
                                UiActionId.MOUSE_SENSITIVITY_DECREMENT,
                                "-",
                                controlsEnabled),
                        new Button(
                                UiActionId.MOUSE_SENSITIVITY_INCREMENT,
                                "+",
                                controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                "INVERT Y  APPLIED "
                        + onOff(applied.invertY())
                        + "  DRAFT "
                        + onOff(draft.invertY()),
                List.of(new Button(
                        UiActionId.INVERT_Y_TOGGLE, "TOGGLE", controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                "RENDER DISTANCE  APPLIED "
                        + applied.chunkRadius()
                        + "  DRAFT "
                        + draft.chunkRadius()
                        + "  RANGE 2-8  NEXT NEW WORLD",
                List.of(
                        new Button(
                                UiActionId.CHUNK_RADIUS_DECREMENT,
                                "-",
                                controlsEnabled),
                        new Button(
                                UiActionId.CHUNK_RADIUS_INCREMENT,
                                "+",
                                controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                volumeRow("MASTER VOLUME", applied.masterVolume(), draft.masterVolume()),
                List.of(
                        new Button(
                                UiActionId.MASTER_VOLUME_DECREMENT,
                                "-",
                                controlsEnabled),
                        new Button(
                                UiActionId.MASTER_VOLUME_INCREMENT,
                                "+",
                                controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                volumeRow("MUSIC VOLUME", applied.musicVolume(), draft.musicVolume()),
                List.of(
                        new Button(
                                UiActionId.MUSIC_VOLUME_DECREMENT,
                                "-",
                                controlsEnabled),
                        new Button(
                                UiActionId.MUSIC_VOLUME_INCREMENT,
                                "+",
                                controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                volumeRow("SFX VOLUME", applied.sfxVolume(), draft.sfxVolume()),
                List.of(
                        new Button(
                                UiActionId.SFX_VOLUME_DECREMENT,
                                "-",
                                controlsEnabled),
                        new Button(
                                UiActionId.SFX_VOLUME_INCREMENT,
                                "+",
                                controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                "MUTE WHEN UNFOCUSED  APPLIED "
                        + onOff(applied.muteWhenUnfocused())
                        + "  DRAFT "
                        + onOff(draft.muteWhenUnfocused()),
                List.of(new Button(
                        UiActionId.MUTE_WHEN_UNFOCUSED_TOGGLE,
                        "TOGGLE",
                        controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        rowTop = appendSettingsRow(
                "DEFAULT GAME MODE  APPLIED "
                        + applied.defaultGameMode().name()
                        + "  DRAFT "
                        + draft.defaultGameMode().name()
                        + "  NEXT NEW WORLD",
                List.of(new Button(
                        UiActionId.DEFAULT_GAME_MODE_TOGGLE,
                        "TOGGLE",
                        controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);
        appendSettingsRow(
                "DEBUG HUD DEFAULT  APPLIED "
                        + onOff(applied.debugHudDefault())
                        + "  DRAFT "
                        + onOff(draft.debugHudDefault())
                        + "  NEXT GAME SESSION",
                List.of(new Button(
                        UiActionId.DEBUG_HUD_DEFAULT_TOGGLE,
                        "TOGGLE",
                        controlsEnabled)),
                rowTop,
                focusedAction,
                context,
                draw,
                hitRegions);

        if (!controlsEnabled) {
            appendCenteredText(
                    "SETTINGS APPLY BLOCKED - RESTART REQUIRED",
                    context.logicalHeight() - 82.0d,
                    TEXT,
                    context,
                    draw);
        }
        appendSettingsFooter(
                settingsSnapshot.dirty() && controlsEnabled,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private double appendSettingsRow(
            String value,
            List<Button> controls,
            double top,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        double left = 28.0d;
        double right = context.logicalWidth() - 28.0d;
        UiRect rowBounds = new UiRect(
                left, top, right, top + SETTINGS_ROW_HEIGHT);
        appendSolid(rowBounds, PANEL, context, draw);
        appendText(value, left + 12.0d, top + 25.0d, TEXT, context, draw);

        double controlWidth = controls.size() == 1
                ? SETTINGS_CONTROL_WIDTH * 2.0d
                : SETTINGS_CONTROL_WIDTH;
        double controlsWidth = controls.size() * controlWidth
                + Math.max(0, controls.size() - 1) * SETTINGS_CONTROL_GAP;
        double controlLeft = right - controlsWidth - 8.0d;
        UiActionId selected = selectedAction(controls, focusedAction);
        for (int index = 0; index < controls.size(); index++) {
            Button control = controls.get(index);
            double x = controlLeft
                    + index * (controlWidth + SETTINGS_CONTROL_GAP);
            appendActionButton(
                    control,
                    new UiRect(
                            x,
                            top + 4.0d,
                            x + controlWidth,
                            top + SETTINGS_ROW_HEIGHT - 4.0d),
                    selected,
                    context,
                    draw,
                    hitRegions);
        }
        return top + SETTINGS_ROW_HEIGHT + SETTINGS_ROW_GAP;
    }

    private void appendSettingsFooter(
            boolean applyEnabled,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        List<Button> buttons = List.of(
                new Button(UiActionId.APPLY_SETTINGS, "APPLY", applyEnabled),
                new Button(UiActionId.BACK, "BACK", true));
        UiActionId selected = selectedAction(buttons, focusedAction);
        double width = 160.0d;
        double gap = 12.0d;
        double left = context.logicalWidth() / 2.0d - width - gap / 2.0d;
        double top = context.logicalHeight() - 54.0d;
        for (int index = 0; index < buttons.size(); index++) {
            double x = left + index * (width + gap);
            appendActionButton(
                    buttons.get(index),
                    new UiRect(x, top, x + width, top + BUTTON_HEIGHT),
                    selected,
                    context,
                    draw,
                    hitRegions);
        }
    }

    private void presentDirtySettingsModal(
            SettingsDraftSnapshot settingsSnapshot,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        double centerX = context.logicalWidth() / 2.0d;
        double centerY = context.logicalHeight() / 2.0d;
        UiRect panel = new UiRect(
                centerX - 220.0d,
                centerY - 132.0d,
                centerX + 220.0d,
                centerY + 132.0d);
        appendSolid(panel, PANEL, context, draw);
        boolean transactionEnabled = settingsSnapshot.blockingDiagnostic().isEmpty();
        appendCenteredText(
                transactionEnabled
                        ? "APPLY SETTINGS CHANGES?"
                        : "SETTINGS APPLY BLOCKED - RESTART REQUIRED",
                centerY - 54.0d,
                TEXT,
                context,
                draw);
        appendButtons(
                List.of(
                        new Button(
                                UiActionId.APPLY_SETTINGS,
                                "APPLY",
                                transactionEnabled),
                        new Button(
                                UiActionId.DISCARD_SETTINGS,
                                "DISCARD",
                                transactionEnabled),
                        new Button(
                                UiActionId.CANCEL_SETTINGS,
                                "CANCEL",
                                true)),
                centerY - 16.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentSecondary(
            String title,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendTitle(title, context, draw);
        appendButtons(
                List.of(new Button(UiActionId.BACK, "BACK", true)),
                context.logicalHeight() - 92.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentLoading(
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        double centerY = context.logicalHeight() / 2.0d;
        appendCenteredText("LOADING", centerY - 42.0d, TEXT, context, draw);
        appendButtons(
                List.of(new Button(UiActionId.DISMISS, "CANCEL", true)),
                centerY + 8.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentModal(
            ModalId modal,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        if (modal == ModalId.DIRTY_SETTINGS_CONFIRMATION) {
            presentDirtySettingsModal(
                    Objects.requireNonNull(settings.get(), "settings snapshot"),
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
            return;
        }
        double centerX = context.logicalWidth() / 2.0d;
        double centerY = context.logicalHeight() / 2.0d;
        UiRect panel = new UiRect(centerX - 220.0d, centerY - 112.0d,
                centerX + 220.0d, centerY + 112.0d);
        appendSolid(panel, PANEL, context, draw);

        String message = switch (modal) {
            case QUIT_CONFIRMATION -> "QUIT GAIA LEGACY?";
            case UNSAVED_PROGRESS_CONFIRMATION -> "DISCARD UNSAVED PROGRESS?";
            case DIRTY_SETTINGS_CONFIRMATION -> throw new IllegalStateException(
                    "Dirty settings use their typed modal");
            case ERROR_ACKNOWLEDGEMENT -> "AN ERROR OCCURRED";
        };
        appendCenteredText(message, centerY - 42.0d, TEXT, context, draw);

        List<Button> buttons = modal == ModalId.ERROR_ACKNOWLEDGEMENT
                ? List.of(new Button(UiActionId.DISMISS, "OK", true))
                : List.of(
                        new Button(UiActionId.CONFIRM, "CONFIRM", true),
                        new Button(UiActionId.DISMISS, "CANCEL", true));
        appendButtons(
                buttons, centerY + 8.0d, focusedAction, context, draw, hitRegions);
    }

    private void appendTitle(String title, UiLayoutContext context, UiDrawList draw) {
        appendCenteredText(title, 140.0d, TEXT, context, draw);
    }

    private void appendActionButton(
            Button button,
            UiRect logicalBounds,
            UiActionId selectedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendSolid(
                logicalBounds,
                button.enabled()
                        ? (button.action() == selectedAction
                                ? SELECTED_BUTTON
                                : BUTTON)
                        : DISABLED_BUTTON,
                context,
                draw);
        appendButtonText(
                button.label(),
                logicalBounds,
                button.enabled() ? TEXT : DISABLED_TEXT,
                context,
                draw);
        hitRegions.add(new UiHitRegion(
                button.action(),
                logicalBounds,
                context.safeArea(),
                button.enabled(),
                context.contentScaleX(),
                context.contentScaleY()));
    }

    private void appendButtons(
            List<Button> buttons,
            double top,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        UiActionId selectedAction = selectedAction(buttons, focusedAction);
        double left = context.logicalWidth() / 2.0d - BUTTON_WIDTH / 2.0d;
        for (int index = 0; index < buttons.size(); index++) {
            Button button = buttons.get(index);
            double buttonTop = top + index * (BUTTON_HEIGHT + BUTTON_GAP);
            UiRect logicalBounds = new UiRect(
                    left, buttonTop, left + BUTTON_WIDTH, buttonTop + BUTTON_HEIGHT);
            appendSolid(
                    logicalBounds,
                    button.enabled()
                            ? (button.action() == selectedAction ? SELECTED_BUTTON : BUTTON)
                            : DISABLED_BUTTON,
                    context,
                    draw);
            appendButtonText(button.label(), logicalBounds,
                    button.enabled() ? TEXT : DISABLED_TEXT, context, draw);
            hitRegions.add(new UiHitRegion(
                    button.action(),
                    logicalBounds,
                    context.safeArea(),
                    button.enabled(),
                    context.contentScaleX(),
                    context.contentScaleY()));
        }
    }

    private static UiActionId selectedAction(
            List<Button> buttons,
            Optional<UiActionId> focusedAction) {
        return buttons.stream()
                .filter(Button::enabled)
                .filter(button -> focusedAction.orElse(null) == button.action())
                .map(Button::action)
                .findFirst()
                .orElse(null);
    }

    private void appendButtonText(
            String label,
            UiRect logicalBounds,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        double scaleX = context.contentScaleX();
        double scaleY = context.contentScaleY();
        double width = text.measure(label, scaleX);
        double x = context.snapX((logicalBounds.left() + logicalBounds.right()) / 2.0d)
                - width / 2.0d;
        double baseline = context.snapY(logicalBounds.top() + 27.0d);
        text.append(label, x, baseline, scaleX, scaleY, color, Optional.empty(), draw);
    }

    private void appendCenteredText(
            String value,
            double logicalBaseline,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        double scaleX = context.contentScaleX();
        double scaleY = context.contentScaleY();
        double width = text.measure(value, scaleX);
        double x = context.snapX(context.logicalWidth() / 2.0d) - width / 2.0d;
        text.append(value, x, context.snapY(logicalBaseline), scaleX, scaleY,
                color, Optional.empty(), draw);
    }

    private void appendText(
            String value,
            double logicalX,
            double logicalBaseline,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        text.append(
                value,
                context.snapX(logicalX),
                context.snapY(logicalBaseline),
                context.contentScaleX(),
                context.contentScaleY(),
                color,
                Optional.empty(),
                draw);
    }

    private static SettingsDraftSnapshot defaultSettings() {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        return new SettingsDraftSnapshot(
                defaults, defaults, false, Optional.empty());
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static String volumeRow(
            String label, double applied, double draft) {
        return label
                + "  APPLIED "
                + Math.round(applied * 100.0d)
                + "%  DRAFT "
                + Math.round(draft * 100.0d)
                + "%  RANGE 0-100%";
    }

    private static void appendSolid(
            UiRect logicalBounds,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        draw.append(new UiDrawCommand(
                UiTextureId.SOLID,
                context.toFramebuffer(logicalBounds),
                SOLID_UV,
                color,
                Optional.empty()));
    }

    private record Button(UiActionId action, String label, boolean enabled) {
        private Button {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(label, "label");
        }
    }
}
