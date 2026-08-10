package com.gaia.settings;

/** Applies the complete hot audio settings tuple on the product owner thread. */
@FunctionalInterface
public interface AudioSettingsPort {
    void apply(
            double masterVolume,
            double musicVolume,
            double sfxVolume,
            boolean muteWhenUnfocused);
}
