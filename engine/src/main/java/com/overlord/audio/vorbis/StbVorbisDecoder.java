package com.overlord.audio.vorbis;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Objects;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class StbVorbisDecoder implements VorbisDecoder {
    private static final int MAXIMUM_DIAGNOSTIC_LENGTH = 256;

    private final ByteBuffer compressed;
    private final long decoder;
    private final int channels;
    private final int sampleRate;
    private boolean closed;

    private StbVorbisDecoder(
            ByteBuffer compressed, long decoder, int channels, int sampleRate) {
        this.compressed = compressed;
        this.decoder = decoder;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    /** Opens an Ogg Vorbis stream without taking ownership of the caller buffer. */
    public static StbVorbisDecoder open(ByteBuffer compressed) {
        Objects.requireNonNull(compressed, "compressed");
        if (!compressed.isDirect()) {
            throw new IllegalArgumentException("compressed OGG buffer must be direct");
        }
        if (!compressed.hasRemaining()) {
            throw failure("VORBIS_OPEN_FAILED empty compressed OGG");
        }

        ByteBuffer ownedCompressed = copyCompressed(compressed);
        long decoder = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(ownedCompressed, error, null);
            if (decoder == 0L) {
                throw failure("VORBIS_OPEN_FAILED code=" + error.get(0));
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(decoder, info);
            int channels = info.channels();
            int sampleRate = info.sample_rate();
            if ((channels != 1 && channels != 2) || sampleRate <= 0) {
                throw failure(
                        "VORBIS_FORMAT_UNSUPPORTED channels="
                                + channels
                                + " sampleRate="
                                + sampleRate);
            }
            return new StbVorbisDecoder(ownedCompressed, decoder, channels, sampleRate);
        } catch (RuntimeException | Error failure) {
            if (decoder != 0L) {
                STBVorbis.stb_vorbis_close(decoder);
            }
            MemoryUtil.memFree(ownedCompressed);
            throw failure;
        }
    }

    static ByteBuffer copyCompressed(ByteBuffer caller) {
        Objects.requireNonNull(caller, "caller");
        if (!caller.isDirect()) {
            throw new IllegalArgumentException("compressed OGG buffer must be direct");
        }
        ByteBuffer source = caller.duplicate();
        ByteBuffer owned = MemoryUtil.memAlloc(source.remaining());
        owned.put(source).flip();
        return owned;
    }

    @Override
    public int channels() {
        ensureOpen();
        return channels;
    }

    @Override
    public int sampleRate() {
        ensureOpen();
        return sampleRate;
    }

    @Override
    public int readFrames(ShortBuffer target, int maximumFrames) {
        ensureOpen();
        Objects.requireNonNull(target, "target");
        if (!target.isDirect()) {
            throw new IllegalArgumentException("PCM target must be direct");
        }
        if (maximumFrames < 0) {
            throw new IllegalArgumentException("maximumFrames must be non-negative");
        }
        int maximumSamples;
        try {
            maximumSamples = Math.multiplyExact(maximumFrames, channels);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("maximumFrames exceeds PCM bounds", overflow);
        }
        if (target.remaining() < maximumSamples) {
            throw new IllegalArgumentException("PCM target cannot hold requested frames");
        }
        if (maximumFrames == 0) {
            return 0;
        }

        ShortBuffer bounded = target.slice();
        bounded.limit(maximumSamples);
        int frames =
                STBVorbis.stb_vorbis_get_samples_short_interleaved(
                        decoder, channels, bounded);
        if (frames < 0 || frames > maximumFrames) {
            throw failure("VORBIS_READ_INVALID frames=" + frames);
        }
        target.position(target.position() + frames * channels);
        return frames;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        STBVorbis.stb_vorbis_close(decoder);
        MemoryUtil.memFree(compressed);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vorbis decoder is closed");
        }
    }

    private static DecodeException failure(String message) {
        String bounded =
                message.length() <= MAXIMUM_DIAGNOSTIC_LENGTH
                        ? message
                        : message.substring(0, MAXIMUM_DIAGNOSTIC_LENGTH);
        return new DecodeException(bounded);
    }
}
