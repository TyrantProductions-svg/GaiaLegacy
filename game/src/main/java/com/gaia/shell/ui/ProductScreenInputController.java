package com.gaia.shell.ui;

import com.gaia.shell.ScreenCommand;
import com.overlord.core.input.UiInputSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Routes immutable UI samples while retaining only product-screen focus state. */
public final class ProductScreenInputController {
    private static final int KEY_SPACE = 32;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int MOUSE_BUTTON_LEFT = 0;

    private UiActionId focusedAction;
    private UiActionId presentationHighlight;
    private InputModality modality = InputModality.POINTER;
    private double lastPointerX;
    private double lastPointerY;
    private boolean pointerInitialized;
    private long lastProcessedSampleId = Long.MIN_VALUE;

    public Optional<ScreenCommand> route(UiInputSnapshot input, ProductUiLayout layout) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(layout, "layout");
        if (input.sampleId() <= lastProcessedSampleId) {
            return Optional.empty();
        }
        lastProcessedSampleId = input.sampleId();
        boolean pointerMoved = pointerInitialized
                && (Double.compare(lastPointerX, input.pointerX()) != 0
                        || Double.compare(lastPointerY, input.pointerY()) != 0);
        lastPointerX = input.pointerX();
        lastPointerY = input.pointerY();
        pointerInitialized = true;
        if (pointerMoved) {
            modality = InputModality.POINTER;
        }
        if (!input.focused()) {
            presentationHighlight = null;
            return Optional.empty();
        }

        List<UiHitRegion> enabled = layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .toList();
        if (enabled.isEmpty()) {
            focusedAction = null;
            presentationHighlight = null;
            return Optional.empty();
        }
        reconcileFocus(enabled);

        double logicalPointerX = layout.canMapWindowPointer()
                ? layout.windowToLogicalX(input.pointerX())
                : Double.NaN;
        double logicalPointerY = layout.canMapWindowPointer()
                ? layout.windowToLogicalY(input.pointerY())
                : Double.NaN;
        Optional<UiHitRegion> hovered = layout.withinViewport(logicalPointerX, logicalPointerY)
                ? enabled.stream()
                        .filter(region -> region.contains(logicalPointerX, logicalPointerY))
                        .findFirst()
                : Optional.empty();
        if (modality == InputModality.POINTER) {
            presentationHighlight = hovered.map(UiHitRegion::action).orElse(null);
            hovered.ifPresent(region -> focusedAction = region.action());
        } else {
            presentationHighlight = focusedAction;
        }

