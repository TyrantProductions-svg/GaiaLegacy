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
    private static final RenderStateSpec OVERLAY_STATE =
            new RenderStateSpec(
                    true,
                    DepthFunction.LEQUAL,
                    false,
                    BlendMode.DISABLED,
                    false,
                    true,
                    -1.0f,
                    -1.0f);
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    false,
                    DepthFunction.LESS,
                    false,
                    true,
                    1,
                    2,
                    3,
                    4,
                    5,
                    6,
                    false,
                    17,
                    18,
                    19,
                    false,
                    -2.25f,
                    3.5f,
                    7,
                    8,
                    9,
                    new Viewport(41, 42, 43, 44));

    @Test
    void exceptionalExitRestoresEveryCapturedValue() {
        RecordingRenderStateBackend backend =
                new RecordingRenderStateBackend(INCOMING);

        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (RenderStateScope ignored =
                            RenderStateScope.open(backend, OVERLAY_STATE)) {
                        backend.setViewport(new Viewport(0, 0, 1024, 768));
                        throw new IllegalStateException("draw failed");
                    }
                });

        assertEquals(INCOMING, backend.current());
        assertEquals(1, backend.restoreCount());
    }

    @Test
    void fourArgumentSpecKeepsLegacyDepthAndPolygonOffsetDefaults() {
        assertEquals(DepthFunction.LESS, WORLD_OPAQUE.depthFunction());
        assertFalse(WORLD_OPAQUE.polygonOffsetFill());
        assertEquals(0.0f, WORLD_OPAQUE.polygonOffsetFactor());
        assertEquals(0.0f, WORLD_OPAQUE.polygonOffsetUnits());
    }

    @Test
    void viewportRejectsNegativeDimensions() {
        IllegalArgumentException negativeWidth =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Viewport(0, 0, -1, 1));
        IllegalArgumentException negativeHeight =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Viewport(0, 0, 1, -1));

        assertEquals(
                "viewport dimensions must be non-negative",
                negativeWidth.getMessage());
        assertEquals(
                "viewport dimensions must be non-negative",
                negativeHeight.getMessage());
    }

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

        @Override
        public void setViewport(Viewport viewport) {
            calls.add("setViewport:" + viewport);
        }
    }

    private static final class RecordingRenderStateBackend
            implements RenderStateBackend {
        private RenderStateSnapshot current;
        private int restoreCount;

        private RecordingRenderStateBackend(RenderStateSnapshot incoming) {
            current = incoming;
        }

        RenderStateSnapshot current() {
            return current;
        }

        int restoreCount() {
            return restoreCount;
        }

        @Override
        public RenderStateSnapshot capture() {
            return current;
        }

        @Override
        public void apply(RenderStateSpec state) {
            current =
                    new RenderStateSnapshot(
                            state.depthTest(),
                            state.depthFunction(),
                            state.depthWrite(),
                            state.blendMode() != BlendMode.DISABLED,
                            current.blendSourceRgb(),
                            current.blendDestinationRgb(),
                            current.blendSourceAlpha(),
                            current.blendDestinationAlpha(),
                            current.blendEquationRgb(),
                            current.blendEquationAlpha(),
                            state.cullFace(),
                            current.vertexArray(),
                            current.arrayBuffer(),
                            current.elementArrayBuffer(),
                            state.polygonOffsetFill(),
                            state.polygonOffsetFactor(),
                            state.polygonOffsetUnits(),
                            current.currentProgram(),
                            current.activeTexture(),
                            current.texture2dUnit0(),
                            current.viewport());
        }

        @Override
        public void restore(RenderStateSnapshot snapshot) {
            current = snapshot;
            restoreCount++;
        }

        @Override
        public void clearColorAndDepth() {}

        @Override
        public void setViewport(Viewport viewport) {
            current =
                    new RenderStateSnapshot(
                            current.depthTest(),
                            current.depthFunction(),
                            current.depthWrite(),
                            current.blend(),
                            current.blendSourceRgb(),
                            current.blendDestinationRgb(),
                            current.blendSourceAlpha(),
                            current.blendDestinationAlpha(),
                            current.blendEquationRgb(),
                            current.blendEquationAlpha(),
                            current.cullFace(),
                            current.vertexArray(),
                            current.arrayBuffer(),
                            current.elementArrayBuffer(),
                            current.polygonOffsetFill(),
                            current.polygonOffsetFactor(),
                            current.polygonOffsetUnits(),
                            current.currentProgram(),
                            current.activeTexture(),
                            current.texture2dUnit0(),
                            viewport);
        }
    }
}
