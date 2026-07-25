package com.gaia.world;

import java.util.Objects;

public final class WorldLoadException extends RuntimeException {
    private final WorldLoadFailure failure;

    public WorldLoadException(WorldLoadFailure failure) {
        super(
                "World loading failed: "
                        + Objects.requireNonNull(failure, "failure").code(),
                failure.cause());
        this.failure = failure;
    }

    public WorldLoadFailure failure() {
        return failure;
    }
}
