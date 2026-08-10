package com.overlord.audio;

import com.overlord.assets.ResourceLocation;

public interface AudioBackend extends AutoCloseable {
    MusicHandle startMusic(ResourceLocation track, boolean loop);

    void setMusicGain(MusicHandle handle, float gain);

    boolean isMusicPlaying(MusicHandle handle);

    void stopMusic(MusicHandle handle);

    void update();

    @Override
    void close();
}
