package com.gaia;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.audio.GaiaAudioSettingsAdapter;
import com.gaia.audio.GaiaMusicCatalog;
import com.gaia.audio.MusicManager;
import com.gaia.session.GameSessionFactory;
import com.gaia.session.GameSessionFrame;
import com.gaia.settings.DefaultSettingsPathProvider;
import com.gaia.settings.JsonSettingsStore;
import com.gaia.settings.ProductSettingsLifecycle;
import com.gaia.settings.SettingsApplier;
import com.gaia.settings.SettingsController;
import com.gaia.shell.ProductLoop;
import com.gaia.shell.ProductShellController;
import com.gaia.shell.ScreenRouter;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiCompositor;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.ui.GaiaUiAssetLoader;
import com.gaia.ui.GaiaUiAssets;
import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetManager;
import com.overlord.audio.AudioAssetSource;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.AudioDiagnostic;
import com.overlord.audio.openal.LwjglOpenAlApi;
import com.overlord.audio.openal.OpenAlAudioBackend;
import com.overlord.audio.vorbis.StbVorbisDecoder;
import com.overlord.core.Engine;
import com.overlord.core.ModuleManager;
import com.overlord.core.Window;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FrameClock;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.Renderer;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class GameBootstrap {
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    public void run() {
        MainThreadGuard mainThreadGuard =
                MainThreadGuard.captureCurrentThread();
        ShutdownCoordinator shutdownCoordinator =
                new ShutdownCoordinator();
        Throwable primaryFailure = null;
        try {
            AssetManager assetManager =
                    new AssetManager(
                            GameBootstrap.class.getClassLoader());
            GaiaAssetCatalog catalog =
                    new GaiaResourceLoader(assetManager).load();
            GaiaUiAssets uiAssets =
                    new GaiaUiAssetLoader(assetManager).load();
            logAssetReport(catalog.report());

            AtomicReference<Engine> engineReference = new AtomicReference<>();
            AtomicReference<AudioDevice> audioDeviceReference = new AtomicReference<>();
            AtomicReference<MusicManager> musicManagerReference = new AtomicReference<>();
            ProductSettingsLifecycle settingsLifecycle =
                    ProductSettingsLifecycle.open(
                            new DefaultSettingsPathProvider(),
                            JsonSettingsStore::new,
                            initialVsync -> {
                                Engine initializedEngine =
                                        new Engine(
                                                mainThreadGuard,
                                                catalog.renderAssets(),
                                                assetManager,
                                                RenderVisualSettings.milestoneOneDefaults(),
                                                initialVsync);
                                initializedEngine.init();
                                shutdownCoordinator.register(
                                        "engine", initializedEngine::shutdown);
                                initializedEngine
                                        .getRenderer()
                                        .installUiAssets(uiAssets.renderAssets());
                                AudioDevice audioDevice =
                                        AudioDevice.open(
                                                () ->
                                                        new OpenAlAudioBackend(
                                                                new LwjglOpenAlApi(),
                                                                mainThreadGuard,
                                                                createAudioAssetSource(assetManager),
                                                                StbVorbisDecoder::open),
                                                mainThreadGuard,
                                                GameBootstrap::logAudioDiagnostic);
                                shutdownCoordinator.register(
                                        "audio-device", audioDevice::close);
                                MusicManager musicManager =
                                        new MusicManager(
                                                audioDevice,
                                                new GaiaMusicCatalog(),
                                                GameBootstrap::logAudioDiagnostic);
                                shutdownCoordinator.register(
                                        "music-manager", musicManager::close);
                                engineReference.set(initializedEngine);
                                audioDeviceReference.set(audioDevice);
                                musicManagerReference.set(musicManager);
                                return new SettingsApplier(
                                        initializedEngine.getWindow(),
                                        initializedEngine.getRenderer(),
                                        initializedEngine.getCamera(),
                                        new GaiaAudioSettingsAdapter(
                                                audioDevice, musicManager));
                            });
            shutdownCoordinator.register("settings", settingsLifecycle::close);
            logSettingsStartup(settingsLifecycle);
            Engine engine = Objects.requireNonNull(
                    engineReference.get(), "settings runtime engine");
            Objects.requireNonNull(
                    audioDeviceReference.get(), "settings runtime audio device");
            MusicManager musicManager = Objects.requireNonNull(
                    musicManagerReference.get(), "settings runtime music manager");

            InputManager inputManager =
                    new InputManager(mainThreadGuard);
            inputManager.install(engine.getWindow().getWindow());
            ModuleManager.getInstance().initAll();

            FrameClock frameClock =
                    new FrameClock(
                            System::nanoTime,
                            MAX_FRAME_DELTA_SECONDS);
            GameSessionFactory sessionFactory =
                    new GameSessionFactory(
                            engine,
                            inputManager,
                            mainThreadGuard,
                            catalog,
                            uiAssets,
                            Boolean.getBoolean(
                                    "gaia.inventory.debugShortcuts"));
            SettingsController settingsController = settingsLifecycle.controller();
            ProductShellController shell =
                    new ProductShellController(
                            ScreenRouter.mainMenu(), settingsController);
            ProductScreenPresenter productPresenter =
                    new ProductScreenPresenter(
                            new EmptySaveCatalog(),
                            new TextRenderer(
                                    uiAssets.renderAssets().glyphs()),
                            settingsController::snapshot);
            ProductFrameHost frameHost =
                    new ProductFrameHost(
                            engine,
                            new RenderMetricsConsoleReporter(
                                    Boolean.getBoolean(
                                            "gaia.renderMetrics"),
                                    System::nanoTime,
                                    System.out));
            new ProductLoop(
                            inputManager,
                            shell,
                            new ProductScreenInputController(),
                            productPresenter,
                            () ->
                                    sessionFactory.create(
                                            settingsLifecycle.newSessionConfig()),
                            frameClock::tick,
                            frameHost,
                            musicManager,
                            settingsLifecycle::close)
                    .run();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            closeAfterRun(shutdownCoordinator, primaryFailure);
        }
    }

    static void logAssetReport(AssetLoadReport report) {
        for (AssetDiagnostic diagnostic : report.diagnostics()) {
            StringBuilder line =
                    new StringBuilder()
                            .append(diagnostic.severity())
                            .append(' ')
                            .append(diagnostic.code())
                            .append(" source=")
                            .append(diagnostic.source());
            if (diagnostic.resource() != null) {
                line.append(" resource=")
                        .append(diagnostic.resource());
            }
            if (diagnostic.field() != null) {
                line.append(" field=")
                        .append(diagnostic.field());
            }
            line.append(" message=")
                    .append(diagnostic.message());
            if (diagnostic.fallback() != null) {
                line.append(" fallback=")
                        .append(diagnostic.fallback());
            }
            System.out.println(line);
        }
    }

    static AudioAssetSource createAudioAssetSource(AssetManager assetManager) {
        return AudioAssetSource.fromAssetManager(
                Objects.requireNonNull(assetManager, "assetManager"));
    }

    private static void logAudioDiagnostic(AudioDiagnostic diagnostic) {
        AudioDiagnostic observed = Objects.requireNonNull(diagnostic, "diagnostic");
        System.out.println(
                "[Audio] " + observed.code() + " message=" + observed.message());
    }

    static void closeAfterRun(
            ShutdownCoordinator shutdownCoordinator,
            Throwable primaryFailure) {
        try {
            shutdownCoordinator.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void logSettingsStartup(ProductSettingsLifecycle lifecycle) {
        ProductSettingsLifecycle settings =
                Objects.requireNonNull(lifecycle, "lifecycle");
        System.out.println(
                "[Settings] file="
                        + settings.settingsFile().toAbsolutePath().normalize());
        for (var diagnostic : settings.diagnostics()) {
            System.out.println(
                    "[Settings] "
                            + diagnostic.code()
                            + " field="
                            + diagnostic.field());
        }
    }

    /** Main-thread bridge that composes session HUD then product UI in one render pass. */
    private static final class ProductFrameHost
            implements ProductLoop.FrameHost {
        private final Engine engine;
        private final Window window;
        private final Renderer renderer;
        private final RenderMetricsConsoleReporter metricsReporter;
        private GameSessionFrame sessionFrame;
        private double frameDeltaSeconds;

        private ProductFrameHost(
                Engine engine,
                RenderMetricsConsoleReporter metricsReporter) {
            this.engine = Objects.requireNonNull(engine, "engine");
            window = engine.getWindow();
            renderer = engine.getRenderer();
            this.metricsReporter =
                    Objects.requireNonNull(
                            metricsReporter, "metricsReporter");
        }

        @Override
        public boolean shouldClose() {
            return !engine.isRunning() || window.shouldClose();
        }

        @Override
        public void pollEvents() {
            sessionFrame = null;
            window.pollEvents();
            window.consumeSurfaceUpdate()
                    .ifPresent(renderer::updateSurface);
        }

        @Override
        public void beginFrame(double frameDeltaSeconds) {
            this.frameDeltaSeconds = frameDeltaSeconds;
        }

        @Override
        public UiLayoutContext layoutContext() {
            return new UiLayoutContext(
                    window.currentSurfaceMetrics());
        }

        @Override
        public void setCursorCaptured(boolean captured) {
            window.setCursorCaptured(captured);
        }

        @Override
        public void renderSession(GameSessionFrame frame) {
            sessionFrame = Objects.requireNonNull(frame, "frame");
        }

        @Override
        public void renderProduct(ProductUiLayout layout) {
            Objects.requireNonNull(layout, "layout");
            RenderFrameInput sessionInput = sessionFrame == null
                    ? new RenderFrameInput(
                            List.of(), frameDeltaSeconds, 0)
                    : sessionFrame.renderInput();
            var combinedUi = ProductUiCompositor.combine(
                    sessionInput.uiFrame(), layout.frame());
            renderer.renderFrame(new RenderFrameInput(
                    sessionInput.chunks(),
                    sessionInput.frameDeltaSeconds(),
                    sessionInput.meshQueueDepth(),
                    sessionInput.feedback(),
                    combinedUi));
            metricsReporter.report(
                    renderer.metrics().snapshot());
        }

        @Override
        public void swapBuffers() {
            window.swapBuffers();
        }

    }
}
