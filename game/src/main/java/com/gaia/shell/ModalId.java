package com.gaia.shell;

/** Product-shell modals that always take priority over their underlying route. */
public enum ModalId {
    QUIT_CONFIRMATION,
    UNSAVED_PROGRESS_CONFIRMATION,
    DIRTY_SETTINGS_CONFIRMATION,
    ERROR_ACKNOWLEDGEMENT
}
