package com.overlord.audio.openal;

import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioAssetSource;
import com.overlord.audio.AudioBackend;
import com.overlord.audio.MusicHandle;
import com.overlord.audio.vorbis.VorbisDecoder;
import com.overlord.core.thread.MainThreadGuard;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.system.MemoryUtil;

public final class OpenAlAudioBackend implements AudioBackend {
    static final int FRAMES_PER_BUFFER = 4_096;
    private static final int STREAMING_BUFFER_COUNT = 3;

    private final OpenAlApi api;
    private final MainThreadGuard owner;
    private final AudioAssetSource assets;
    private final DecoderFactory decoders;
    private final MusicHandle.Domain handles = MusicHandle.newDomain();
    private final long device;
    private final long context;

    private long nextHandle = 1L;
    private Voice activeVoice;
    private boolean broken;
    private boolean closed;

    public OpenAlAudioBackend(
            OpenAlApi api,
            MainThreadGuard owner,
            AudioAssetSource assets,
            DecoderFactory decoders) {
        this.api = Objects.requireNonNull(api, "api");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.decoders = Objects.requireNonNull(decoders, "decoders");
        owner.assertMainThread("OpenAL backend initialization");

        long openedDevice = 0L;
        long openedContext = 0L;
        boolean current = false;
        try {
            openedDevice = api.openDefaultDevice();
            if (openedDevice == 0L) {
                throw new IllegalStateException("OpenAL default device is unavailable");
            }
            openedContext = api.createContext(openedDevice);
            if (openedContext == 0L) {
                throw new IllegalStateException("OpenAL context creation failed");
            }
            api.makeContextCurrent(openedContext);
            current = true;
            api.createCapabilities(openedDevice);
        } catch (RuntimeException | Error failure) {
            Throwable result = failure;
            if (current) {
                result = cleanup(result, () -> api.makeContextCurrent(0L));
            }
            if (openedContext != 0L) {
                long failedContext = openedContext;
                result = cleanup(result, () -> api.destroyContext(failedContext));
            }
            if (openedDevice != 0L) {
                long failedDevice = openedDevice;
                result = cleanup(result, () -> api.closeDevice(failedDevice));
            }
            throwUnchecked(result);
            throw new AssertionError("unreachable");
        }
        device = openedDevice;
        context = openedContext;
    }

    @Override
    public MusicHandle startMusic(ResourceLocation track, boolean loop) {
        assertOwner("music start");
        ensureOpen();
        Objects.requireNonNull(track, "track");
        if (activeVoice != null) {
            throw new IllegalStateException("only one music voice may be active");
        }

        VorbisDecoder decoder = openDecoder(track);
        int source = 0;
        int[] buffers = null;
        Voice pending = null;
        try {
            validateDecoder(decoder);
            source = api.generateSource();
            if (source == 0) {
                throw new IllegalStateException("OpenAL returned an invalid source");
            }
            buffers = api.generateBuffers(STREAMING_BUFFER_COUNT);
            validateBuffers(buffers);
            pending = new Voice(track, loop, decoder, source, buffers);
            int queued = 0;
            for (int buffer : buffers) {
                if (!fillAndQueue(pending, buffer)) {
                    break;
                }
                queued++;
            }
            if (queued == 0) {
                throw new IllegalStateException("music stream contains no PCM frames");
            }
            api.playSource(source);
            MusicHandle handle = handles.issue(nextHandle++);
            pending.handle = handle;
            activeVoice = pending;
            return handle;
        } catch (RuntimeException | Error failure) {
            CleanupOutcome outcome = CleanupOutcome.withPrimary(failure);
            if (source != 0) {
                int failedSource = source;
                outcome = cleanup(outcome, () -> api.stopSource(failedSource));
                outcome = cleanup(outcome, () -> api.deleteSource(failedSource));
            }
            if (buffers != null) {
                int[] failedBuffers = buffers;
                outcome = cleanup(outcome, () -> api.deleteBuffers(failedBuffers));
            }
            VorbisDecoder failedDecoder = pending == null ? decoder : pending.decoder;
            outcome = cleanup(outcome, failedDecoder::close);
            if (outcome.cleanupFailed()) {
                broken = true;
            }
            throwUnchecked(outcome.failure());
            throw new AssertionError("unreachable");
        }
    }

