package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.session.GameSessionConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductSettingsLifecycleTest {
    @Test
    void schemaDefaultsApplyConfiguredMusicBusAgainstFullVolumeRuntimeBaseline() {
        RecordingStore store = new RecordingStore(
                new SettingsLoadResult(SettingsDefaults.schemaV1(), List.of()));
        RecordingAudioSettingsPort audio = new RecordingAudioSettingsPort();

        ProductSettingsLifecycle lifecycle = ProductSettingsLifecycle.open(
                () -> Path.of("safe-test-root", "GaiaLegacy", "settings.json"),
                ignored -> store,
                ignored ->
                        new SettingsApplier(
                                ignoredVsync -> {},
                                ignoredFov -> {},
                                (ignoredSensitivity, ignoredInvertY) -> {},
                                audio));

        assertEquals(
                List.of(new AudioApply(1.0, 0.65, 1.0, true)),
                audio.applies(),
                "startup must apply schema defaults over the runtime 1/1/1 bus baseline");
        assertEquals(SettingsDefaults.schemaV1(), lifecycle.controller().applied());
    }

    @Test
    void injectedLoadPrecedesInitialVsyncConstructionAndPostConstructionHotApply() {
        Path settingsFile = Path.of("diagnostics", "settings.json");
        SettingsSnapshot loaded = changedSettings();
        RecordingStore store = new RecordingStore(
                new SettingsLoadResult(loaded, List.of()));
        List<String> trace = new ArrayList<>();
        store.traceInto(trace);

        ProductSettingsLifecycle lifecycle = ProductSettingsLifecycle.open(
                () -> {
                    trace.add("resolve-path");
                    return settingsFile;
                },
                path -> {
                    trace.add("create-store:" + path);
                    return store;
                },
                initialVsync -> {
                    trace.add("construct-runtime-vsync:" + initialVsync);
                    return recordingApplier(trace);
                });

        assertEquals(
                List.of(
                        "resolve-path",
                        "create-store:" + settingsFile,
                        "load",
                        "construct-runtime-vsync:false",
                        "hot-fov:90.0",
                        "hot-look:0.25:true",
                        "hot-audio:0.8:0.4:0.6:false"),
                trace);
        assertEquals(loaded, lifecycle.controller().applied());
        assertEquals(settingsFile, lifecycle.settingsFile());
        assertTrue(lifecycle.diagnostics().isEmpty());
        assertFalse(
                trace.stream().anyMatch(entry -> entry.startsWith("hot-vsync:")),
                "loaded VSync must participate in context-current construction, not be replayed later");
    }

    @Test
    void validatesAnInjectedLoadedSnapshotBeforeRuntimeConstruction() {
        SettingsSnapshot unvalidated = new SettingsSnapshot(
                1,
                false,
                140.0,
                Double.NaN,
                true,
                99,
                -1.0,
                2.0,
                Double.POSITIVE_INFINITY,
                false,
                GameMode.CREATIVE,
                true);
        RecordingStore store = new RecordingStore(
                new SettingsLoadResult(unvalidated, List.of()));
        List<String> trace = new ArrayList<>();

        ProductSettingsLifecycle lifecycle = ProductSettingsLifecycle.open(
                () -> Path.of("safe-test-root", "settings.json"),
                ignored -> store,
                initialVsync -> {
                    trace.add("construct-runtime-vsync:" + initialVsync);
                    return recordingApplier(trace);
                });

        assertEquals(
                new SettingsSnapshot(
                        1,
                        false,
                        100.0,
                        0.10,
                        true,
                        8,
                        0.0,
                        1.0,
                        1.0,
                        false,
                        GameMode.CREATIVE,
                        true),
                lifecycle.controller().applied());
        assertEquals(
                List.of(
                        "construct-runtime-vsync:false",
                        "hot-fov:100.0",
                        "hot-look:0.1:true",
                        "hot-audio:0.0:1.0:1.0:false"),
                trace);
        assertEquals(
                List.of(
                        new SettingsDiagnostic("CLAMPED_VALUE", "fovDegrees"),
                        new SettingsDiagnostic("NON_FINITE_VALUE", "mouseSensitivity"),
                        new SettingsDiagnostic("CLAMPED_VALUE", "chunkRadius"),
                        new SettingsDiagnostic("CLAMPED_VALUE", "masterVolume"),
                        new SettingsDiagnostic("CLAMPED_VALUE", "musicVolume"),
                        new SettingsDiagnostic("NON_FINITE_VALUE", "sfxVolume")),
                lifecycle.diagnostics());
    }

    @Test
    void missingAndCorruptStartupRemainSafeWithBoundedDiagnostics() {
        SettingsLoadResult missing = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of());
        SettingsDiagnostic corruptDiagnostic =
                new SettingsDiagnostic("INVALID_JSON", "$");
        SettingsLoadResult corrupt = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of(corruptDiagnostic));

        ProductSettingsLifecycle missingLifecycle = open(new RecordingStore(missing));
        ProductSettingsLifecycle corruptLifecycle = open(new RecordingStore(corrupt));

        assertEquals(SettingsDefaults.schemaV1(), missingLifecycle.controller().applied());
        assertTrue(missingLifecycle.diagnostics().isEmpty());
        assertEquals(SettingsDefaults.schemaV1(), corruptLifecycle.controller().applied());
        assertEquals(List.of(corruptDiagnostic), corruptLifecycle.diagnostics());
        assertThrows(
                UnsupportedOperationException.class,
                () -> corruptLifecycle.diagnostics().add(corruptDiagnostic));
    }

    @Test
    void eachNewSessionCapturesOnlyTheCurrentAppliedSettingsAtRequestTime() {
        SettingsLoadResult loaded = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of());
        RecordingStore store = new RecordingStore(loaded);
        ProductSettingsLifecycle lifecycle = open(store);

        GameSessionConfig first = lifecycle.newSessionConfig();
        lifecycle.controller().adjustChunkRadius(7);
        lifecycle.controller().toggleDefaultGameMode();
        lifecycle.controller().toggleDebugHudDefault();
        GameSessionConfig whileDraftIsOpen = lifecycle.newSessionConfig();

        lifecycle.controller().apply();
        GameSessionConfig afterApply = lifecycle.newSessionConfig();
        lifecycle.controller().adjustChunkRadius(3);
        GameSessionConfig afterAnotherDraftEdit = lifecycle.newSessionConfig();

        assertEquals(
                new GameSessionConfig(12345L, 4, GameMode.SURVIVAL, false),
                first);
        assertEquals(first, whileDraftIsOpen);
        assertEquals(
                new GameSessionConfig(12345L, 7, GameMode.CREATIVE, true),
                afterApply);
        assertEquals(afterApply, afterAnotherDraftEdit);
    }

    @Test
    void closeRewritesOnlyTheCurrentAppliedSnapshotOnceAndNeverTheOpenDraft() {
        SettingsLoadResult loaded = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of());
        RecordingStore store = new RecordingStore(loaded);
        ProductSettingsLifecycle lifecycle = open(store);
        lifecycle.controller().adjustFov(80.0);
        lifecycle.controller().apply();
        SettingsSnapshot applied = lifecycle.controller().applied();
        lifecycle.controller().adjustFov(95.0);

        lifecycle.close();
        lifecycle.close();

        assertEquals(95.0, lifecycle.controller().snapshot().draft().fovDegrees());
        assertEquals(
                List.of(applied, applied),
                store.saveAttempts(),
                "one explicit Apply write plus exactly one final applied-snapshot rewrite");
        assertEquals(applied, store.persisted());
    }

    @Test
    void closeSaveFailureIsNonSilentIdempotentAndPreservesPriorPersistedSnapshot() {
        SettingsLoadResult loaded = new SettingsLoadResult(
                SettingsDefaults.schemaV1(), List.of());
        RecordingStore store = new RecordingStore(loaded);
        ProductSettingsLifecycle lifecycle = open(store);
        lifecycle.controller().adjustFov(80.0);
        lifecycle.controller().apply();
        SettingsSnapshot priorPersisted = store.persisted();
        lifecycle.controller().adjustFov(95.0);
        SettingsPersistenceException closeFailure = new SettingsPersistenceException(
                "injected close save failure",
                new IllegalStateException("disk unavailable"));
        store.failNextSave(closeFailure);

        SettingsPersistenceException thrown = assertThrows(
                SettingsPersistenceException.class, lifecycle::close);
        lifecycle.close();

        assertSame(closeFailure, thrown);
        assertEquals(priorPersisted, store.persisted());
        assertEquals(2, store.saveAttempts().size());
        assertEquals(priorPersisted, store.saveAttempts().get(1));
    }

    private static ProductSettingsLifecycle open(RecordingStore store) {
        return ProductSettingsLifecycle.open(
                () -> Path.of("safe-test-root", "GaiaLegacy", "settings.json"),
                ignored -> store,
                ignored -> noOpApplier());
    }

    private static SettingsApplier noOpApplier() {
        return new SettingsApplier(
                ignored -> {},
                ignored -> {},
                (ignoredSensitivity, ignoredInvertY) -> {},
                (ignoredMaster, ignoredMusic, ignoredSfx, ignoredMute) -> {});
    }

    private static SettingsApplier recordingApplier(List<String> trace) {
        return new SettingsApplier(
                vsync -> trace.add("hot-vsync:" + vsync),
                fov -> trace.add("hot-fov:" + fov),
                (sensitivity, invertY) ->
                        trace.add("hot-look:" + sensitivity + ":" + invertY),
                (master, music, sfx, mute) ->
                        trace.add(
                                "hot-audio:"
                                        + master
                                        + ":"
                                        + music
                                        + ":"
                                        + sfx
                                        + ":"
                                        + mute));
    }

    private static SettingsSnapshot changedSettings() {
        return new SettingsSnapshot(
                1,
                false,
                90.0,
                0.25,
                true,
                7,
                0.8,
                0.4,
                0.6,
                false,
                GameMode.CREATIVE,
                true);
    }

    private static final class RecordingStore implements SettingsStore {
        private final SettingsLoadResult loadResult;
        private final List<SettingsSnapshot> saveAttempts = new ArrayList<>();
        private SettingsSnapshot persisted;
        private List<String> trace = new ArrayList<>();
        private SettingsPersistenceException nextSaveFailure;

        private RecordingStore(SettingsLoadResult loadResult) {
            this.loadResult = loadResult;
            persisted = loadResult.snapshot();
        }

        @Override
        public SettingsLoadResult load() {
            trace.add("load");
            return loadResult;
        }

        @Override
        public void save(SettingsSnapshot snapshot) {
            saveAttempts.add(snapshot);
            if (nextSaveFailure != null) {
                SettingsPersistenceException failure = nextSaveFailure;
                nextSaveFailure = null;
                throw failure;
            }
            persisted = snapshot;
        }

        private void traceInto(List<String> trace) {
            this.trace = trace;
        }

        private void failNextSave(SettingsPersistenceException failure) {
            nextSaveFailure = failure;
        }

        private List<SettingsSnapshot> saveAttempts() {
            return List.copyOf(saveAttempts);
        }

        private SettingsSnapshot persisted() {
            return persisted;
        }
    }

    private record AudioApply(
            double master,
            double music,
            double sfx,
            boolean muteWhenUnfocused) {}

    private static final class RecordingAudioSettingsPort implements AudioSettingsPort {
        private final List<AudioApply> applies = new ArrayList<>();

        @Override
        public void apply(
                double masterVolume,
                double musicVolume,
                double sfxVolume,
                boolean muteWhenUnfocused) {
            applies.add(
                    new AudioApply(
                            masterVolume,
                            musicVolume,
                            sfxVolume,
                            muteWhenUnfocused));
        }

        private List<AudioApply> applies() {
            return List.copyOf(applies);
        }
    }
}
