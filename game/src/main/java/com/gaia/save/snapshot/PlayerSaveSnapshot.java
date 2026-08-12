package com.gaia.save.snapshot;

import com.gaia.interaction.GameMode;
import com.overlord.interaction.api.EntityRef;
import java.util.Objects;

/** Immutable authoritative player state at a save boundary. */
public record PlayerSaveSnapshot(
        EntityRef owner,
        double feetPositionX,
        double feetPositionY,
        double feetPositionZ,
        double velocityX,
        double velocityY,
        double velocityZ,
        double yaw,
        double pitch,
        GameMode gameMode,
        boolean noclip) {
    private static final double MIN_PITCH = -89.0;
    private static final double MAX_PITCH = 89.0;

    public PlayerSaveSnapshot {
        owner = Objects.requireNonNull(owner, "owner");
        gameMode = Objects.requireNonNull(gameMode, "gameMode");
        requireFinite(feetPositionX, "feetPositionX");
        requireFinite(feetPositionY, "feetPositionY");
        requireFinite(feetPositionZ, "feetPositionZ");
        requireFinite(velocityX, "velocityX");
        requireFinite(velocityY, "velocityY");
        requireFinite(velocityZ, "velocityZ");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
            throw new IllegalArgumentException(
                    "pitch must be within Camera's closed [-89, 89] range");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
