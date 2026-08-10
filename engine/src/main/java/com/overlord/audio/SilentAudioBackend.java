package com.overlord.audio;

import com.overlord.assets.ResourceLocation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Objects;

public final class SilentAudioBackend implements AudioBackend {
    private final MusicHandle.Domain handleDomain = MusicHandle.newDomain();
    private final Set<MusicHandle> activeHandles =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private long nextHandle = 1L;
    private boolean closed;

    @Override
    public MusicHandle startMusic(ResourceLocation track, boolean loop) {
        ensureOpen();
        Objects.requireNonNull(track, "track");
        if (nextHandle <= 0L) {
            throw new IllegalStateException("silent music handle space exhausted");
        }
        MusicHandle handle = handleDomain.issue(nextHandle++);
        activeHandles.add(handle);
        return handle;
    }

    @Override
    public void setMusicGain(MusicHandle handle, float gain) {
        ensureOpen();
        requireActive(handle);
        AudioBusSettings.requireGain(gain, "gain");
    }

    @Override
    public boolean isMusicPlaying(MusicHandle handle) {
        ensureOpen();
        requireOwned(handle);
        return activeHandles.contains(handle);
    }

    @Override
    public void stopMusic(MusicHandle handle) {
        ensureOpen();
        activeHandles.remove(requireActive(handle));
    }

    @Override
    public void update() {
        ensureOpen();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        activeHandles.clear();
    }

    int activeHandleCount() {
        return activeHandles.size();
    }

    private MusicHandle requireOwned(MusicHandle handle) {
        return handleDomain.requireOwned(handle);
    }

    private MusicHandle requireActive(MusicHandle handle) {
        requireOwned(handle);
        if (!activeHandles.contains(handle)) {
            throw new IllegalArgumentException("inactive music handle: " + handle.value());
        }
        return handle;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("silent audio backend is closed");
        }
    }
}