    @Override
    public void setMusicGain(MusicHandle handle, float gain) {
        assertOwner("music gain update");
        ensureOpen();
        requireActiveMutation(handle);
        requireGain(gain);
        api.setSourceGain(activeVoice.source, gain);
    }

    @Override
    public boolean isMusicPlaying(MusicHandle handle) {
        assertOwner("music playback query");
        ensureOpen();
        MusicHandle owned = handles.requireOwned(handle);
        return activeVoice != null && activeVoice.handle == owned;
    }

    @Override
    public void stopMusic(MusicHandle handle) {
        assertOwner("music stop");
        ensureOpen();
        requireActiveMutation(handle);
        Voice stopped = activeVoice;
        activeVoice = null;
        CleanupOutcome outcome = releaseVoice(stopped, null);
        if (outcome.failure() != null) {
            broken = true;
            throwUnchecked(outcome.failure());
        }
    }

    @Override
    public void update() {
        assertOwner("audio update");
        ensureOpen();
        Voice voice = activeVoice;
        if (voice == null) {
            return;
        }
        try {
            int processed = api.processedBufferCount(voice.source);
            for (int index = 0; index < processed; index++) {
                int buffer = api.unqueueProcessedBuffer(voice.source);
                if (fillAndQueue(voice, buffer)) {
                    continue;
                }
            }
            int queued = api.queuedBufferCount(voice.source);
            if (queued == 0) {
                activeVoice = null;
                CleanupOutcome outcome = releaseVoice(voice, null);
                if (outcome.failure() != null) {
                    broken = true;
                    throwUnchecked(outcome.failure());
                }
            } else if (!api.isSourcePlaying(voice.source)) {
                api.playSource(voice.source);
            }
        } catch (RuntimeException | Error failure) {
            if (activeVoice == voice) {
                activeVoice = null;
                CleanupOutcome outcome = releaseVoice(voice, failure);
                if (outcome.cleanupFailed()) {
                    broken = true;
                }
                throwUnchecked(outcome.failure());
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        assertOwner("audio backend close");
        if (closed) {
            return;
        }
        closed = true;

        Throwable failure = null;
        Voice voice = activeVoice;
        activeVoice = null;
        if (voice != null) {
            failure = releaseVoice(voice, failure).failure();
        }
        failure = cleanup(failure, () -> api.makeContextCurrent(0L));
        failure = cleanup(failure, () -> api.destroyContext(context));
        failure = cleanup(failure, () -> api.closeDevice(device));
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private VorbisDecoder openDecoder(ResourceLocation track) {
        ByteBuffer compressed =
                Objects.requireNonNull(
                        assets.read(track), "audio asset source returned null buffer");
        VorbisDecoder decoder;
        try {
            decoder =
                    Objects.requireNonNull(
                            decoders.open(compressed), "decoder factory returned null");
        } catch (RuntimeException | Error factoryFailure) {
            CleanupOutcome outcome =
                    cleanup(
                            CleanupOutcome.withPrimary(factoryFailure),
                            () -> assets.release(compressed));
            if (outcome.cleanupFailed()) {
                broken = true;
            }
            throwUnchecked(outcome.failure());
            throw new AssertionError("unreachable");
        }
        try {
            assets.release(compressed);
            return decoder;
        } catch (RuntimeException | Error releaseFailure) {
            Throwable result = cleanup(releaseFailure, decoder::close);
            broken = true;
            throwUnchecked(result);
            throw new AssertionError("unreachable");
        }
    }

    private boolean fillAndQueue(Voice voice, int buffer) {
        int channels = voice.decoder.channels();
        ShortBuffer pcm = MemoryUtil.memAllocShort(FRAMES_PER_BUFFER * channels);
        try {
            int frames = voice.decoder.readFrames(pcm, FRAMES_PER_BUFFER);
            if (frames == 0 && voice.loop) {
                voice.decoder.close();
                voice.decoder = openDecoder(voice.track);
                validateDecoder(voice.decoder);
                channels = voice.decoder.channels();
                if (pcm.capacity() < FRAMES_PER_BUFFER * channels) {
                    MemoryUtil.memFree(pcm);
                    pcm = MemoryUtil.memAllocShort(FRAMES_PER_BUFFER * channels);
                } else {
                    pcm.clear();
                }
                frames = voice.decoder.readFrames(pcm, FRAMES_PER_BUFFER);
                if (frames == 0) {
                    throw new IllegalStateException("looping music stream contains no PCM frames");
                }
            }
            if (frames == 0) {
                return false;
            }
            pcm.flip();
            api.uploadPcm16(buffer, channels, voice.decoder.sampleRate(), pcm);
            api.queueBuffer(voice.source, buffer);
            return true;
        } finally {
            MemoryUtil.memFree(pcm);
        }
    }

    private CleanupOutcome releaseVoice(Voice voice, Throwable primary) {
        CleanupOutcome outcome =
                cleanup(CleanupOutcome.withPrimary(primary), () -> api.stopSource(voice.source));
        outcome = cleanup(outcome, () -> api.deleteSource(voice.source));
        outcome = cleanup(outcome, () -> api.deleteBuffers(voice.buffers));
        return cleanup(outcome, voice.decoder::close);
    }

    private void requireActiveMutation(MusicHandle handle) {
        MusicHandle owned = handles.requireOwned(handle);
        if (activeVoice == null || activeVoice.handle != owned) {
            throw new IllegalArgumentException("music handle is not active: " + owned.value());
        }
    }

    private static void validateDecoder(VorbisDecoder decoder) {
        int channels = decoder.channels();
        if ((channels != 1 && channels != 2) || decoder.sampleRate() <= 0) {
            throw new IllegalArgumentException("decoder must expose mono/stereo positive-rate PCM");
        }
    }

    private static void validateBuffers(int[] buffers) {
        if (buffers == null || buffers.length != STREAMING_BUFFER_COUNT) {
            throw new IllegalStateException("OpenAL must create exactly three streaming buffers");
        }
        Set<Integer> unique = new HashSet<>();
        for (int buffer : buffers) {
            if (buffer == 0 || !unique.add(buffer)) {
                throw new IllegalStateException("OpenAL returned invalid streaming buffers");
            }
        }
    }

    private static void requireGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("music gain must be finite and within [0, 1]");
        }
    }

    private void assertOwner(String operation) {
        owner.assertMainThread(operation);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OpenAL backend is closed");
        }
        if (broken) {
            throw new IllegalStateException("OpenAL backend is broken");
        }
    }