        if (hovered.isPresent() && input.isMousePressed(MOUSE_BUTTON_LEFT)) {
            return commandFor(hovered.orElseThrow().action());
        }
        if (input.isKeyPressed(KEY_ESCAPE)) {
            return escapeCommand(enabled);
        }
        if (input.isKeyPressed(KEY_TAB) || input.isKeyPressed(KEY_DOWN)) {
            moveFocus(enabled, 1);
            selectKeyboardFocus();
        } else if (input.isKeyPressed(KEY_UP)) {
            moveFocus(enabled, -1);
            selectKeyboardFocus();
        }
        if (input.isKeyPressed(KEY_ENTER) || input.isKeyPressed(KEY_SPACE)) {
            selectKeyboardFocus();
            return commandFor(focusedAction);
        }
        return Optional.empty();
    }

    /** Immutable presentation value captured after routing the current UI sample. */
    public Optional<UiActionId> presentationHighlight() {
        return Optional.ofNullable(presentationHighlight);
    }

    private void selectKeyboardFocus() {
        modality = InputModality.KEYBOARD;
        presentationHighlight = focusedAction;
    }

    private void reconcileFocus(List<UiHitRegion> enabled) {
        if (enabled.stream().noneMatch(region -> region.action() == focusedAction)) {
            focusedAction = enabled.get(0).action();
        }
    }

    private void moveFocus(List<UiHitRegion> enabled, int delta) {
        int current = 0;
        for (int index = 0; index < enabled.size(); index++) {
            if (enabled.get(index).action() == focusedAction) {
                current = index;
                break;
            }
        }
        focusedAction = enabled.get(Math.floorMod(current + delta, enabled.size())).action();
    }

    private static Optional<ScreenCommand> escapeCommand(List<UiHitRegion> enabled) {
        if (hasAction(enabled, UiActionId.CANCEL_SETTINGS)) {
            return Optional.of(new ScreenCommand.CancelSettings());
        }
        if (hasAction(enabled, UiActionId.DISMISS)) {
            return Optional.of(new ScreenCommand.Dismiss());
        }
        if (hasAction(enabled, UiActionId.BACK)) {
            return Optional.of(new ScreenCommand.Back());
        }
        if (hasAction(enabled, UiActionId.RESUME)) {
            return Optional.of(new ScreenCommand.Resume());
        }
        if (hasAction(enabled, UiActionId.QUIT)) {
            return Optional.of(new ScreenCommand.Quit());
        }
        return Optional.empty();
    }

    private static boolean hasAction(List<UiHitRegion> regions, UiActionId action) {
        return regions.stream().anyMatch(region -> region.action() == action);
    }

    private static Optional<ScreenCommand> commandFor(UiActionId action) {
        return switch (action) {
            case NEW_WORLD -> Optional.of(new ScreenCommand.NewWorld());
            case SETTINGS -> Optional.of(new ScreenCommand.OpenSettings());
            case CONTROLS -> Optional.of(new ScreenCommand.OpenControls());
            case QUIT -> Optional.of(new ScreenCommand.Quit());
            case RESUME -> Optional.of(new ScreenCommand.Resume());
            case RETURN_TO_MAIN_MENU -> Optional.of(new ScreenCommand.ReturnToMainMenu());
            case BACK -> Optional.of(new ScreenCommand.Back());
            case CONFIRM -> Optional.of(new ScreenCommand.Confirm());
            case DISMISS -> Optional.of(new ScreenCommand.Dismiss());
            case LOAD_WORLD -> Optional.empty();
            case VSYNC_TOGGLE -> Optional.of(new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.VSYNC));
            case FOV_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.FOV,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case FOV_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.FOV,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case MOUSE_SENSITIVITY_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case MOUSE_SENSITIVITY_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case INVERT_Y_TOGGLE -> Optional.of(new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.INVERT_Y));
            case CHUNK_RADIUS_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.CHUNK_RADIUS,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case CHUNK_RADIUS_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.CHUNK_RADIUS,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case MASTER_VOLUME_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case MASTER_VOLUME_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case MUSIC_VOLUME_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case MUSIC_VOLUME_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case SFX_VOLUME_DECREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                    ScreenCommand.AdjustmentDirection.DECREMENT);
            case SFX_VOLUME_INCREMENT -> adjustment(
                    ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                    ScreenCommand.AdjustmentDirection.INCREMENT);
            case MUTE_WHEN_UNFOCUSED_TOGGLE -> Optional.of(
                    new ScreenCommand.ToggleSetting(
                            ScreenCommand.ToggleTarget.MUTE_WHEN_UNFOCUSED));
            case DEFAULT_GAME_MODE_TOGGLE -> Optional.of(
                    new ScreenCommand.ToggleSetting(
                            ScreenCommand.ToggleTarget.DEFAULT_GAME_MODE));
            case DEBUG_HUD_DEFAULT_TOGGLE -> Optional.of(
                    new ScreenCommand.ToggleSetting(
                            ScreenCommand.ToggleTarget.DEBUG_HUD_DEFAULT));
            case APPLY_SETTINGS -> Optional.of(new ScreenCommand.ApplySettings());
            case DISCARD_SETTINGS -> Optional.of(new ScreenCommand.DiscardSettings());
            case CANCEL_SETTINGS -> Optional.of(new ScreenCommand.CancelSettings());
        };
    }

    private static Optional<ScreenCommand> adjustment(
            ScreenCommand.AdjustmentTarget target,
            ScreenCommand.AdjustmentDirection direction) {
        return Optional.of(new ScreenCommand.AdjustSetting(target, direction));
    }

    private enum InputModality {
        POINTER,
        KEYBOARD
    }
}
