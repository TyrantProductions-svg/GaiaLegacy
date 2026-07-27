package com.gaia.interaction;

public record BreakRule(boolean breakable, double requiredSeconds) {
    public BreakRule {
        if (!Double.isFinite(requiredSeconds) || requiredSeconds < 0) {
            throw new IllegalArgumentException(
                    "requiredSeconds must be finite and non-negative");
        }
        if (!breakable && requiredSeconds != 0) {
            throw new IllegalArgumentException(
                    "unbreakable rule must not carry a break duration");
        }
    }

    public static BreakRule unbreakable() {
        return new BreakRule(false, 0);
    }
}
