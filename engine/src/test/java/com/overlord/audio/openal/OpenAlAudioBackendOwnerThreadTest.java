package com.overlord.audio.openal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.audio.MusicHandle;
import com.overlord.core.thread.MainThreadGuard;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenAlAudioBackendOwnerThreadTest {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");

    @Test
    void backendInitializationRejectsWorkerBeforeOpeningDevice()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        OpenAlAudioBackendTest.RecordingOpenAlApi api =
                new OpenAlAudioBackendTest.RecordingOpenAlApi();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            new OpenAlAudioBackend(
                                                                    api,
                                                                    guard,
                                                                    ignored ->
                                                                            ByteBuffer
                                                                                    .allocateDirect(
                                                                                            8),
                                                                    ignored ->
                                                                            new OpenAlAudioBackendTest
                                                                                    .RecordingDecoder(
                                                                                            1)))
                                            .get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(api.calls.isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void everyBackendOperationRejectsWorkerBeforeOpenAlOrDecoderAccess()
            throws InterruptedException {
        OpenAlAudioBackendTest.RecordingOpenAlApi api =
                new OpenAlAudioBackendTest.RecordingOpenAlApi();
        OpenAlAudioBackendTest.RecordingDecoder decoder =
                new OpenAlAudioBackendTest.RecordingDecoder(100_000);
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        ignored -> ByteBuffer.allocateDirect(8),
                        ignored -> decoder);
        MusicHandle handle = backend.startMusic(GAIA, true);
        int apiCallsBeforeWorker = api.calls.size();
        int maximumFramesBeforeWorker = decoder.maximumRequestedFrames;
        int decoderCloseCallsBeforeWorker = decoder.closeCalls;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            List<Runnable> operations =
                    List.of(
                            () -> backend.startMusic(GAIA, true),
                            () -> backend.setMusicGain(handle, 0.5f),
                            () -> backend.isMusicPlaying(handle),
                            () -> backend.stopMusic(handle),
                            backend::update,
                            backend::close);

            for (Runnable operation : operations) {
                ExecutionException failure =
                        assertThrows(
                                ExecutionException.class,
                                () -> worker.submit(operation).get());
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }

            assertEquals(apiCallsBeforeWorker, api.calls.size());
            assertEquals(maximumFramesBeforeWorker, decoder.maximumRequestedFrames);
            assertEquals(decoderCloseCallsBeforeWorker, decoder.closeCalls);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
        backend.close();
    }
}
