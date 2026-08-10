package com.gaia.settings;

import com.overlord.core.Window;
import com.overlord.renderer.Camera;
import com.overlord.renderer.Renderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Reversibly applies settings that may change during an active product lifetime. */
public final class SettingsApplier {
    private final Consumer<Boolean> vsyncPort;
    private final Consumer<Float> fovPort;
    private final BiConsumer<Float, Boolean> lookPort;
    private final AudioSettingsPort audioPort;

    public SettingsApplier(
            Window window,
            Renderer renderer,
            Camera camera,
            AudioSettingsPort audioPort) {
        this(
                Objects.requireNonNull(window, "window")::setVsync,
                Objects.requireNonNull(renderer, "renderer")::setFovDegrees,
                Objects.requireNonNull(camera, "camera")::setLookSettings,
                audioPort);
    }

    SettingsApplier(
            Consumer<Boolean> vsyncPort,
            Consumer<Float> fovPort,
            BiConsumer<Float, Boolean> lookPort,
            AudioSettingsPort audioPort) {
        this.vsyncPort = Objects.requireNonNull(vsyncPort, "vsyncPort");
        this.fovPort = Objects.requireNonNull(fovPort, "fovPort");
        this.lookPort = Objects.requireNonNull(lookPort, "lookPort");
        this.audioPort = Objects.requireNonNull(audioPort, "audioPort");
    }

    public void apply(SettingsSnapshot previous, SettingsSnapshot next) {
        SettingsSnapshot oldSettings = Objects.requireNonNull(previous, "previous");
        SettingsSnapshot newSettings = Objects.requireNonNull(next, "next");
        List<Runnable> rollbacks = new ArrayList<>(4);
        try {
            applyVsync(oldSettings, newSettings, rollbacks);
            applyFov(oldSettings, newSettings, rollbacks);
            applyLook(oldSettings, newSettings, rollbacks);
            applyAudio(oldSettings, newSettings, rollbacks);
        } catch (RuntimeException | Error failure) {
            rollback(rollbacks, failure);
            throw failure;
        }
    }

    private void applyVsync(
            SettingsSnapshot previous,
            SettingsSnapshot next,
            List<Runnable> rollbacks) {
        if (previous.vsync() == next.vsync()) {
            return;
        }
        vsyncPort.accept(next.vsync());
        rollbacks.add(() -> vsyncPort.accept(previous.vsync()));
    }

    private void applyFov(
            SettingsSnapshot previous,
            SettingsSnapshot next,
            List<Runnable> rollbacks) {
        if (Double.compare(previous.fovDegrees(), next.fovDegrees()) == 0) {
            return;
        }
        fovPort.accept((float) next.fovDegrees());
        rollbacks.add(() -> fovPort.accept((float) previous.fovDegrees()));
    }

    private void applyLook(
            SettingsSnapshot previous,
            SettingsSnapshot next,
            List<Runnable> rollbacks) {
        if (Double.compare(
                        previous.mouseSensitivity(),
                        next.mouseSensitivity())
                        == 0
                && previous.invertY() == next.invertY()) {
            return;
        }
        lookPort.accept(
                (float) next.mouseSensitivity(), next.invertY());
        rollbacks.add(
                () ->
                        lookPort.accept(
                                (float) previous.mouseSensitivity(),
                                previous.invertY()));
    }

    private void applyAudio(
            SettingsSnapshot previous,
            SettingsSnapshot next,
            List<Runnable> rollbacks) {
        if (!audioChanged(previous, next)) {
            return;
        }
        audioPort.apply(
                next.masterVolume(),
                next.musicVolume(),
                next.sfxVolume(),
                next.muteWhenUnfocused());
        rollbacks.add(
                () ->
                        audioPort.apply(
                                previous.masterVolume(),
                                previous.musicVolume(),
                                previous.sfxVolume(),
                                previous.muteWhenUnfocused()));
    }

    private static boolean audioChanged(
            SettingsSnapshot previous, SettingsSnapshot next) {
        return Double.compare(
                                previous.masterVolume(),
                                next.masterVolume())
                        != 0
                || Double.compare(
                                previous.musicVolume(),
                                next.musicVolume())
                        != 0
                || Double.compare(previous.sfxVolume(), next.sfxVolume()) != 0
                || previous.muteWhenUnfocused()
                        != next.muteWhenUnfocused();
    }

    private static void rollback(
            List<Runnable> rollbacks, Throwable primaryFailure) {
        for (int index = rollbacks.size() - 1; index >= 0; index--) {
            try {
                rollbacks.get(index).run();
            } catch (RuntimeException | Error rollbackFailure) {
                if (rollbackFailure != primaryFailure) {
                    primaryFailure.addSuppressed(rollbackFailure);
                }
            }
        }
    }
}
