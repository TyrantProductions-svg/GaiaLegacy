package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.session.GameSessionFrame;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.overlord.core.input.InputManager;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductLoopClosePolicyTest {
    private static final double FRAME_DELTA_SECONDS = 1.0d / 60.0d;

    @Test
    void ordinaryFramesNeverPersistAndProductCloseRunsExactlyOnce() {
        RecordingHost host = new RecordingHost();
        AtomicInteger persistenceCalls = new AtomicInteger();
        ProductLoop loop = loop(host, persistenceCalls::incrementAndGet);

        loop.runFrame(FRAME_DELTA_SECONDS);
        loop.runFrame(FRAME_DELTA_SECONDS);
        loop.runFrame(FRAME_DELTA_SECONDS);

        assertEquals(0, persistenceCalls.get());
        assertEquals(3, host.productRenders());
        assertEquals(3, host.swaps());

        host.requestClose();
        loop.run();
        loop.run();

        assertEquals(1, persistenceCalls.get());
    }

    @Test
    void closePersistenceFailurePropagatesWithoutBeingSilenced() {
        RecordingHost host = new RecordingHost();
        host.requestClose();
        RuntimeException closeFailure =
                new RuntimeException("injected close persistence failure");
        ProductLoop loop = loop(
                host,
                () -> {
                    throw closeFailure;
                });

        RuntimeException thrown = assertThrows(RuntimeException.class, loop::run);

        assertSame(closeFailure, thrown);
    }

    @Test
    void closePersistenceFailureIsSuppressedOntoThePrimaryLoopFailure() {
        RuntimeException primaryFailure =
                new RuntimeException("injected poll failure");
        RuntimeException closeFailure =
                new RuntimeException("injected close persistence failure");
        RecordingHost host = new RecordingHost();
        host.failPollWith(primaryFailure);
        ProductLoop loop = loop(
                host,
                () -> {
                    throw closeFailure;
                });

        RuntimeException thrown = assertThrows(RuntimeException.class, loop::run);

        assertSame(primaryFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    private static ProductLoop loop(RecordingHost host, Runnable closePolicy) {
        return new ProductLoop(
                new InputManager(),
                new ProductShellController(ScreenRouter.mainMenu()),
                new ProductScreenInputController(),
                new ProductScreenPresenter(new EmptySaveCatalog(), textRenderer()),
                () -> {
                    throw new AssertionError("settings close tests must not launch a session");
                },
                () -> FRAME_DELTA_SECONDS,
                host,
                closePolicy);
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static final class RecordingHost implements ProductLoop.FrameHost {
        private final UiLayoutContext layout = new UiLayoutContext(
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
        private boolean closeRequested;
        private RuntimeException pollFailure;
        private int productRenders;
        private int swaps;

        @Override
        public boolean shouldClose() {
            return closeRequested;
        }

        @Override
        public void pollEvents() {
            if (pollFailure != null) {
                throw pollFailure;
            }
        }

        @Override
        public UiLayoutContext layoutContext() {
            return layout;
        }

        @Override
        public void setCursorCaptured(boolean captured) {}

        @Override
        public void renderSession(GameSessionFrame frame) {
            throw new AssertionError("settings close tests must not render a session");
        }

        @Override
        public void renderProduct(ProductUiLayout layout) {
            productRenders++;
        }

        @Override
        public void swapBuffers() {
            swaps++;
        }

        private void requestClose() {
            closeRequested = true;
        }

        private void failPollWith(RuntimeException failure) {
            pollFailure = failure;
        }

        private int productRenders() {
            return productRenders;
        }

        private int swaps() {
            return swaps;
        }
    }
}
