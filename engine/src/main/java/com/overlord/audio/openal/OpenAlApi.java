package com.overlord.audio.openal;

import java.nio.ShortBuffer;

public interface OpenAlApi {
    long openDefaultDevice();

    long createContext(long device);

    void makeContextCurrent(long context);

    void createCapabilities(long device);

    int generateSource();

    int[] generateBuffers(int count);

    void uploadPcm16(int buffer, int channels, int sampleRate, ShortBuffer samples);

    void queueBuffer(int source, int buffer);

    int processedBufferCount(int source);

    int unqueueProcessedBuffer(int source);

    int queuedBufferCount(int source);

    boolean isSourcePlaying(int source);

    void playSource(int source);

    void setSourceGain(int source, float gain);

    void stopSource(int source);

    void deleteSource(int source);

    void deleteBuffers(int[] buffers);

    void destroyContext(long context);

    void closeDevice(long device);
}
