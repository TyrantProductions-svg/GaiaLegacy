package com.overlord.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FixedStepClockTest {
    private static final double STEP_SECONDS = 1.0 / 60.0;

    @Test
    void accumulatesPartialFramesAndExecutesExactStep() {
        FixedStepClock clock = new FixedStepClock(STEP_SECONDS, 5);

        assertEquals(0, clock.advance(STEP_SECONDS * 0.4));
        assertEquals(1, clock.advance(STEP_SECONDS * 0.6));
        assertEquals(0.0, clock.remainderSeconds(), 1.0e-9);
    }

    @Test
    void capsCatchUpAndKeepsFractionalRemainder() {
        FixedStepClock clock = new FixedStepClock(STEP_SECONDS, 5);

        assertEquals(5, clock.advance(STEP_SECONDS * 12.5));
        assertEquals(STEP_SECONDS * 0.5, clock.remainderSeconds(), 1.0e-9);
    }

    @Test
    void exposesFixedStepAsFloat() {
        FixedStepClock clock = new FixedStepClock(STEP_SECONDS, 5);

        assertEquals((float) STEP_SECONDS, clock.fixedStepSeconds());
    }

    @Test
    void exposesRemainderAsInterpolationAlpha() {
        FixedStepClock clock = new FixedStepClock(STEP_SECONDS, 8);

        clock.advance(STEP_SECONDS * 1.5);

        assertEquals(0.5, clock.interpolationAlpha(), 1.0e-9);
    }
}
