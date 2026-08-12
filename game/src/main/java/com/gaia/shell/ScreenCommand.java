package com.gaia.shell;

import java.util.Objects;
import com.gaia.save.format.SaveGameId;
import com.gaia.session.NewWorldRequest;

/** Typed product-shell intents produced by screen input. */
public sealed interface ScreenCommand permits
        ScreenCommand.None,
        ScreenCommand.NewWorld,
        ScreenCommand.OpenNewWorldSetup,
        ScreenCommand.OpenWorldSlots,
        ScreenCommand.CreateWorld,
        ScreenCommand.PreviousWorldSlotsPage,
        ScreenCommand.NextWorldSlotsPage,
        ScreenCommand.LoadWorld,
        ScreenCommand.DeleteWorld,
        ScreenCommand.RecoverBackup,
        ScreenCommand.Save,
        ScreenCommand.SaveAndQuit,
        ScreenCommand.OpenSettings,
        ScreenCommand.OpenControls,
        ScreenCommand.Resume,
        ScreenCommand.ReturnToMainMenu,
        ScreenCommand.Quit,
        ScreenCommand.Back,
        ScreenCommand.Confirm,
        ScreenCommand.Dismiss,
        ScreenCommand.ToggleSetting,
        ScreenCommand.AdjustSetting,
        ScreenCommand.ApplySettings,
        ScreenCommand.DiscardSettings,
        ScreenCommand.CancelSettings {
    record None() implements ScreenCommand {}

    record NewWorld() implements ScreenCommand {}

    record OpenNewWorldSetup() implements ScreenCommand {}

    record OpenWorldSlots() implements ScreenCommand {}

    record CreateWorld(NewWorldRequest request) implements ScreenCommand {
        public CreateWorld {
            Objects.requireNonNull(request, "request");
        }
    }

    record PreviousWorldSlotsPage() implements ScreenCommand {}

    record NextWorldSlotsPage() implements ScreenCommand {}

    record LoadWorld(SaveGameId saveGameId) implements ScreenCommand {
        public LoadWorld {
            Objects.requireNonNull(saveGameId, "saveGameId");
        }
    }

    record DeleteWorld(SaveGameId saveGameId) implements ScreenCommand {
        public DeleteWorld {
            Objects.requireNonNull(saveGameId, "saveGameId");
        }
    }

    record RecoverBackup(SaveGameId saveGameId) implements ScreenCommand {
        public RecoverBackup {
            Objects.requireNonNull(saveGameId, "saveGameId");
        }
    }

    record Save() implements ScreenCommand {}

    record SaveAndQuit() implements ScreenCommand {}

    static ScreenCommand none() {
        return new None();
    }

    record OpenSettings() implements ScreenCommand {}

    record OpenControls() implements ScreenCommand {}

    record Resume() implements ScreenCommand {}

    record ReturnToMainMenu() implements ScreenCommand {}

    record Quit() implements ScreenCommand {}

    record Back() implements ScreenCommand {}

    record Confirm() implements ScreenCommand {}

    record Dismiss() implements ScreenCommand {}

    record ToggleSetting(ToggleTarget target) implements ScreenCommand {
        public ToggleSetting {
            Objects.requireNonNull(target, "target");
        }
    }

    record AdjustSetting(
            AdjustmentTarget target,
            AdjustmentDirection direction) implements ScreenCommand {
        public AdjustSetting {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(direction, "direction");
        }
    }

    record ApplySettings() implements ScreenCommand {}

    record DiscardSettings() implements ScreenCommand {}

    record CancelSettings() implements ScreenCommand {}

    enum ToggleTarget {
        VSYNC,
        INVERT_Y,
        MUTE_WHEN_UNFOCUSED,
        DEFAULT_GAME_MODE,
        DEBUG_HUD_DEFAULT
    }

    enum AdjustmentTarget {
        FOV,
        MOUSE_SENSITIVITY,
        CHUNK_RADIUS,
        MASTER_VOLUME,
        MUSIC_VOLUME,
        SFX_VOLUME
    }

    enum AdjustmentDirection {
        DECREMENT,
        INCREMENT
    }
}
