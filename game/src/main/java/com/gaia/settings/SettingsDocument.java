package com.gaia.settings;

public record SettingsDocument(
        Integer schemaVersion,
        Boolean vsync,
        Double fovDegrees,
        Double mouseSensitivity,
        Boolean invertY,
        Integer chunkRadius,
        Double masterVolume,
        Double musicVolume,
        Double sfxVolume,
        Boolean muteWhenUnfocused,
        String defaultGameMode,
        Boolean debugHudDefault) {}
