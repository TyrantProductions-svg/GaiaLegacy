package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiMotionStateTest {
    @Test
    void exposesTheApprovedPresentationDurations() {
        assertEquals(0.080, UiMotionState.durationSeconds(UiMotionState.Token.PRESS, false));
        assertEquals(0.120, UiMotionState.durationSeconds(UiMotionState.Token.HOVER, false));
        assertEquals(0.160, UiMotionState.durationSeconds(UiMotionState.Token.SELECTED, false));
        assertEquals(0.200, UiMotionState.durationSeconds(UiMotionState.Token.MODAL_ENTER, false));
        assertEquals(0.220, UiMotionState.durationSeconds(UiMotionState.Token.MODAL_EXIT, false));
        assertEquals(0.240, UiMotionState.durationSeconds(UiMotionState.Token.SCREEN, false));
        for (UiMotionState.Token token : UiMotionState.Token.values()) {
            assertTrue(UiMotionState.durationSeconds(token, true) <= 0.040);
        }
    }

    @Test
    void samplingIsPureMonotonicClampedAndSettlesAfterLongPresentationGap() {
        UiMotionState.Sample start = UiMotionState.sample(
                0.0, 0.0, UiMotionState.Token.SCREEN, false);
        UiMotionState.Sample middle = UiMotionState.sample(
                0.0, 0.120, UiMotionState.Token.SCREEN, false);
        UiMotionState.Sample end = UiMotionState.sample(
                0.0, 0.240, UiMotionState.Token.SCREEN, false);
        UiMotionState.Sample longGap = UiMotionState.sample(
                1.0, 1.251, UiMotionState.Token.HOVER, false);

        assertEquals(0.0, start.progress());
        assertFalse(start.settled());
        assertEquals(0.5, middle.progress(), 0.000_001);
        assertTrue(middle.progress() >= start.progress());
        assertEquals(1.0, end.progress());
        assertTrue(end.settled());
        assertEquals(1.0, longGap.progress());
        assertTrue(longGap.settled());
    }

    @Test
    void invalidOrReversedPresentationTimeSettlesWithoutCallbacksOrHistory() {
        assertTrue(UiMotionState.sample(
                2.0, 1.0, UiMotionState.Token.PRESS, false).settled());
        assertTrue(UiMotionState.class.getDeclaredFields().length == 0,
                "motion authority must not retain callback or presentation history");
    }
}
