package com.overlord.audio;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;
import java.util.function.Consumer;

public final class AudioDevice implements AutoCloseable {
    private final MainThreadGuard mainThreadGuard;
    private final AudioBackend backend;
    private AudioBusSettings busSettings = AudioBusSettings.fullVolume();
    private boolean closed;

    private AudioDevice(MainThreadGuard mainThreadGuard, AudioBackend backend) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public static AudioDevice open(
            AudioBackendFactory factory,
            MainThreadGuard mainThreadGuard,
            Consumer<AudioDiagnostic> diagnostics) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        Objects.requireNonNull(diagnostics, "diagnostics");
        mainThreadGuard.assertMainThread("audio device open");

        AudioBackend created;
        try {
            created = factory.create();
        } catch (RuntimeException | LinkageError initializationFailure) {
            created = new SilentAudioBackend();
            try {
                diagnostics.accept(
                        AudioDiagnostic.backendInitializationFailure(initializationFailure));
            } catch (RuntimeException ignoredDiagnosticFailure) {
                // Audio reporting must not cancel the already-established silent fallback.
            }
        }
        return new AudioDevice(
                mainThreadGuard,
                Objects.requireNonNull(created, "audio backend factory returned null"));
    }

    public void applyBusSettings(AudioBusSettings settings) {
        assertOwner("audio bus settings update");
        ensureOpen();
        busSettings = Objects.requireNonNull(settings, "settings");
    }

    public MusicHandle startMusic(ResourceLocation track, boolean loop) {
        assertOwner("music start");
        ensureOpen();
        MusicHandle handle = backend.startMusic(Objects.requireNonNull(track, "track"), loop);
        return Objects.requireNonNull(handle, "audio backend returned null music handle");
    }

    public void setMusicEnvelope(MusicHandle handle, float cueGain, float envelope) {
        assertOwner("music envelope update");
        ensureOpen();
        Objects.requireNonNull(handle, "handle");
        AudioBusSettings.requireGain(envelope, "envelope");
        float effectiveGain = busSettings.effectiveMusicGain(cueGain) * envelope;
        backend.setMusicGain(handle, effectiveGain);
    }

    public boolean isMusicPlaying(MusicHandle handle) {
        assertOwner("music playback query");
        ensureOpen();
        return backend.isMusicPlaying(Objects.requireNonNull(handle, "handle"));
    }

    public void stopMusic(MusicHandle handle) {
        assertOwner("music stop");
        ensureOpen();
        backend.stopMusic(Objects.requireNonNull(handle, "handle"));
    }

    public void update() {
        assertOwner("audio device update");
        ensureOpen();
        backend.update();
    }

    @Override
    public void close() {
        assertOwner("audio device close");
        if (closed) {
            return;
        }
        closed = true;
        backend.close();
    }

    private void assertOwner(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("audio device is closed");
        }
    }
}
