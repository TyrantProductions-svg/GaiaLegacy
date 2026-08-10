package com.gaia.audio;

import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.AudioDiagnostic;
import com.overlord.audio.MusicHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Owns one product music voice and deterministic presentation envelopes. */
public final class MusicManager implements AutoCloseable {
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;
    private static final double STARTUP_FADE_SECONDS = 2.0;
    private static final double ROUTE_FADE_SECONDS = 0.35;
    private static final double FOCUS_FADE_SECONDS = 0.20;
    private static final double NORMAL_ROUTE_GAIN = 1.0;
    private static final double DUCKED_ROUTE_GAIN = 0.70;
    private static final float CUE_GAIN = 1.0f;

    private final AudioDevice device;
    private final GaiaMusicCatalog catalog;
    private final Consumer<AudioDiagnostic> diagnostics;
    private final Thread ownerThread;

    private MusicRoute route = MusicRoute.STOPPED;
    private ResourceLocation desiredTrack;
    private ResourceLocation failedDesiredTrack;
    private ResourceLocation activeTrack;
    private MusicHandle activeHandle;
    private boolean focused = true;
    private boolean muteWhenUnfocused = true;
    private final Envelope startupEnvelope = new Envelope();
    private final Envelope routeEnvelope = new Envelope();
    private final Envelope focusEnvelope = new Envelope();
    private boolean closed;

    public MusicManager(
            AudioDevice device,
            GaiaMusicCatalog catalog,
            Consumer<AudioDiagnostic> diagnostics) {
        this.device = Objects.requireNonNull(device, "device");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        ownerThread = Thread.currentThread();
    }

    public void requestRoute(MusicRoute requestedRoute) {
        ensureUsable();
        MusicRoute nextRoute = Objects.requireNonNull(requestedRoute, "requestedRoute");
        if (nextRoute == MusicRoute.STOPPED) {
            stopForRoute();
            return;
        }
        if (route == nextRoute) {
            return;
        }

        route = nextRoute;
        if (desiredTrack == null) {
            desiredTrack = catalog.gaia();
        }
        if (activeHandle == null) {
            startDesiredTrack();
        } else {
            routeEnvelope.transitionTo(routeTarget(), ROUTE_FADE_SECONDS);
        }
    }

    public void requestTrack(ResourceLocation requestedTrack) {
        ensureUsable();
        ResourceLocation nextTrack = Objects.requireNonNull(requestedTrack, "requestedTrack");
        if (!catalog.contains(nextTrack)) {
            throw new IllegalArgumentException("track is not in the Gaia music catalog: " + nextTrack);
        }
        if (nextTrack.equals(desiredTrack)) {
            return;
        }

        desiredTrack = nextTrack;
        failedDesiredTrack = null;
        if (!stopActiveVoiceForReplacement()) {
            return;
        }
        if (route != MusicRoute.STOPPED) {
            startDesiredTrack();
        }
    }

    public void setFocused(boolean focused) {
        ensureUsable();
        if (this.focused == focused) {
            return;
        }
        this.focused = focused;
        if (activeHandle != null) {
            focusEnvelope.transitionTo(focusTarget(), FOCUS_FADE_SECONDS);
        }
    }

    public void setMuteWhenUnfocused(boolean muteWhenUnfocused) {
        ensureUsable();
        if (this.muteWhenUnfocused == muteWhenUnfocused) {
            return;
        }
        this.muteWhenUnfocused = muteWhenUnfocused;
        if (activeHandle != null) {
            focusEnvelope.transitionTo(focusTarget(), FOCUS_FADE_SECONDS);
        }
    }

    public void update(double deltaSeconds) {
        ensureUsable();
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        double boundedDelta = Math.min(deltaSeconds, MAX_FRAME_DELTA_SECONDS);
        if (activeHandle == null && Objects.equals(desiredTrack, failedDesiredTrack)) {
            return;
        }
        try {
            device.update();
        } catch (RuntimeException failure) {
            if (activeHandle == null) {
                throw failure;
            }
            handlePlaybackFailure(failure, false);
            return;
        }

        if (activeHandle != null) {
            boolean playing;
            try {
                playing = device.isMusicPlaying(activeHandle);
            } catch (RuntimeException failure) {
                handlePlaybackFailure(failure, false);
                return;
            }
            if (!playing) {
                clearActiveVoice();
                startDesiredTrack();
            }
        }
        if (activeHandle == null) {
            return;
        }

        startupEnvelope.advance(boundedDelta);
        routeEnvelope.advance(boundedDelta);
        focusEnvelope.advance(boundedDelta);
        try {
            applyEnvelope();
        } catch (RuntimeException failure) {
            handlePlaybackFailure(failure, true);
        }
    }

    public MusicManagerSnapshot snapshot() {
        ensureUsable();
        return new MusicManagerSnapshot(
                route,
                Optional.ofNullable(desiredTrack),
                Optional.ofNullable(activeTrack),
                combinedEnvelope(),
                combinedTargetEnvelope(),
                focused,
                muteWhenUnfocused);
    }

    void refreshGain() {
        ensureUsable();
        if (activeHandle != null) {
            applyEnvelope();
        }
    }

    @Override
    public void close() {
        assertOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        MusicHandle handle = activeHandle;
        clearActiveVoice();
        desiredTrack = null;
        failedDesiredTrack = null;
        route = MusicRoute.STOPPED;
        if (handle != null) {
            stopBestEffort(handle);
        }
    }

