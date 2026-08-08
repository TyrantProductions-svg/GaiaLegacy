package com.gaia.interaction.feedback;

import com.overlord.renderer.feedback.CameraImpulseVisual;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Bounded analytic view-only impulse envelope. Canonical Camera values never enter this owner. */
public final class CameraImpulseController implements AutoCloseable {
    private static final double PLACEMENT_PITCH = 0.35;
    private static final double PLACEMENT_TRANSLATION_Y = -0.006;
    private static final double PLACEMENT_DURATION = 0.15;
    private static final double BREAK_PITCH = 0.275;
    private static final double BREAK_YAW = 0.07;
    private static final double BREAK_DURATION = 0.20;

    private final EnvelopeAxis pitch = new EnvelopeAxis(-1.0, 1.0);
    private final EnvelopeAxis yaw = new EnvelopeAxis(-1.0, 1.0);
    private final EnvelopeAxis translationY = new EnvelopeAxis(-0.025, 0.025);
    private boolean closed;

    public void triggerPlacement(long eventIdentity) {
        if (closed) {
            return;
        }
        pitch.add(PLACEMENT_PITCH, PLACEMENT_DURATION);
        translationY.add(PLACEMENT_TRANSLATION_Y, PLACEMENT_DURATION);
    }

    public void triggerBreak(long eventIdentity) {
        if (closed) {
            return;
        }
        pitch.restart(BREAK_PITCH, BREAK_DURATION);
        double side = (mix64(eventIdentity) & 1L) == 0 ? 1.0 : -1.0;
        yaw.restart(side * BREAK_YAW, BREAK_DURATION);
    }

    public void update(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        if (closed) {
            return;
        }
        pitch.update(deltaSeconds);
        yaw.update(deltaSeconds);
        translationY.update(deltaSeconds);
    }

    public CameraImpulseVisual snapshot() {
        return new CameraImpulseVisual(
                (float) pitch.value(), (float) yaw.value(), (float) translationY.value());
    }

    public Matrix4f applyToView(Matrix4fc canonicalView) {
        Matrix4f result = new Matrix4f(Objects.requireNonNull(canonicalView, "canonicalView"));
        if (pitch.value() == 0 && yaw.value() == 0 && translationY.value() == 0) {
            return result;
        }
        return result
                .rotateX((float) Math.toRadians(pitch.value()))
                .rotateY((float) Math.toRadians(yaw.value()))
                .translate(0.0f, (float) translationY.value(), 0.0f);
    }

    public void reset() {
        pitch.reset();
        yaw.reset();
        translationY.reset();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        reset();
        closed = true;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static final class EnvelopeAxis {
        private final double minimum;
        private final double maximum;
        private double start;
        private double value;
        private double elapsed;
        private double duration;

        private EnvelopeAxis(double minimum, double maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        private void add(double impulse, double newDuration) {
            start = clamp(value + impulse, minimum, maximum);
            value = start;
            elapsed = 0.0;
            duration = newDuration;
        }

        private void restart(double impulse, double newDuration) {
            start = clamp(impulse, minimum, maximum);
            value = start;
            elapsed = 0.0;
            duration = newDuration;
        }

        private void update(double deltaSeconds) {
            if (value == 0.0 || deltaSeconds == 0.0) {
                return;
            }
            elapsed += deltaSeconds;
            if (elapsed + 1.0e-12 >= duration) {
                reset();
                return;
            }
            double remaining = 1.0 - elapsed / duration;
            value = start * remaining * remaining * remaining;
        }

        private double value() {
            return value;
        }

        private void reset() {
            start = 0.0;
            value = 0.0;
            elapsed = 0.0;
            duration = 0.0;
        }
    }
}
