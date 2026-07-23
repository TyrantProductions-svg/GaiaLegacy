package com.overlord.core.input;

public record MouseDelta(double x, double y) {
    public static final MouseDelta ZERO = new MouseDelta(0.0, 0.0);
}
