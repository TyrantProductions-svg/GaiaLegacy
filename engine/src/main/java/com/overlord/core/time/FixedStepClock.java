package com.overlord.core.time;

public final class FixedStepClock {
    private static final double EPSILON_SECONDS = 1.0e-12;

    private final double fixedStepSeconds;
    private final int maxStepsPerFrame;
    private double accumulatorSeconds;

    public FixedStepClock(double fixedStepSeconds, int maxStepsPerFrame) {
        if (!Double.isFinite(fixedStepSeconds) || fixedStepSeconds <= 0.0) {
            throw new IllegalArgumentException("Fixed step must be finite and positive");
        }
        if (maxStepsPerFrame <= 0) {
            throw new IllegalArgumentException("Maximum steps per frame must be positive");
        }
        this.fixedStepSeconds = fixedStepSeconds;
        this.maxStepsPerFrame = maxStepsPerFrame;
    }

    public int advance(double deltaSeconds) {
        if (Double.isFinite(deltaSeconds) && deltaSeconds > 0.0) {
            accumulatorSeconds += deltaSeconds;
        }

        int availableSteps =
                (int) Math.floor((accumulatorSeconds + EPSILON_SECONDS) / fixedStepSeconds);
        int executedSteps = Math.min(availableSteps, maxStepsPerFrame);
        accumulatorSeconds -= availableSteps * fixedStepSeconds;
        if (accumulatorSeconds < 0.0 && accumulatorSeconds > -EPSILON_SECONDS) {
            accumulatorSeconds = 0.0;
        }
        return executedSteps;
    }

    public float fixedStepSeconds() {
        return (float) fixedStepSeconds;
    }

    public double remainderSeconds() {
        return accumulatorSeconds;
    }
}
