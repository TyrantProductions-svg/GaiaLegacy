package com.overlord.renderer.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final class FakeRenderStateBackend implements RenderStateBackend {
        private final RenderStateSnapshot incoming;
        private final List<String> calls = new ArrayList<>();

        private FakeRenderStateBackend(RenderStateSnapshot incoming) {
            this.incoming = incoming;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        String lastCall() {
            return calls.get(calls.size() - 1);
        }

        @Override
        public RenderStateSnapshot capture() {
            calls.add("capture");
            return incoming;
        }

        @Override
        public void apply(RenderStateSpec state) {
            calls.add("apply:" + state);
        }

        @Override
        public void restore(RenderStateSnapshot snapshot) {
            calls.add("restore:" + snapshot);
        }

        @Override
        public void clearColorAndDepth() {
            calls.add("clearColorAndDepth");
        }
    }
}
