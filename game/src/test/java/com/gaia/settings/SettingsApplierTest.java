package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SettingsApplierTest {
    @Test
    void thirdBoundaryFailureRollsBackChangedBoundariesInReverseOrder() {
        SettingsSnapshot previous = SettingsDefaults.schemaV1();
        SettingsSnapshot next = allHotFieldsChanged();
        RecordingFixture fixture = new RecordingFixture(previous, next);
        RuntimeException applyFailure = new RuntimeException("camera apply failed");
        fixture.failNextLookWith(applyFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fixture.applier().apply(previous, next));

        assertSame(applyFailure, thrown);
        assertEquals(
                List.of(
                        "vsync:false",
                        "fov:90.0",
                        "look:0.2:true",
                        "fov:70.0",
                        "vsync:true"),
                fixture.trace());
        assertEquals(previous, fixture.currentSnapshot());
    }

    @Test
    void rollbackFailuresAreSuppressedAndDoNotStopEarlierRollback() {
        SettingsSnapshot previous = SettingsDefaults.schemaV1();
        SettingsSnapshot next = allHotFieldsChanged();
        RecordingFixture fixture = new RecordingFixture(previous, next);
        RuntimeException applyFailure = new RuntimeException("camera apply failed");
        RuntimeException rollbackFailure = new RuntimeException("FOV rollback failed");
        fixture.failNextLookWith(applyFailure);
        fixture.failFovRollbackWith(rollbackFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fixture.applier().apply(previous, next));

        assertSame(applyFailure, thrown);
        assertEquals(List.of(rollbackFailure), List.of(thrown.getSuppressed()));
        assertEquals(
                List.of(
                        "vsync:false",
                        "fov:90.0",
                        "look:0.2:true",
                        "fov:70.0",
                        "vsync:true"),
                fixture.trace());
        assertTrue(fixture.currentSnapshot().vsync());
    }

    @Test
    void appliesOnlyChangedHotBoundariesUsingTheCompleteNextAudioState() {
        SettingsSnapshot previous = SettingsDefaults.schemaV1();
        SettingsSnapshot next = new SettingsSnapshot(
                1,
                true,
                90.0,
                0.10,
                false,
                8,
                1.0,
                0.25,
                1.0,
                true,
                GameMode.CREATIVE,
                true);
        RecordingFixture fixture = new RecordingFixture(previous, next);

        fixture.applier().apply(previous, next);

        assertEquals(
                List.of(
                        "fov:90.0",
                        "audio:1.0:0.25:1.0:true"),
                fixture.trace());
    }

    private static SettingsSnapshot allHotFieldsChanged() {
        return new SettingsSnapshot(
                1,
                false,
                90.0,
                0.20,
                true,
                8,
                0.80,
                0.40,
                0.60,
                false,
                GameMode.CREATIVE,
                true);
    }

    private static final class RecordingFixture {
        private final SettingsSnapshot baseline;
        private final SettingsSnapshot next;
        private final List<String> trace = new ArrayList<>();
        private boolean vsync;
        private double fovDegrees;
        private double mouseSensitivity;
        private boolean invertY;
        private double masterVolume;
        private double musicVolume;
        private double sfxVolume;
        private boolean muteWhenUnfocused;
        private RuntimeException nextLookFailure;
        private RuntimeException fovRollbackFailure;

        private RecordingFixture(
                SettingsSnapshot baseline, SettingsSnapshot next) {
            this.baseline = baseline;
            this.next = next;
            vsync = baseline.vsync();
            fovDegrees = baseline.fovDegrees();
            mouseSensitivity = baseline.mouseSensitivity();
            invertY = baseline.invertY();
            masterVolume = baseline.masterVolume();
            musicVolume = baseline.musicVolume();
            sfxVolume = baseline.sfxVolume();
            muteWhenUnfocused = baseline.muteWhenUnfocused();
        }

        private SettingsApplier applier() {
            Consumer<Boolean> vsyncPort = this::applyVsync;
            Consumer<Float> fovPort = this::applyFov;
            BiConsumer<Float, Boolean> lookPort = this::applyLook;
            AudioSettingsPort audioPort = this::applyAudio;
            return new SettingsApplier(
                    vsyncPort, fovPort, lookPort, audioPort);
        }

        private void failNextLookWith(RuntimeException failure) {
            nextLookFailure = failure;
        }

        private void failFovRollbackWith(RuntimeException failure) {
            fovRollbackFailure = failure;
        }

        private void applyVsync(boolean value) {
            trace.add("vsync:" + value);
            vsync = value;
        }

        private void applyFov(float value) {
            trace.add("fov:" + value);
            if (fovRollbackFailure != null
                    && Float.compare(value, (float) baseline.fovDegrees()) == 0) {
                throw fovRollbackFailure;
            }
            fovDegrees = value;
        }

        private void applyLook(float sensitivity, boolean inverted) {
            trace.add("look:" + sensitivity + ":" + inverted);
            if (nextLookFailure != null
                    && Float.compare(
                                    sensitivity,
                                    (float) next.mouseSensitivity())
                            == 0
                    && inverted == next.invertY()) {
                throw nextLookFailure;
            }
            mouseSensitivity = sensitivity;
            invertY = inverted;
        }

        private void applyAudio(
                double master,
                double music,
                double sfx,
                boolean muteWhenUnfocused) {
            trace.add(
                    "audio:"
                            + master
                            + ":"
                            + music
                            + ":"
                            + sfx
                            + ":"
                            + muteWhenUnfocused);
            masterVolume = master;
            musicVolume = music;
            sfxVolume = sfx;
            this.muteWhenUnfocused = muteWhenUnfocused;
        }

        private List<String> trace() {
            return List.copyOf(trace);
        }

        private SettingsSnapshot currentSnapshot() {
            return new SettingsSnapshot(
                    baseline.schemaVersion(),
                    vsync,
                    fovDegrees,
                    mouseSensitivity,
                    invertY,
                    baseline.chunkRadius(),
                    masterVolume,
                    musicVolume,
                    sfxVolume,
                    muteWhenUnfocused,
                    baseline.defaultGameMode(),
                    baseline.debugHudDefault());
        }
    }
}
