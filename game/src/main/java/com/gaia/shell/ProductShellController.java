package com.gaia.shell;

import com.gaia.settings.SettingsController;
import com.gaia.settings.SettingsPersistenceException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Translates typed product-screen commands into route changes and lifecycle intent. */
public final class ProductShellController {
    private final ScreenRouter router;
    private final Optional<SettingsController> settings;

    public ProductShellController(ScreenRouter router) {
        this.router = Objects.requireNonNull(router, "router");
        settings = Optional.empty();
    }

    public ProductShellController(
            ScreenRouter router, SettingsController settings) {
        this.router = Objects.requireNonNull(router, "router");
        this.settings = Optional.of(
                Objects.requireNonNull(settings, "settings"));
    }

    public ProductShellSnapshot snapshot() {
        return router.snapshot();
    }

    public LifecycleIntent handle(ScreenCommand command) {
        Objects.requireNonNull(command, "command");
        ProductShellSnapshot current = snapshot();
        if (current.modal().isPresent()) {
            return handleModal(command, current.modal().orElseThrow());
        }
        if (command instanceof ScreenCommand.Dismiss
                && current.screen() == ScreenId.LOADING) {
            router.loadingCancelled();
            return LifecycleIntent.CLOSE_ACTIVE_SESSION;
        }
        if (command instanceof ScreenCommand.Confirm
                || command instanceof ScreenCommand.Dismiss) {
            return LifecycleIntent.NONE;
        }
        if (isSettingsCommand(command)) {
            handleSettingsCommand(command, current.screen());
            return LifecycleIntent.NONE;
        }
        if (command instanceof ScreenCommand.NewWorld) {
            if (current.screen() != ScreenId.MAIN_MENU) {
                return LifecycleIntent.NONE;
            }
            router.beginLoading();
            return LifecycleIntent.START_NEW_SESSION;
        }
        if (command instanceof ScreenCommand.OpenSettings) {
            openSettings(current.screen());
        } else if (command instanceof ScreenCommand.OpenControls) {
            openControls(current.screen());
        } else if (command instanceof ScreenCommand.Resume) {
            if (current.screen() == ScreenId.PAUSED) {
                router.resume();
            }
        } else if (command instanceof ScreenCommand.ReturnToMainMenu) {
            if (current.screen() == ScreenId.PAUSED) {
                router.openModal(ModalId.UNSAVED_PROGRESS_CONFIRMATION);
            }
        } else if (command instanceof ScreenCommand.Quit) {
            if (current.screen() == ScreenId.MAIN_MENU) {
                router.openModal(ModalId.QUIT_CONFIRMATION);
            }
        } else if (command instanceof ScreenCommand.Back) {
            if (current.screen() == ScreenId.SETTINGS) {
                handleSettingsBack();
            } else if (current.screen() == ScreenId.CONTROLS) {
                router.back();
            }
        }
        return LifecycleIntent.NONE;
    }

    public void loadingSucceeded() {
        if (snapshot().screen() == ScreenId.LOADING
                && snapshot().modal().isEmpty()) {
            router.loadingSucceeded();
        }
    }

    public void loadingFailed() {
        if (snapshot().screen() == ScreenId.LOADING
                && snapshot().modal().isEmpty()) {
            router.loadingFailed();
        }
    }

    public void togglePlaying() {
        ProductShellSnapshot current = snapshot();
        if (current.modal().isPresent()) {
            return;
        }
        if (current.screen() == ScreenId.PLAYING) {
            router.pause();
        } else if (current.screen() == ScreenId.PAUSED) {
            router.resume();
        }
    }

    private LifecycleIntent handleModal(
            ScreenCommand command,
            ModalId modal) {
        if (modal == ModalId.DIRTY_SETTINGS_CONFIRMATION
                && settings.isPresent()) {
            return handleDirtySettingsModal(command);
        }
        if (command instanceof ScreenCommand.Dismiss) {
            router.dismissModal();
            return LifecycleIntent.NONE;
        }
        if (!(command instanceof ScreenCommand.Confirm)) {
            return LifecycleIntent.NONE;
        }

        router.dismissModal();
        return switch (modal) {
            case QUIT_CONFIRMATION -> LifecycleIntent.EXIT_PRODUCT;
            case UNSAVED_PROGRESS_CONFIRMATION -> {
                router.returnedToMainMenu();
                yield LifecycleIntent.CLOSE_ACTIVE_SESSION;
            }
            case DIRTY_SETTINGS_CONFIRMATION -> {
                router.back();
                yield LifecycleIntent.NONE;
            }
            case ERROR_ACKNOWLEDGEMENT -> LifecycleIntent.NONE;
        };
    }

