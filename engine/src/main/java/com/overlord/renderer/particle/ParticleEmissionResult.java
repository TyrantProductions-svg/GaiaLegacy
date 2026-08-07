package com.overlord.renderer.particle;

import java.util.Objects;

public record ParticleEmissionResult(
        Status status,
        int admittedCount,
        int evictedCount) {
    public ParticleEmissionResult {
        status = Objects.requireNonNull(status, "status");
        if (admittedCount < 0 || evictedCount < 0 || evictedCount > admittedCount) {
            throw new IllegalArgumentException("particle result counts are inconsistent");
        }
        if ((status == Status.ADMITTED) != (admittedCount > 0)) {
            throw new IllegalArgumentException(
                    "only ADMITTED may contain admitted particles");
        }
    }

    public enum Status {
        ADMITTED,
        REJECTED_REQUEST_CAP,
        REJECTED_LOW_CAP,
        REJECTED_TOTAL_CAP
    }
}
