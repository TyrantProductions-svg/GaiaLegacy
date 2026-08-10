package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionState;
import com.gaia.shell.ProductLoop;
import com.gaia.shell.ProductShellController;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenRouter;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.gaia.shell.ui.UiHitRegion;
import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioBackend;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.AudioDiagnostic;
import com.overlord.audio.MusicHandle;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductMusicLifecycleIntegrationTest {
    private static final double FRAME_DELTA_SECONDS = 0.25d;
    private static final double EPSILON = 1.0e-9d;
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");

    @Test
    void oneGaiaVoiceSurvivesRoutesFocusAndSessionsWithAudioBeforeSessionWork() {
        Fixture fixture = new Fixture();

        fixture.frame();
        fixture.assertMusic(MusicRoute.MAIN_MENU, 1.0d, true);

        fixture.shell().handle(new ScreenCommand.OpenSettings());
        fixture.frame();
        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.MAIN_MENU, 1.0d, true);
        fixture.shell().handle(new ScreenCommand.Back());
        fixture.frame();
        fixture.shell().handle(new ScreenCommand.OpenControls());
        fixture.frame();
        assertEquals(ScreenId.CONTROLS, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.MAIN_MENU, 1.0d, true);
        fixture.shell().handle(new ScreenCommand.Back());
        fixture.frame();

        fixture.session().completeLoadOnPoll(false);
        fixture.click(UiActionId.NEW_WORLD);
        assertEquals(ScreenId.LOADING, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.GAMEPLAY, 1.0d, true);
        assertEquals(0, fixture.session().fixedSteps());

        fixture.session().completeLoadOnPoll(true);
        fixture.frame();
        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.GAMEPLAY, 1.0d, true);
        assertEquals(0, fixture.session().fixedSteps());

        fixture.loseFocus();
        fixture.frame();
        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.PAUSED, 0.0d, false);
        fixture.restoreFocus();
        fixture.frame();
        fixture.assertMusic(MusicRoute.PAUSED, 0.70d, true);

        fixture.shell().handle(new ScreenCommand.OpenSettings());
        fixture.frame();
        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.SETTINGS_FROM_PAUSE, 0.70d, true);
        fixture.shell().handle(new ScreenCommand.Back());
        fixture.frame();

        fixture.shell().handle(new ScreenCommand.OpenControls());
        fixture.frame();
        assertEquals(ScreenId.CONTROLS, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.CONTROLS_FROM_PAUSE, 0.70d, true);
        fixture.shell().handle(new ScreenCommand.Back());
        fixture.frame();

        fixture.shell().handle(new ScreenCommand.Resume());
        fixture.frame();
        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        fixture.assertMusic(MusicRoute.GAMEPLAY, 1.0d, true);
        assertEquals(0, fixture.session().fixedSteps());

        fixture.shell().togglePlaying();
        fixture.frame();
        fixture.click(UiActionId.RETURN_TO_MAIN_MENU);
        fixture.click(UiActionId.CONFIRM);
        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.session().closed());
        fixture.assertMusic(MusicRoute.MAIN_MENU, 1.0d, true);

        fixture.click(UiActionId.QUIT);
        fixture.click(UiActionId.CONFIRM);

        assertEquals(1, fixture.backend().startCalls());
        assertEquals(List.of(GAIA), fixture.backend().startedTracks());
        assertEquals(0, fixture.backend().stopCalls());
        assertEquals(fixture.host().swapCount(), fixture.backend().updateCalls());
        fixture.assertEveryFrameUpdatesAudioBeforeSessionWork();

        fixture.loop().run();

        assertEquals(1, fixture.backend().stopCalls());
        assertEquals(
                List.of("music-stop", "audio-device-close", "engine-close"),
                fixture.events().subList(fixture.events().size() - 3, fixture.events().size()));
    }

    @Test
    void failedLoadThenSecondSessionKeepsTheOriginalGaiaHandle() {
        Fixture fixture = new Fixture();
        fixture.frame();
        MusicHandle original = fixture.backend().activeHandle();
        fixture.session().completeLoadOnPoll(false);
        fixture.click(UiActionId.NEW_WORLD);
        fixture.session().failOnNextLoadPoll();

        fixture.frame();

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.session().closed());
        assertEquals(1, fixture.backend().startCalls());
        assertSame(original, fixture.backend().activeHandle());

        fixture.click(UiActionId.DISMISS);
        RecordingSession second = fixture.addSession();
        second.completeLoadOnPoll(false);
        fixture.click(UiActionId.NEW_WORLD);
        second.completeLoadOnPoll(true);
        fixture.frame();

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(1, fixture.backend().startCalls());
        assertSame(original, fixture.backend().activeHandle());
        fixture.host().requestClose();
        fixture.loop().run();
    }

    @Test
    void cleanupContinuesAndSuppressesAfterMusicAndDeviceCloseFailures() {
        Fixture fixture = new Fixture();
        fixture.frame();
        AssertionError musicFailure = new AssertionError("music stop failed");
        RuntimeException deviceFailure = new RuntimeException("audio device close failed");
        fixture.backend().failStopWith(musicFailure);
        fixture.backend().failCloseWith(deviceFailure);
        fixture.host().requestClose();

        AssertionError thrown = assertThrows(AssertionError.class, fixture.loop()::run);

        assertSame(musicFailure, thrown);
        assertEquals(List.of(deviceFailure), List.of(thrown.getSuppressed()));
        assertEquals(
                List.of("music-stop", "audio-device-close", "engine-close"),
                fixture.events().subList(fixture.events().size() - 3, fixture.events().size()));
    }

    @Test
    void nativeInitializationFailureKeepsTheProductAliveThroughSilentAudio() {
        List<AudioDiagnostic> diagnostics = new ArrayList<>();
        AudioDevice device = AudioDevice.open(
                () -> {
                    throw new IllegalStateException("no native audio device");
                },
                MainThreadGuard.captureCurrentThread(),
                diagnostics::add);
        MusicManager manager = new MusicManager(device, new GaiaMusicCatalog(), diagnostics::add);
        RecordingHost host = new RecordingHost(new ArrayList<>());
        host.closeAfterPolls(2);
        ProductLoop loop = productLoop(
                new InputManager(),
                new ProductShellController(ScreenRouter.mainMenu()),
                new RecordingSession(new ArrayList<>()),
                host,
                manager,
                device::close);

        assertDoesNotThrow(loop::run);

        assertEquals(2, host.swapCount());
        assertEquals(1, diagnostics.size());
        assertEquals("AUDIO_BACKEND_INIT_FAILED", diagnostics.get(0).code());
    }

    private static ProductLoop productLoop(
            InputManager input,
            ProductShellController shell,
            RecordingSession session,
            RecordingHost host,
            MusicManager manager,
            Runnable closePolicy) {
        return new ProductLoop(
                input,
                shell,
                new ProductScreenInputController(),
                new ProductScreenPresenter(new EmptySaveCatalog(), textRenderer()),
                () -> session,
                () -> FRAME_DELTA_SECONDS,
                host,
                manager,
                closePolicy);
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final InputManager input = new InputManager();
        private final ProductShellController shell =
                new ProductShellController(ScreenRouter.mainMenu());
        private final List<RecordingSession> sessions = new ArrayList<>();
        private final RecordingHost host = new RecordingHost(events);
        private final RecordingBackend backend = new RecordingBackend(events);
        private final AudioDevice device = AudioDevice.open(
                () -> backend,
                MainThreadGuard.captureCurrentThread(),
                ignored -> {});
        private final MusicManager manager =
                new MusicManager(device, new GaiaMusicCatalog(), ignored -> {});
        private final ProductScreenPresenter presenter =
                new ProductScreenPresenter(new EmptySaveCatalog(), textRenderer());
        private final ProductLoop loop;
        private int launchedSessions;

        private Fixture() {
            sessions.add(new RecordingSession(events));
            ShutdownCoordinator shutdown = new ShutdownCoordinator();
            shutdown.register("engine", () -> events.add("engine-close"));
            shutdown.register("audio-device", device::close);
            loop = new ProductLoop(
                    input,
                    shell,
                    new ProductScreenInputController(),
                    presenter,
                    () -> sessions.get(launchedSessions++),
                    () -> FRAME_DELTA_SECONDS,
                    host,
                    manager,
                    shutdown::close);
        }

        private ProductLoop loop() {
            return loop;
        }

        private ProductShellController shell() {
            return shell;
        }

        private RecordingSession session() {
            return sessions.get(Math.max(0, launchedSessions - 1));
        }

        private RecordingSession addSession() {
            RecordingSession next = new RecordingSession(events);
            sessions.add(next);
            return next;
        }

        private RecordingHost host() {
            return host;
        }

        private RecordingBackend backend() {
            return backend;
        }

        private List<String> events() {
            return events;
        }

        private void frame() {
            loop.runFrame(FRAME_DELTA_SECONDS);
        }

        private void click(UiActionId action) {
            ProductUiLayout layout = presenter.present(shell.snapshot(), host.layoutContext());
            UiHitRegion region = layout.region(action);
            InputManagerTestDriver.cursor(input, region.centerX(), region.centerY());
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
            frame();
            InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        }

        private void loseFocus() {
            InputManagerTestDriver.windowFocus(input, false);
        }

        private void restoreFocus() {
            InputManagerTestDriver.windowFocus(input, true);
        }

        private void assertMusic(MusicRoute route, double target, boolean focused) {
            MusicManagerSnapshot snapshot = manager.snapshot();
            assertEquals(route, snapshot.route());
            assertEquals(target, snapshot.targetEnvelope(), EPSILON);
            assertEquals(focused, snapshot.focused());
        }

        private void assertEveryFrameUpdatesAudioBeforeSessionWork() {
            int frameStart = -1;
            for (int index = 0; index < events.size(); index++) {
                String event = events.get(index);
                if (event.equals("frame-start")) {
                    frameStart = index;
                } else if (event.equals("frame-end")) {
                    List<String> frameEvents = events.subList(frameStart, index + 1);
                    assertEquals(
                            1,
                            frameEvents.stream().filter("audio-update"::equals).count(),
                            frameEvents::toString);
                    int audio = frameEvents.indexOf("audio-update");
                    for (String sessionWork : List.of("advance-playing", "capture-paused")) {
                        int work = frameEvents.indexOf(sessionWork);
                        if (work >= 0) {
                            assertTrue(audio < work, frameEvents::toString);
                        }
                    }
                }
            }
        }
    }

    private static final class RecordingSession implements GameSession {
        private final List<String> events;
        private GameSessionState state = GameSessionState.LOADING;
        private boolean completeLoadOnPoll = true;
        private boolean failOnNextLoadPoll;
        private boolean closed;
        private int fixedSteps;
        private GameSessionFrame frame = frame(0.0d);

        private RecordingSession(List<String> events) {
            this.events = events;
        }

        @Override
        public GameSessionState state() {
            return state;
        }

        @Override
        public void pollLoad() {
            events.add("poll-load");
            if (failOnNextLoadPoll) {
                failOnNextLoadPoll = false;
                state = GameSessionState.FAILED;
                return;
            }
            if (completeLoadOnPoll) {
                state = GameSessionState.READY;
            }
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds, MouseDelta look, boolean focused) {
            events.add("advance-playing");
            if (frameDeltaSeconds > 0.0d) {
                fixedSteps++;
            }
            frame = frame(frameDeltaSeconds);
            return frame;
        }

        @Override
        public GameSessionFrame capturePaused() {
            events.add("capture-paused");
            return frame.copy();
        }

        @Override
        public void discardFixedTime() {
            events.add("discard-fixed-time");
        }

        @Override
        public void close() {
            events.add("session-close");
            closed = true;
            state = GameSessionState.CLOSED;
        }

        private void completeLoadOnPoll(boolean complete) {
            completeLoadOnPoll = complete;
        }

        private void failOnNextLoadPoll() {
            failOnNextLoadPoll = true;
        }

        private int fixedSteps() {
            return fixedSteps;
        }

        private boolean closed() {
            return closed;
        }

        private static GameSessionFrame frame(double deltaSeconds) {
            return new GameSessionFrame(new RenderFrameInput(List.of(), deltaSeconds, 0));
        }
    }

    private static final class RecordingHost implements ProductLoop.FrameHost {
        private final List<String> events;
        private final UiLayoutContext layout = new UiLayoutContext(
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
        private boolean closeRequested;
        private int closeAfterPolls;
        private int pollCount;
        private int swapCount;

        private RecordingHost(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean shouldClose() {
            return closeRequested || closeAfterPolls > 0 && pollCount >= closeAfterPolls;
        }

        @Override
        public void pollEvents() {
            events.add("frame-start");
            pollCount++;
        }

        @Override
        public UiLayoutContext layoutContext() {
            return layout;
        }

        @Override
        public void setCursorCaptured(boolean captured) {}

        @Override
        public void renderSession(GameSessionFrame frame) {}

        @Override
        public void renderProduct(ProductUiLayout layout) {}

        @Override
        public void swapBuffers() {
            events.add("frame-end");
            swapCount++;
        }

        private void requestClose() {
            closeRequested = true;
        }

        private void closeAfterPolls(int count) {
            closeAfterPolls = count;
        }

        private int swapCount() {
            return swapCount;
        }
    }

    private static final class RecordingBackend implements AudioBackend {
        private final List<String> events;
        private final MusicHandle.Domain handles = MusicHandle.newDomain();
        private final List<ResourceLocation> startedTracks = new ArrayList<>();
        private MusicHandle active;
        private int updateCalls;
        private int stopCalls;
        private Error stopFailure;
        private RuntimeException closeFailure;

        private RecordingBackend(List<String> events) {
            this.events = events;
        }

        @Override
        public MusicHandle startMusic(ResourceLocation track, boolean loop) {
            startedTracks.add(track);
            active = handles.issue(startedTracks.size());
            events.add("music-start");
            return active;
        }

        @Override
        public void setMusicGain(MusicHandle handle, float gain) {
            assertSame(active, handles.requireOwned(handle));
        }

        @Override
        public boolean isMusicPlaying(MusicHandle handle) {
            return active == handles.requireOwned(handle);
        }

        @Override
        public void stopMusic(MusicHandle handle) {
            assertSame(active, handles.requireOwned(handle));
            events.add("music-stop");
            stopCalls++;
            active = null;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        @Override
        public void update() {
            events.add("audio-update");
            updateCalls++;
        }

        @Override
        public void close() {
            events.add("audio-device-close");
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private int startCalls() {
            return startedTracks.size();
        }

        private List<ResourceLocation> startedTracks() {
            return List.copyOf(startedTracks);
        }

        private int updateCalls() {
            return updateCalls;
        }

        private int stopCalls() {
            return stopCalls;
        }

        private MusicHandle activeHandle() {
            return active;
        }

        private void failStopWith(Error failure) {
            stopFailure = failure;
        }

        private void failCloseWith(RuntimeException failure) {
            closeFailure = failure;
        }
    }
}
