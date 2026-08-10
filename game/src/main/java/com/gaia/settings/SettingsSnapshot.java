package com.gaia.settings;

import com.gaia.interaction.GameMode;

public record SettingsSnapshot(
        int schemaVersion,
        boolean vsync,
        double fovDegrees,
        double mouseSensitivity,
        boolean invertY,
        int chunkRadius,
        double masterVolume,
        double musicVolume,
        double sfxVolume,
        boolean muteWhenUnfocused,
        GameMode defaultGameMode,
        boolean debugHudDefault) {}
