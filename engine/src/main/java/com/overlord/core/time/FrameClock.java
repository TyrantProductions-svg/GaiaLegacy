package com.overlord.core.time;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class FrameClock {
    private final LongSupplier nanoTime;
    private final double maxDeltaSeconds;
    private boolean initialized;
    private long previousNanos;

    public FrameClock(LongSupplier nanoTime, double maxDeltaSeconds) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (!Double.isFinite(maxDeltaSeconds) || maxDeltaSeconds <= 0.0) {
            throw new IllegalArgumentException("maxDeltaSeconds must be finite and positive");
        }
        this.maxDeltaSeconds = maxDeltaSeconds;
    }

    public double tick() {
        long currentNanos = nanoTime.getAsLong();
        if (!initialized) {
            initialized = true;
            previousNanos = currentNanos;
            return 0.0;
        }

        long elapsedNanos = currentNanos - previousNanos;
        previousNanos = currentNanos;
        double deltaSeconds = Math.max(0.0, elapsedNanos / 1_000_000_000.0);
        return Math.min(deltaSeconds, maxDeltaSeconds);
    }
}
