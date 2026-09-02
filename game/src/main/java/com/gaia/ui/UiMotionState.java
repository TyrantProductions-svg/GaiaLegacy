package com.gaia.ui;

import java.util.Objects;

/** Pure presentation-time sampling for bounded UI motion; owns no history or callbacks. */
public final class UiMotionState {
    private UiMotionState() {}

    public static double durationSeconds(Token token, boolean reducedMotion) {
        Objects.requireNonNull(token, "token");
        double duration = switch (token) {
            case PRESS -> 0.080;
            case HOVER -> 0.120;
            case SELECTED -> 0.160;
            case MODAL_ENTER -> 0.200;
            case MODAL_EXIT -> 0.220;
            case SCREEN -> 0.240;
        };
        return reducedMotion ? Math.min(duration, 0.040) : duration;
    }

    public static Sample sample(
            double startedAtSeconds,
            double presentationTimeSeconds,
            Token token,
            boolean reducedMotion) {
        if (!Double.isFinite(startedAtSeconds) || !Double.isFinite(presentationTimeSeconds)) {
            throw new IllegalArgumentException("presentation time must be finite");
        }
        double elapsed = presentationTimeSeconds - startedAtSeconds;
        if (elapsed < 0.0 || elapsed > 0.250) {
            return new Sample(1.0, true);
        }
        double duration = durationSeconds(token, reducedMotion);
        double progress = Math.max(0.0, Math.min(1.0, elapsed / duration));
        return new Sample(progress, progress >= 1.0);
    }

    public enum Token {
        PRESS,
        HOVER,
        SELECTED,
        MODAL_ENTER,
        MODAL_EXIT,
        SCREEN
    }

    public record Sample(double progress, boolean settled) {
        public Sample {
            if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
                throw new IllegalArgumentException("motion progress must be within [0, 1]");
            }
        }
    }
}
