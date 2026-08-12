package com.gaia.shell.ui;

import com.gaia.shell.ScreenCommand;
import com.overlord.renderer.ui.UiRect;
import java.util.Objects;

/** Immutable logical hit bounds and presentation metadata for one product action. */
public record UiHitRegion(
        UiControlId id,
        ScreenCommand command,
        UiRect logicalBounds,
        UiRect logicalViewport,
        boolean enabled,
        float contentScaleX,
        float contentScaleY) {
    public UiHitRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(logicalBounds, "logicalBounds");
        Objects.requireNonNull(logicalViewport, "logicalViewport");
        if (!Float.isFinite(contentScaleX)
                || !Float.isFinite(contentScaleY)
                || contentScaleX <= 0.0f
                || contentScaleY <= 0.0f) {
            throw new IllegalArgumentException("content scales must be finite and positive");
        }
    }

    /** Compatibility constructor for the established static product controls. */
    public UiHitRegion(
            UiActionId action,
            UiRect logicalBounds,
            UiRect logicalViewport,
            boolean enabled,
            float contentScaleX,
            float contentScaleY) {
        this(
                action,
                commandFor(action),
                logicalBounds,
                logicalViewport,
                enabled,
                contentScaleX,
                contentScaleY);
    }

    /** Compatibility accessor for layouts that still contain only static controls. */
    public UiActionId action() {
        if (id instanceof UiActionId action) {
            return action;
        }
        throw new IllegalStateException("dynamic controls do not have a static action");
    }

    public ScreenCommand activate(double logicalX, double logicalY) {
        return enabled && contains(logicalX, logicalY)
                ? command
                : ScreenCommand.none();
    }

    public boolean contains(double logicalX, double logicalY) {
        return Double.isFinite(logicalX)
                && Double.isFinite(logicalY)
                && logicalX >= logicalBounds.left()
                && logicalX <= logicalBounds.right()
                && logicalY >= logicalBounds.top()
                && logicalY <= logicalBounds.bottom();
    }

    public double centerX() {
        return logicalBounds.left() + (logicalBounds.right() - logicalBounds.left()) / 2.0d;
    }

    public double centerY() {
        return logicalBounds.top() + (logicalBounds.bottom() - logicalBounds.top()) / 2.0d;
    }

    public boolean withinViewport(double logicalX, double logicalY) {
        return Double.isFinite(logicalX)
                && Double.isFinite(logicalY)
                && logicalX >= logicalViewport.left()
                && logicalX < logicalViewport.right()
                && logicalY >= logicalViewport.top()
                && logicalY < logicalViewport.bottom();
    }

    private static ScreenCommand commandFor(UiActionId action) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case NEW_WORLD -> new ScreenCommand.OpenNewWorldSetup();
            case LOAD_WORLD -> new ScreenCommand.OpenWorldSlots();
            case NEW_WORLD_NAME, NEW_WORLD_SEED -> ScreenCommand.none();
            case CREATE_WORLD -> new ScreenCommand.NewWorld();
            case WORLD_SLOTS_PREVIOUS -> new ScreenCommand.PreviousWorldSlotsPage();
            case WORLD_SLOTS_NEXT -> new ScreenCommand.NextWorldSlotsPage();
            case SETTINGS -> new ScreenCommand.OpenSettings();
            case CONTROLS -> new ScreenCommand.OpenControls();
            case QUIT -> new ScreenCommand.Quit();
            case RESUME -> new ScreenCommand.Resume();
            case SAVE -> new ScreenCommand.Save();
            case SAVE_AND_QUIT -> new ScreenCommand.SaveAndQuit();
            case RETURN_TO_MAIN_MENU -> new ScreenCommand.ReturnToMainMenu();
            case BACK -> new ScreenCommand.Back();
            case CONFIRM -> new ScreenCommand.Confirm();
            case DISMISS -> new ScreenCommand.Dismiss();
            case VSYNC_TOGGLE -> new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.VSYNC);
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
            case INVERT_Y_TOGGLE -> new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.INVERT_Y);
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
            case MUTE_WHEN_UNFOCUSED_TOGGLE -> new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.MUTE_WHEN_UNFOCUSED);
            case DEFAULT_GAME_MODE_TOGGLE -> new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.DEFAULT_GAME_MODE);
            case DEBUG_HUD_DEFAULT_TOGGLE -> new ScreenCommand.ToggleSetting(
                    ScreenCommand.ToggleTarget.DEBUG_HUD_DEFAULT);
            case APPLY_SETTINGS -> new ScreenCommand.ApplySettings();
            case DISCARD_SETTINGS -> new ScreenCommand.DiscardSettings();
            case CANCEL_SETTINGS -> new ScreenCommand.CancelSettings();
        };
    }

    private static ScreenCommand adjustment(
            ScreenCommand.AdjustmentTarget target,
            ScreenCommand.AdjustmentDirection direction) {
        return new ScreenCommand.AdjustSetting(target, direction);
    }
}
