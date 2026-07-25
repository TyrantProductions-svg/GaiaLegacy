package com.overlord.renderer.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderStateScopeTest {
    private static final RenderStateSpec WORLD_OPAQUE =
            new RenderStateSpec(true, true, BlendMode.DISABLED, true);
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    false,
                    false,
                    true,
                    1,
                    2,
                    3,
                    4,
                    5,
                    6,
                    false,
                    7,
                    8,
                    9);

    @Test
    void capturesThenAppliesRequestedStateAndRestoresIncomingStateOnClose() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);

        try (RenderStateScope ignored = RenderStateScope.open(backend, WORLD_OPAQUE)) {
            assertEquals(
                    List.of("capture", "apply:" + WORLD_OPAQUE),
                    backend.calls());
        }

        assertEquals(
                List.of(
                        "capture",
                        "apply:" + WORLD_OPAQUE,
                        "restore:" + INCOMING),
                backend.calls());
    }

    @Test
    void restoresIncomingStateBeforeTheSameExceptionEscapes() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);
        IllegalArgumentException expected = new IllegalArgumentException("pass failed");

        IllegalArgumentException escaped =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> {
                            try (RenderStateScope ignored =
                                    RenderStateScope.open(backend, WORLD_OPAQUE)) {
                                throw expected;
                            }
                        });

        assertSame(expected, escaped);
        assertEquals("restore:" + INCOMING, backend.lastCall());
    }

    @Test
    void closeRestoresIncomingStateOnlyOnce() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);
        RenderStateScope scope = RenderStateScope.open(backend, WORLD_OPAQUE);

        scope.close();
        scope.close();

        assertEquals(
                List.of(
                        "capture",
                        "apply:" + WORLD_OPAQUE,
                        "restore:" + INCOMING),
                backend.calls());
    }

    @Test
    void restoresIncomingStateWhenApplyFailsAndRethrowsTheSameError() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);
        AssertionError expected = new AssertionError("pass setup failed");
        backend.failApply(expected);

        AssertionError escaped =
                assertThrows(
                        AssertionError.class,
                        () -> RenderStateScope.open(backend, WORLD_OPAQUE));

        assertSame(expected, escaped);
        assertFalse(backend.mutated());
        assertEquals(
                List.of(
                        "capture",
                        "apply:" + WORLD_OPAQUE,
                        "restore:" + INCOMING),
                backend.calls());
    }

    @Test
    void suppressesRestoreFailureWithoutReplacingApplyFailure() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);
        IllegalStateException applyFailure =
                new IllegalStateException("pass setup failed");
        IllegalArgumentException restoreFailure =
                new IllegalArgumentException("rollback failed");
        backend.failApply(applyFailure);
        backend.failRestore(restoreFailure);

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () -> RenderStateScope.open(backend, WORLD_OPAQUE));

        assertSame(applyFailure, escaped);
        assertEquals(1, escaped.getSuppressed().length);
        assertSame(restoreFailure, escaped.getSuppressed()[0]);
        assertEquals(
                List.of(
                        "capture",
                        "apply:" + WORLD_OPAQUE,
                        "restore:" + INCOMING),
                backend.calls());
    }

    @Test
    void avoidsSelfSuppressionWhenApplyAndRestoreThrowTheSameFailure() {
        FakeRenderStateBackend backend = new FakeRenderStateBackend(INCOMING);
        IllegalStateException failure = new IllegalStateException("shared failure");
        backend.failApply(failure);
        backend.failRestore(failure);

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () -> RenderStateScope.open(backend, WORLD_OPAQUE));

        assertSame(failure, escaped);
        assertEquals(0, escaped.getSuppressed().length);
        assertEquals(
                List.of(
                        "capture",
                        "apply:" + WORLD_OPAQUE,
                        "restore:" + INCOMING),
                backend.calls());
    }

    private static final class FakeRenderStateBackend implements RenderStateBackend {
        private final RenderStateSnapshot incoming;
        private final List<String> calls = new ArrayList<>();
        private RuntimeException applyRuntimeFailure;
        private Error applyError;
        private RuntimeException restoreFailure;
        private boolean mutated;

        private FakeRenderStateBackend(RenderStateSnapshot incoming) {
            this.incoming = incoming;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        String lastCall() {
            return calls.get(calls.size() - 1);
        }

        boolean mutated() {
            return mutated;
        }

        void failApply(RuntimeException failure) {
            applyRuntimeFailure = failure;
        }

        void failApply(Error failure) {
            applyError = failure;
        }

        void failRestore(RuntimeException failure) {
            restoreFailure = failure;
        }

        @Override
        public RenderStateSnapshot capture() {
            calls.add("capture");
            return incoming;
        }

        @Override
        public void apply(RenderStateSpec state) {
            calls.add("apply:" + state);
            mutated = true;
            if (applyRuntimeFailure != null) {
                throw applyRuntimeFailure;
            }
            if (applyError != null) {
                throw applyError;
            }
        }

        @Override
        public void restore(RenderStateSnapshot snapshot) {
            calls.add("restore:" + snapshot);
            mutated = false;
            if (restoreFailure != null) {
                throw restoreFailure;
            }
        }

        @Override
        public void clearColorAndDepth() {
            calls.add("clearColorAndDepth");
        }
    }
}
