package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MouseInteractionLifecycleTest {
    @Test
    void cursorCaptureTransitionCancelsInteractionBeforeAnyFixedStep() {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicBoolean captured = new AtomicBoolean(true);
        MouseInteractionLifecycle lifecycle =
                new MouseInteractionLifecycle(cancellations::incrementAndGet);

        boolean nextCaptured = lifecycle.toggleCursorCapture(true, captured::set);

        assertFalse(nextCaptured);
        assertFalse(captured.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void focusLossCancelsInteractionBeforeAnyFixedStep() {
        AtomicInteger cancellations = new AtomicInteger();
        MouseInteractionLifecycle lifecycle =
                new MouseInteractionLifecycle(cancellations::incrementAndGet);

        lifecycle.onFocusLost();

        assertEquals(1, cancellations.get());
    }
}
