package com.gaia.shell;

import com.gaia.settings.SettingsController;
import com.gaia.settings.SettingsPersistenceException;
import com.gaia.session.LoadWorldRequest;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Translates typed product-screen commands into route changes and lifecycle intent. */
public final class ProductShellController {
    private final ScreenRouter router;
    private final Optional<SettingsController> settings;
    private OperationProgressSnapshot operationProgress;
    private long animationOperationId;
    private double operationAnimationSeconds;

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
        ProductShellSnapshot route = router.snapshot();
        return new ProductShellSnapshot(
                route.screen(),
                route.modal(),
                route.returnTarget(),
                Optional.ofNullable(operationProgress),
                animationStep());
    }

    public void updateOperationProgress(OperationProgressSnapshot progress) {
        OperationProgressSnapshot checked = Objects.requireNonNull(
                progress, "progress");
        ScreenId screen = router.snapshot().screen();
        if (screen != ScreenId.LOADING && screen != ScreenId.SAVING) {
            throw new IllegalStateException(
                    "operation progress requires a loading or saving route");
        }
        if (checked.operationId() != animationOperationId) {
            animationOperationId = checked.operationId();
            operationAnimationSeconds = 0.0d;
        }
        operationProgress = checked;
    }

    public void advanceOperationAnimation(double frameDeltaSeconds) {
        if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0d) {
            throw new IllegalArgumentException(
                    "frameDeltaSeconds must be finite and non-negative");
        }
        if (operationProgress != null
                && operationProgress.terminalState()
                        == OperationProgressSnapshot.TerminalState.RUNNING) {
            operationAnimationSeconds = (operationAnimationSeconds
                    + frameDeltaSeconds) % 1.0d;
        }
    }

    public void clearOperationProgress() {
        operationProgress = null;
        animationOperationId = 0L;
        operationAnimationSeconds = 0.0d;
    }

    private int animationStep() {
        return Math.min(59, (int) Math.floor(operationAnimationSeconds * 60.0d));
    }

    public ProductLifecycleIntent handle(ScreenCommand command) {
        return handle(command, true);
    }

    public ProductLifecycleIntent handle(
            ScreenCommand command, boolean activeSessionDirty) {
        Objects.requireNonNull(command, "command");
        ProductShellSnapshot current = snapshot();
        if (current.modal().isPresent()) {
            return handleModal(command, current.modal().orElseThrow());
        }
        if (command instanceof ScreenCommand.Dismiss
                && current.screen() == ScreenId.LOADING) {
            if (current.operationProgress().isPresent()
                    && !current.operationProgress().orElseThrow().cancelable()) {
                return ProductLifecycleIntent.none();
            }
            router.loadingCancelled();
            clearOperationProgress();
            return new ProductLifecycleIntent.CloseActiveSession();
        }
        if (command instanceof ScreenCommand.Confirm
                || command instanceof ScreenCommand.Dismiss) {
            return ProductLifecycleIntent.none();
        }
        if (isSettingsCommand(command)) {
            handleSettingsCommand(command, current.screen());
            return ProductLifecycleIntent.none();
        }
        if (command instanceof ScreenCommand.OpenNewWorldSetup) {
            if (current.screen() != ScreenId.MAIN_MENU) {
                return ProductLifecycleIntent.none();
            }
            router.openNewWorldSetup();
            return ProductLifecycleIntent.none();
        }
        if (command instanceof ScreenCommand.OpenWorldSlots) {
            if (current.screen() == ScreenId.MAIN_MENU) {
                router.openWorldSlots();
            }
            return ProductLifecycleIntent.none();
        }
        if (command instanceof ScreenCommand.CreateWorld create) {
            if (current.screen() != ScreenId.NEW_WORLD_SETUP) {
                return ProductLifecycleIntent.none();
            }
            router.beginLoading();
            return new ProductLifecycleIntent.StartNewWorld(create.request());
        }
        if (command instanceof ScreenCommand.LoadWorld load) {
            if (current.screen() != ScreenId.WORLD_SLOTS) {
                return ProductLifecycleIntent.none();
            }
            router.beginLoading();
            return new ProductLifecycleIntent.LoadWorld(
                    new LoadWorldRequest(load.saveGameId()));
        }
        if (command instanceof ScreenCommand.DeleteWorld delete) {
            if (current.screen() == ScreenId.WORLD_SLOTS) {
                router.openDeleteWorldConfirmation(delete.saveGameId());
            }
            return ProductLifecycleIntent.none();
        }
        if (command instanceof ScreenCommand.RecoverBackup recover) {
            if (current.screen() == ScreenId.WORLD_SLOTS) {
                router.openRecoverBackupConfirmation(recover.saveGameId());
            }
            return ProductLifecycleIntent.none();
        }
        if (command instanceof ScreenCommand.Save
                || command instanceof ScreenCommand.SaveAndQuit) {
            if (current.screen() != ScreenId.PAUSED) {
                return ProductLifecycleIntent.none();
            }
            router.beginSaving();
            return new ProductLifecycleIntent.Save(
                    command instanceof ScreenCommand.Save
                            ? ProductLifecycleIntent.SavePolicy.SAVE_AND_STAY
                            : ProductLifecycleIntent.SavePolicy.SAVE_AND_QUIT);
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
                if (activeSessionDirty) {
                    router.openModal(ModalId.UNSAVED_PROGRESS_CONFIRMATION);
                } else {
                    router.returnedToMainMenu();
                    return new ProductLifecycleIntent.CloseActiveSession();
                }
            }
        } else if (command instanceof ScreenCommand.Quit) {
            if (current.screen() == ScreenId.MAIN_MENU) {
                router.openModal(ModalId.QUIT_CONFIRMATION);
            }
        } else if (command instanceof ScreenCommand.Back) {
            if (current.screen() == ScreenId.SETTINGS) {
                handleSettingsBack();
            } else if (current.screen() == ScreenId.CONTROLS
                    || current.screen() == ScreenId.NEW_WORLD_SETUP
                    || current.screen() == ScreenId.WORLD_SLOTS) {
                router.back();
            }
        }
        return ProductLifecycleIntent.none();
    }

    public ProductLifecycleIntent savingSucceeded(
            ProductLifecycleIntent.SavePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        clearOperationProgress();
        if (policy == ProductLifecycleIntent.SavePolicy.SAVE_AND_STAY) {
            router.savingReturnedToPause();
            return ProductLifecycleIntent.none();
        }
        router.savingReturnedToMainMenu();
        return new ProductLifecycleIntent.CloseActiveSession();
    }

    public void savingFailed() {
        clearOperationProgress();
        router.savingReturnedToPause();
        router.openModal(ModalId.ERROR_ACKNOWLEDGEMENT);
    }

    public void operationFailed() {
        clearOperationProgress();
        router.openModal(ModalId.ERROR_ACKNOWLEDGEMENT);
    }

    ProductLifecycleIntent startLegacySession() {
        router.beginLoading();
        return ProductLifecycleIntent.none();
    }

    public void loadingSucceeded() {
        if (snapshot().screen() == ScreenId.LOADING
                && snapshot().modal().isEmpty()) {
            router.loadingSucceeded();
            clearOperationProgress();
        }
    }

    public void loadingFailed() {
        if (snapshot().screen() == ScreenId.LOADING
                && snapshot().modal().isEmpty()) {
            router.loadingFailed();
            clearOperationProgress();
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

    private ProductLifecycleIntent handleModal(
            ScreenCommand command,
            ModalId modal) {
        if (modal == ModalId.DIRTY_SETTINGS_CONFIRMATION
                && settings.isPresent()) {
            return handleDirtySettingsModal(command);
        }
        if (command instanceof ScreenCommand.Dismiss) {
            router.dismissModal();
            return ProductLifecycleIntent.none();
        }
        if (!(command instanceof ScreenCommand.Confirm)) {
            return ProductLifecycleIntent.none();
        }

        Optional<com.gaia.save.format.SaveGameId> modalSaveGameId =
                router.modalSaveGameId();
        router.dismissModal();
        return switch (modal) {
            case QUIT_CONFIRMATION -> new ProductLifecycleIntent.ExitProduct();
            case UNSAVED_PROGRESS_CONFIRMATION -> {
                router.returnedToMainMenu();
                yield new ProductLifecycleIntent.CloseActiveSession();
            }
            case DIRTY_SETTINGS_CONFIRMATION -> {
                router.back();
                yield ProductLifecycleIntent.none();
            }
            case DELETE_WORLD_CONFIRMATION -> new ProductLifecycleIntent.DeleteWorld(
                    modalSaveGameId.orElseThrow());
            case RECOVER_BACKUP_CONFIRMATION -> new ProductLifecycleIntent.RecoverBackup(
                    modalSaveGameId.orElseThrow());
            case ERROR_ACKNOWLEDGEMENT -> ProductLifecycleIntent.none();
        };
    }

    private ProductLifecycleIntent handleDirtySettingsModal(ScreenCommand command) {
        if (command instanceof ScreenCommand.CancelSettings
                || command instanceof ScreenCommand.Dismiss) {
            router.dismissModal();
            return ProductLifecycleIntent.none();
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
        return ProductLifecycleIntent.none();
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

}
