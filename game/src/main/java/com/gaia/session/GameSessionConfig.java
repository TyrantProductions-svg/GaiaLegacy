package com.gaia.session;

import com.gaia.interaction.GameMode;
import com.gaia.settings.SettingsSnapshot;
import java.util.Objects;

public record GameSessionConfig(
        long seed,
        int chunkRadius,
        GameMode defaultGameMode,
        boolean debugHudDefault) {
    public GameSessionConfig {
        if (chunkRadius < 0) {
            throw new IllegalArgumentException(
                    "chunkRadius must be non-negative");
        }
        defaultGameMode =
                Objects.requireNonNull(
                        defaultGameMode, "defaultGameMode");
    }

    public static GameSessionConfig from(SettingsSnapshot settings) {
        SettingsSnapshot snapshot = Objects.requireNonNull(settings, "settings");
        return new GameSessionConfig(
                12345L,
                snapshot.chunkRadius(),
                snapshot.defaultGameMode(),
                snapshot.debugHudDefault());
    }
}
