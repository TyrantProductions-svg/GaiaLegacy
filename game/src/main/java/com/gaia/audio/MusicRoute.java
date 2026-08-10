package com.gaia.audio;

/** Product route intent translated into music presentation behavior. */
public enum MusicRoute {
    STOPPED,
    MAIN_MENU,
    GAMEPLAY,
    PAUSED,
    SETTINGS_FROM_PAUSE,
    CONTROLS_FROM_PAUSE;

    boolean isDucked() {
        return this == PAUSED || this == SETTINGS_FROM_PAUSE || this == CONTROLS_FROM_PAUSE;
    }
}
