package com.overlord.audio;

public record AudioBusSettings(float masterGain, float musicGain, float sfxGain) {
    public AudioBusSettings {
        requireGain(masterGain, "masterGain");
        requireGain(musicGain, "musicGain");
        requireGain(sfxGain, "sfxGain");
    }

    public static AudioBusSettings fullVolume() {
        return new AudioBusSettings(1.0f, 1.0f, 1.0f);
    }

    public float effectiveMusicGain(float cueGain) {
        requireGain(cueGain, "cueGain");
        return masterGain * musicGain * cueGain;
    }

    public float effectiveSfxGain(float cueGain) {
        requireGain(cueGain, "cueGain");
        return masterGain * sfxGain * cueGain;
    }

    static float requireGain(float gain, String name) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
        return gain;
    }
}