    private static Throwable cleanup(Throwable primary, Runnable operation) {
        try {
            operation.run();
            return primary;
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) {
                return cleanupFailure;
            }
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
            return primary;
        }
    }

    private static CleanupOutcome cleanup(CleanupOutcome outcome, Runnable operation) {
        try {
            operation.run();
            return outcome;
        } catch (RuntimeException | Error cleanupFailure) {
            Throwable primary = outcome.failure();
            if (primary == null) {
                return new CleanupOutcome(cleanupFailure, true);
            }
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
            return new CleanupOutcome(primary, true);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    @FunctionalInterface
    public interface DecoderFactory {
        VorbisDecoder open(ByteBuffer compressed);
    }

    private record CleanupOutcome(Throwable failure, boolean cleanupFailed) {
        private static CleanupOutcome withPrimary(Throwable primary) {
            return new CleanupOutcome(primary, false);
        }
    }

    private static final class Voice {
        private final ResourceLocation track;
        private final boolean loop;
        private final int source;
        private final int[] buffers;
        private VorbisDecoder decoder;
        private MusicHandle handle;

        private Voice(
                ResourceLocation track,
                boolean loop,
                VorbisDecoder decoder,
                int source,
                int[] buffers) {
            this.track = track;
            this.loop = loop;
            this.decoder = decoder;
            this.source = source;
            this.buffers = buffers.clone();
        }
    }
}
