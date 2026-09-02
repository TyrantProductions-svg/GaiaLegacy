package com.gaia.shell.ui;

import com.gaia.ui.GaiaUiTheme;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.settings.SettingsSnapshot;
import com.gaia.shell.ModalId;
import com.gaia.shell.OperationProgressSnapshot;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.NewWorldDraftSnapshot;
import com.gaia.shell.world.WorldSlotsController;
import com.gaia.shell.world.WorldSlotsSnapshot;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.TypographyRole;
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
    private static final UiColor BACKDROP = new UiColor(0.024f, 0.067f, 0.118f, 0.82f);
    private static final UiColor PANEL = GaiaUiTheme.SECONDARY_PANEL;
    private static final UiColor BUTTON = new UiColor(0.035f, 0.10f, 0.16f, 0.72f);
    private static final UiColor SELECTED_BUTTON = new UiColor(0.18f, 0.52f, 0.62f, 0.42f);
    private static final UiColor DISABLED_BUTTON = new UiColor(0.03f, 0.07f, 0.10f, 0.58f);
    private static final UiColor TEXT = GaiaUiTheme.PRIMARY_TEXT;
    private static final UiColor DISABLED_TEXT = new UiColor(0.42f, 0.45f, 0.52f, 1.0f);
    private static final double BUTTON_WIDTH = 300.0d;
    private static final double BUTTON_HEIGHT = 42.0d;
    private static final double BUTTON_GAP = 10.0d;
    private static final double SETTINGS_ROW_HEIGHT = 38.0d;
    private static final double SETTINGS_ROW_GAP = 4.0d;
    private static final double SETTINGS_CONTROL_WIDTH = 42.0d;
    private static final double SETTINGS_CONTROL_GAP = 6.0d;

    private final TextRenderer text;
    private final Supplier<SettingsDraftSnapshot> settings;
    private final NewWorldDraftController newWorldDraft;
    private final WorldSlotsController worldSlots;

    public ProductScreenPresenter(SaveCatalog saves, TextRenderer text) {
        this(
                saves,
                text,
                ProductScreenPresenter::defaultSettings,
                new NewWorldDraftController(saves),
                new WorldSlotsController(saves, 4));
    }

    public ProductScreenPresenter(
            SaveCatalog saves,
            TextRenderer text,
            Supplier<SettingsDraftSnapshot> settings) {
        this(
                saves,
                text,
                settings,
                new NewWorldDraftController(saves),
                new WorldSlotsController(saves, 4));
    }

    public ProductScreenPresenter(
            SaveCatalog saves,
            TextRenderer text,
            Supplier<SettingsDraftSnapshot> settings,
            NewWorldDraftController newWorldDraft,
            WorldSlotsController worldSlots) {
        Objects.requireNonNull(saves, "saves");
        this.text = Objects.requireNonNull(text, "text");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.newWorldDraft = Objects.requireNonNull(newWorldDraft, "newWorldDraft");
        this.worldSlots = Objects.requireNonNull(worldSlots, "worldSlots");
    }

    public ProductUiLayout present(ProductShellSnapshot snapshot, UiLayoutContext context) {
        return presentFocused(snapshot, context, Optional.empty());
    }

    public ProductUiLayout present(
            ProductShellSnapshot snapshot,
            UiLayoutContext context,
            Optional<UiActionId> focusedAction) {
        Objects.requireNonNull(focusedAction, "focusedAction");
        return presentFocused(
                snapshot,
                context,
                focusedAction.map(action -> (UiControlId) action));
    }

    public ProductUiLayout presentFocused(
            ProductShellSnapshot snapshot,
            UiLayoutContext context,
            Optional<UiControlId> focusedControl) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(focusedControl, "focusedControl");
        Optional<UiActionId> focusedAction = focusedControl
                .filter(UiActionId.class::isInstance)
                .map(UiActionId.class::cast);

        if (snapshot.screen() == ScreenId.PLAYING && snapshot.modal().isEmpty()) {
            return new ProductUiLayout(UiFrame.empty(), List.of(), context);
        }

        UiDrawList draw = new UiDrawList();
        List<UiHitRegion> hitRegions = new ArrayList<>();
        appendHero(context, draw);
        if (snapshot.modal().isPresent() || snapshot.screen() != ScreenId.MAIN_MENU) {
            appendSolid(context.safeArea(), BACKDROP, context, draw);
        } else {
            appendSolid(
                    new UiRect(0.0d, 0.0d,
                            Math.min(510.0d, context.logicalWidth() * 0.44d),
                            context.logicalHeight()),
                    GaiaUiTheme.HERO_LEFT_OVERLAY,
                    context,
                    draw);
        }

        if (snapshot.modal().isPresent()) {
            presentModal(
                    snapshot.modal().orElseThrow(),
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
        } else {
            presentScreen(
                    snapshot.screen(),
                    snapshot.operationProgress(),
                    snapshot.operationAnimationStep(),
                    focusedAction,
                    focusedControl,
                    context,
                    draw,
                    hitRegions);
        }
        return new ProductUiLayout(draw.seal(), hitRegions, context);
    }

    private void presentScreen(
            ScreenId screen,
            Optional<OperationProgressSnapshot> operationProgress,
            int operationAnimationStep,
            Optional<UiActionId> focusedAction,
            Optional<UiControlId> focusedControl,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        switch (screen) {
            case MAIN_MENU -> presentMainMenu(focusedAction, context, draw, hitRegions);
            case NEW_WORLD_SETUP -> presentNewWorld(
                    newWorldDraft.snapshot(), focusedAction, context, draw, hitRegions);
            case WORLD_SLOTS -> presentWorldSlots(
                    worldSlots.snapshot(), focusedControl, context, draw, hitRegions);
            case PAUSED -> presentPause(focusedAction, context, draw, hitRegions);
            case CONTROLS -> presentControls(focusedAction, context, draw, hitRegions);
            case SETTINGS -> presentSettings(
                    Objects.requireNonNull(settings.get(), "settings snapshot"),
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
            case LOADING -> presentOperation(
                    operationProgress.orElseGet(() -> fallbackProgress(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "PREPARING", "Waiting for load work", true)),
                    "LOADING WORLD",
                    operationAnimationStep,
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
            case SAVING -> presentOperation(
                    operationProgress.orElseGet(() -> fallbackProgress(
                            OperationProgressSnapshot.Kind.SAVE_WORLD,
                            "PREPARING", "Waiting for save work", false)),
                    "SAVING WORLD",
                    operationAnimationStep,
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
            case PLAYING -> {
                // Gameplay and its HUD are composed by the session rather than the product shell.
            }
        }
    }

    private void presentNewWorld(
            NewWorldDraftSnapshot draft,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendTitle("NEW WORLD", context, draw);
        double left = context.logicalWidth() / 2.0d - BUTTON_WIDTH / 2.0d;
        appendField(
                UiActionId.NEW_WORLD_NAME,
                "NAME  " + draft.name(),
                new UiRect(left, 220.0d, left + BUTTON_WIDTH, 262.0d),
                draft.focusedField() == NewWorldDraftSnapshot.Field.NAME,
                context,
                draw,
                hitRegions);
        appendField(
                UiActionId.NEW_WORLD_SEED,
                "SEED  " + draft.seedText(),
                new UiRect(left, 278.0d, left + BUTTON_WIDTH, 320.0d),
                draft.focusedField() == NewWorldDraftSnapshot.Field.SEED,
                context,
                draw,
                hitRegions);
        draft.diagnostic().ifPresent(value -> appendCenteredText(
                switch (value) {
                    case INVALID_NAME -> "WORLD NAME IS INVALID";
                    case DUPLICATE_NAME -> "WORLD NAME ALREADY EXISTS";
                    case INVALID_SEED -> "SEED MUST BE A SIGNED 64-BIT INTEGER";
                },
                350.0d,
                DISABLED_TEXT,
                context,
                draw));
        appendButtons(
                List.of(
                        new Button(UiActionId.CREATE_WORLD, "CREATE", true),
                        new Button(UiActionId.BACK, "BACK", true)),
                390.0d,
                focusedAction,
                context,
                draw,
                hitRegions);
    }

    private void presentWorldSlots(
            WorldSlotsSnapshot slots,
            Optional<UiControlId> focusedControl,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        double normalRowsBottom = 190 + Math.max(0, slots.rows().size() - 1) * 92 + 50;
        double footerSpace = slots.pageCount() > 1 ? 196 : 92;
        boolean compact = context.logicalHeight() < normalRowsBottom + footerSpace + 8;
        appendCenteredText("WORLD SLOTS", TypographyRole.HEADING_LARGE,
                compact ? 60 : 140, TEXT, context, draw);
        if (slots.rows().isEmpty()) {
            appendCenteredText("NO SAVED WORLDS", 300.0d, DISABLED_TEXT, context, draw);
        }
        double rowTop = compact ? 88.0d : 190.0d;
        for (SaveSummary row : slots.rows()) {
            appendCenteredText(
                    row.name()
                            + "  "
                            + row.health().name()
                            + row.worldSeed().map(seed -> "  SEED " + seed).orElse(""),
                    rowTop,
                    TEXT,
                    context,
                    draw);
            Optional<ScreenCommand> primary = worldSlots.primaryCommand(row.id());
            if (primary.isPresent()) {
                WorldSlotControlId.WorldSlotAction action =
                        primary.orElseThrow() instanceof ScreenCommand.LoadWorld
                                ? WorldSlotControlId.WorldSlotAction.LOAD
                                : WorldSlotControlId.WorldSlotAction.RECOVER;
                appendDynamicButton(
                        new WorldSlotControlId(row.id(), action),
                        primary.orElseThrow(),
                        action == WorldSlotControlId.WorldSlotAction.LOAD ? "LOAD" : "RECOVER",
                        new UiRect(
                                context.logicalWidth() / 2.0d - 150.0d,
                                rowTop + 12.0d,
                                context.logicalWidth() / 2.0d - 6.0d,
                                rowTop + (compact ? 42.0d : 50.0d)),
                        true,
                        focusedControl.filter(id -> id.equals(new WorldSlotControlId(
                                row.id(), action))).isPresent(),
                        context,
                        draw,
                        hitRegions);
            }
            appendDynamicButton(
                    new WorldSlotControlId(
                            row.id(), WorldSlotControlId.WorldSlotAction.DELETE),
                    worldSlots.deleteCommand(row.id()),
                    "DELETE",
                    new UiRect(
                            context.logicalWidth() / 2.0d + 6.0d,
                            rowTop + 12.0d,
                            context.logicalWidth() / 2.0d + 150.0d,
                            rowTop + (compact ? 42.0d : 50.0d)),
                    true,
                    focusedControl.filter(id -> id.equals(new WorldSlotControlId(
                            row.id(), WorldSlotControlId.WorldSlotAction.DELETE))).isPresent(),
                    context,
                    draw,
                    hitRegions);
            rowTop += compact ? 54.0d : 92.0d;
        }
        if (slots.pageCount() > 1 && compact) {
            List<Button> paging = List.of(
                    new Button(UiActionId.WORLD_SLOTS_PREVIOUS, "PREVIOUS", slots.hasPreviousPage()),
                    new Button(UiActionId.WORLD_SLOTS_NEXT, "NEXT", slots.hasNextPage()));
            for (int index = 0; index < paging.size(); index++) {
                double left = context.logicalWidth() / 2 - 150 + index * 156;
                double top = context.logicalHeight() - 146;
                appendActionButton(paging.get(index), new UiRect(left, top, left + 144, top + 38),
                        selectedAction(paging, Optional.empty()), context, draw, hitRegions);
            }
        } else if (slots.pageCount() > 1) {
            appendButtons(
                    List.of(
                            new Button(
                                    UiActionId.WORLD_SLOTS_PREVIOUS,
                                    "PREVIOUS",
                                    slots.hasPreviousPage()),
                            new Button(
                                    UiActionId.WORLD_SLOTS_NEXT,
                                    "NEXT",
                                    slots.hasNextPage())),
                    context.logicalHeight() - 196.0d,
                    Optional.empty(),
                    context,
                    draw,
                    hitRegions);
        }
        appendButtons(
                List.of(new Button(UiActionId.BACK, "BACK", true)),
                context.logicalHeight() - 92.0d,
                Optional.empty(),
                context,
                draw,
                hitRegions);
    }

    private void appendField(
            UiActionId id,
            String label,
            UiRect bounds,
            boolean selected,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendSolid(bounds, selected ? SELECTED_BUTTON : BUTTON, context, draw);
        appendButtonText(label, bounds, TEXT, context, draw);
        hitRegions.add(new UiHitRegion(
                id,
                ScreenCommand.none(),
                bounds,
                context.safeArea(),
                true,
                context.contentScaleX(),
                context.contentScaleY()));
    }

    private void appendDynamicButton(
            WorldSlotControlId id,
            ScreenCommand command,
            String label,
            UiRect bounds,
            boolean enabled,
            boolean selected,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendSolid(
                bounds,
                enabled ? (selected ? SELECTED_BUTTON : BUTTON) : DISABLED_BUTTON,
                context,
                draw);
        appendButtonText(label, bounds, enabled ? TEXT : DISABLED_TEXT, context, draw);
        hitRegions.add(new UiHitRegion(
                id,
                command,
                bounds,
                context.safeArea(),
                enabled,
                context.contentScaleX(),
                context.contentScaleY()));
    }

    private void presentMainMenu(
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        appendWordmark(context, draw);
        List<Button> buttons = List.of(
                new Button(UiActionId.NEW_WORLD, "NEW WORLD", true),
                new Button(UiActionId.LOAD_WORLD, "WORLD ARCHIVE", worldSlots.hasRows()),
                new Button(UiActionId.SETTINGS, "SETTINGS", true),
                new Button(UiActionId.CONTROLS, "CONTROLS", true),
                new Button(UiActionId.QUIT, "QUIT", true));
        appendMainMenuButtons(buttons, focusedAction, context, draw, hitRegions);
        appendText("GAIA // FRONTIER CHANNEL 01", TypographyRole.BODY,
                84.0d, context.logicalHeight() - 30.0d, 0.78d,
                DISABLED_TEXT, context, draw);
        String version = "v0.2 // MILESTONE 2";
        double versionScale = 0.78d;
        double width = text.measure(
                version, TypographyRole.BODY,
                context.contentScaleX() * versionScale);
        appendText(version, TypographyRole.BODY,
                context.logicalWidth() - 34.0d - width / context.contentScaleX(),
                context.logicalHeight() - 30.0d,
                versionScale, DISABLED_TEXT, context, draw);
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
                        new Button(UiActionId.SAVE, "SAVE", true),
                        new Button(UiActionId.SAVE_AND_QUIT, "SAVE & QUIT", true),
                        new Button(UiActionId.SETTINGS, "SETTINGS", true),
                        new Button(UiActionId.CONTROLS, "CONTROLS", true),
                        new Button(UiActionId.RETURN_TO_MAIN_MENU, "RETURN TO MAIN MENU", true)),
                178.0d,
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
        appendCenteredText(
                "SETTINGS", TypographyRole.HEADING_LARGE, 38.0d, TEXT, context, draw);

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
                    context.logicalHeight() - 68.0d,
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
        double rowHeight = Math.min(SETTINGS_ROW_HEIGHT, Math.max(28,
                (context.logicalHeight() - 48 - 92) / 11 - SETTINGS_ROW_GAP));
        UiRect rowBounds = new UiRect(
                left, top, right, top + rowHeight);
        appendSolid(rowBounds, PANEL, context, draw);
        appendText(value, left + 12.0d, top + Math.min(25, rowHeight - 8), TEXT, context, draw);

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
                            top + rowHeight - 4.0d),
                    selected,
                    context,
                    draw,
                    hitRegions);
        }
        return top + rowHeight + SETTINGS_ROW_GAP;
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
                TypographyRole.HEADING_LARGE,
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

    private void presentOperation(
            OperationProgressSnapshot progress,
            String title,
            int animationStep,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        double centerY = context.logicalHeight() / 2.0d;
        appendCenteredText(title, centerY - 92.0d, TEXT, context, draw);
        appendCenteredText(progress.phase(), centerY - 58.0d, TEXT, context, draw);
        appendCenteredText(progress.status(), centerY - 34.0d, TEXT, context, draw);
        progress.exactUnitsText().ifPresent(units ->
                appendCenteredText(units, centerY - 10.0d, TEXT, context, draw));
        UiRect track = new UiRect(
                context.logicalWidth() / 2.0d - 180.0d,
                centerY + 16.0d,
                context.logicalWidth() / 2.0d + 180.0d,
                centerY + 34.0d);
        appendSolid(track, DISABLED_BUTTON, context, draw);
        if (progress.fraction().isPresent()) {
            double right = track.left()
                    + (track.right() - track.left())
                            * progress.fraction().orElseThrow();
            if (right > track.left()) {
                appendSolid(
                        new UiRect(track.left(), track.top(), right, track.bottom()),
                        SELECTED_BUTTON,
                        context,
                        draw);
            }
        } else {
            double segmentWidth = 72.0d;
            double travel = track.right() - track.left() - segmentWidth;
            double left = track.left() + travel * animationStep / 59.0d;
            appendSolid(
                    new UiRect(left, track.top(), left + segmentWidth, track.bottom()),
                    SELECTED_BUTTON,
                    context,
                    draw);
        }
        progress.detail().ifPresent(detail ->
                appendCenteredText(detail, centerY + 52.0d, DISABLED_TEXT, context, draw));
        if (progress.cancelable()
                && progress.terminalState()
                        == OperationProgressSnapshot.TerminalState.RUNNING) {
            appendButtons(
                    List.of(new Button(UiActionId.DISMISS, "CANCEL", true)),
                    centerY + 74.0d,
                    focusedAction,
                    context,
                    draw,
                    hitRegions);
        }
    }

    private static OperationProgressSnapshot fallbackProgress(
            OperationProgressSnapshot.Kind kind,
            String phase,
            String status,
            boolean cancelable) {
        return OperationProgressSnapshot.indeterminate(
                kind, phase, status, cancelable);
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
            case DELETE_WORLD_CONFIRMATION -> "DELETE THIS WORLD?";
            case RECOVER_BACKUP_CONFIRMATION -> "RECOVER THIS BACKUP?";
            case ERROR_ACKNOWLEDGEMENT -> "AN ERROR OCCURRED";
        };
        appendCenteredText(
                message, TypographyRole.HEADING_LARGE,
                centerY - 42.0d, TEXT, context, draw);

        List<Button> buttons = modal == ModalId.ERROR_ACKNOWLEDGEMENT
                ? List.of(new Button(UiActionId.DISMISS, "OK", true))
                : List.of(
                        new Button(UiActionId.CONFIRM, "CONFIRM", true),
                        new Button(UiActionId.DISMISS, "CANCEL", true));
        appendButtons(
                buttons, centerY + 8.0d, focusedAction, context, draw, hitRegions);
    }

    private void appendTitle(String title, UiLayoutContext context, UiDrawList draw) {
        appendCenteredText(
                title, TypographyRole.HEADING_LARGE, 140.0d, TEXT, context, draw);
    }

    private void appendWordmark(UiLayoutContext context, UiDrawList draw) {
        double x = 84.0d;
        double y = 62.0d;
        UiColor cyan = GaiaUiTheme.GAIA_CYAN;
        draw.append(new UiDrawCommand(UiTextureId.BRAND_EMBLEM,
                context.toFramebuffer(new UiRect(x - 14, y - 16, x + 82, y + 80)),
                SOLID_UV, new UiColor(1, 1, 1, 1), Optional.empty()));
        appendText("GAIA", TypographyRole.DISPLAY_TITLE,
                x + 88.0d, y + 36.0d, 1.08d, cyan, context, draw);
        appendText("L E G A C Y", TypographyRole.FUNCTIONAL,
                x + 92.0d, y + 68.0d, 0.88d, TEXT, context, draw);
        appendSolid(new UiRect(x + 90.0d, y + 78.0d, x + 258.0d, y + 79.0d),
                new UiColor(0.49f, 0.91f, 1.0f, 0.34f), context, draw);
    }

    private void appendMainMenuButtons(
            List<Button> buttons,
            Optional<UiActionId> focusedAction,
            UiLayoutContext context,
            UiDrawList draw,
            List<UiHitRegion> hitRegions) {
        UiActionId selected = selectedAction(buttons, focusedAction);
        double left = 84.0d;
        double width = 292.0d;
        double step = 48.0d;
        double totalHeight = 38.0d + Math.max(0, buttons.size() - 1) * step;
        double top = Math.max(160.0d, Math.min(
                260.0d,
                context.logicalHeight() - 48.0d - totalHeight));
        for (int index = 0; index < buttons.size(); index++) {
            Button button = buttons.get(index);
            UiRect bounds = new UiRect(
                    left, top + index * step,
                    left + width, top + index * step + 38.0d);
            boolean active = button.action() == selected;
            appendSolid(bounds,
                    button.enabled()
                            ? (active ? SELECTED_BUTTON : new UiColor(0.02f, 0.05f, 0.08f, 0.12f))
                            : DISABLED_BUTTON,
                    context, draw);
            if (active) {
                appendSolid(new UiRect(
                        bounds.left(), bounds.top(), bounds.left() + 3.0d, bounds.bottom()),
                        GaiaUiTheme.GAIA_CYAN, context, draw);
            }
            appendText(button.label(), TypographyRole.FUNCTIONAL,
                    bounds.left() + 18.0d, bounds.top() + 25.0d, 1.0d,
                    button.enabled() ? (active ? GaiaUiTheme.GAIA_CYAN : TEXT) : DISABLED_TEXT,
                    context, draw);
            hitRegions.add(new UiHitRegion(
                    button.action(), bounds, context.safeArea(), button.enabled(),
                    context.contentScaleX(), context.contentScaleY()));
        }
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
        double width = text.measure(label, TypographyRole.FUNCTIONAL, scaleX);
        double x = context.snapX((logicalBounds.left() + logicalBounds.right()) / 2.0d)
                - width / 2.0d;
        double baseline = context.snapY(logicalBounds.top() + Math.min(
                27.0d, logicalBounds.bottom() - logicalBounds.top() - 6.0d));
        text.append(label, TypographyRole.FUNCTIONAL,
                x, baseline, scaleX, scaleY, color, Optional.empty(), draw);
    }

    private void appendCenteredText(
            String value,
            double logicalBaseline,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        appendCenteredText(
                value, TypographyRole.BODY, logicalBaseline, color, context, draw);
    }

    private void appendCenteredText(
            String value,
            TypographyRole role,
            double logicalBaseline,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        double scaleX = context.contentScaleX();
        double scaleY = context.contentScaleY();
        double width = text.measure(value, role, scaleX);
        double x = context.snapX(context.logicalWidth() / 2.0d) - width / 2.0d;
        text.append(value, role, x, context.snapY(logicalBaseline), scaleX, scaleY,
                color, Optional.empty(), draw);
    }

    private void appendText(
            String value,
            double logicalX,
            double logicalBaseline,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        appendText(value, TypographyRole.BODY, logicalX, logicalBaseline, 1.0d,
                color, context, draw);
    }

    private void appendText(
            String value,
            TypographyRole role,
            double logicalX,
            double logicalBaseline,
            double roleScale,
            UiColor color,
            UiLayoutContext context,
            UiDrawList draw) {
        text.append(
                value,
                role,
                context.snapX(logicalX),
                context.snapY(logicalBaseline),
                context.contentScaleX() * roleScale,
                context.contentScaleY() * roleScale,
                color,
                Optional.empty(),
                draw);
    }

    private static void appendHero(UiLayoutContext context, UiDrawList draw) {
        draw.append(new UiDrawCommand(
                UiTextureId.HERO_BACKGROUND,
                context.toFramebuffer(context.safeArea()),
                SOLID_UV,
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                Optional.empty()));
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
