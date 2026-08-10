package com.overlord.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AudioDeviceTest {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");

    @Test
    void openCreatesBackendOnOwnerThreadWithoutDiagnostic() {
        RecordingBackend backend = new RecordingBackend();
        List<AudioDiagnostic> diagnostics = new ArrayList<>();

        AudioDevice device =
                AudioDevice.open(
                        () -> backend,
                        MainThreadGuard.captureCurrentThread(),
                        diagnostics::add);

        assertTrue(diagnostics.isEmpty());
        MusicHandle handle = device.startMusic(GAIA, true);
        assertSame(backend.startedHandle, handle);
        device.close();
    }

    @Test
    void initializationFailureEmitsOneBoundedDiagnosticAndSelectsSilentFallback() {
        List<AudioDiagnostic> diagnostics = new ArrayList<>();
        String nativeMessage = "native failure " + "x".repeat(600);

        AudioDevice device =
                AudioDevice.open(
                        () -> {
                            throw new IllegalStateException(nativeMessage);
                        },
                        MainThreadGuard.captureCurrentThread(),
                        diagnostics::add);

        assertEquals(1, diagnostics.size());
        AudioDiagnostic diagnostic = diagnostics.get(0);
        assertEquals("AUDIO_BACKEND_INIT_FAILED", diagnostic.code());
        assertTrue(diagnostic.message().length() <= 256);
        MusicHandle handle = assertDoesNotThrow(() -> device.startMusic(GAIA, true));
        assertTrue(device.isMusicPlaying(handle));
        assertDoesNotThrow(device::update);
        device.close();
    }

    @ParameterizedTest(name = "{0} selects Silent fallback")
    @MethodSource("nativeLinkageFailures")
    void nativeLinkageFailureEmitsOneBoundedDiagnosticAndSelectsUsableSilentFallback(
            LinkageError nativeFailure) {
        List<AudioDiagnostic> diagnostics = new ArrayList<>();

        AudioDevice device = AudioDevice.open(
                () -> {
                    throw nativeFailure;
                },
                MainThreadGuard.captureCurrentThread(),
                diagnostics::add);

        assertEquals(1, diagnostics.size());
        assertEquals("AUDIO_BACKEND_INIT_FAILED", diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).message().length() <= AudioDiagnostic.MAX_MESSAGE_LENGTH);
        MusicHandle handle = assertDoesNotThrow(() -> device.startMusic(GAIA, true));
        assertTrue(device.isMusicPlaying(handle));
        assertDoesNotThrow(device::update);
        device.stopMusic(handle);
        assertDoesNotThrow(device::close);
    }

    @Test
    void nativeLinkageFallbackSurvivesDiagnosticSinkRuntimeException() {
        AtomicInteger diagnosticCalls = new AtomicInteger();

        AudioDevice device = assertDoesNotThrow(
                () ->
                        AudioDevice.open(
                                () -> {
                                    throw new UnsatisfiedLinkError("OpenAL unavailable");
                                },
                                MainThreadGuard.captureCurrentThread(),
                                diagnostic -> {
                                    diagnosticCalls.incrementAndGet();
                                    throw new IllegalStateException("diagnostic sink failed");
                                }));

        assertEquals(1, diagnosticCalls.get());
        MusicHandle handle = assertDoesNotThrow(() -> device.startMusic(GAIA, false));
        assertTrue(device.isMusicPlaying(handle));
        assertDoesNotThrow(device::close);
    }

    @Test
    void diagnosticConsumerFailureCannotCancelSilentFallbackOrRetryInitialization() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger diagnosticCalls = new AtomicInteger();

        AudioDevice device =
                assertDoesNotThrow(
                        () ->
                                AudioDevice.open(
                                        () -> {
                                            factoryCalls.incrementAndGet();
                                            throw new IllegalStateException("native init failed");
                                        },
                                        MainThreadGuard.captureCurrentThread(),
                                        diagnostic -> {
                                            diagnosticCalls.incrementAndGet();
                                            throw new IllegalStateException("diagnostic sink failed");
                                        }));

        assertEquals(1, factoryCalls.get());
        assertEquals(1, diagnosticCalls.get());
        MusicHandle handle = assertDoesNotThrow(() -> device.startMusic(GAIA, true));
        assertTrue(device.isMusicPlaying(handle));
        assertDoesNotThrow(device::update);
        device.stopMusic(handle);
        assertFalse(device.isMusicPlaying(handle));
        assertDoesNotThrow(device::close);
        assertDoesNotThrow(device::close);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, diagnosticCalls.get());
    }

    @Test
    void diagnosticConsumerErrorIsNotSwallowedOrRetried() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger diagnosticCalls = new AtomicInteger();
        AssertionError fatalDiagnosticFailure = new AssertionError("fatal diagnostic failure");

        AssertionError observed =
                assertThrows(
                        AssertionError.class,
                        () ->
                                AudioDevice.open(
                                        () -> {
                                            factoryCalls.incrementAndGet();
                                            throw new IllegalStateException("native init failed");
                                        },
                                        MainThreadGuard.captureCurrentThread(),
                                        diagnostic -> {
                                            diagnosticCalls.incrementAndGet();
                                            throw fatalDiagnosticFailure;
                                        }));

        assertSame(fatalDiagnosticFailure, observed);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, diagnosticCalls.get());
    }

    @Test
    void ordinaryAssertionErrorAndInvalidDependenciesStillPropagate() {
        List<AudioDiagnostic> diagnostics = new ArrayList<>();
        AssertionError fatal = new AssertionError("fatal VM/native linkage condition");

        AssertionError observed =
                assertThrows(
                        AssertionError.class,
                        () ->
                                AudioDevice.open(
                                        () -> {
                                            throw fatal;
                                        },
                                        MainThreadGuard.captureCurrentThread(),
                                        diagnostics::add));

        assertSame(fatal, observed);
        assertTrue(diagnostics.isEmpty());
        assertThrows(
                NullPointerException.class,
                () ->
                        AudioDevice.open(
                                null,
                                MainThreadGuard.captureCurrentThread(),
                                diagnostics::add));
        assertTrue(diagnostics.isEmpty());
    }

    private static Stream<LinkageError> nativeLinkageFailures() {
        String detail = "native linkage failure " + "x".repeat(600);
        return Stream.of(
                new UnsatisfiedLinkError(detail),
                new NoClassDefFoundError(detail));
    }

    @Test
    void setMusicEnvelopeAppliesMasterMusicCueAndEnvelopeExactlyOnce() {
        RecordingBackend backend = new RecordingBackend();
        AudioDevice device = open(backend);
        MusicHandle handle = device.startMusic(GAIA, true);
        device.applyBusSettings(new AudioBusSettings(0.5f, 0.4f, 0.8f));

        device.setMusicEnvelope(handle, 0.25f, 0.5f);

        assertEquals(0.025f, backend.lastGain, 1.0e-6f);
        assertEquals(1, backend.gainCalls);
        device.close();
    }

    @Test
    void envelopeRejectsInvalidCueAndEnvelopeBeforeBackendCall() {
        RecordingBackend backend = new RecordingBackend();
        AudioDevice device = open(backend);
        MusicHandle handle = device.startMusic(GAIA, true);

        for (float invalid : new float[] {-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> device.setMusicEnvelope(handle, invalid, 1.0f));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> device.setMusicEnvelope(handle, 1.0f, invalid));
        }

        assertEquals(0, backend.gainCalls);
        device.close();
    }

    @Test
    void publicOperationsDelegateCoherentlyOnOwnerThread() {
        RecordingBackend backend = new RecordingBackend();
        AudioDevice device = open(backend);

        device.applyBusSettings(new AudioBusSettings(0.7f, 0.6f, 0.5f));
        MusicHandle handle = device.startMusic(GAIA, false);
        assertEquals(GAIA, backend.lastTrack);
        assertFalse(backend.lastLoop);
        assertTrue(device.isMusicPlaying(handle));
        device.update();
        device.stopMusic(handle);
        assertFalse(device.isMusicPlaying(handle));

        assertEquals(1, backend.updateCalls);
        assertEquals(1, backend.stopCalls);
        device.close();
        assertEquals(1, backend.closeCalls);
    }

    @Test
    void everyPublicOperationRejectsWorkerThreadBeforeBackendAccess()
            throws InterruptedException {
        RecordingBackend backend = new RecordingBackend();
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        AudioDevice device = AudioDevice.open(() -> backend, guard, ignored -> {});
        MusicHandle handle = device.startMusic(GAIA, true);
        int callsBeforeWorker = backend.totalCalls();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            List<Runnable> operations =
                    List.of(
                            () -> device.applyBusSettings(AudioBusSettings.fullVolume()),
                            () -> device.startMusic(GAIA, true),
                            () -> device.setMusicEnvelope(handle, 1.0f, 1.0f),
                            () -> device.isMusicPlaying(handle),
                            () -> device.stopMusic(handle),
                            device::update,
                            device::close);

            for (Runnable operation : operations) {
                ExecutionException failure =
                        assertThrows(
                                ExecutionException.class,
                                () -> worker.submit(operation).get());
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }

            assertEquals(callsBeforeWorker, backend.totalCalls());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
        device.close();
    }

    @Test
    void openItselfRejectsWorkerBeforeCallingFactory() throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        AtomicInteger factoryCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            AudioDevice.open(
                                                                    () -> {
                                                                        factoryCalls.incrementAndGet();
                                                                        return new RecordingBackend();
                                                                    },
                                                                    guard,
                                                                    ignored -> {}))
                                            .get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, factoryCalls.get());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeIsIdempotentAndAllOtherOperationsRemainTerminal() {
        RecordingBackend backend = new RecordingBackend();
        AudioDevice device = open(backend);
        MusicHandle handle = device.startMusic(GAIA, true);

        device.close();
        assertDoesNotThrow(device::close);

        assertEquals(1, backend.closeCalls);
        assertThrows(
                IllegalStateException.class,
                () -> device.applyBusSettings(AudioBusSettings.fullVolume()));
        assertThrows(IllegalStateException.class, () -> device.startMusic(GAIA, true));
        assertThrows(
                IllegalStateException.class,
                () -> device.setMusicEnvelope(handle, 1.0f, 1.0f));
        assertThrows(IllegalStateException.class, () -> device.isMusicPlaying(handle));
        assertThrows(IllegalStateException.class, () -> device.stopMusic(handle));
        assertThrows(IllegalStateException.class, device::update);
        assertEquals(1, backend.closeCalls);
    }

    @Test
    void failedCloseStillLeavesDeviceTerminalAndDoesNotRetryBackendCleanup() {
        RecordingBackend backend = new RecordingBackend();
        backend.closeFailure = new IllegalStateException("close failed");
        AudioDevice device = open(backend);

        assertSame(backend.closeFailure, assertThrows(IllegalStateException.class, device::close));
        assertDoesNotThrow(device::close);
        assertEquals(1, backend.closeCalls);
        assertThrows(IllegalStateException.class, device::update);
    }

    @Test
    void assetSourceContractCanReturnCallerOwnedDirectBuffer() {
        AudioAssetSource source =
                location -> {
                    ByteBuffer owned = ByteBuffer.allocateDirect(4);
                    owned.putInt(location.hashCode()).flip();
                    return owned;
                };

        ByteBuffer first = source.read(GAIA);
        ByteBuffer second = source.read(GAIA);

        assertTrue(first.isDirect());
        assertTrue(second.isDirect());
        assertFalse(first == second);
    }

    private static AudioDevice open(RecordingBackend backend) {
        return AudioDevice.open(
                () -> backend,
                MainThreadGuard.captureCurrentThread(),
                ignored -> {});
    }

    private static final class RecordingBackend implements AudioBackend {
        private final MusicHandle startedHandle = new MusicHandle(41L);
        private ResourceLocation lastTrack;
        private boolean lastLoop;
        private boolean playing;
        private float lastGain;
        private int startCalls;
        private int gainCalls;
        private int queryCalls;
        private int stopCalls;
        private int updateCalls;
        private int closeCalls;
        private RuntimeException closeFailure;

        @Override
        public MusicHandle startMusic(ResourceLocation track, boolean loop) {
            startCalls++;
            lastTrack = track;
            lastLoop = loop;
            playing = true;
            return startedHandle;
        }

        @Override
        public void setMusicGain(MusicHandle handle, float gain) {
            gainCalls++;
            lastGain = gain;
        }

        @Override
        public boolean isMusicPlaying(MusicHandle handle) {
            queryCalls++;
            return playing;
        }

        @Override
        public void stopMusic(MusicHandle handle) {
            stopCalls++;
            playing = false;
        }

        @Override
        public void update() {
            updateCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private int totalCalls() {
            return startCalls + gainCalls + queryCalls + stopCalls + updateCalls + closeCalls;
        }
    }
}
