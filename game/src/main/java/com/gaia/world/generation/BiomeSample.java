package com.gaia.world.generation;

public record BiomeSample(
        double plains,
        double rollingHills,
        double rockyHighlands) {
    public BiomeSample {
        requireFiniteNonNegative("plains", plains);
        requireFiniteNonNegative(
                "rollingHills", rollingHills);
        requireFiniteNonNegative(
                "rockyHighlands", rockyHighlands);
    }

    public BiomeType dominant() {
        if (plains >= rollingHills
                && plains >= rockyHighlands) {
            return BiomeType.PLAINS;
        }
        if (rollingHills >= rockyHighlands) {
            return BiomeType.ROLLING_HILLS;
        }
        return BiomeType.ROCKY_HIGHLANDS;
    }

    private static void requireFiniteNonNegative(
            String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
        }
    }
}
