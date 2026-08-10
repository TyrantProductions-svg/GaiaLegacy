package com.gaia.settings;

import com.gaia.session.GameSessionConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Owns startup settings composition and the final applied-snapshot rewrite. */
public final class ProductSettingsLifecycle implements AutoCloseable {
    private static final int MAX_STARTUP_DIAGNOSTICS = 16;

    private final Path settingsFile;
    private final SettingsStore store;
    private final SettingsController controller;
    private final List<SettingsDiagnostic> diagnostics;
    private boolean closed;

    private ProductSettingsLifecycle(
            Path settingsFile,
            SettingsStore store,
            SettingsController controller,
            List<SettingsDiagnostic> diagnostics) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
        this.store = Objects.requireNonNull(store, "store");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static ProductSettingsLifecycle open(
            SettingsPathProvider pathProvider,
            Function<Path, ? extends SettingsStore> storeFactory,
            Function<Boolean, SettingsApplier> runtimeFactory) {
        Path settingsFile = Objects.requireNonNull(
                Objects.requireNonNull(pathProvider, "pathProvider").settingsFile(),
                "settingsFile");
        SettingsStore store = Objects.requireNonNull(
                Objects.requireNonNull(storeFactory, "storeFactory").apply(settingsFile),
                "settingsStore");
        SettingsLoadResult loaded = Objects.requireNonNull(store.load(), "settingsLoadResult");
        SettingsLoadResult validation = validateLoadedSnapshot(loaded.snapshot());
        SettingsSnapshot applied = validation.snapshot();
        List<SettingsDiagnostic> diagnostics = boundedDiagnostics(
                loaded.diagnostics(), validation.diagnostics());

        SettingsApplier applier = Objects.requireNonNull(
                Objects.requireNonNull(runtimeFactory, "runtimeFactory")
                        .apply(applied.vsync()),
                "settingsApplier");
        applier.apply(startupBaseline(applied.vsync()), applied);

        return new ProductSettingsLifecycle(
                settingsFile,
                store,
                new SettingsController(applied, store, applier),
                diagnostics);
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public List<SettingsDiagnostic> diagnostics() {
        return diagnostics;
    }

    public SettingsController controller() {
        return controller;
    }

    public GameSessionConfig newSessionConfig() {
        return GameSessionConfig.from(controller.applied());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        store.save(controller.applied());
    }

    private static SettingsSnapshot startupBaseline(boolean loadedVsync) {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        return new SettingsSnapshot(
                defaults.schemaVersion(),
                loadedVsync,
                defaults.fovDegrees(),
                defaults.mouseSensitivity(),
                defaults.invertY(),
                defaults.chunkRadius(),
                1.0,
                1.0,
                1.0,
                defaults.muteWhenUnfocused(),
                defaults.defaultGameMode(),
                defaults.debugHudDefault());
    }

    private static SettingsLoadResult validateLoadedSnapshot(SettingsSnapshot snapshot) {
        SettingsSnapshot candidate = Objects.requireNonNull(snapshot, "loadedSettings");
        return SettingsValidator.validate(new SettingsDocument(
                candidate.schemaVersion(),
                candidate.vsync(),
                candidate.fovDegrees(),
                candidate.mouseSensitivity(),
                candidate.invertY(),
                candidate.chunkRadius(),
                candidate.masterVolume(),
                candidate.musicVolume(),
                candidate.sfxVolume(),
                candidate.muteWhenUnfocused(),
                Objects.requireNonNull(candidate.defaultGameMode(), "defaultGameMode").name(),
                candidate.debugHudDefault()));
    }

    private static List<SettingsDiagnostic> boundedDiagnostics(
            List<SettingsDiagnostic> loadDiagnostics,
            List<SettingsDiagnostic> validationDiagnostics) {
        List<SettingsDiagnostic> combined = new ArrayList<>(MAX_STARTUP_DIAGNOSTICS);
        appendBounded(combined, loadDiagnostics);
        appendBounded(combined, validationDiagnostics);
        return List.copyOf(combined);
    }

    private static void appendBounded(
            List<SettingsDiagnostic> target, List<SettingsDiagnostic> source) {
        for (SettingsDiagnostic diagnostic : Objects.requireNonNull(source, "diagnostics")) {
            if (target.size() == MAX_STARTUP_DIAGNOSTICS) {
                return;
            }
            target.add(Objects.requireNonNull(diagnostic, "diagnostic"));
        }
    }
}
