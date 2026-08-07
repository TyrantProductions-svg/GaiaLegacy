package com.gaia.interaction.feedback;

import com.overlord.config.GameConfig;
import com.overlord.renderer.feedback.FirstPersonMovementVisual;
import java.util.Objects;

/** Deterministic fixed-step owner for view-only first-person movement presentation. */
public final class FirstPersonMovementPresentation implements AutoCloseable {
    public static final double FIXED_STEP_SECONDS = 1.0 / 60.0;

    private static final double WALK_FREQUENCY_HZ = 1.8;
    private static final float WALK_VERTICAL_AMPLITUDE = 0.025f;
    private static final float WALK_LATERAL_AMPLITUDE = 0.012f;
    private static final float WALK_ROLL_AMPLITUDE_DEGREES = 0.18f;
    private static final double WALK_ATTACK_SECONDS = 0.10;
    private static final double WALK_RELEASE_SECONDS = 0.14;
    private static final float MEANINGFUL_HORIZONTAL_SPEED = 0.05f;
    private static final double FIXED_STEP_TOLERANCE = 1.0e-7;
    private static final float SETTLED_EPSILON = 1.0e-6f;
    private static final float MINIMUM_STEP_DELTA = 0.02f;
    private static final float STEP_LIMIT_TOLERANCE = 1.0e-4f;
    private static final double STEP_UP_DURATION_SECONDS = 0.13;
    private static final double STEP_DOWN_DURATION_SECONDS = 0.11;
    private static final float JUMP_TAKEOFF_AMPLITUDE = 0.018f;
    private static final double JUMP_TAKEOFF_DURATION_SECONDS = 0.10;
    private static final float MAX_LANDING_COMPRESSION = 0.035f;
    private static final float FULL_LANDING_IMPACT_SPEED = 12.0f;
    private static final double LANDING_DURATION_SECONDS = 0.16;

    private FirstPersonMovementVisual previous = FirstPersonMovementVisual.identity();
    private FirstPersonMovementVisual current = FirstPersonMovementVisual.identity();
    private double walkPhase;
    private float walkStrength;
    private FirstPersonMovementState previousState;
    private final RecoveryEnvelope step = new RecoveryEnvelope();
    private final RecoveryEnvelope takeoff = new RecoveryEnvelope();
    private final RecoveryEnvelope landing = new RecoveryEnvelope();
    private boolean closed;

    public void fixedUpdate(double fixedDeltaSeconds, FirstPersonMovementState state) {
        requireFixedStep(fixedDeltaSeconds);
        FirstPersonMovementState sample = Objects.requireNonNull(state, "state");
        if (closed) {
            return;
        }

        previous = current;
        step.update(fixedDeltaSeconds);
        takeoff.update(fixedDeltaSeconds);
        landing.update(fixedDeltaSeconds);
        classifyTraversal(sample);
        updateWalk(fixedDeltaSeconds, sample);

        float lateralWave = (float) Math.sin(walkPhase);
        float verticalWave = (float) Math.sin(walkPhase * 2.0);
        float translationX = WALK_LATERAL_AMPLITUDE * walkStrength * lateralWave;
        float translationY = WALK_VERTICAL_AMPLITUDE * walkStrength * verticalWave
                + step.value()
                + takeoff.value()
                + landing.value();
        float roll = WALK_ROLL_AMPLITUDE_DEGREES * walkStrength * -lateralWave;
        current = isSettled(translationX, translationY, roll)
                ? FirstPersonMovementVisual.identity()
                : new FirstPersonMovementVisual(translationX, translationY, roll);
        previousState = sample;
    }

    private void updateWalk(double fixedDeltaSeconds, FirstPersonMovementState sample) {
        float targetStrength = targetWalkStrength(sample);
        double duration = targetStrength > walkStrength
                ? WALK_ATTACK_SECONDS
                : WALK_RELEASE_SECONDS;
        walkStrength = moveTowards(
                walkStrength,
                targetStrength,
                (float) (fixedDeltaSeconds / duration));

        if (targetStrength > 0.0f) {
            double speedScale = Math.max(0.0, Math.min(
                    1.0,
                    sample.horizontalSpeed() / GameConfig.Player.MOVEMENT_SPEED));
            walkPhase = wrapPhase(
                    walkPhase + Math.PI * 2.0 * WALK_FREQUENCY_HZ
                            * speedScale * fixedDeltaSeconds);
        }

        if (walkStrength <= SETTLED_EPSILON) {
            walkStrength = 0.0f;
            walkPhase = 0.0;
        }
    }