    private void stopForRoute() {
        MusicHandle handle = activeHandle;
        clearActiveVoice();
        desiredTrack = null;
        failedDesiredTrack = null;
        route = MusicRoute.STOPPED;
        if (handle != null) {
            stopBestEffort(handle);
        }
    }

    private boolean stopActiveVoiceForReplacement() {
        MusicHandle handle = activeHandle;
        if (handle == null) {
            clearActiveVoice();
            return true;
        }
        clearActiveVoice();
        try {
            device.stopMusic(handle);
            return true;
        } catch (RuntimeException failure) {
            failedDesiredTrack = desiredTrack;
            reportFailure("MUSIC_STOP_FAILED", failure);
            return false;
        }
    }

    private void stopBestEffort(MusicHandle handle) {
        try {
            device.stopMusic(handle);
        } catch (RuntimeException failure) {
            reportFailure("MUSIC_STOP_FAILED", failure);
        }
    }

    private void startDesiredTrack() {
        if (desiredTrack == null
                || route == MusicRoute.STOPPED
                || desiredTrack.equals(failedDesiredTrack)) {
            return;
        }
        MusicHandle handle;
        try {
            handle = device.startMusic(desiredTrack, false);
        } catch (RuntimeException failure) {
            failedDesiredTrack = desiredTrack;
            clearActiveVoice();
            reportTrackFailure(failure);
            return;
        }
        activeHandle = handle;
        activeTrack = desiredTrack;
        beginStartupTransition();
        try {
            applyEnvelope();
        } catch (RuntimeException failure) {
            handlePlaybackFailure(failure, true);
        }
    }

    private void reportTrackFailure(RuntimeException failure) {
        reportFailure("MUSIC_TRACK_START_FAILED", failure);
    }

    private void handlePlaybackFailure(RuntimeException failure, boolean stopPossiblyLiveVoice) {
        MusicHandle failedHandle = activeHandle;
        ResourceLocation failedTrack = desiredTrack;
        if (stopPossiblyLiveVoice && failedHandle != null) {
            try {
                device.stopMusic(failedHandle);
            } catch (RuntimeException stopFailure) {
                if (stopFailure != failure) {
                    failure.addSuppressed(stopFailure);
                }
            }
        }
        clearActiveVoice();
        failedDesiredTrack = failedTrack;
        reportFailure("MUSIC_PLAYBACK_FAILED", failure);
    }

    private void reportFailure(String code, RuntimeException failure) {
        String failureType = failure.getClass().getSimpleName();
        if (failureType.isBlank()) {
            failureType = "RuntimeException";
        }
        String detail = failure.getMessage();
        String message = failureType + (detail == null || detail.isBlank() ? "" : ": " + detail);
        if (message.length() > AudioDiagnostic.MAX_MESSAGE_LENGTH) {
            message = message.substring(0, AudioDiagnostic.MAX_MESSAGE_LENGTH);
        }
        try {
            diagnostics.accept(new AudioDiagnostic(code, message));
        } catch (RuntimeException ignoredDiagnosticFailure) {
            // The state transition has already established coherent silence.
        }
    }

    private void beginStartupTransition() {
        startupEnvelope.reset(0.0);
        startupEnvelope.transitionTo(1.0, STARTUP_FADE_SECONDS);
        routeEnvelope.reset(routeTarget());
        focusEnvelope.reset(focusTarget());
    }

    private double routeTarget() {
        if (route == MusicRoute.STOPPED) {
            return 0.0;
        }
        return route.isDucked() ? DUCKED_ROUTE_GAIN : NORMAL_ROUTE_GAIN;
    }

    private double focusTarget() {
        return muteWhenUnfocused && !focused ? 0.0 : 1.0;
    }

    private double combinedEnvelope() {
        return startupEnvelope.current() * routeEnvelope.current() * focusEnvelope.current();
    }

    private double combinedTargetEnvelope() {
        return startupEnvelope.target() * routeEnvelope.target() * focusEnvelope.target();
    }

    private void applyEnvelope() {
        device.setMusicEnvelope(activeHandle, CUE_GAIN, (float) combinedEnvelope());
    }

    private void clearActiveVoice() {
        activeHandle = null;
        activeTrack = null;
        startupEnvelope.reset(0.0);
        routeEnvelope.reset(0.0);
        focusEnvelope.reset(0.0);
    }

    private void ensureUsable() {
        assertOwnerThread();
        if (closed) {
            throw new IllegalStateException("music manager is closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("music manager operation must run on its owner thread");
        }
    }

    private static final class Envelope {
        private double current;
        private double start;
        private double target;
        private double elapsed;
        private double duration;

        private double current() {
            return current;
        }

        private double target() {
            return target;
        }

        private void reset(double value) {
            current = value;
            start = value;
            target = value;
            elapsed = 0.0;
            duration = 0.0;
        }

        private void transitionTo(double nextTarget, double nextDuration) {
            if (Double.compare(target, nextTarget) == 0) {
                return;
            }
            start = current;
            target = nextTarget;
            elapsed = 0.0;
            duration = nextDuration;
            if (Double.compare(current, target) == 0) {
                start = target;
                elapsed = duration;
            }
        }

        private void advance(double deltaSeconds) {
            if (Double.compare(current, target) == 0) {
                return;
            }
            elapsed = Math.min(duration, elapsed + deltaSeconds);
            if (elapsed >= duration) {
                current = target;
                return;
            }
            double progress = elapsed / duration;
            current = start + (target - start) * progress;
        }
    }
}
