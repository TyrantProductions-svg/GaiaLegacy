package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.save.store.SaveRecoveryResult;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionLauncher;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionState;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.gaia.shell.ui.UiHitRegion;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.WorldSlotsController;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.input.MouseDelta;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProductLoopSaveOrderingTest {
    private static final double DELTA = 1.0d / 60.0d;
    private static final SaveGameId ID = GameSessionSaveLifecycleTest.ID;

    @Test
    void newWorldLoadingFrameDoesNotCapturePausedBeforeSessionIsReady() {
        Fixture fixture = new Fixture();
        fixture.raw.completeLoadOnPoll = false;

        fixture.click(UiActionId.NEW_WORLD);

        assertDoesNotThrow(() -> fixture.pressButton(UiActionId.CREATE_WORLD));
        assertEquals(ScreenId.LOADING, fixture.shell.snapshot().screen());
        assertEquals(0, fixture.raw.capturePausedCalls);
        assertEquals(0, fixture.raw.advanceCalls);
    }

    @Test
    void cancelingNewWorldLoadingReleasesItsProgressOperationBeforeMainMenu() {
        Fixture fixture = new Fixture();
        fixture.raw.completeLoadOnPoll = false;
        fixture.click(UiActionId.NEW_WORLD);
        fixture.click(UiActionId.CREATE_WORLD);

        fixture.click(UiActionId.DISMISS);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell.snapshot().screen());
        assertTrue(fixture.shell.snapshot().operationProgress().isEmpty());
        assertDoesNotThrow(fixture::frame,
                "a canceled create operation must not republish into the main-menu route");
        assertEquals(1, fixture.raw.closeCalls);
    }

    @Test
    void saveRendersOneStaticSavingFrameThenWritesAndCheckpointsNextFrame() {
        Fixture fixture = new Fixture();
        fixture.enterPaused();
        fixture.raw.dirty = true;
        fixture.events.clear();

        fixture.pressButton(UiActionId.SAVE);

        assertEquals(ScreenId.SAVING, fixture.shell.snapshot().screen());
        assertEquals(0, fixture.manualWrites());
        assertTrue(fixture.events.contains("render-product:SAVING"));
        assertTrue(fixture.events.contains("swap"));
        assertEquals(0, fixture.raw.advanceCalls);

        fixture.events.clear();
        fixture.releaseButton();
        fixture.awaitScreen(ScreenId.PAUSED);

        assertEquals(ScreenId.PAUSED, fixture.shell.snapshot().screen());
        assertEquals(1, fixture.manualWrites());
        assertFalse(fixture.raw.dirty);
        assertOrder(fixture.events, "write:2", "checkpoint", "render-product:PAUSED", "swap");
        assertEquals(0, fixture.raw.advanceCalls);
        assertTrue(fixture.shell.snapshot().operationProgress().isEmpty());
        assertEquals(0, fixture.acceptedOperationCount());
        assertDoesNotThrow(fixture::frame,
                "save success must not forward progress after PAUSED");
    }

    @Test
    void saveAndQuitClosesOnlyAfterTheSuccessfulCheckpoint() {
        Fixture fixture = new Fixture();
        fixture.enterPaused();
        fixture.raw.dirty = true;
        fixture.events.clear();

        fixture.pressButton(UiActionId.SAVE_AND_QUIT);
        assertEquals(ScreenId.SAVING, fixture.shell.snapshot().screen());
        assertEquals(0, fixture.raw.closeCalls);
        assertEquals(0, fixture.manualWrites());

        fixture.events.clear();
        fixture.releaseButton();
        fixture.awaitScreen(ScreenId.MAIN_MENU);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell.snapshot().screen());
        assertEquals(1, fixture.manualWrites());
        assertEquals(1, fixture.raw.closeCalls);
        assertOrder(fixture.events, "write:2", "checkpoint", "close-session", "render-product:MAIN_MENU");
        assertTrue(fixture.shell.snapshot().operationProgress().isEmpty());
        assertEquals(0, fixture.acceptedOperationCount());
        assertDoesNotThrow(fixture::frame,
                "save-and-quit must not forward progress after MAIN_MENU");
    }

    @Test
    void blockedSaveKeepsPollingRenderingAndSwappingWithoutEarlyCheckpoint()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture fixture = new Fixture(entered, release);
        fixture.enterPaused();
        fixture.raw.dirty = true;

        fixture.pressButton(UiActionId.SAVE);
        fixture.releaseButton();
        fixture.frame();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        fixture.events.clear();
        fixture.frame();
        fixture.frame();
        fixture.frame();

        assertEquals(ScreenId.SAVING, fixture.shell.snapshot().screen());
        assertTrue(fixture.raw.dirty);
        assertEquals(3, fixture.events.stream().filter("poll"::equals).count());
        assertEquals(3, fixture.events.stream().filter("swap"::equals).count());
        assertEquals(3, fixture.events.stream()
                .filter("render-product:SAVING"::equals).count());

        release.countDown();
        fixture.awaitScreen(ScreenId.PAUSED);
        assertFalse(fixture.raw.dirty);
    }

    @Test
    void closeDuringBlockedInitialSaveStopsWorkerThenClosesSessionExactlyOnce()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture fixture = new Fixture(entered, release, 1);

        fixture.click(UiActionId.NEW_WORLD);
        fixture.pressButton(UiActionId.CREATE_WORLD);
        fixture.releaseButton();
        fixture.frame();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        fixture.host.shouldClose = true;
        fixture.loop.run();

        assertEquals(1, fixture.raw.closeCalls,
                "the initial-save ticket and wrapped session must close once");
        assertEquals(GameSessionState.CLOSED, fixture.raw.state());
    }

    private static void assertOrder(List<String> events, String... expected) {
        int previous = -1;
        for (String event : expected) {
            int index = -1;
            for (int candidate = previous + 1; candidate < events.size(); candidate++) {
                if (events.get(candidate).equals(event)) {
                    index = candidate;
                    break;
                }
            }
            assertTrue(index > previous, () -> event + " was out of order in " + events);
            previous = index;
        }
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final InputManager input = new InputManager();
        private final ScreenRouter router = ScreenRouter.mainMenu();
        private final ProductShellController shell = new ProductShellController(router);
        private final EmptySaveCatalog catalog = new EmptySaveCatalog();
        private final NewWorldDraftController draft = new NewWorldDraftController(catalog);
        private final WorldSlotsController slots = new WorldSlotsController(catalog, 4);
        private final ProductScreenPresenter presenter = new ProductScreenPresenter(
                catalog, textRenderer(), ProductLoopSaveOrderingTest::defaultSettings, draft, slots);
        private final RecordingSession raw = new RecordingSession(events);
        private final RecordingHost host = new RecordingHost(events, shell);
        private final int[] writes = new int[1];
        private final ProductLoop loop;

        private Fixture() {
            this(null, null, Integer.MIN_VALUE);
        }

        private Fixture(CountDownLatch entered, CountDownLatch release) {
            this(entered, release, 2);
        }

        private Fixture(
                CountDownLatch entered,
                CountDownLatch release,
                int blockedWriteNumber) {
            SaveCoordinator coordinator = new SaveCoordinator(id -> (snapshot, modified) -> {
                writes[0]++;
                events.add("write:" + writes[0]);
                if (entered != null && writes[0] == blockedWriteNumber) {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out awaiting save release");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
            });
            GameSessionLauncher launcher = new GameSessionLauncher(
                    (request, config) -> raw,
                    snapshot -> { throw new AssertionError("restore not expected"); },
                    id -> SaveArchiveReadResult.corrupt(
                            com.gaia.save.archive.SaveDiagnostic.of(
                                    "test.unused", "load not expected")),
                    coordinator,
                    request -> new com.gaia.session.GameSessionConfig(
                            request.seed(), 2, com.gaia.interaction.GameMode.SURVIVAL, false),
                    () -> Instant.parse("2026-08-12T01:00:00Z"));
            ProductLoop.PersistenceServices persistence =
                    new ProductLoop.PersistenceServices(
                            launcher,
                            draft,
                            slots,
                            () -> ID,
                            new ProductLoop.WorldSlotOperations() {
                                @Override
                                public SaveDeleteResult delete(SaveGameId id) {
                                    throw new AssertionError("delete not expected");
                                }

                                @Override
                                public SaveRecoveryResult recover(SaveGameId id) {
                                    throw new AssertionError("recovery not expected");
                                }
                            });
            loop = new ProductLoop(
                    input,
                    shell,
                    new ProductScreenInputController(),
                    presenter,
                    persistence,
                    () -> DELTA,
                    host);
        }

        private int manualWrites() {
            return Math.max(0, writes[0] - 1);
        }

        private int acceptedOperationCount() {
            try {
                var field = ProductLoop.class.getDeclaredField("operations");
                field.setAccessible(true);
                return ((ProductOperationRunner) field.get(loop)).acceptedCount();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        private void enterPaused() {
            click(UiActionId.NEW_WORLD);
            click(UiActionId.CREATE_WORLD);
            awaitScreen(ScreenId.PLAYING);
            InputManagerTestDriver.key(input, GLFW_KEY_ESCAPE, GLFW_PRESS);
            frame();
            InputManagerTestDriver.key(input, GLFW_KEY_ESCAPE, GLFW_RELEASE);
            assertEquals(ScreenId.PAUSED, shell.snapshot().screen());
            raw.advanceCalls = 0;
        }

        private void click(UiActionId action) {
            pressButton(action);
            releaseButton();
            frame();
        }

        private void pressButton(UiActionId action) {
            ProductUiLayout layout = presenter.present(
                    shell.snapshot(), host.layoutContext());
            UiHitRegion region = layout.region(action);
            InputManagerTestDriver.cursor(input, region.centerX(), region.centerY());
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            frame();
        }

        private void releaseButton() {
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        }

        private void frame() {
            loop.runFrame(DELTA);
        }

        private void awaitScreen(ScreenId expected) {
            for (int attempt = 0; attempt < 500; attempt++) {
                frame();
                if (shell.snapshot().screen() == expected) {
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            throw new AssertionError(
                    "screen did not become " + expected + ": " + shell.snapshot()
                            + ", writes=" + writes[0] + ", events=" + events);
        }
    }

    private static final class RecordingSession implements GameSession {
        private final List<String> events;
        private GameSessionState state = GameSessionState.LOADING;
        private boolean dirty = true;
        private boolean completeLoadOnPoll = true;
        private int advanceCalls;
        private int capturePausedCalls;
        private int closeCalls;

        private RecordingSession(List<String> events) {
            this.events = events;
        }

        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() {
            if (completeLoadOnPoll) {
                state = GameSessionState.READY;
            }
        }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            advanceCalls++;
            return frame(delta);
        }
        @Override public GameSessionFrame capturePaused() {
            if (state != GameSessionState.READY) {
                throw new IllegalStateException("session is not ready");
            }
            capturePausedCalls++;
            return frame(0.0d);
        }
        @Override public SessionSaveCaptureResult captureSave() {
            return GameSessionPersistenceTestFixture.runtimeCaptured(
                    GameSessionSaveLifecycleTest.snapshot(), 0L);
        }
        @Override public void markSaved(SessionPersistenceRevision revision) {
            events.add("checkpoint");
            dirty = false;
        }
        @Override public boolean hasUnsavedChanges() { return dirty; }
        @Override public void discardFixedTime() {}
        @Override public void close() {
            events.add("close-session");
            closeCalls++;
            state = GameSessionState.CLOSED;
        }

        private static GameSessionFrame frame(double delta) {
            return new GameSessionFrame(new RenderFrameInput(List.of(), delta, 0));
        }
    }

    private static final class RecordingHost implements ProductLoop.FrameHost {
        private final List<String> events;
        private final ProductShellController shell;
        private final UiLayoutContext context = new UiLayoutContext(
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
        private boolean shouldClose;

        private RecordingHost(List<String> events, ProductShellController shell) {
            this.events = events;
            this.shell = shell;
        }

        @Override public boolean shouldClose() { return shouldClose; }
        @Override public void pollEvents() { events.add("poll"); }
        @Override public UiLayoutContext layoutContext() { return context; }
        @Override public void setCursorCaptured(boolean captured) {}
        @Override public void renderSession(GameSessionFrame frame) {
            events.add("render-session");
        }
        @Override public void renderProduct(ProductUiLayout layout) {
            events.add("render-product:" + shell.snapshot().screen());
        }
        @Override public void swapBuffers() { events.add("swap"); }
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static SettingsDraftSnapshot defaultSettings() {
        var defaults = SettingsDefaults.schemaV1();
        return new SettingsDraftSnapshot(
                defaults, defaults, false, Optional.empty());
    }
}
