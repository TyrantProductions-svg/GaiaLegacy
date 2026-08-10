package com.gaia.settings;

import com.gaia.interaction.GameMode;

public final class SettingsDefaults {
    private SettingsDefaults() {}

    public static SettingsSnapshot schemaV1() {
        return new SettingsSnapshot(
                1,
                true,
                70.0,
                0.10,
                false,
                4,
                1.0,
                0.65,
                1.0,
                true,
                GameMode.SURVIVAL,
                false);
    }
}
