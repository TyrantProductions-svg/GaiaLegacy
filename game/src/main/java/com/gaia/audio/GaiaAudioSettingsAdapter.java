package com.gaia.audio;

import com.gaia.settings.AudioSettingsPort;
import com.overlord.audio.AudioBusSettings;
import com.overlord.audio.AudioDevice;
import java.util.Objects;

/** Applies settings snapshots without exposing audio runtime objects to settings UI code. */
public final class GaiaAudioSettingsAdapter implements AudioSettingsPort {
    private final AudioDevice device;
    private final MusicManager musicManager;
    private AudioBusSettings committedBusSettings = AudioBusSettings.fullVolume();
    private boolean committedMuteWhenUnfocused = true;

    public GaiaAudioSettingsAdapter(AudioDevice device, MusicManager musicManager) {
        this.device = Objects.requireNonNull(device, "device");
        this.musicManager = Objects.requireNonNull(musicManager, "musicManager");
    }

    @Override
    public void apply(
            double masterVolume,
            double musicVolume,
            double sfxVolume,
            boolean muteWhenUnfocused) {
        AudioBusSettings busSettings = new AudioBusSettings(
                requireVolume(masterVolume, "masterVolume"),
                requireVolume(musicVolume, "musicVolume"),
                requireVolume(sfxVolume, "sfxVolume"));
        AudioBusSettings previousBusSettings = committedBusSettings;
        try {
            device.applyBusSettings(busSettings);
            musicManager.refreshGain();
            musicManager.setMuteWhenUnfocused(muteWhenUnfocused);
        } catch (RuntimeException | Error failure) {
            compensate(previousBusSettings, failure);
            throw failure;
        }
        committedBusSettings = busSettings;
        committedMuteWhenUnfocused = muteWhenUnfocused;
    }

    private void compensate(AudioBusSettings previousBusSettings, Throwable primaryFailure) {
        tryCompensation(() -> device.applyBusSettings(previousBusSettings), primaryFailure);
        tryCompensation(musicManager::refreshGain, primaryFailure);
    }

    private static void tryCompensation(Runnable compensation, Throwable primaryFailure) {
        try {
            compensation.run();
        } catch (RuntimeException | Error compensationFailure) {
            if (compensationFailure != primaryFailure) {
                primaryFailure.addSuppressed(compensationFailure);
            }
        }
    }

    private static float requireVolume(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
        return (float) value;
    }
}
