package com.overlord.voxel;

import java.util.Objects;
import java.util.Optional;

public record ParentCellObservationResult(
        ChunkAvailability status,
        Optional<ParentCellObservation> observation,
        Optional<ChunkKey> unavailableKey) {
    public ParentCellObservationResult {
        status = Objects.requireNonNull(status, "status");
        observation = Objects.requireNonNull(observation, "observation");
        unavailableKey =
                Objects.requireNonNull(unavailableKey, "unavailableKey");
        if (status == ChunkAvailability.AVAILABLE
                && unavailableKey.isPresent()) {
            throw new IllegalArgumentException(
                    "available observation cannot have an unavailable key");
        }
        if (status != ChunkAvailability.AVAILABLE
                && (observation.isPresent() || unavailableKey.isEmpty())) {
            throw new IllegalArgumentException(
                    "unavailable observation requires only a canonical key");
        }
    }

    public static ParentCellObservationResult available(
            ParentCellObservation observation) {
        return new ParentCellObservationResult(
                ChunkAvailability.AVAILABLE,
                Optional.of(Objects.requireNonNull(
                        observation, "observation")),
                Optional.empty());
    }

    public static ParentCellObservationResult availableEmpty() {
        return new ParentCellObservationResult(
                ChunkAvailability.AVAILABLE,
                Optional.empty(),
                Optional.empty());
    }

    public static ParentCellObservationResult unavailable(
            ChunkAvailability status, ChunkKey key) {
        if (status == ChunkAvailability.AVAILABLE) {
            throw new IllegalArgumentException(
                    "status must be unavailable");
        }
        return new ParentCellObservationResult(
                status,
                Optional.empty(),
                Optional.of(ChunkCoordinatePolicy.requireSafe(key)));
    }
}
