package com.gaia.interaction.feedback;

import com.overlord.renderer.feedback.VisualTransform;

/** Deterministic render-time animation state. New committed actions restart at time zero. */
public final class FirstPersonActionAnimator implements AutoCloseable {
    private static final double PLACE_DURATION = 0.14;
    private static final double BREAK_DURATION = 0.19;
    private static final double DROP_DURATION = 0.12;
    private static final float FIRST_VISIBLE_AMPLITUDE = 1.0f / 64.0f;

    private State state = State.IDLE;
    private double elapsedSeconds;
    private long eventIdentity;
    private boolean closed;

    public void triggerPlacement(long eventIdentity) {
        trigger(State.PLACE, eventIdentity);
    }

    public void triggerBreak(long eventIdentity) {
        trigger(State.BREAK_SWING, eventIdentity);
    }

    public void triggerDrop(long eventIdentity) {
        trigger(State.DROP, eventIdentity);
    }

    public void update(double deltaSeconds) {
        requireDelta(deltaSeconds);
        if (closed || state == State.IDLE || deltaSeconds == 0) {
            return;
        }
        elapsedSeconds += deltaSeconds;
        if (elapsedSeconds + 1.0e-12 >= durationSeconds()) {
            reset();
        }
    }

    public State state() {
        return state;
    }

    public double durationSeconds() {
        return switch (state) {
            case IDLE -> 0.0;
            case PLACE -> PLACE_DURATION;
            case BREAK_SWING -> BREAK_DURATION;
            case DROP -> DROP_DURATION;
        };
    }

    public VisualTransform snapshot() {
        if (state == State.IDLE) {
            return VisualTransform.identity();
        }
        float progress = (float) Math.min(1.0, elapsedSeconds / durationSeconds());
        return switch (state) {
            case IDLE -> VisualTransform.identity();
            case PLACE -> placement(progress);
            case BREAK_SWING -> breaking(progress, eventIdentity);
            case DROP -> dropping(progress);
        };
    }

    public void reset() {
        state = State.IDLE;
        elapsedSeconds = 0;
        eventIdentity = 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        reset();
        closed = true;
    }

    private void trigger(State next, long identity) {
        if (closed) {
            return;
        }
        state = next;
        elapsedSeconds = 0;
        eventIdentity = identity;
    }

    private static VisualTransform placement(float progress) {
        float amplitude = attackRecovery(progress, 0.35f);
        float roll = ((progress * 2.0f - 1.0f) * 2.0f) * amplitude;
        return new VisualTransform(
                0,
                -0.10f * amplitude,
                0.035f * amplitude,
                12.0f * amplitude,
                0,
                roll,
                1,
                1);
    }

    private static VisualTransform breaking(float progress, long identity) {
        float amplitude = attackRecovery(progress, 0.32f);
        float side = (identity & 1L) == 0 ? 1.0f : -1.0f;
        return new VisualTransform(
                0,
                -0.05f * amplitude,
                -0.10f * amplitude,
                0,
                side * 16.0f * amplitude,
                side * 10.0f * amplitude,
                1,
                1);
    }

    private static VisualTransform dropping(float progress) {
        float amplitude = attackRecovery(progress, 0.42f);
        return new VisualTransform(
                0.025f * amplitude,
                0.015f * amplitude,
                -0.085f * amplitude,
                -4.0f * amplitude,
                0,
                -3.0f * amplitude,
                1,
                1);
    }

    private static float attackRecovery(float progress, float attackEnd) {
        if (progress <= attackEnd) {
            float normalized = progress / attackEnd;
            float eased = 1.0f - cube(1.0f - normalized);
            return FIRST_VISIBLE_AMPLITUDE
                    + (1.0f - FIRST_VISIBLE_AMPLITUDE) * eased;
        }
        float normalized = (progress - attackEnd) / (1.0f - attackEnd);
        float smooth = normalized * normalized * (3.0f - 2.0f * normalized);
        return 1.0f - smooth;
    }

    private static float cube(float value) {
        return value * value * value;
    }

    private static void requireDelta(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
    }

    public enum State {
        IDLE,
        PLACE,
        BREAK_SWING,
        DROP
    }
}
