package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SettingsControllerTest {
    @Test
    void draftAdjustmentsAreValidatedAndPreviouslyCapturedStateRemainsImmutable() {
        Fixture fixture = new Fixture();
        SettingsDraftSnapshot before = fixture.controller().snapshot();

        fixture.controller().adjustFov(140.0);
        fixture.controller().adjustMouseSensitivity(0.001);
        fixture.controller().adjustChunkRadius(99);
        fixture.controller().adjustMasterVolume(-0.25);
        fixture.controller().adjustMusicVolume(1.25);
        fixture.controller().adjustSfxVolume(0.35);

        SettingsDraftSnapshot after = fixture.controller().snapshot();
        assertEquals(SettingsDefaults.schemaV1(), before.applied());
        assertEquals(SettingsDefaults.schemaV1(), before.draft());
        assertFalse(before.dirty());
        assertEquals(SettingsDefaults.schemaV1(), after.applied());
        assertEquals(
                new SettingsSnapshot(
                        1,
                        true,
                        100.0,
                        0.02,
                        false,
                        8,
                        0.0,
                        1.0,
                        0.35,
                        true,
                        GameMode.SURVIVAL,
                        false),
                after.draft());
        assertTrue(after.dirty());
        assertEquals(Optional.empty(), after.blockingDiagnostic());
    }

    @Test
    void successfulApplyHotAppliesThenPersistsThenPublishesValidatedDraft() {
        Fixture fixture = new Fixture();
        fixture.controller().toggleVsync();
        fixture.controller().adjustFov(140.0);

        fixture.controller().apply();

        SettingsSnapshot expected = new SettingsSnapshot(
                1,
                false,
                100.0,
                0.10,
                false,
                4,
                1.0,
                0.65,
                1.0,
                true,
                GameMode.SURVIVAL,
                false);
        assertEquals(
                List.of(
                        "hot.vsync:false:publishedFov=70.0",
                        "hot.fov:100.0:publishedFov=70.0",
                        "store.save:100.0:publishedFov=70.0"),
                fixture.trace());
        assertEquals(List.of(expected), fixture.store().saveAttempts());
        assertEquals(expected, fixture.store().persisted());
        assertEquals(expected, fixture.controller().applied());
        assertEquals(expected, fixture.controller().snapshot().draft());
        assertFalse(fixture.controller().snapshot().dirty());
        assertFalse(fixture.runtime().vsync());
        assertEquals(100.0f, fixture.runtime().fov());
    }

    @Test
    void applicationFailureRollsBackHotSettingsAndDoesNotPersistOrPublish() {
        Fixture fixture = new Fixture();
        RuntimeException applicationFailure =
                new RuntimeException("injected FOV application failure");
        fixture.controller().toggleVsync();
        fixture.controller().adjustFov(90.0);
        fixture.runtime().failFovValue(90.0f, applicationFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class, fixture.controller()::apply);

        assertSame(applicationFailure, thrown);
        assertEquals(
                List.of(
                        "hot.vsync:false:publishedFov=70.0",
                        "hot.fov:90.0:publishedFov=70.0",
                        "hot.vsync:true:publishedFov=70.0"),
                fixture.trace());
        assertTrue(fixture.store().saveAttempts().isEmpty());
        assertEquals(SettingsDefaults.schemaV1(), fixture.store().persisted());
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertTrue(fixture.controller().snapshot().dirty());
        assertTrue(fixture.runtime().vsync());
        assertEquals(70.0f, fixture.runtime().fov());
        assertEquals(Optional.empty(), fixture.controller().snapshot().blockingDiagnostic());
    }

    @Test
    void applicationRollbackFailureIsSuppressedAndBlocksFurtherTransactions() {
        Fixture fixture = new Fixture();
        RuntimeException applicationFailure =
                new RuntimeException("injected FOV application failure");
        RuntimeException rollbackFailure =
                new RuntimeException("injected VSync rollback failure");
        fixture.controller().toggleVsync();
        fixture.controller().adjustFov(90.0);
        fixture.runtime().failFovValue(90.0f, applicationFailure);
        fixture.runtime().failVsyncValue(true, rollbackFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class, fixture.controller()::apply);

        assertSame(applicationFailure, thrown);
        assertEquals(List.of(rollbackFailure), List.of(thrown.getSuppressed()));
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertFalse(fixture.runtime().vsync());
        assertTrue(fixture.controller().snapshot().blockingDiagnostic().isPresent());
        assertThrows(IllegalStateException.class, fixture.controller()::apply);
        assertTrue(fixture.store().saveAttempts().isEmpty());
    }

    @Test
    void persistenceFailureRollsBackHotSettingsAndDoesNotPublishDraft() {
        Fixture fixture = new Fixture();
        SettingsPersistenceException persistenceFailure = persistenceFailure();
        fixture.controller().toggleVsync();
        fixture.controller().adjustFov(90.0);
        fixture.store().failNextSave(persistenceFailure);

        SettingsPersistenceException thrown = assertThrows(
                SettingsPersistenceException.class, fixture.controller()::apply);

        assertSame(persistenceFailure, thrown);
        assertEquals(
                List.of(
                        "hot.vsync:false:publishedFov=70.0",
                        "hot.fov:90.0:publishedFov=70.0",
                        "store.save:90.0:publishedFov=70.0",
                        "hot.vsync:true:publishedFov=70.0",
                        "hot.fov:70.0:publishedFov=70.0"),
                fixture.trace());
        assertEquals(SettingsDefaults.schemaV1(), fixture.store().persisted());
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertTrue(fixture.controller().snapshot().dirty());
        assertTrue(fixture.runtime().vsync());
        assertEquals(70.0f, fixture.runtime().fov());
        assertEquals(Optional.empty(), fixture.controller().snapshot().blockingDiagnostic());
    }

    @Test
    void rollbackFailureIsSuppressedAndBlocksFurtherTransactionsWithDiagnostic() {
        Fixture fixture = new Fixture();
        SettingsPersistenceException persistenceFailure = persistenceFailure();
        RuntimeException rollbackFailure =
                new RuntimeException("injected FOV rollback failure");
        fixture.controller().toggleVsync();
        fixture.controller().adjustFov(90.0);
        fixture.store().failNextSave(persistenceFailure);
        fixture.runtime().failFovValue(70.0f, rollbackFailure);

        SettingsPersistenceException thrown = assertThrows(
                SettingsPersistenceException.class, fixture.controller()::apply);

        assertSame(persistenceFailure, thrown);
        assertEquals(List.of(rollbackFailure), List.of(thrown.getSuppressed()));
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertFalse(fixture.runtime().vsync());
        assertEquals(90.0f, fixture.runtime().fov());
        assertTrue(fixture.controller().snapshot().blockingDiagnostic().isPresent());
        assertThrows(IllegalStateException.class, fixture.controller()::apply);
        assertEquals(1, fixture.store().saveAttempts().size());
    }

    @Test
    void unchangedApplyDoesNotHotApplyOrWrite() {
        Fixture fixture = new Fixture();

        fixture.controller().apply();

        assertTrue(fixture.trace().isEmpty());
        assertTrue(fixture.store().saveAttempts().isEmpty());
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertFalse(fixture.controller().snapshot().dirty());
    }

    @Test
    void discardRestoresAppliedDraftWithoutHotApplicationOrPersistence() {
        Fixture fixture = new Fixture();
        fixture.controller().adjustFov(90.0);

        fixture.controller().discard();

        assertTrue(fixture.trace().isEmpty());
        assertTrue(fixture.store().saveAttempts().isEmpty());
        assertEquals(SettingsDefaults.schemaV1(), fixture.controller().applied());
        assertEquals(
                SettingsDefaults.schemaV1(), fixture.controller().snapshot().draft());
        assertFalse(fixture.controller().snapshot().dirty());
        assertEquals(70.0f, fixture.runtime().fov());
    }

    @Test
    void dirtyBackRequestsConfirmationWithoutDiscardingAndCleanBackReturns() {
        Fixture fixture = new Fixture();

        assertEquals(
                SettingsController.BackDecision.RETURN,
                fixture.controller().requestBack());

        fixture.controller().adjustFov(90.0);

        assertEquals(
                SettingsController.BackDecision.CONFIRM_DIRTY,
                fixture.controller().requestBack());
        assertEquals(90.0, fixture.controller().snapshot().draft().fovDegrees());
        assertTrue(fixture.controller().snapshot().dirty());
        assertTrue(fixture.trace().isEmpty());
        assertTrue(fixture.store().saveAttempts().isEmpty());
    }

    private static SettingsPersistenceException persistenceFailure() {
        return new SettingsPersistenceException(
                "injected persistence failure", new IllegalStateException("disk unavailable"));
    }

    private static final class Fixture {
        private final AtomicReference<SettingsController> controllerReference =
                new AtomicReference<>();
        private final List<String> trace = new ArrayList<>();
        private final RecordingHotRuntime runtime = new RecordingHotRuntime(
                trace, () -> controllerReference.get().applied());
        private final RecordingStore store = new RecordingStore(
                trace, () -> controllerReference.get().applied());
        private final SettingsController controller = new SettingsController(
                SettingsDefaults.schemaV1(), store, runtime.applier());

        private Fixture() {
            controllerReference.set(controller);
        }

        private SettingsController controller() {
            return controller;
        }

        private RecordingHotRuntime runtime() {
            return runtime;
        }

        private RecordingStore store() {
            return store;
        }

        private List<String> trace() {
            return List.copyOf(trace);
        }
    }

    private static final class RecordingStore implements SettingsStore {
        private final List<String> trace;
        private final Supplier<SettingsSnapshot> published;
        private final List<SettingsSnapshot> saveAttempts = new ArrayList<>();
        private SettingsSnapshot persisted = SettingsDefaults.schemaV1();
        private SettingsPersistenceException nextSaveFailure;

        private RecordingStore(
                List<String> trace, Supplier<SettingsSnapshot> published) {
            this.trace = trace;
            this.published = published;
        }

        @Override
        public SettingsLoadResult load() {
            return new SettingsLoadResult(persisted, List.of());
        }

        @Override
        public void save(SettingsSnapshot snapshot) {
            saveAttempts.add(snapshot);
            trace.add(
                    "store.save:"
                            + snapshot.fovDegrees()
                            + ":publishedFov="
                            + published.get().fovDegrees());
            if (nextSaveFailure != null) {
                SettingsPersistenceException failure = nextSaveFailure;
                nextSaveFailure = null;
                throw failure;
            }
            persisted = snapshot;
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

    private static final class RecordingHotRuntime {
        private final List<String> trace;
        private final Supplier<SettingsSnapshot> published;
        private boolean vsync = true;
        private float fov = 70.0f;
        private float mouseSensitivity = 0.10f;
        private boolean invertY;
        private double masterVolume = 1.0;
        private double musicVolume = 0.65;
        private double sfxVolume = 1.0;
        private boolean muteWhenUnfocused = true;
        private Boolean failingVsync;
        private RuntimeException vsyncFailure;
        private Float failingFov;
        private RuntimeException fovFailure;

        private RecordingHotRuntime(
                List<String> trace, Supplier<SettingsSnapshot> published) {
            this.trace = trace;
            this.published = published;
        }

        private SettingsApplier applier() {
            return new SettingsApplier(
                    this::applyVsync,
                    this::applyFov,
                    this::applyLook,
                    this::applyAudio);
        }

        private void applyVsync(boolean nextVsync) {
            trace.add(
                    "hot.vsync:"
                            + nextVsync
                            + ":publishedFov="
                            + published.get().fovDegrees());
            if (failingVsync != null && failingVsync == nextVsync) {
                throw vsyncFailure;
            }
            vsync = nextVsync;
        }

        private void applyFov(float nextFov) {
            trace.add(
                    "hot.fov:"
                            + nextFov
                            + ":publishedFov="
                            + published.get().fovDegrees());
            if (failingFov != null && Float.compare(failingFov, nextFov) == 0) {
                throw fovFailure;
            }
            fov = nextFov;
        }

        private void applyLook(float sensitivity, boolean inverted) {
            mouseSensitivity = sensitivity;
            invertY = inverted;
        }

        private void applyAudio(
                double master,
                double music,
                double sfx,
                boolean muteWhenUnfocused) {
            masterVolume = master;
            musicVolume = music;
            sfxVolume = sfx;
            this.muteWhenUnfocused = muteWhenUnfocused;
        }

        private void failFovValue(float value, RuntimeException failure) {
            failingFov = value;
            fovFailure = failure;
        }

        private void failVsyncValue(boolean value, RuntimeException failure) {
            failingVsync = value;
            vsyncFailure = failure;
        }

        private boolean vsync() {
            return vsync;
        }

        private float fov() {
            return fov;
        }
    }
}
