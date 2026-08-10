package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioBackend;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.AudioDiagnostic;
import com.overlord.audio.MusicHandle;
import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MusicManagerTest {
    private static final double EPSILON = 1.0e-6;

    @Test
    void mainGameplayPauseAndReturnMainRetainOneGaiaPlaybackInstance() {
        Fixture fixture = new Fixture();

        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        MusicHandle first = fixture.backend.lastHandle;
        fixture.manager.requestRoute(MusicRoute.GAMEPLAY);
        fixture.manager.requestRoute(MusicRoute.PAUSED);
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertSame(first, fixture.backend.lastHandle);
        assertEquals(1, fixture.backend.startCalls);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        assertEquals(fixture.catalog.gaia(), fixture.backend.startedTracks.get(0));
        fixture.close();
    }

    @Test
    void pausedSettingsAndControlsDuckSameHandleToSeventyPercent() {
        for (MusicRoute route : List.of(
                MusicRoute.PAUSED,
                MusicRoute.SETTINGS_FROM_PAUSE,
                MusicRoute.CONTROLS_FROM_PAUSE)) {
            Fixture fixture = new Fixture();
            fixture.startAndSettle();
            MusicHandle handle = fixture.backend.lastHandle;

            fixture.manager.requestRoute(route);
            fixture.manager.update(0.175);
            fixture.manager.update(0.175);

            assertSame(handle, fixture.backend.lastHandle);
            assertEquals(1, fixture.backend.startCalls);
            assertEquals(0.70, fixture.manager.snapshot().envelope(), EPSILON);
            fixture.close();
        }
    }

    @Test
    void explicitLegacySelectionIsTheOnlyWayNormalRoutesChangeTrack() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.manager.requestRoute(MusicRoute.GAMEPLAY);
        assertEquals(List.of(fixture.catalog.gaia()), fixture.backend.startedTracks);

        fixture.manager.requestTrack(fixture.catalog.legacy());
        fixture.manager.requestRoute(MusicRoute.PAUSED);

        assertEquals(
                List.of(fixture.catalog.gaia(), fixture.catalog.legacy()),
                fixture.backend.startedTracks);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        assertEquals(Optional.of(fixture.catalog.legacy()), fixture.manager.snapshot().activeTrack());
        fixture.close();
    }

    @Test
    void startupFadeUsesBoundedDeltaAndSettlesExactlyWithoutOvershoot() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        fixture.manager.update(100.0);
        assertEquals(0.125, fixture.manager.snapshot().envelope(), EPSILON);
        for (int index = 0; index < 7; index++) {
            fixture.manager.update(0.25);
        }
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.manager.update(0.25);
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void policyConfiguredWhileStoppedCannotShortenTheLaterStartupFade() {
        Fixture fixture = new Fixture();
        fixture.manager.setMuteWhenUnfocused(false);

        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.manager.update(0.25);

        assertEquals(0.125, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void routeAndFocusTransitionsAreOrderIndependentProducts() {
        Fixture focusThenRoute = new Fixture();
        Fixture routeThenFocus = new Fixture();
        focusThenRoute.startPausedUnfocusedAndSettle();
        routeThenFocus.startPausedUnfocusedAndSettle();

        focusThenRoute.manager.setFocused(true);
        focusThenRoute.manager.requestRoute(MusicRoute.GAMEPLAY);
        routeThenFocus.manager.requestRoute(MusicRoute.GAMEPLAY);
        routeThenFocus.manager.setFocused(true);

        focusThenRoute.manager.update(0.20);
        routeThenFocus.manager.update(0.20);

        double expectedIntermediate = 0.70 + 0.30 * (0.20 / 0.35);
        assertEquals(expectedIntermediate, focusThenRoute.manager.snapshot().envelope(), EPSILON);
        assertEquals(expectedIntermediate, routeThenFocus.manager.snapshot().envelope(), EPSILON);

        focusThenRoute.manager.update(0.15);
        routeThenFocus.manager.update(0.15);

        assertEquals(1.0, focusThenRoute.manager.snapshot().envelope(), EPSILON);
        assertEquals(
                focusThenRoute.manager.snapshot().envelope(),
                routeThenFocus.manager.snapshot().envelope(),
                EPSILON);
        focusThenRoute.close();
        routeThenFocus.close();
    }

    @Test
    void pauseDuckAndRecoveryUseApprovedDurationsAndSettleExactly() {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();

        fixture.manager.requestRoute(MusicRoute.PAUSED);
        fixture.manager.update(0.175);
        assertEquals(0.85, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.manager.update(0.175);
        assertEquals(0.70, fixture.manager.snapshot().envelope(), EPSILON);

        fixture.manager.requestRoute(MusicRoute.GAMEPLAY);
        fixture.manager.update(0.25);
        assertEquals(0.914285714, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.manager.update(0.10);
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @ParameterizedTest
    @EnumSource(PlaybackFailurePoint.class)
    void playbackPipelineFailureLatchesSilenceWithoutRetrySpam(
            PlaybackFailurePoint failurePoint) {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();
        int startsBeforeFailure = fixture.backend.startCalls;
        fixture.backend.failNextPlaybackOperation(
                failurePoint,
                new IllegalStateException(failurePoint + " failed " + "x".repeat(600)));

        assertDoesNotThrow(() -> fixture.manager.update(0.10));

        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());
        assertEquals(0.0, fixture.manager.snapshot().envelope(), EPSILON);
        assertEquals(0, fixture.backend.activeVoices);
        assertEquals(
                failurePoint == PlaybackFailurePoint.GAIN ? 1 : 0,
                fixture.backend.stopAttempts);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals("MUSIC_PLAYBACK_FAILED", fixture.diagnostics.get(0).code());
        assertTrue(
                fixture.diagnostics.get(0).message().length()
                        <= AudioDiagnostic.MAX_MESSAGE_LENGTH);

        fixture.manager.update(0.25);
        fixture.manager.update(0.25);
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertEquals(startsBeforeFailure, fixture.backend.startCalls);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals(1, fixture.backend.maximumActiveVoices);

        fixture.manager.requestTrack(fixture.catalog.legacy());

        assertEquals(startsBeforeFailure + 1, fixture.backend.startCalls);
        assertEquals(Optional.of(fixture.catalog.legacy()), fixture.manager.snapshot().activeTrack());
        fixture.close();
    }

    @Test
    void gainFailureStopFailureStillDiscardsTheLiveVoiceUnderOneDiagnostic() {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();
        int startsBeforeFailure = fixture.backend.startCalls;
        fixture.backend.failNextPlaybackOperation(
                PlaybackFailurePoint.GAIN,
                new IllegalStateException("gain failed with live voice " + "x".repeat(600)));
        fixture.backend.failNextStopAfterInvalidating(
                new IllegalStateException("best-effort stop failed"));

        assertDoesNotThrow(() -> fixture.manager.update(0.10));

        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());
        assertEquals(0, fixture.backend.activeVoices);
        assertEquals(1, fixture.backend.stopAttempts);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals("MUSIC_PLAYBACK_FAILED", fixture.diagnostics.get(0).code());
        assertTrue(
                fixture.diagnostics.get(0).message().length()
                        <= AudioDiagnostic.MAX_MESSAGE_LENGTH);

        fixture.manager.update(0.25);
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertEquals(startsBeforeFailure, fixture.backend.startCalls);
        assertEquals(1, fixture.diagnostics.size());

        fixture.manager.requestTrack(fixture.catalog.legacy());

        assertEquals(startsBeforeFailure + 1, fixture.backend.startCalls);
        assertEquals(Optional.of(fixture.catalog.legacy()), fixture.manager.snapshot().activeTrack());
        fixture.manager.close();
        int attemptsAfterClose = fixture.backend.stopAttempts;
        assertDoesNotThrow(fixture.manager::close);
        assertEquals(2, attemptsAfterClose);
        assertEquals(attemptsAfterClose, fixture.backend.stopAttempts);
        assertEquals(0, fixture.backend.activeVoices);
        fixture.device.close();
    }

    @Test
    void focusMuteAndRecoveryUseTwoTenthsWhenPolicyEnabled() {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();

        fixture.manager.setFocused(false);
        fixture.manager.update(0.10);
        assertEquals(0.50, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.manager.update(0.10);
        assertEquals(0.0, fixture.manager.snapshot().envelope(), EPSILON);

        fixture.manager.setFocused(true);
        fixture.manager.update(0.20);
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void disabledFocusPolicyNeverMutesAndCanRestoreWhileAlreadyUnfocused() {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();
        fixture.manager.setMuteWhenUnfocused(false);
        fixture.manager.setFocused(false);
        fixture.manager.update(0.25);
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);

        fixture.manager.setMuteWhenUnfocused(true);
        fixture.manager.update(0.20);
        assertEquals(0.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.manager.setMuteWhenUnfocused(false);
        fixture.manager.update(0.20);
        assertEquals(1.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void invalidDeltaIsRejectedBeforeAudioUpdateOrEnvelopeMutation() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        int updateCalls = fixture.backend.updateCalls;
        double before = fixture.manager.snapshot().envelope();

        for (double invalid : new double[] {-0.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> fixture.manager.update(invalid));
        }

        assertEquals(updateCalls, fixture.backend.updateCalls);
        assertEquals(before, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void repeatedRouteAndTrackRequestsAreNoOpsWithoutDuplicateVoice() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.manager.requestTrack(fixture.catalog.gaia());
        fixture.manager.requestTrack(fixture.catalog.gaia());

        assertEquals(1, fixture.backend.startCalls);
        assertEquals(0, fixture.backend.stopCalls);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        fixture.close();
    }

    @Test
    void stagedTrackWhileStoppedCannotSurviveCanonicalStoppedIntent() {
        Fixture fixture = new Fixture();

        fixture.manager.requestTrack(fixture.catalog.legacy());
        fixture.manager.requestRoute(MusicRoute.STOPPED);

        assertEquals(Optional.empty(), fixture.manager.snapshot().desiredTrack());

        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertEquals(List.of(fixture.catalog.gaia()), fixture.backend.startedTracks);
        fixture.close();
    }

    @Test
    void stoppedRouteStopsOnceAndLaterMainStartsOneFreshVoice() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        MusicHandle first = fixture.backend.lastHandle;

        fixture.manager.requestRoute(MusicRoute.STOPPED);
        fixture.manager.requestRoute(MusicRoute.STOPPED);

        assertEquals(1, fixture.backend.stopCalls);
        assertEquals(0, fixture.backend.activeVoices);
        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());

        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertNotSame(first, fixture.backend.lastHandle);
        assertEquals(2, fixture.backend.startCalls);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        fixture.close();
    }

    @Test
    void replacementStopFailureClearsStaleVoiceAndNeverRetriesItDuringClose() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.backend.failNextStopAfterInvalidating(
                new IllegalStateException("replacement stop failed " + "x".repeat(600)));

        Throwable requestFailure = captureFailure(
                () -> fixture.manager.requestTrack(fixture.catalog.legacy()));
        MusicManagerSnapshot afterRequest = fixture.manager.snapshot();
        Throwable closeFailure = captureFailure(fixture.manager::close);
        Throwable duplicateCloseFailure = captureFailure(fixture.manager::close);

        assertNull(requestFailure);
        assertNull(closeFailure);
        assertNull(duplicateCloseFailure);
        assertEquals(Optional.empty(), afterRequest.activeTrack());
        assertEquals(1, fixture.backend.startCalls);
        assertEquals(1, fixture.backend.stopAttempts);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals("MUSIC_STOP_FAILED", fixture.diagnostics.get(0).code());
        assertTrue(
                fixture.diagnostics.get(0).message().length()
                        <= AudioDiagnostic.MAX_MESSAGE_LENGTH);
        fixture.device.close();
    }

    @Test
    void stoppedRouteContainsStopFailureAndRemainsCanonicalAndIdempotent() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.backend.failNextStopAfterInvalidating(
                new IllegalStateException("stopped route failed"));

        Throwable routeFailure = captureFailure(
                () -> fixture.manager.requestRoute(MusicRoute.STOPPED));
        MusicManagerSnapshot afterStop = fixture.manager.snapshot();
        Throwable duplicateRouteFailure = captureFailure(
                () -> fixture.manager.requestRoute(MusicRoute.STOPPED));
        Throwable closeFailure = captureFailure(fixture.manager::close);

        assertNull(routeFailure);
        assertNull(duplicateRouteFailure);
        assertNull(closeFailure);
        assertEquals(MusicRoute.STOPPED, afterStop.route());
        assertEquals(Optional.empty(), afterStop.desiredTrack());
        assertEquals(Optional.empty(), afterStop.activeTrack());
        assertEquals(1, fixture.backend.stopAttempts);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals("MUSIC_STOP_FAILED", fixture.diagnostics.get(0).code());
        fixture.device.close();
    }

    @Test
    void closeContainsInvalidatingStopFailureWithoutRetryOrResurrection() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.backend.failNextStopAfterInvalidating(
                new IllegalStateException("close stop failed"));

        Throwable closeFailure = captureFailure(fixture.manager::close);
        Throwable duplicateCloseFailure = captureFailure(fixture.manager::close);

        assertNull(closeFailure);
        assertNull(duplicateCloseFailure);
        assertEquals(1, fixture.backend.stopAttempts);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals("MUSIC_STOP_FAILED", fixture.diagnostics.get(0).code());
        fixture.device.close();
    }

    @Test
    void endedTrackReplaysOrdinarilyWithoutOverlappingVoices() {
        Fixture fixture = new Fixture();
        fixture.startAndSettle();
        fixture.backend.finishCurrentTrack();

        fixture.manager.update(0.0);

        assertEquals(2, fixture.backend.startCalls);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        assertEquals(0.0, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void everyManagerOperationRejectsWorkerThreadBeforeStateOrBackendMutation()
            throws InterruptedException {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        MusicManagerSnapshot before = fixture.manager.snapshot();
        int backendCallsBefore = fixture.backend.totalCalls();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            List<Runnable> operations = List.of(
                    () -> fixture.manager.requestRoute(MusicRoute.GAMEPLAY),
                    () -> fixture.manager.requestTrack(fixture.catalog.legacy()),
                    () -> fixture.manager.setFocused(false),
                    () -> fixture.manager.setMuteWhenUnfocused(false),
                    () -> fixture.manager.update(0.0),
                    fixture.manager::snapshot,
                    fixture.manager::close);

            for (Runnable operation : operations) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> worker.submit(operation).get());
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(before, fixture.manager.snapshot());
        assertEquals(backendCallsBefore, fixture.backend.totalCalls());
        fixture.close();
    }

    @Test
    void failedDesiredTrackDoesNotRetryOrRepeatItsDiagnosticUntilDesireChanges() {
        Fixture fixture = new Fixture();
        fixture.backend.startFailure = new IllegalStateException("decode failed");

        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.manager.update(0.25);
        fixture.manager.update(0.25);
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        assertEquals(1, fixture.backend.startCalls);
        assertEquals(1, fixture.diagnostics.size());
        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());
        fixture.close();
    }

    @Test
    void trackStartFailureBecomesOneBoundedDiagnosticAndCoherentSilence() {
        Fixture fixture = new Fixture();
        fixture.backend.startFailure = new IllegalStateException("decode failed " + "x".repeat(600));

        assertDoesNotThrow(() -> fixture.manager.requestRoute(MusicRoute.MAIN_MENU));

        assertEquals(1, fixture.diagnostics.size());
        AudioDiagnostic diagnostic = fixture.diagnostics.get(0);
        assertEquals("MUSIC_TRACK_START_FAILED", diagnostic.code());
        assertTrue(diagnostic.message().length() <= AudioDiagnostic.MAX_MESSAGE_LENGTH);
        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());
        assertDoesNotThrow(() -> fixture.manager.update(0.25));
        fixture.close();
    }

    @Test
    void diagnosticSinkRuntimeFailureCannotTurnTrackFailureIntoProductFailure() {
        RecordingBackend backend = new RecordingBackend();
        AudioDevice device = AudioDevice.open(
                () -> backend, MainThreadGuard.captureCurrentThread(), ignored -> {});
        MusicManager manager = new MusicManager(
                device,
                new GaiaMusicCatalog(),
                ignored -> {
                    throw new IllegalStateException("diagnostic sink failed");
                });
        backend.startFailure = new IllegalStateException("decode failed");

        assertDoesNotThrow(() -> manager.requestRoute(MusicRoute.MAIN_MENU));

        assertEquals(1, backend.startCalls);
        assertEquals(0, backend.activeVoices);
        assertEquals(Optional.empty(), manager.snapshot().activeTrack());
        manager.close();
        device.close();
    }

    @Test
    void failedExplicitReplacementNeverOverlapsOrLeaksTheOldVoice() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        fixture.backend.startFailure = new IllegalStateException("legacy decode failed");

        assertDoesNotThrow(() -> fixture.manager.requestTrack(fixture.catalog.legacy()));

        assertEquals(1, fixture.backend.stopCalls);
        assertEquals(0, fixture.backend.activeVoices);
        assertEquals(1, fixture.backend.maximumActiveVoices);
        assertEquals(Optional.empty(), fixture.manager.snapshot().activeTrack());
        fixture.close();
    }

    @Test
    void immutableSnapshotDoesNotChangeAfterLaterManagerUpdates() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);
        MusicManagerSnapshot before = fixture.manager.snapshot();

        fixture.manager.update(0.25);

        assertEquals(0.0, before.envelope(), EPSILON);
        assertEquals(0.125, fixture.manager.snapshot().envelope(), EPSILON);
        fixture.close();
    }

    @Test
    void closeStopsManagerVoiceButLeavesDeviceForItsLaterOwnerAndIsTerminal() {
        Fixture fixture = new Fixture();
        fixture.manager.requestRoute(MusicRoute.MAIN_MENU);

        fixture.manager.close();
        int callsAfterFirstClose = fixture.backend.totalCalls();
        assertDoesNotThrow(fixture.manager::close);
        assertEquals(1, fixture.backend.stopCalls);
        assertEquals(0, fixture.backend.closeCalls);
        assertThrows(IllegalStateException.class, () -> fixture.manager.requestRoute(MusicRoute.MAIN_MENU));
        assertThrows(IllegalStateException.class, () -> fixture.manager.requestTrack(fixture.catalog.legacy()));
        assertThrows(IllegalStateException.class, () -> fixture.manager.setFocused(false));
        assertThrows(IllegalStateException.class, () -> fixture.manager.setMuteWhenUnfocused(false));
        assertThrows(IllegalStateException.class, () -> fixture.manager.update(0.0));
        assertEquals(callsAfterFirstClose, fixture.backend.totalCalls());
        assertEquals(1, fixture.backend.startCalls);

        fixture.device.close();
        assertEquals(1, fixture.backend.closeCalls);
    }

    private static final class Fixture {
        private final GaiaMusicCatalog catalog = new GaiaMusicCatalog();
        private final RecordingBackend backend = new RecordingBackend();
        private final List<AudioDiagnostic> diagnostics = new ArrayList<>();
        private final AudioDevice device = AudioDevice.open(
                () -> backend, MainThreadGuard.captureCurrentThread(), diagnostics::add);
        private final MusicManager manager = new MusicManager(device, catalog, diagnostics::add);

        private void startAndSettle() {
            manager.requestRoute(MusicRoute.MAIN_MENU);
            for (int index = 0; index < 8; index++) {
                manager.update(0.25);
            }
            assertEquals(1.0, manager.snapshot().envelope(), EPSILON);
        }

        private void startPausedUnfocusedAndSettle() {
            startAndSettle();
            manager.requestRoute(MusicRoute.PAUSED);
            manager.update(0.175);
            manager.update(0.175);
            manager.setFocused(false);
            manager.update(0.20);
            assertEquals(0.0, manager.snapshot().envelope(), EPSILON);
        }

        private void close() {
            manager.close();
            device.close();
        }
    }

    private static final class RecordingBackend implements AudioBackend {
        private final List<ResourceLocation> startedTracks = new ArrayList<>();
        private int startCalls;
        private int gainCalls;
        private int queryCalls;
        private int stopCalls;
        private int stopAttempts;
        private int updateCalls;
        private int closeCalls;
        private int activeVoices;
        private int maximumActiveVoices;
        private boolean playing;
        private MusicHandle lastHandle;
        private RuntimeException startFailure;
        private RuntimeException stopFailure;
        private RuntimeException playbackFailure;
        private PlaybackFailurePoint playbackFailurePoint;
        private boolean closed;

        @Override
        public MusicHandle startMusic(ResourceLocation track, boolean loop) {
            requireOpen();
            startCalls++;
            if (startFailure != null) {
                RuntimeException failure = startFailure;
                startFailure = null;
                throw failure;
            }
            if (activeVoices != 0) {
                throw new AssertionError("attempted to overlap music voices");
            }
            if (loop) {
                throw new AssertionError("music manager must use ordinary non-looping playback");
            }
            startedTracks.add(track);
            lastHandle = new MusicHandle(startCalls);
            playing = true;
            activeVoices++;
            maximumActiveVoices = Math.max(maximumActiveVoices, activeVoices);
            return lastHandle;
        }

        @Override
        public void setMusicGain(MusicHandle handle, float gain) {
            requireOpen();
            requireCurrentHandle(handle);
            if (!playing) {
                throw new AssertionError("gain applied to an inactive music voice");
            }
            if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
                throw new AssertionError("invalid backend music gain: " + gain);
            }
            failPlaybackAt(PlaybackFailurePoint.GAIN);
            gainCalls++;
        }

        @Override
        public boolean isMusicPlaying(MusicHandle handle) {
            requireOpen();
            requireCurrentHandle(handle);
            queryCalls++;
            failPlaybackAt(PlaybackFailurePoint.QUERY);
            return playing;
        }

        @Override
        public void stopMusic(MusicHandle handle) {
            stopAttempts++;
            requireOpen();
            requireCurrentHandle(handle);
            if (!playing) {
                throw new AssertionError("attempted to stop an inactive music voice");
            }
            stopCalls++;
            playing = false;
            activeVoices--;
            if (stopFailure != null) {
                RuntimeException failure = stopFailure;
                stopFailure = null;
                throw failure;
            }
        }

        @Override
        public void update() {
            requireOpen();
            updateCalls++;
            failPlaybackAt(PlaybackFailurePoint.UPDATE);
        }

        @Override
        public void close() {
            requireOpen();
            closed = true;
            closeCalls++;
        }

        private void finishCurrentTrack() {
            if (!playing || activeVoices != 1) {
                throw new AssertionError("no active track to finish");
            }
            playing = false;
            activeVoices = 0;
        }

        private void failNextPlaybackOperation(
                PlaybackFailurePoint point, RuntimeException failure) {
            playbackFailurePoint = point;
            playbackFailure = failure;
        }

        private void failPlaybackAt(PlaybackFailurePoint point) {
            if (playbackFailurePoint != point) {
                return;
            }
            RuntimeException failure = playbackFailure;
            playbackFailurePoint = null;
            playbackFailure = null;
            if (playing && point != PlaybackFailurePoint.GAIN) {
                playing = false;
                activeVoices = 0;
            }
            throw failure;
        }

        private void failNextStopAfterInvalidating(RuntimeException failure) {
            stopFailure = failure;
        }

        private void requireOpen() {
            if (closed) {
                throw new AssertionError("backend accessed after close");
            }
        }

        private void requireCurrentHandle(MusicHandle handle) {
            if (handle != lastHandle) {
                throw new AssertionError("manager used an unknown music handle");
            }
        }

        private int totalCalls() {
            return startCalls + gainCalls + queryCalls + stopCalls + updateCalls + closeCalls;
        }
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private enum PlaybackFailurePoint {
        UPDATE,
        QUERY,
        GAIN
    }
}