    public FirstPersonMovementVisual snapshot(float interpolationAlpha) {
        if (closed) {
            return FirstPersonMovementVisual.identity();
        }
        return previous.interpolate(current, interpolationAlpha);
    }

    public void reset() {
        previous = FirstPersonMovementVisual.identity();
        current = FirstPersonMovementVisual.identity();
        walkPhase = 0.0;
        walkStrength = 0.0f;
        previousState = null;
        step.reset();
        takeoff.reset();
        landing.reset();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        reset();
        closed = true;
    }

    private static float targetWalkStrength(FirstPersonMovementState state) {
        if (!state.grounded()
                || state.noclip()
                || state.horizontalSpeed() <= MEANINGFUL_HORIZONTAL_SPEED) {
            return 0.0f;
        }
        return Math.min(
                1.0f,
                (state.horizontalSpeed() - MEANINGFUL_HORIZONTAL_SPEED)
                        / (GameConfig.Player.MOVEMENT_SPEED - MEANINGFUL_HORIZONTAL_SPEED));
    }

    private void classifyTraversal(FirstPersonMovementState sample) {
        if (previousState == null || previousState.noclip() || sample.noclip()) {
            if (sample.noclip()) {
                step.reset();
                takeoff.reset();
                landing.reset();
            }
            return;
        }

        float deltaY = sample.feetY() - previousState.feetY();
        if (previousState.grounded()
                && sample.grounded()
                && Math.abs(deltaY) >= MINIMUM_STEP_DELTA
                && Math.abs(deltaY)
                        <= GameConfig.Player.MAX_STEP_HEIGHT + STEP_LIMIT_TOLERANCE) {
            float restarted = clamp(
                    step.value() - deltaY,
                    -GameConfig.Player.MAX_STEP_HEIGHT,
                    GameConfig.Player.MAX_STEP_HEIGHT);
            step.trigger(
                    restarted,
                    deltaY > 0.0f
                            ? STEP_UP_DURATION_SECONDS
                            : STEP_DOWN_DURATION_SECONDS);
            return;
        }

        if (previousState.grounded()
                && !sample.grounded()
                && sample.verticalSpeed() > 0.0f) {
            takeoff.trigger(JUMP_TAKEOFF_AMPLITUDE, JUMP_TAKEOFF_DURATION_SECONDS);
            return;
        }

        if (!previousState.grounded() && sample.grounded()) {
            float impactSpeed = Math.max(0.0f, -previousState.verticalSpeed());
            float scale = Math.min(1.0f, impactSpeed / FULL_LANDING_IMPACT_SPEED);
            landing.trigger(-MAX_LANDING_COMPRESSION * scale, LANDING_DURATION_SECONDS);
        }
    }

    private static boolean isSettled(float x, float y, float roll) {
        return Math.abs(x) <= SETTLED_EPSILON
                && Math.abs(y) <= SETTLED_EPSILON
                && Math.abs(roll) <= SETTLED_EPSILON;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float moveTowards(float value, float target, float maximumDelta) {
        if (value < target) {
            return Math.min(target, value + maximumDelta);
        }
        return Math.max(target, value - maximumDelta);
    }

    private static double wrapPhase(double phase) {
        double fullTurn = Math.PI * 2.0;
        return phase >= fullTurn ? phase - fullTurn : phase;
    }

    private static void requireFixedStep(double value) {
        if (!Double.isFinite(value)
                || Math.abs(value - FIXED_STEP_SECONDS) > FIXED_STEP_TOLERANCE) {
            throw new IllegalArgumentException("fixedDeltaSeconds must equal 1/60");
        }
    }

    private static final class RecoveryEnvelope {
        private float start;
        private float value;
        private double elapsed;
        private double duration;

        private void trigger(float nextStart, double nextDuration) {
            start = nextStart;
            value = nextStart;
            elapsed = 0.0;
            duration = nextDuration;
        }

        private void update(double deltaSeconds) {
            if (value == 0.0f) {
                return;
            }
            elapsed += deltaSeconds;
            if (elapsed + 1.0e-12 >= duration) {
                reset();
                return;
            }
            float remaining = (float) (1.0 - elapsed / duration);
            value = start * remaining * remaining * remaining;
            if (Math.abs(value) <= SETTLED_EPSILON) {
                reset();
            }
        }

        private float value() {
            return value;
        }

        private void reset() {
            start = 0.0f;
            value = 0.0f;
            elapsed = 0.0;
            duration = 0.0;
        }
    }
}
