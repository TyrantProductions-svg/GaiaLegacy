package com.gaia.worlditem;

import com.overlord.config.GameConfig;

/** Immutable fixed-step configuration for physical world-item projections. */
public record WorldItemPhysicsConfig(
        float edgeLength,
        float maximumFallSpeed,
        float restitution,
        float friction,
        float groundProbeDistance,
        float sleepSpeedThreshold,
        int sleepStableSteps,
        int depenetrationIterations,
        int worldHeight,
        float pickupReach,
        int maxProjections) {
    public WorldItemPhysicsConfig {
        if (!Float.isFinite(edgeLength) || edgeLength <= 0) {
            throw new IllegalArgumentException("edgeLength must be finite and positive");
        }
        if (!Float.isFinite(maximumFallSpeed) || maximumFallSpeed >= 0) {
            throw new IllegalArgumentException("maximumFallSpeed must be finite and negative");
        }
        requireUnitInterval(restitution, "restitution");
        requireUnitInterval(friction, "friction");
        requireNonNegativeFinite(groundProbeDistance, "groundProbeDistance");
        requireNonNegativeFinite(sleepSpeedThreshold, "sleepSpeedThreshold");
        if (sleepStableSteps <= 0) {
            throw new IllegalArgumentException("sleepStableSteps must be positive");
        }
        if (depenetrationIterations <= 0) {
            throw new IllegalArgumentException("depenetrationIterations must be positive");
        }
        if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException(
                    "worldHeight must be in [1, GameConfig.Chunk.MAX_HEIGHT]");
        }
        if (!Float.isFinite(pickupReach) || pickupReach <= 0) {
            throw new IllegalArgumentException("pickupReach must be finite and positive");
        }
        if (maxProjections <= 0) {
            throw new IllegalArgumentException("maxProjections must be positive");
        }
    }

    /** Compatibility constructor retained for the Gate 11.1 projection tests. */
    public WorldItemPhysicsConfig(float edgeLength, int maxProjections) {
        this(
                edgeLength,
                -30.0f,
                0.12f,
                0.25f,
                0.02f,
                0.05f,
                30,
                8,
                GameConfig.Chunk.MAX_HEIGHT,
                3.5f,
                maxProjections);
    }

    /** Full Gate 11.2 constructor with the capacity in the legacy position. */
    public WorldItemPhysicsConfig(
            float edgeLength,
            float maximumFallSpeed,
            float restitution,
            float friction,
            float groundProbeDistance,
            float sleepSpeedThreshold,
            int sleepStableSteps,
            int depenetrationIterations,
            int worldHeight,
            float pickupReach) {
        this(
                edgeLength,
                maximumFallSpeed,
                restitution,
                friction,
                groundProbeDistance,
                sleepSpeedThreshold,
                sleepStableSteps,
                depenetrationIterations,
                worldHeight,
                pickupReach,
                1024);
    }

    public static WorldItemPhysicsConfig production() {
        return new WorldItemPhysicsConfig(
                GameConfig.Interaction.WORLD_ITEM_EDGE_LENGTH,
                -30.0f,
                0.12f,
                0.25f,
                0.02f,
                0.05f,
                30,
                8,
                GameConfig.Chunk.MAX_HEIGHT,
                3.5f,
                1024);
    }

    private static void requireUnitInterval(float value, String name) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static void requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
