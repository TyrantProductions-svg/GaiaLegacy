package com.gaia;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.audio.GaiaAudioSettingsAdapter;
import com.gaia.audio.GaiaMusicCatalog;
import com.gaia.audio.MusicManager;
import com.gaia.blocks.BlockRegistry;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.path.DefaultSaveRootProvider;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.store.AtomicSaveStore;
import com.gaia.save.store.FileSaveCatalog;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveRepository;
import com.gaia.save.streaming.Phase14MigrationResult;
import com.gaia.save.streaming.Phase14SaveMigrator;
import com.gaia.save.streaming.StreamedSessionSaveTarget;
import com.gaia.save.streaming.StreamedChunkCodec;
import com.gaia.save.streaming.StreamedChunkIndex;
import com.gaia.save.streaming.StreamedChunkIndexCodec;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedWorldItemPageBackend;
import com.gaia.save.format.SaveGameId;
import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFactory;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionLauncher;
import com.gaia.settings.DefaultSettingsPathProvider;
import com.gaia.settings.JsonSettingsStore;
import com.gaia.settings.ProductSettingsLifecycle;
import com.gaia.settings.SettingsApplier;
import com.gaia.settings.SettingsController;
import com.gaia.shell.ProductLoop;
import com.gaia.shell.ProductShellController;
import com.gaia.shell.ScreenRouter;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiCompositor;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.WorldSlotsController;
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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
            Path saveRoot = new DefaultSaveRootProvider().saveRoot();
            GameSessionFactory.StreamingBackendFactory streamingBackends =
                    composeStreamingBackends(saveRoot, catalog.blockRegistry());
            GameSessionFactory sessionFactory =
                    new GameSessionFactory(
                            engine,
                            inputManager,
                            mainThreadGuard,
                            catalog,
                             uiAssets,
                             Boolean.getBoolean(
                                     "gaia.inventory.debugShortcuts"),
                             streamingBackends);
            SettingsController settingsController = settingsLifecycle.controller();
            ProductShellController shell =
                    new ProductShellController(
                            ScreenRouter.mainMenu(), settingsController);
            SaveComposition saveComposition = composeSaveLoad(
                    saveRoot,
                    sessionFactory::create,
                    sessionFactory::restore,
                    settingsLifecycle::newSessionConfig,
                    Instant::now,
                    () -> SaveGameId.parse(UUID.randomUUID().toString()),
                    catalog.blockRegistry());
            ProductScreenPresenter productPresenter =
                    new ProductScreenPresenter(
                            saveComposition.catalog(),
                            new TextRenderer(
                                    uiAssets.renderAssets().glyphs()),
                            settingsController::snapshot,
                            saveComposition.newWorldDraft(),
                            saveComposition.worldSlots());
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
                            saveComposition.persistenceServices(),
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

    private static GameSessionFactory.StreamingBackendFactory
            composeStreamingBackends(Path saveRoot) {
        return composeStreamingBackends(saveRoot, null);
    }

    private static GameSessionFactory.StreamingBackendFactory
            composeStreamingBackends(Path saveRoot, BlockRegistry blockRegistry) {
        Path root = Objects.requireNonNull(saveRoot, "saveRoot");
        return saveGameId -> {
            SaveGameId id = Objects.requireNonNull(saveGameId, "saveGameId");
            StreamedChunkStore store = new StreamedChunkStore(
                    root,
                    id,
                    new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(),
                    new JdkSaveFileOperations());
            StreamedWorldItemPageBackend pages =
                    new StreamedWorldItemPageBackend(store);
            GameSessionFactory.StreamingBackends graph =
                    new GameSessionFactory.StreamingBackends(store, pages);
            return new GameSessionFactory.StreamingBackends(
                    store,
                    pages,
                    Optional.of(composeStreamedSaveTarget(
                            root, id, graph, blockRegistry)));
        };
    }

    static SaveComposition composeSaveLoad(
            Path saveRoot,
            GameSessionLauncher.NewSessionFactory newSessions,
            GameSessionLauncher.RestoreSessionFactory restoredSessions,
            Supplier<GameSessionConfig> sessionDefaults,
            Supplier<Instant> clock,
            Supplier<SaveGameId> saveGameIds) {
        return composeSaveLoad(
                saveRoot,
                newSessions,
                restoredSessions,
                sessionDefaults,
                clock,
                saveGameIds,
                null);
    }

    static SaveComposition composeSaveLoad(
            Path saveRoot,
            GameSessionLauncher.NewSessionFactory newSessions,
            GameSessionLauncher.RestoreSessionFactory restoredSessions,
            Supplier<GameSessionConfig> sessionDefaults,
            Supplier<Instant> clock,
            Supplier<SaveGameId> saveGameIds,
            BlockRegistry blockRegistry) {
        Path root = Objects.requireNonNull(saveRoot, "saveRoot");
        Objects.requireNonNull(newSessions, "newSessions");
        Objects.requireNonNull(restoredSessions, "restoredSessions");
        Objects.requireNonNull(sessionDefaults, "sessionDefaults");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(saveGameIds, "saveGameIds");

        SaveSnapshotCodec snapshotCodec = new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                new PlayerSectionCodec(),
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
        SaveArchiveReader archiveReader = new SaveArchiveReader(snapshotCodec);
        SaveArchiveWriter archiveWriter = new SaveArchiveWriter();
        JdkSaveFileOperations files = new JdkSaveFileOperations();
        SaveRepository repository = SaveRepository.open(root, archiveReader, files);
        SaveCatalog catalog = new FileSaveCatalog(repository);
        NewWorldDraftController newWorldDraft = new NewWorldDraftController(catalog);
        WorldSlotsController worldSlots = new WorldSlotsController(catalog, 4);
        SaveCoordinator coordinator = new SaveCoordinator(id -> {
            if (Phase14SaveMigrator.readPublished(
                    root, id, archiveReader, files).isPresent()) {
                return new StreamedSessionSaveTarget(
                        root, id, archiveReader, files, blockRegistry);
            }
            AtomicSaveStore store = new AtomicSaveStore(
                    root,
                    id,
                    snapshotCodec,
                    archiveWriter,
                    archiveReader,
                    files);
            return store::save;
        });
        GameSessionLauncher launcher = new GameSessionLauncher(
                newSessions,
                restoredSessions,
                repository::load,
                coordinator,
                request -> {
                    GameSessionConfig defaults = Objects.requireNonNull(
                            sessionDefaults.get(), "session defaults");
                    return new GameSessionConfig(
                            request.seed(),
                            defaults.chunkRadius(),
                            defaults.defaultGameMode(),
                            defaults.debugHudDefault());
                },
                clock);
        ProductLoop.WorldSlotOperations operations =
                new ProductLoop.WorldSlotOperations() {
                    @Override
                    public com.gaia.save.store.SaveDeleteResult delete(
                            SaveGameId saveGameId) {
                        return repository.delete(saveGameId);
                    }

                    @Override
                    public com.gaia.save.store.SaveRecoveryResult recover(
                            SaveGameId saveGameId) {
                        return repository.recoverBackup(saveGameId);
                    }
                };
        ProductLoop.PersistenceServices persistenceServices =
                new ProductLoop.PersistenceServices(
                        launcher,
                        newWorldDraft,
                        worldSlots,
                        saveGameIds,
                        operations);
        return new SaveComposition(
                catalog,
                newWorldDraft,
                worldSlots,
                persistenceServices);
    }

    private static SaveCoordinator.SaveTarget composeStreamedSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            GameSessionFactory.StreamingBackends backends) {
        return composeStreamedSaveTarget(
                saveRoot, saveGameId, backends, null);
    }

    private static SaveCoordinator.SaveTarget composeStreamedSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            GameSessionFactory.StreamingBackends backends,
            BlockRegistry blockRegistry) {
        SaveSnapshotCodec snapshotCodec = new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                new PlayerSectionCodec(),
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
        return composeStreamedSaveTarget(
                saveRoot,
                saveGameId,
                backends,
                snapshotCodec,
                new SaveArchiveReader(snapshotCodec),
                new JdkSaveFileOperations(),
                blockRegistry);
    }

    private static SaveCoordinator.SaveTarget composeStreamedSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            GameSessionFactory.StreamingBackends backends,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveReader archiveReader,
            JdkSaveFileOperations files) {
        return composeStreamedSaveTarget(
                saveRoot,
                saveGameId,
                backends,
                snapshotCodec,
                archiveReader,
                files,
                null);
    }

    private static SaveCoordinator.SaveTarget composeStreamedSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            GameSessionFactory.StreamingBackends backends,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveReader archiveReader,
            JdkSaveFileOperations files,
            BlockRegistry blockRegistry) {
        GameSessionFactory.StreamingBackends graph = Objects.requireNonNull(
                backends, "backends");
        return new StreamedSessionSaveTarget(
                saveRoot,
                saveGameId,
                archiveReader,
                files,
                graph.chunkStore(),
                graph.worldItems(),
                (snapshot, modifiedTime) -> bootstrapFreshStreamedAuthority(
                        saveRoot,
                        saveGameId,
                        graph.chunkStore(),
                        snapshotCodec,
                        archiveReader,
                        files,
                        snapshot,
                        modifiedTime),
                blockRegistry);
    }

    private static void bootstrapFreshStreamedAuthority(
            Path saveRoot,
            SaveGameId saveGameId,
            StreamedChunkStore store,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveReader archiveReader,
            JdkSaveFileOperations files,
            com.gaia.save.snapshot.SaveGameSnapshot snapshot,
            Instant modifiedTime) {
        Path current = saveRoot.resolve(saveGameId.value()).resolve("current.glsave");
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            requirePristineFreshStreamedStore(saveRoot, saveGameId, store);
            com.gaia.save.snapshot.SaveGameSnapshot floor =
                    requireFreshLegacyMigrationFloor(snapshot);
            var write = new AtomicSaveStore(
                            saveRoot,
                            saveGameId,
                            snapshotCodec,
                            new SaveArchiveWriter(),
                            archiveReader,
                            files)
                    .save(floor, modifiedTime);
            if (write.status()
                    != com.gaia.save.store.SaveWriteResult.Status.SUCCESS) {
                throw new IllegalStateException(
                        "Fresh streamed migration floor could not be written: "
                                + write.diagnostics());
            }
        }
        Phase14MigrationResult migration = new Phase14SaveMigrator(
                        saveRoot,
                        archiveReader,
                        new StreamedChunkCodec(),
                        new StreamedChunkIndexCodec(),
                        files)
                .migrate(saveGameId);
        if (migration.status() != Phase14MigrationResult.Status.MIGRATED
                && migration.status()
                        != Phase14MigrationResult.Status.NOT_REQUIRED) {
            throw new IllegalStateException(
                    "Fresh streamed authority could not be published: "
                            + migration.diagnostics().stream()
                                    .map(diagnostic -> diagnostic.code() + ": "
                                            + diagnostic.message() + " cause="
                                            + diagnostic.cause()
                                                    .map(Throwable::toString)
                                                    .orElse("none"))
                                    .toList());
        }
        StreamedChunkIndex migratedIndex = migration.validatedIndex().orElseThrow();
        store.acknowledgePublishedMigration(
                migratedIndex.migrationCompatibility().orElseThrow());
    }

    private static void requirePristineFreshStreamedStore(
            Path saveRoot, SaveGameId saveGameId, StreamedChunkStore store) {
        var authority = store.readCurrentAuthority(saveGameId);
        var index = authority.index().orElseThrow(() -> new IllegalStateException(
                "Fresh streamed store is not readable: " + authority.diagnostics()));
        if (!store.hasPristineUnpublishedAuthority()
                || !index.entries().isEmpty()
                || !index.globalExtensions().isEmpty()
                || index.migrationCompatibility().isPresent()
                || !authority.payloads().isEmpty()) {
            throw new IllegalStateException(
                    "Only a pristine empty streamed store may bootstrap a v1 floor");
        }
        Path world = saveRoot.resolve(saveGameId.value());
        for (String name : List.of(
                "streamed-migration.a.v2",
                "streamed-migration.b.v2",
                "streamed-migration.published.a.v2",
                "streamed-migration.published.b.v2")) {
            if (Files.exists(world.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "Fresh streamed store has migration side evidence");
            }
        }
    }

    private static com.gaia.save.snapshot.SaveGameSnapshot
            requireFreshLegacyMigrationFloor(
                    com.gaia.save.snapshot.SaveGameSnapshot snapshot) {
        var worldItems = snapshot.worldItems();
        if (snapshot.fixedTick() != 0L
                || worldItems.fixedTick() != 0L
                || !worldItems.entries().isEmpty()
                || worldItems.nextItemId() != 0L
                || worldItems.itemIdsExhausted()) {
            throw new IllegalStateException(
                    "Only an untouched initial world may create a v1 migration floor");
        }
        return new com.gaia.save.snapshot.SaveGameSnapshot(
                snapshot.metadata(),
                snapshot.fixedTick(),
                new com.overlord.voxel.ChunkRepositorySnapshot(
                        snapshot.chunks().worldHeight(),
                        snapshot.chunks().revisionHighWater(),
                        List.of()),
                snapshot.player(),
                snapshot.inventory(),
                new com.gaia.save.snapshot.WorldItemsSaveSnapshot(
                        worldItems.fixedTick(),
                        List.of(),
                        0L,
                        false,
                        com.overlord.worlditem.api.LogicalWorldItemSnapshot.Completeness
                                .LEGACY_COMPLETE));
    }

    record SaveComposition(
            SaveCatalog catalog,
            NewWorldDraftController newWorldDraft,
            WorldSlotsController worldSlots,
            ProductLoop.PersistenceServices persistenceServices) {
        SaveComposition {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(newWorldDraft, "newWorldDraft");
            Objects.requireNonNull(worldSlots, "worldSlots");
            Objects.requireNonNull(persistenceServices, "persistenceServices");
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
