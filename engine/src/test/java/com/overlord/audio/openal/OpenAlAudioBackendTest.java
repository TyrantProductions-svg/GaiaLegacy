package com.overlord.audio.openal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioAssetSource;
import com.overlord.audio.MusicHandle;
import com.overlord.audio.vorbis.VorbisDecoder;
import com.overlord.core.thread.MainThreadGuard;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAlAudioBackendTest {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");

    @Test
    void zeroGeneratedSourceFailsBeforeBuffersOrHandleAndRemainsReusableAfterCleanCleanup() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        api.forceZeroSource = true;
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        Deque<RecordingDecoder> decoders =
                new ArrayDeque<>(
                        List.of(
                                new RecordingDecoder(100_000),
                                new RecordingDecoder(100_000)));
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        ignored -> decoders.removeFirst());

        assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertEquals(1, source.releaseCalls);
        assertEquals(0, api.generatedBufferCount);
        assertEquals(0, api.playCalls);

        api.forceZeroSource = false;
        MusicHandle firstPublished = backend.startMusic(GAIA, true);
        assertEquals(1L, firstPublished.value());
        assertEquals(3, api.generatedBufferCount);
        backend.close();
    }

    @Test
    void musicUsesThreeBuffersAndRefillsOnlyProcessedBuffers() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        RecordingDecoder decoder = new RecordingDecoder(100_000);
        OpenAlAudioBackend backend = backend(api, location -> decoder);

        backend.startMusic(GAIA, true);

        assertEquals(3, api.generatedBufferCount);
        assertEquals(3, api.uploadedBuffers.size());
        api.reportProcessedBuffers(1);
        int uploadsBeforeUpdate = api.uploadedBuffers.size();
        backend.update();
        assertEquals(1, api.unqueuedCount);
        assertEquals(1, api.requeuedCount);
        assertEquals(uploadsBeforeUpdate + 1, api.uploadedBuffers.size());
        assertTrue(decoder.maximumRequestedFrames <= OpenAlAudioBackend.FRAMES_PER_BUFFER);
        backend.close();
    }

    @Test
    void zeroProcessedBuffersPerformNoUploadOrQueueMutation() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, location -> new RecordingDecoder(100_000));
        backend.startMusic(GAIA, true);
        int uploads = api.uploadedBuffers.size();
        int unqueues = api.unqueuedCount;
        int queues = api.requeuedCount;

        api.reportProcessedBuffers(0);
        backend.update();

        assertEquals(uploads, api.uploadedBuffers.size());
        assertEquals(unqueues, api.unqueuedCount);
        assertEquals(queues, api.requeuedCount);
        backend.close();
    }

    @Test
    void loopingEofReopensAssetAndDecoderBeforeRequeueing() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        Deque<RecordingDecoder> decoders =
                new ArrayDeque<>(
                        List.of(
                                new RecordingDecoder(OpenAlAudioBackend.FRAMES_PER_BUFFER * 3),
                                new RecordingDecoder(OpenAlAudioBackend.FRAMES_PER_BUFFER * 3)));
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        compressed -> decoders.removeFirst());
        backend.startMusic(GAIA, true);

        api.reportProcessedBuffers(1);
        backend.update();

        assertEquals(2, source.readCalls);
        assertEquals(2, source.releaseCalls);
        assertEquals(1, api.unqueuedCount);
        assertEquals(1, api.requeuedCount);
        assertEquals(4, api.uploadedBuffers.size());
        backend.close();
    }

    @Test
    void successfulDecoderOpenReleasesCallerBufferExactlyOnceBeforePlayback() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        List<String> events = new ArrayList<>();
        TrackingAssetSource source = new TrackingAssetSource(events);
        RecordingDecoder decoder = new RecordingDecoder(100_000);
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        compressed -> {
                            events.add("decoderOpen");
                            return decoder;
                        });

        backend.startMusic(GAIA, true);

        assertEquals(List.of("read", "decoderOpen", "release"), events);
        assertEquals(1, source.releaseCalls);
        backend.close();
    }

    @Test
    void decoderFactoryFailureReleasesCallerBufferOnceAndPreservesPrimary() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        IllegalStateException primary = new IllegalStateException("decoder factory failed");
        AtomicInteger decoderCalls = new AtomicInteger();
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        compressed -> {
                            if (decoderCalls.getAndIncrement() == 0) {
                                throw primary;
                            }
                            return new RecordingDecoder(100_000);
                        });

        assertSame(primary, assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true)));
        assertEquals(1, source.readCalls);
        assertEquals(1, source.releaseCalls);
        MusicHandle replacement = backend.startMusic(GAIA, true);
        assertEquals(1L, replacement.value());
        assertEquals(2, source.releaseCalls);
        backend.close();
    }

    @Test
    void decoderFactoryPrimaryRetainsReleaseFailureAsSuppressed() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        IllegalStateException primary = new IllegalStateException("decoder factory failed");
        IllegalStateException release = new IllegalStateException("asset release failed");
        source.releaseFailure = release;
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        compressed -> {
                            throw primary;
                        });

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertSame(primary, observed);
        assertEquals(List.of(release), Arrays.asList(observed.getSuppressed()));
        assertEquals(1, source.releaseCalls);
        backend.close();
    }

    @Test
    void releaseFailureAfterDecoderOpenClosesDecoderAndSuppressesCloseFailure() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        IllegalStateException release = new IllegalStateException("asset release failed");
        IllegalStateException decoderClose = new IllegalStateException("decoder close failed");
        source.releaseFailure = release;
        RecordingDecoder decoder = new RecordingDecoder(100_000);
        decoder.closeFailure = decoderClose;
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        ignored -> decoder);

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertSame(release, observed);
        assertEquals(List.of(decoderClose), Arrays.asList(observed.getSuppressed()));
        assertEquals(1, source.releaseCalls);
        assertEquals(1, decoder.closeCalls);
        assertEquals(0, api.generatedSourceCount);
        backend.close();
    }

    @Test
    void nonLoopingEofDrainsQueuedBuffersThenReportsNotPlaying() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        RecordingDecoder firstDecoder =
                new RecordingDecoder(OpenAlAudioBackend.FRAMES_PER_BUFFER);
        RecordingDecoder secondDecoder = new RecordingDecoder(100_000);
        Deque<RecordingDecoder> decoders =
                new ArrayDeque<>(List.of(firstDecoder, secondDecoder));
        OpenAlAudioBackend backend =
                backend(api, location -> decoders.removeFirst());
        MusicHandle handle = backend.startMusic(GAIA, false);
        assertTrue(backend.isMusicPlaying(handle));

        api.reportProcessedBuffers(1);
        backend.update();

        assertFalse(backend.isMusicPlaying(handle));
        assertEquals(0, api.queuedBufferCount(api.generatedSource));
        assertEquals(1, firstDecoder.closeCalls);
        assertTrue(api.calls.contains("deleteSource:31"));
        assertTrue(api.calls.contains("deleteBuffers:[41, 42, 43]"));
        assertThrows(IllegalArgumentException.class, () -> backend.stopMusic(handle));

        MusicHandle replacement = backend.startMusic(GAIA, false);
        assertTrue(replacement.value() > handle.value());
        assertTrue(backend.isMusicPlaying(replacement));
        assertEquals(2, api.generatedSourceCount);
        assertEquals(6, api.generatedBufferCount);
        backend.close();
    }

    @Test
    void secondActiveVoiceIsRejectedWithoutResourceGrowth() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, location -> new RecordingDecoder(100_000));
        backend.startMusic(GAIA, true);

        assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));

        assertEquals(1, api.generatedSourceCount);
        assertEquals(3, api.generatedBufferCount);
        backend.close();
    }

    @Test
    void stopReleasesVoiceInReverseOrderAndStaleOwnedHandleReportsFalse() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        RecordingDecoder decoder = new RecordingDecoder(100_000);
        OpenAlAudioBackend backend = backend(api, location -> decoder);
        MusicHandle handle = backend.startMusic(GAIA, true);
        api.calls.clear();

        backend.stopMusic(handle);

        assertEquals(
                List.of("stopSource:31", "deleteSource:31", "deleteBuffers:[41, 42, 43]"),
                api.calls);
        assertEquals(1, decoder.closeCalls);
        assertFalse(backend.isMusicPlaying(handle));
        assertThrows(IllegalArgumentException.class, () -> backend.stopMusic(handle));
        assertEquals(1, decoder.closeCalls);
        backend.close();
    }

    @Test
    void gainValidationRejectsInvalidValuesBeforeOpenAlAndForwardsOneExactValidValue() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        MusicHandle handle = backend.startMusic(GAIA, true);
        int callsBeforeGain = api.calls.size();

        for (float invalid :
                new float[] {-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.setMusicGain(handle, invalid));
        }
        assertEquals(callsBeforeGain, api.calls.size());

        backend.setMusicGain(handle, 0.375f);
        assertEquals("gain:0.375", api.calls.get(api.calls.size() - 1));
        backend.close();
    }

    @Test
    void forgedAndForeignHandlesCannotControlOrQueryTheVoice() {
        RecordingOpenAlApi firstApi = new RecordingOpenAlApi();
        RecordingOpenAlApi secondApi = new RecordingOpenAlApi();
        OpenAlAudioBackend first = backend(firstApi, ignored -> new RecordingDecoder(100_000));
        OpenAlAudioBackend second = backend(secondApi, ignored -> new RecordingDecoder(100_000));
        MusicHandle firstHandle = first.startMusic(GAIA, true);
        MusicHandle secondHandle = second.startMusic(GAIA, true);
        MusicHandle forged = new MusicHandle(firstHandle.value());

        for (MusicHandle invalid : List.of(secondHandle, forged)) {
            assertThrows(IllegalArgumentException.class, () -> first.isMusicPlaying(invalid));
            assertThrows(IllegalArgumentException.class, () -> first.setMusicGain(invalid, 0.5f));
            assertThrows(IllegalArgumentException.class, () -> first.stopMusic(invalid));
        }
        assertTrue(first.isMusicPlaying(firstHandle));
        first.close();
        second.close();
    }

    @Test
    void starvedSourceRestartsOnlyWhenQueuedAudioRemains() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        backend.startMusic(GAIA, true);
        int playsAfterStart = api.playCalls;
        api.sourcePlaying = false;

        api.reportProcessedBuffers(1);
        backend.update();

        assertEquals(playsAfterStart + 1, api.playCalls);
        backend.close();
    }

    @Test
    void initializationFailureCleansPartialDeviceInReverseAndSuppressesCleanupFailure() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        IllegalStateException primary = new IllegalStateException("capabilities failed");
        IllegalStateException cleanup = new IllegalStateException("destroy context failed");
        api.capabilitiesFailure = primary;
        api.destroyContextFailure = cleanup;

        IllegalStateException observed =
                assertThrows(
                        IllegalStateException.class,
                        () -> backend(api, ignored -> new RecordingDecoder(1)));

        assertSame(primary, observed);
        assertEquals(List.of(cleanup), Arrays.asList(observed.getSuppressed()));
        assertTrue(api.calls.indexOf("makeContextCurrent:0") < api.calls.indexOf("destroyContext:22"));
        assertTrue(api.calls.indexOf("destroyContext:22") < api.calls.indexOf("closeDevice:11"));
    }

    @Test
    void queuedVoiceStartFailureDetachesSourceBeforeBuffersAndSuppressesCleanupFailure() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        RecordingDecoder decoder = new RecordingDecoder(100_000);
        OpenAlAudioBackend backend = backend(api, ignored -> decoder);
        IllegalStateException primary = new IllegalStateException("queue failed");
        IllegalStateException cleanup = new IllegalStateException("delete buffers failed");
        api.queueFailure = primary;
        api.queueFailureCall = 2;
        api.deleteBuffersFailure = cleanup;
        api.calls.clear();

        IllegalStateException observed =
                assertThrows(
                        IllegalStateException.class,
                        () -> backend.startMusic(GAIA, true));

        assertSame(primary, observed);
        assertEquals(List.of(cleanup), Arrays.asList(observed.getSuppressed()));
        assertEquals(
                List.of(
                        "generateSource:31",
                        "generateBuffers:3",
                        "upload:41",
                        "queue:41",
                        "upload:42",
                        "queue:42",
                        "stopSource:31",
                        "deleteSource:31",
                        "deleteBuffers:[41, 42, 43]"),
                api.calls);
        assertEquals(1, decoder.closeCalls);
        int sourcesAfterFailure = api.generatedSourceCount;
        int buffersAfterFailure = api.generatedBufferCount;
        assertBackendBroken(backend, new MusicHandle(91L));
        assertEquals(sourcesAfterFailure, api.generatedSourceCount);
        assertEquals(buffersAfterFailure, api.generatedBufferCount);
        backend.close();
    }

    @Test
    void failedStopTeardownMarksBackendBrokenButCloseStillClosesDeviceOnce() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        MusicHandle handle = backend.startMusic(GAIA, true);
        IllegalStateException teardown = new IllegalStateException("delete buffers failed");
        api.deleteBuffersFailure = teardown;

        assertSame(
                teardown,
                assertThrows(IllegalStateException.class, () -> backend.stopMusic(handle)));
        int sourceCount = api.generatedSourceCount;
        int bufferCount = api.generatedBufferCount;
        assertBackendBroken(backend, handle);
        assertEquals(sourceCount, api.generatedSourceCount);
        assertEquals(bufferCount, api.generatedBufferCount);

        api.calls.clear();
        backend.close();
        backend.close();
        assertEquals(
                List.of("makeContextCurrent:0", "destroyContext:22", "closeDevice:11"),
                api.calls);
    }

    @Test
    void updatePrimaryWithTeardownFailureMarksBackendBrokenAndKeepsSuppressedOrder() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        MusicHandle handle = backend.startMusic(GAIA, true);
        IllegalStateException primary = new IllegalStateException("refill upload failed");
        IllegalStateException teardown = new IllegalStateException("delete buffers failed");
        api.uploadFailure = primary;
        api.deleteBuffersFailure = teardown;
        api.reportProcessedBuffers(1);

        IllegalStateException observed = assertThrows(IllegalStateException.class, backend::update);
        assertSame(primary, observed);
        assertEquals(List.of(teardown), Arrays.asList(observed.getSuppressed()));
        int sourceCount = api.generatedSourceCount;
        int bufferCount = api.generatedBufferCount;
        assertBackendBroken(backend, handle);
        assertEquals(sourceCount, api.generatedSourceCount);
        assertEquals(bufferCount, api.generatedBufferCount);
        backend.close();
    }

    @Test
    void sameThrowablePartialStartCleanupStillMarksBackendBrokenWithoutSelfSuppression() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        IllegalStateException shared = new IllegalStateException("shared start and cleanup failure");
        api.queueFailure = shared;
        api.queueFailureCall = 2;
        api.deleteBuffersFailure = shared;

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertSame(shared, observed);
        assertEquals(0, observed.getSuppressed().length);
        int sources = api.generatedSourceCount;
        int buffers = api.generatedBufferCount;
        assertBackendBroken(backend, new MusicHandle(92L));
        assertEquals(sources, api.generatedSourceCount);
        assertEquals(buffers, api.generatedBufferCount);
        assertCloseContextOnce(backend, api);
    }

    @Test
    void sameThrowableUpdateCleanupStillMarksBackendBrokenWithoutSelfSuppression() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        MusicHandle handle = backend.startMusic(GAIA, true);
        IllegalStateException shared = new IllegalStateException("shared update and cleanup failure");
        api.uploadFailure = shared;
        api.deleteBuffersFailure = shared;
        api.reportProcessedBuffers(1);

        IllegalStateException observed = assertThrows(IllegalStateException.class, backend::update);
        assertSame(shared, observed);
        assertEquals(0, observed.getSuppressed().length);
        int sources = api.generatedSourceCount;
        int buffers = api.generatedBufferCount;
        assertBackendBroken(backend, handle);
        assertEquals(sources, api.generatedSourceCount);
        assertEquals(buffers, api.generatedBufferCount);
        assertCloseContextOnce(backend, api);
    }

    @Test
    void sameThrowableFactoryAndReleaseStillMarksBackendBrokenWithoutSelfSuppression() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        TrackingAssetSource source = new TrackingAssetSource(new ArrayList<>());
        IllegalStateException shared = new IllegalStateException("shared factory and release failure");
        source.releaseFailure = shared;
        OpenAlAudioBackend backend =
                new OpenAlAudioBackend(
                        api,
                        MainThreadGuard.captureCurrentThread(),
                        source,
                        ignored -> {
                            throw shared;
                        });

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertSame(shared, observed);
        assertEquals(0, observed.getSuppressed().length);
        assertEquals(1, source.readCalls);
        assertBackendBroken(backend, new MusicHandle(93L));
        assertEquals(1, source.readCalls);
        assertCloseContextOnce(backend, api);
    }

    @Test
    void closeStopsVoiceThenClosesContextAndDeviceOnceAndRemainsTerminalAfterFailure() {
        RecordingOpenAlApi api = new RecordingOpenAlApi();
        OpenAlAudioBackend backend = backend(api, ignored -> new RecordingDecoder(100_000));
        MusicHandle handle = backend.startMusic(GAIA, true);
        api.calls.clear();
        api.destroyContextFailure = new IllegalStateException("destroy failed");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, backend::close);
        assertSame(api.destroyContextFailure, failure);
        assertEquals(
                List.of(
                        "stopSource:31",
                        "deleteSource:31",
                        "deleteBuffers:[41, 42, 43]",
                        "makeContextCurrent:0",
                        "destroyContext:22",
                        "closeDevice:11"),
                api.calls);
        int callsAfterFailure = api.calls.size();
        assertDoesNotThrow(backend::close);
        assertEquals(callsAfterFailure, api.calls.size());
        assertThrows(IllegalStateException.class, backend::update);
        assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertThrows(IllegalStateException.class, () -> backend.stopMusic(handle));
    }

    private static OpenAlAudioBackend backend(
            RecordingOpenAlApi api, OpenAlAudioBackend.DecoderFactory decoderFactory) {
        return new OpenAlAudioBackend(
                api,
                MainThreadGuard.captureCurrentThread(),
                ignored -> ByteBuffer.allocateDirect(8),
                decoderFactory);
    }

    private static void assertBackendBroken(OpenAlAudioBackend backend, MusicHandle handle) {
        assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertThrows(IllegalStateException.class, backend::update);
        assertThrows(IllegalStateException.class, () -> backend.setMusicGain(handle, 0.5f));
        assertThrows(IllegalStateException.class, () -> backend.isMusicPlaying(handle));
        assertThrows(IllegalStateException.class, () -> backend.stopMusic(handle));
    }

    private static void assertCloseContextOnce(
            OpenAlAudioBackend backend, RecordingOpenAlApi api) {
        api.calls.clear();
        backend.close();
        backend.close();
        assertEquals(
                List.of("makeContextCurrent:0", "destroyContext:22", "closeDevice:11"),
                api.calls);
    }

    static final class RecordingDecoder implements VorbisDecoder {
        private final int channels;
        private int remainingFrames;
        int maximumRequestedFrames;
        int closeCalls;
        private boolean closed;
        RuntimeException closeFailure;

        RecordingDecoder(int frames) {
            this(frames, 2);
        }

        RecordingDecoder(int frames, int channels) {
            this.remainingFrames = frames;
            this.channels = channels;
        }

        @Override
        public int channels() {
            ensureOpen();
            return channels;
        }

        @Override
        public int sampleRate() {
            ensureOpen();
            return 44_100;
        }

        @Override
        public int readFrames(ShortBuffer target, int maximumFrames) {
            ensureOpen();
            maximumRequestedFrames = Math.max(maximumRequestedFrames, maximumFrames);
            int frames = Math.min(remainingFrames, maximumFrames);
            for (int sample = 0; sample < frames * channels; sample++) {
                target.put((short) (sample & 0x7fff));
            }
            remainingFrames -= frames;
            return frames;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closeCalls++;
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("decoder is closed");
            }
        }
    }

    static final class TrackingAssetSource implements AudioAssetSource {
        private final List<String> events;
        int readCalls;
        int releaseCalls;
        RuntimeException releaseFailure;

        TrackingAssetSource(List<String> events) {
            this.events = events;
        }

        @Override
        public ByteBuffer read(ResourceLocation location) {
            readCalls++;
            events.add("read");
            return ByteBuffer.allocateDirect(8);
        }

        @Override
        public void release(ByteBuffer buffer) {
            releaseCalls++;
            events.add("release");
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }

    static class RecordingOpenAlApi implements OpenAlApi {
        final List<String> calls = new ArrayList<>();
        final List<Integer> uploadedBuffers = new ArrayList<>();
        final Deque<Integer> queuedBuffers = new ArrayDeque<>();
        int generatedSource = 31;
        int generatedSourceCount;
        int generatedBufferCount;
        int unqueuedCount;
        int requeuedCount;
        int playCalls;
        int processedBuffers;
        boolean sourcePlaying;
        RuntimeException capabilitiesFailure;
        RuntimeException destroyContextFailure;
        RuntimeException uploadFailure;
        RuntimeException deleteBuffersFailure;
        RuntimeException queueFailure;
        int queueFailureCall;
        int queueCalls;
        boolean forceZeroSource;

        void reportProcessedBuffers(int count) {
            processedBuffers = count;
        }

        @Override
        public long openDefaultDevice() {
            calls.add("openDevice:11");
            return 11L;
        }

        @Override
        public long createContext(long device) {
            calls.add("createContext:" + device);
            return 22L;
        }

        @Override
        public void makeContextCurrent(long context) {
            calls.add("makeContextCurrent:" + context);
        }

        @Override
        public void createCapabilities(long device) {
            calls.add("createCapabilities:" + device);
            if (capabilitiesFailure != null) {
                throw capabilitiesFailure;
            }
        }

        @Override
        public int generateSource() {
            generatedSourceCount++;
            if (forceZeroSource) {
                generatedSource = 0;
                calls.add("generateSource:0");
                return 0;
            }
            generatedSource = 30 + generatedSourceCount;
            calls.add("generateSource:" + generatedSource);
            return generatedSource;
        }

        @Override
        public int[] generateBuffers(int count) {
            generatedBufferCount += count;
            calls.add("generateBuffers:" + count);
            int first = 38 + generatedBufferCount;
            return new int[] {first, first + 1, first + 2};
        }

        @Override
        public void uploadPcm16(
                int buffer, int channels, int sampleRate, ShortBuffer samples) {
            uploadedBuffers.add(buffer);
            calls.add("upload:" + buffer);
            if (uploadFailure != null) {
                throw uploadFailure;
            }
        }

        @Override
        public void queueBuffer(int source, int buffer) {
            queuedBuffers.addLast(buffer);
            queueCalls++;
            if (playCalls > 0) {
                requeuedCount++;
                calls.add("requeue:" + buffer);
            } else {
                calls.add("queue:" + buffer);
            }
            if (queueFailure != null && queueCalls == queueFailureCall) {
                throw queueFailure;
            }
        }

        @Override
        public int processedBufferCount(int source) {
            return processedBuffers;
        }

        @Override
        public int unqueueProcessedBuffer(int source) {
            unqueuedCount++;
            processedBuffers--;
            return queuedBuffers.removeFirst();
        }

        @Override
        public int queuedBufferCount(int source) {
            return queuedBuffers.size();
        }

        @Override
        public boolean isSourcePlaying(int source) {
            return sourcePlaying;
        }

        @Override
        public void playSource(int source) {
            playCalls++;
            sourcePlaying = true;
            calls.add("playSource:" + source);
        }

        @Override
        public void setSourceGain(int source, float gain) {
            calls.add("gain:" + gain);
        }

        @Override
        public void stopSource(int source) {
            sourcePlaying = false;
            queuedBuffers.clear();
            calls.add("stopSource:" + source);
        }

        @Override
        public void deleteSource(int source) {
            calls.add("deleteSource:" + source);
        }

        @Override
        public void deleteBuffers(int[] buffers) {
            calls.add("deleteBuffers:" + Arrays.toString(buffers));
            if (deleteBuffersFailure != null) {
                throw deleteBuffersFailure;
            }
        }

        @Override
        public void destroyContext(long context) {
            calls.add("destroyContext:" + context);
            if (destroyContextFailure != null) {
                throw destroyContextFailure;
            }
        }

        @Override
        public void closeDevice(long device) {
            calls.add("closeDevice:" + device);
        }
    }
}
