package com.overlord.interaction.testing;

import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3fc;

public final class StubBlockRaycastService
        implements BlockRaycastService {
    private Optional<BlockHitResult> result;
    private int calls;

    public StubBlockRaycastService(
            Optional<BlockHitResult> result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public Optional<BlockHitResult> raycast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        calls++;
        return result;
    }

    public int calls() {
        return calls;
    }
}