    private LifecycleIntent handleDirtySettingsModal(ScreenCommand command) {
        if (command instanceof ScreenCommand.CancelSettings
                || command instanceof ScreenCommand.Dismiss) {
            router.dismissModal();
            return LifecycleIntent.NONE;
        }
        if (command instanceof ScreenCommand.ApplySettings) {
            if (tryApplySettings(settings.orElseThrow())) {
                router.dismissModal();
                router.back();
            }
        } else if (command instanceof ScreenCommand.DiscardSettings) {
            settings.orElseThrow().discard();
            router.dismissModal();
            router.back();
        }
        return LifecycleIntent.NONE;
    }

    private void handleSettingsCommand(
            ScreenCommand command, ScreenId screen) {
        if (screen != ScreenId.SETTINGS || settings.isEmpty()) {
            return;
        }
        SettingsController controller = settings.orElseThrow();
        if (command instanceof ScreenCommand.ApplySettings) {
            tryApplySettings(controller);
        } else if (command instanceof ScreenCommand.ToggleSetting toggle) {
            switch (toggle.target()) {
                case VSYNC -> controller.toggleVsync();
                case INVERT_Y -> controller.toggleInvertY();
                case MUTE_WHEN_UNFOCUSED -> controller.toggleMuteWhenUnfocused();
                case DEFAULT_GAME_MODE -> controller.toggleDefaultGameMode();
                case DEBUG_HUD_DEFAULT -> controller.toggleDebugHudDefault();
            }
        } else if (command instanceof ScreenCommand.AdjustSetting adjust) {
            applyAdjustment(controller, adjust);
        }
    }

    private void handleSettingsBack() {
        if (settings.isEmpty()
                || settings.orElseThrow().requestBack()
                        == SettingsController.BackDecision.RETURN) {
            router.back();
            return;
        }
        router.openModal(ModalId.DIRTY_SETTINGS_CONFIRMATION);
    }

    private static boolean tryApplySettings(SettingsController controller) {
        try {
            controller.apply();
            return true;
        } catch (SettingsPersistenceException persistenceFailure) {
            if (persistenceFailure.getSuppressed().length > 0
                    || controller.snapshot().blockingDiagnostic().isPresent()) {
                throw persistenceFailure;
            }
            // A coherent persistence failure leaves the applied snapshot and
            // hot runtime unchanged, so the user may retry, discard, or cancel.
            return false;
        }
    }

    private static void applyAdjustment(
            SettingsController controller,
            ScreenCommand.AdjustSetting adjustment) {
        double direction = adjustment.direction()
                        == ScreenCommand.AdjustmentDirection.INCREMENT
                ? 1.0d
                : -1.0d;
        var draft = controller.snapshot().draft();
        switch (adjustment.target()) {
            case FOV -> controller.adjustFov(
                    draft.fovDegrees() + direction);
            case MOUSE_SENSITIVITY -> controller.adjustMouseSensitivity(
                    decimalStep(draft.mouseSensitivity(), direction * 0.01d));
            case CHUNK_RADIUS -> controller.adjustChunkRadius(
                    draft.chunkRadius() + (int) direction);
            case MASTER_VOLUME -> controller.adjustMasterVolume(
                    decimalStep(draft.masterVolume(), direction * 0.05d));
            case MUSIC_VOLUME -> controller.adjustMusicVolume(
                    decimalStep(draft.musicVolume(), direction * 0.05d));
            case SFX_VOLUME -> controller.adjustSfxVolume(
                    decimalStep(draft.sfxVolume(), direction * 0.05d));
        }
    }

    private static double decimalStep(double value, double delta) {
        return BigDecimal.valueOf(value)
                .add(BigDecimal.valueOf(delta))
                .doubleValue();
    }

    private static boolean isSettingsCommand(ScreenCommand command) {
        return command instanceof ScreenCommand.ToggleSetting
                || command instanceof ScreenCommand.AdjustSetting
                || command instanceof ScreenCommand.ApplySettings
                || command instanceof ScreenCommand.DiscardSettings
                || command instanceof ScreenCommand.CancelSettings;
    }

    private void openSettings(ScreenId screen) {
        if (screen == ScreenId.MAIN_MENU) {
            router.openSettings(ScreenReturnTarget.MAIN_MENU);
        } else if (screen == ScreenId.PAUSED) {
            router.openSettings(ScreenReturnTarget.PAUSED);
        }
    }

    private void openControls(ScreenId screen) {
        if (screen == ScreenId.MAIN_MENU) {
            router.openControls(ScreenReturnTarget.MAIN_MENU);
        } else if (screen == ScreenId.PAUSED) {
            router.openControls(ScreenReturnTarget.PAUSED);
        }
    }

    /** External product-lifecycle work requested by one routed UI command. */
    public enum LifecycleIntent {
        NONE,
        START_NEW_SESSION,
        CLOSE_ACTIVE_SESSION,
        EXIT_PRODUCT
    }
}
