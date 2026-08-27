package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import com.gaia.interaction.GameMode;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionLauncher;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionState;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiControlId;
import com.gaia.shell.ui.UiActionId;
import com.gaia.shell.ui.UiHitRegion;
import com.gaia.shell.ui.WorldSlotControlId;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductLoopResponsiveLoadTest {
    @Test
    void blockedArchiveReadKeepsFramesResponsiveAndPublishesOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.beginLoad();
        assertTrue(fixture.readEntered.await(2, TimeUnit.SECONDS));
        fixture.events.clear();

        InputManagerTestDriver.windowFocus(fixture.input, false);
        fixture.host.resize(900, 600);
        fixture.frame();
        fixture.frame();
        fixture.frame();

        assertEquals(ScreenId.LOADING, fixture.shell.snapshot().screen());
        assertEquals(OperationProgressSnapshot.Kind.LOAD_WORLD,
                fixture.shell.snapshot().operationProgress().orElseThrow().kind());
        assertEquals(0, fixture.restores.get());
        assertEquals(3, fixture.events.stream().filter("poll"::equals).count());
        assertEquals(3, fixture.events.stream().filter("swap"::equals).count());
        InputManagerTestDriver.windowFocus(fixture.input, true);
        fixture.releaseRead.countDown();
        fixture.awaitScreen(ScreenId.PLAYING);
        assertEquals(1, fixture.restores.get());
    }

    @Test
    void successfulLoadCompletingInsideOwnerPollReleasesBeforeNextPlayingFrame()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.beginLoad();
        assertTrue(fixture.readEntered.await(2, TimeUnit.SECONDS));
        fixture.releaseRead.countDown();
        fixture.awaitOperationCompletion();

        assertDoesNotThrow(fixture::frame,
                "the frame sampling RUNNING progress may also publish READY");
        assertEquals(ScreenId.PLAYING, fixture.shell.snapshot().screen());
        assertTrue(fixture.shell.snapshot().operationProgress().isEmpty());
        assertDoesNotThrow(fixture::frame,
                "a retained load snapshot must not cross into PLAYING");
        assertEquals(0, fixture.acceptedOperationCount(),
                "successful load must release its token exactly once");
        assertFalse(fixture.operationProgress().isPresent());
    }

    @Test
    void canceledArchiveReadCannotPublishItsLateResult() throws Exception {
        Fixture fixture = new Fixture();
        fixture.beginLoad();
        assertTrue(fixture.readEntered.await(2, TimeUnit.SECONDS));

        fixture.click(UiActionId.DISMISS);
        assertEquals(ScreenId.MAIN_MENU, fixture.shell.snapshot().screen());
        fixture.releaseRead.countDown();
        for (int index = 0; index < 20; index++) {
            fixture.frame();
            Thread.sleep(1L);
        }

        assertEquals(0, fixture.restores.get());
        assertEquals(ScreenId.MAIN_MENU, fixture.shell.snapshot().screen());
        assertEquals(0, fixture.acceptedOperationCount());
        assertTrue(fixture.operationProgress().isEmpty());
    }

    @Test
    void ownerRestoreFailureReleasesProgressGeneration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loaded.failOnPoll = true;
        fixture.beginLoad();
        assertTrue(fixture.readEntered.await(2, TimeUnit.SECONDS));
        fixture.releaseRead.countDown();

        for (int index = 0; index < 500
                && fixture.shell.snapshot().modal().isEmpty(); index++) {
            fixture.frame();
            Thread.sleep(1L);
        }

        assertEquals(ScreenId.MAIN_MENU, fixture.shell.snapshot().screen());
        assertTrue(fixture.shell.snapshot().modal().isPresent());
        assertEquals(0, fixture.acceptedOperationCount(),
                "failed owner restore must release the completed-undrained token");
        assertDoesNotThrow(fixture::frame,
                "failed progress must not republish into the main-menu route");
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final AtomicInteger restores = new AtomicInteger();
        private final LoadedSession loaded = new LoadedSession();
        private final InputManager input = new InputManager();
        private final ScreenRouter router = ScreenRouter.mainMenu();
        private final ProductShellController shell = new ProductShellController(router);
        private final SaveCatalog catalog = () -> List.of(new SaveSummary(
                GameSessionSaveLifecycleTest.ID,
                "Loaded World",
                Optional.of(Instant.parse("2026-08-12T00:00:00Z")),
                Instant.parse("2026-08-12T01:00:00Z"),
                Optional.of(12345L),
                Optional.of(SaveFormatVersion.STREAMED_CHUNKS),
                SaveSummary.Health.VALID,
                List.of()));
        private final NewWorldDraftController draft = new NewWorldDraftController(catalog);
        private final WorldSlotsController slots = new WorldSlotsController(catalog, 4);
        private final ProductScreenPresenter presenter = new ProductScreenPresenter(
                catalog, textRenderer(), ProductLoopResponsiveLoadTest::settings, draft, slots);
        private final Host host = new Host(events);
        private final ProductLoop loop;

        private Fixture() {
            GameSessionLauncher launcher = new GameSessionLauncher(
                    (request, config) -> { throw new AssertionError("new not expected"); },
                    snapshot -> {
                        restores.incrementAndGet();
                        return loaded;
                    },
                    id -> {
                        readEntered.countDown();
                        try {
                            if (!releaseRead.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timed out awaiting read release");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return SaveArchiveReadResult.valid(
                                GameSessionSaveLifecycleTest.snapshot(), List.of());
                    },
                    new SaveCoordinator(id -> (snapshot, modified) -> {
                        throw new AssertionError("save not expected");
                    }),
                    request -> new GameSessionConfig(
                            request.seed(), 2, GameMode.SURVIVAL, false),
                    () -> Instant.parse("2026-08-12T01:00:00Z"));
            loop = new ProductLoop(
                    input,
                    shell,
                    new ProductScreenInputController(),
                    presenter,
                    new ProductLoop.PersistenceServices(
                            launcher,
                            draft,
                            slots,
                            () -> GameSessionSaveLifecycleTest.ID,
                            new ProductLoop.WorldSlotOperations() {
                                @Override public com.gaia.save.store.SaveDeleteResult delete(
                                        com.gaia.save.format.SaveGameId id) {
                                    throw new AssertionError();
                                }
                                @Override public com.gaia.save.store.SaveRecoveryResult recover(
                                        com.gaia.save.format.SaveGameId id) {
                                    throw new AssertionError();
                                }
                            }),
                    () -> 1.0d / 60.0d,
                    host);
        }

        private void beginLoad() {
            click(UiActionId.LOAD_WORLD);
            click(new WorldSlotControlId(
                    GameSessionSaveLifecycleTest.ID,
                    WorldSlotControlId.WorldSlotAction.LOAD));
            frame();
        }

        private void click(UiControlId action) {
            ProductUiLayout layout = presenter.present(shell.snapshot(), host.layoutContext());
            UiHitRegion region = layout.region(action);
            InputManagerTestDriver.cursor(input, region.centerX(), region.centerY());
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            frame();
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
            frame();
        }

        private void frame() {
            loop.runFrame(1.0d / 60.0d);
        }

        private void awaitScreen(ScreenId expected) throws Exception {
            for (int index = 0; index < 500; index++) {
                frame();
                if (shell.snapshot().screen() == expected) {
                    return;
                }
                Thread.sleep(1L);
            }
            throw new AssertionError("screen did not become " + expected);
        }


        private int acceptedOperationCount() {
            return operationRunner().acceptedCount();
        }

        private Optional<OperationProgressSnapshot> operationProgress() {
            return operationRunner().progress();
        }

        private void awaitOperationCompletion() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (operationRunner().peekCompletion().isPresent()) {
                    return;
                }
                Thread.sleep(1L);
            }
            throw new AssertionError("operation did not complete");
        }

        private ProductOperationRunner operationRunner() {
            try {
                var field = ProductLoop.class.getDeclaredField("operations");
                field.setAccessible(true);
                return (ProductOperationRunner) field.get(loop);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class LoadedSession implements GameSession {
        private GameSessionState state = GameSessionState.LOADING;
        private boolean failOnPoll;
        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() {
            state = failOnPoll ? GameSessionState.FAILED : GameSessionState.READY;
        }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            return new GameSessionFrame(new RenderFrameInput(List.of(), delta, 0));
        }
        @Override public GameSessionFrame capturePaused() {
            return new GameSessionFrame(new RenderFrameInput(List.of(), 0.0d, 0));
        }
        @Override public SessionSaveCaptureResult captureSave() {
            return GameSessionPersistenceTestFixture.runtimeCaptured(
                    GameSessionSaveLifecycleTest.snapshot(), 1L);
        }
        @Override public void markSaved(SessionPersistenceRevision revision) {}
        @Override public boolean hasUnsavedChanges() { return false; }
        @Override public void discardFixedTime() {}
        @Override public void close() { state = GameSessionState.CLOSED; }
    }

    private static final class Host implements ProductLoop.FrameHost {
        private final List<String> events;
        private UiLayoutContext context = new UiLayoutContext(
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
        private Host(List<String> events) { this.events = events; }
        @Override public boolean shouldClose() { return false; }
        @Override public void pollEvents() { events.add("poll"); }
        @Override public UiLayoutContext layoutContext() { return context; }
        @Override public void setCursorCaptured(boolean captured) {}
        @Override public void renderSession(GameSessionFrame frame) {}
        @Override public void renderProduct(ProductUiLayout layout) { events.add("render"); }
        @Override public void swapBuffers() { events.add("swap"); }
        private void resize(int width, int height) {
            context = new UiLayoutContext(new RenderSurfaceMetrics(
                    width, height, width, height, 1.0f, 1.0f));
        }
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        return new TextRenderer(new BitmapFont(
                8, 8, Map.of(), new BitmapGlyph(0xfffd, uv, 8, 0, 8)));
    }

    private static SettingsDraftSnapshot settings() {
        var defaults = SettingsDefaults.schemaV1();
        return new SettingsDraftSnapshot(
                defaults, defaults, false, Optional.empty());
    }
}
