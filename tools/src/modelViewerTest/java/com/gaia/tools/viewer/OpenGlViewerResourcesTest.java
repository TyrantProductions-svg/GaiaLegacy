package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenGlViewerResourcesTest {
    @Test
    void drainsEveryQueuedErrorBeforeReportingGroupedOperationFailure() {
        ArrayDeque<Integer> errors = new ArrayDeque<>();
        errors.add(0x0500);
        errors.add(0x0501);
        errors.add(0x0502);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> OpenGlViewerResources.assertNoErrors(
                        "candidate upload", () -> errors.isEmpty() ? 0 : errors.removeFirst()));

        assertTrue(errors.isEmpty());
        assertTrue(failure.getMessage().contains("0x500"));
        assertTrue(failure.getMessage().contains("0x501"));
        assertTrue(failure.getMessage().contains("0x502"));
    }

    @Test
    void pathologicalErrorSourceStopsAtBoundAndReportsQueueNotClean() {
        AtomicInteger reads = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> OpenGlViewerResources.assertNoErrors(
                        "candidate upload", () -> {
                            reads.incrementAndGet();
                            return 0x0500;
                        }));

        assertEquals(OpenGlViewerResources.MAX_GL_ERRORS_PER_DRAIN + 1, reads.get());
        assertTrue(failure.getMessage().contains("did not drain"));
    }
}
