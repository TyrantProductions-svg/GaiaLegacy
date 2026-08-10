package com.gaia.audio;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.Optional;

/** Immutable measurement of current music intent and presentation state. */
public record MusicManagerSnapshot(
        MusicRoute route,
        Optional<ResourceLocation> desiredTrack,
        Optional<ResourceLocation> activeTrack,
        double envelope,
        double targetEnvelope,
        boolean focused,
        boolean muteWhenUnfocused) {
    public MusicManagerSnapshot {
        route = Objects.requireNonNull(route, "route");
        desiredTrack = Objects.requireNonNull(desiredTrack, "desiredTrack");
        activeTrack = Objects.requireNonNull(activeTrack, "activeTrack");
        requireEnvelope(envelope, "envelope");
        requireEnvelope(targetEnvelope, "targetEnvelope");
    }

    private static void requireEnvelope(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
    }
}
