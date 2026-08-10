package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.settings.AudioSettingsPort;
import com.gaia.settings.SettingsApplier;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioBackend;
import com.overlord.audio.AudioBusSettings;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.MusicHandle;
import com.overlord.core.thread.MainThreadGuard;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GaiaAudioSettingsAdapterTest {
    @Test
    void appliesTheExactBusTupleAndRefreshesGainOnceWithoutDoubleMultiplication() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.settle();
        int gainCallsBeforeApply = fixture.backend.gainCalls;

        AudioSettingsPort port = fixture.adapter;
        port.apply(0.5, 0.4, 0.25, true);

        assertEquals(
                new AudioBusSettings(0.5f, 0.4f, 0.25f),
                currentBusSettings(fixture.device));
        assertEquals(0.20f, fixture.backend.lastGain, 1.0e-6f);
        assertEquals(gainCallsBeforeApply + 1, fixture.backend.gainCalls);
        fixture.close();
    }

    @Test
    void settingsApplierStyleRollbackRestoresExactPriorVolumesAndFocusPolicy() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.settle();
        fixture.manager.setFocused(false);

        fixture.adapter.apply(0.8, 0.5, 0.6, false);
        fixture.manager.update(0.20);
        assertEquals(0.40f, fixture.backend.lastGain, 1.0e-6f);

        fixture.adapter.apply(1.0, 0.65, 1.0, true);
        fixture.manager.update(0.20);
        assertEquals(0.0f, fixture.backend.lastGain, 1.0e-6f);
        fixture.manager.setFocused(true);
        fixture.manager.update(0.20);
        assertEquals(0.65f, fixture.backend.lastGain, 1.0e-6f);
        fixture.close();
    }

    @Test
    void rejectsInvalidVolumeTupleBeforeChangingTheCurrentGain() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.settle();
        fixture.adapter.apply(0.5, 0.4, 0.25, true);
        float prior = fixture.backend.lastGain;
        int priorGainCalls = fixture.backend.gainCalls;
        AudioBusSettings priorBuses = currentBusSettings(fixture.device);

        for (double invalid : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.adapter.apply(invalid, 0.4, 0.25, true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.adapter.apply(0.5, invalid, 0.25, true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.adapter.apply(0.5, 0.4, invalid, true));
        }

        assertEquals(prior, fixture.backend.lastGain, 1.0e-6f);
        assertEquals(priorGainCalls, fixture.backend.gainCalls);
        assertEquals(priorBuses, currentBusSettings(fixture.device));
        fixture.close();
    }

    @Test
    void failedRefreshRestoresCommittedStateInsideSettingsApplierBoundary() {
        Fixture fixture = committedUnfocusedFixture();
        SettingsSnapshot previous = audioSnapshot(0.8, 0.5, 0.6, false);
        SettingsApplier applier = settingsApplier(fixture.adapter);
        RuntimeException firstFailure = new IllegalStateException("first refresh failed");
        fixture.backend.failNextGains(firstFailure);

        RuntimeException firstThrown = assertThrows(
                RuntimeException.class,
                () -> applier.apply(previous, audioSnapshot(1.0, 0.65, 1.0, true)));

        assertSame(firstFailure, firstThrown);
        assertCommittedState(fixture, new AudioBusSettings(0.8f, 0.5f, 0.6f), false, 0.4f);

        RuntimeException secondFailure = new IllegalStateException("second refresh failed");
        fixture.backend.failNextGains(secondFailure);

        RuntimeException secondThrown = assertThrows(
                RuntimeException.class,
                () -> applier.apply(previous, audioSnapshot(0.3, 0.2, 0.1, true)));

        assertSame(secondFailure, secondThrown);
        assertCommittedState(fixture, new AudioBusSettings(0.8f, 0.5f, 0.6f), false, 0.4f);
        fixture.close();
    }

    @Test
    void compensationFailureIsSuppressedUnderThePrimaryRefreshFailure() {
        Fixture fixture = committedUnfocusedFixture();
        RuntimeException primary = new IllegalStateException("new gain failed");
        RuntimeException compensation = new IllegalStateException("old gain restore failed");
        fixture.backend.failNextGains(primary, compensation);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fixture.adapter.apply(1.0, 0.65, 1.0, true));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(compensation, thrown.getSuppressed()[0]);
        assertEquals(
                new AudioBusSettings(0.8f, 0.5f, 0.6f),
                currentBusSettings(fixture.device));
        assertEquals(false, fixture.manager.snapshot().muteWhenUnfocused());
        fixture.close();
    }

    @Test
    void failedRefreshDoesNotRestartAnInFlightFocusTransition() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.settle();
        fixture.manager.setFocused(false);
        fixture.manager.update(0.10);
        assertEquals(0.50, fixture.manager.snapshot().envelope(), 1.0e-6);
        RuntimeException refreshFailure = new IllegalStateException("refresh failed midway");
        fixture.backend.failNextGains(refreshFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fixture.adapter.apply(0.8, 0.5, 0.6, false));

        assertSame(refreshFailure, thrown);
        assertEquals(AudioBusSettings.fullVolume(), currentBusSettings(fixture.device));
        assertEquals(true, fixture.manager.snapshot().muteWhenUnfocused());

        fixture.manager.update(0.10);

        assertEquals(0.0, fixture.manager.snapshot().envelope(), 1.0e-6);
        fixture.close();
    }

    private static Fixture committedUnfocusedFixture() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.settle();
        fixture.manager.setMuteWhenUnfocused(false);
        fixture.manager.setFocused(false);
        fixture.adapter.apply(0.8, 0.5, 0.6, false);
        assertCommittedState(
                fixture, new AudioBusSettings(0.8f, 0.5f, 0.6f), false, 0.4f);
        return fixture;
    }

    private static void assertCommittedState(
            Fixture fixture,
            AudioBusSettings expectedBuses,
            boolean expectedMuteWhenUnfocused,
            float expectedGain) {
        assertEquals(expectedBuses, currentBusSettings(fixture.device));
        assertEquals(
                expectedMuteWhenUnfocused,
                fixture.manager.snapshot().muteWhenUnfocused());
        assertEquals(expectedGain, fixture.backend.lastGain, 1.0e-6f);
    }

    private static SettingsSnapshot audioSnapshot(
            double master, double music, double sfx, boolean muteWhenUnfocused) {
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        return new SettingsSnapshot(
                defaults.schemaVersion(),
                defaults.vsync(),
                defaults.fovDegrees(),
                defaults.mouseSensitivity(),
                defaults.invertY(),
                defaults.chunkRadius(),
                master,
                music,
                sfx,
                muteWhenUnfocused,
                defaults.defaultGameMode(),
                defaults.debugHudDefault());
    }

    private static SettingsApplier settingsApplier(AudioSettingsPort audioPort) {
        try {
            Constructor<SettingsApplier> constructor = SettingsApplier.class.getDeclaredConstructor(
                    Consumer.class, Consumer.class, BiConsumer.class, AudioSettingsPort.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    (Consumer<Boolean>) ignored -> {},
                    (Consumer<Float>) ignored -> {},
                    (BiConsumer<Float, Boolean>) (ignoredValue, ignoredFlag) -> {},
                    audioPort);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot construct SettingsApplier test boundary", failure);
        }
    }

    private static AudioBusSettings currentBusSettings(AudioDevice device) {
        Field busField = Arrays.stream(AudioDevice.class.getDeclaredFields())
                .filter(field -> field.getType() == AudioBusSettings.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AudioDevice bus settings field is absent"));
        try {
            busField.setAccessible(true);
            return (AudioBusSettings) busField.get(device);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect current AudioDevice bus settings", failure);
        }
    }

    private static final class Fixture {
        private final GainBackend backend = new GainBackend();
        private final AudioDevice device = AudioDevice.open(
                () -> backend, MainThreadGuard.captureCurrentThread(), ignored -> {});
        private final MusicManager manager =
                new MusicManager(device, new GaiaMusicCatalog(), ignored -> {});
        private final GaiaAudioSettingsAdapter adapter =
                new GaiaAudioSettingsAdapter(device, manager);

        private void settle() {
            for (int index = 0; index < 8; index++) {
                manager.update(0.25);
            }
        }

        private void close() {
            manager.close();
            device.close();
        }
    }

    private static final class GainBackend implements AudioBackend {
        private final MusicHandle handle = new MusicHandle(1L);
        private boolean playing;
        private float lastGain;
        private int gainCalls;
        private final Deque<RuntimeException> gainFailures = new ArrayDeque<>();
        private boolean closed;

        @Override
        public MusicHandle startMusic(ResourceLocation track, boolean loop) {
            requireOpen();
            if (playing || loop) {
                throw new AssertionError("invalid adapter-test music start");
            }
            playing = true;
            return handle;
        }

        @Override
        public void setMusicGain(MusicHandle handle, float gain) {
            requireOpen();
            requireHandle(handle);
            if (!playing || !Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
                throw new AssertionError("invalid adapter-test music gain: " + gain);
            }
            gainCalls++;
            if (!gainFailures.isEmpty()) {
                throw gainFailures.removeFirst();
            }
            lastGain = gain;
        }

        @Override
        public boolean isMusicPlaying(MusicHandle handle) {
            requireOpen();
            requireHandle(handle);
            return playing;
        }

        @Override
        public void stopMusic(MusicHandle handle) {
            requireOpen();
            requireHandle(handle);
            if (!playing) {
                throw new AssertionError("attempted to stop inactive adapter-test music");
            }
            playing = false;
        }

        @Override
        public void update() {
            requireOpen();
        }

        @Override
        public void close() {
            requireOpen();
            closed = true;
        }

        private void requireOpen() {
            if (closed) {
                throw new AssertionError("adapter-test backend accessed after close");
            }
        }

        private void requireHandle(MusicHandle observed) {
            if (observed != handle) {
                throw new AssertionError("adapter used an unknown music handle");
            }
        }

        private void failNextGains(RuntimeException... failures) {
            gainFailures.addAll(Arrays.asList(failures));
        }
    }
}
