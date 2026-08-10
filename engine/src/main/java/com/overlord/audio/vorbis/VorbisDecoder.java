package com.overlord.audio.vorbis;

import java.nio.ShortBuffer;

public interface VorbisDecoder extends AutoCloseable {
    int channels();

    int sampleRate();

    int readFrames(ShortBuffer target, int maximumFrames);

    @Override
    void close();

    final class DecodeException extends RuntimeException {
        public DecodeException(String message) {
            super(message);
        }

        public DecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
