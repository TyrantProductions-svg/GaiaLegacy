package com.gaia.shell;

import com.gaia.save.format.SaveGameId;
import java.util.Objects;
import java.util.Optional;

/** Owns the closed product-shell route and modal state. */
public final class ScreenRouter {
    private ScreenId screen;
    private ModalId modal;
    private ScreenReturnTarget returnTarget;
    private SaveGameId modalSaveGameId;

    private ScreenRouter(ScreenId screen) {
        this.screen = screen;
    }

    public static ScreenRouter mainMenu() {
        return new ScreenRouter(ScreenId.MAIN_MENU);
    }

    public ProductShellSnapshot snapshot() {
        return new ProductShellSnapshot(
                screen, Optional.ofNullable(modal), Optional.ofNullable(returnTarget));
    }

    public void openSettings(ScreenReturnTarget returnTarget) {
        openSecondaryScreen(ScreenId.SETTINGS, returnTarget);
    }

    public void openControls(ScreenReturnTarget returnTarget) {
        openSecondaryScreen(ScreenId.CONTROLS, returnTarget);
    }

    public void openNewWorldSetup() {
        requireScreen(ScreenId.MAIN_MENU);
        screen = ScreenId.NEW_WORLD_SETUP;
    }

    public void openWorldSlots() {
        requireScreen(ScreenId.MAIN_MENU);
        screen = ScreenId.WORLD_SLOTS;
    }

    public void beginSaving() {
        requireScreen(ScreenId.PAUSED);
        screen = ScreenId.SAVING;
    }

    public void savingReturnedToPause() {
        requireScreen(ScreenId.SAVING);
        screen = ScreenId.PAUSED;
    }

    public void savingReturnedToMainMenu() {
        requireScreen(ScreenId.SAVING);
        screen = ScreenId.MAIN_MENU;
    }

    public void openDeleteWorldConfirmation(SaveGameId saveGameId) {
        openSaveModal(ModalId.DELETE_WORLD_CONFIRMATION, saveGameId);
    }

    public void openRecoverBackupConfirmation(SaveGameId saveGameId) {
        openSaveModal(ModalId.RECOVER_BACKUP_CONFIRMATION, saveGameId);
    }

    public Optional<SaveGameId> modalSaveGameId() {
        return Optional.ofNullable(modalSaveGameId);
    }

    public void beginLoading() {
        requireNoModal();
        if (screen != ScreenId.MAIN_MENU
                && screen != ScreenId.NEW_WORLD_SETUP
                && screen != ScreenId.WORLD_SLOTS) {
            throw new IllegalStateException(
                    "Loading is only valid from a main-menu world route");
        }
        screen = ScreenId.LOADING;
    }

    public void loadingSucceeded() {
        requireScreen(ScreenId.LOADING);
        screen = ScreenId.PLAYING;
    }

    public void loadingCancelled() {
        requireScreen(ScreenId.LOADING);
        screen = ScreenId.MAIN_MENU;
    }

    public void loadingFailed() {
        requireScreen(ScreenId.LOADING);
        screen = ScreenId.MAIN_MENU;
        modal = ModalId.ERROR_ACKNOWLEDGEMENT;
    }

    public void pause() {
        requireScreen(ScreenId.PLAYING);
        screen = ScreenId.PAUSED;
    }

    public void resume() {
        requireScreen(ScreenId.PAUSED);
        screen = ScreenId.PLAYING;
    }

    public void openModal(ModalId modal) {
        requireNoModal();
        ModalId requestedModal = Objects.requireNonNull(modal, "modal");
        if (requestedModal == ModalId.DELETE_WORLD_CONFIRMATION
                || requestedModal == ModalId.RECOVER_BACKUP_CONFIRMATION) {
            throw new IllegalStateException(
                    requestedModal + " requires an explicit save-game ID");
        }
        if (!isLegalModalPair(screen, requestedModal)) {
            throw new IllegalStateException(
                    requestedModal + " is not valid from " + screen);
        }
        this.modal = requestedModal;
    }

    public void dismissModal() {
        modal = null;
        modalSaveGameId = null;
    }

    public void back() {
        requireNoModal();
        if (screen == ScreenId.NEW_WORLD_SETUP || screen == ScreenId.WORLD_SLOTS) {
            screen = ScreenId.MAIN_MENU;
            return;
        }
        if (screen != ScreenId.SETTINGS && screen != ScreenId.CONTROLS) {
            throw new IllegalStateException("Back is only valid from a secondary screen");
        }
        screen = screenFor(returnTarget);
        returnTarget = null;
    }

    public void returnedToMainMenu() {
        requireNoModal();
        if (screen == ScreenId.MAIN_MENU) {
            return;
        }
        if (screen != ScreenId.PAUSED) {
            throw new IllegalStateException("Return to main menu is only valid from pause");
        }
        screen = ScreenId.MAIN_MENU;
        returnTarget = null;
    }

    private void openSecondaryScreen(ScreenId destination, ScreenReturnTarget returnTarget) {
        requireNoModal();
        ScreenReturnTarget requestedTarget = Objects.requireNonNull(returnTarget, "returnTarget");
        if (screenFor(requestedTarget) != screen) {
            throw new IllegalStateException("Return target must match the current screen");
        }
        screen = destination;
        this.returnTarget = requestedTarget;
    }

    private void openSaveModal(ModalId modal, SaveGameId saveGameId) {
        requireNoModal();
        if (screen != ScreenId.WORLD_SLOTS) {
            throw new IllegalStateException(modal + " is only valid from WORLD_SLOTS");
        }
        this.modal = Objects.requireNonNull(modal, "modal");
        modalSaveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
    }

    private void requireScreen(ScreenId expected) {
        requireNoModal();
        if (screen != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + screen);
        }
    }

    private void requireNoModal() {
        if (modal != null) {
            throw new IllegalStateException("Modal must be dismissed before changing screens");
        }
    }

    private static ScreenId screenFor(ScreenReturnTarget returnTarget) {
        return switch (returnTarget) {
            case MAIN_MENU -> ScreenId.MAIN_MENU;
            case PAUSED -> ScreenId.PAUSED;
        };
    }

    private static boolean isLegalModalPair(ScreenId screen, ModalId modal) {
        return switch (modal) {
            case QUIT_CONFIRMATION -> screen == ScreenId.MAIN_MENU;
            case UNSAVED_PROGRESS_CONFIRMATION -> screen == ScreenId.PAUSED;
            case DIRTY_SETTINGS_CONFIRMATION -> screen == ScreenId.SETTINGS;
            case DELETE_WORLD_CONFIRMATION, RECOVER_BACKUP_CONFIRMATION ->
                    screen == ScreenId.WORLD_SLOTS;
            case ERROR_ACKNOWLEDGEMENT ->
                    screen == ScreenId.MAIN_MENU
                            || screen == ScreenId.WORLD_SLOTS
                            || screen == ScreenId.PAUSED;
        };
    }
}
