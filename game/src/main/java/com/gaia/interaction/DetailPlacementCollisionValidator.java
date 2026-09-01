package com.gaia.interaction;

import com.overlord.physics.Aabb;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.Objects;
import java.util.function.Supplier;
import org.joml.Vector3f;

public final class DetailPlacementCollisionValidator {
    private static final float QUARTER = 0.25f;
    private final Vector3f bodyPosition = new Vector3f();
    private final Supplier<SimulationOrigin> simulationOrigin;

    public DetailPlacementCollisionValidator() {
        this(() -> new SimulationOrigin(new ChunkKey(0, 0)));
    }

    public DetailPlacementCollisionValidator(
            Supplier<SimulationOrigin> simulationOrigin) {
        this.simulationOrigin = Objects.requireNonNull(
                simulationOrigin, "simulationOrigin");
    }

    public boolean overlapsPlayer(
            DetailPlacementCandidate candidate,
            PhysicsBody playerBody) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(playerBody, "playerBody");
        playerBody.position(bodyPosition);
        return playerBody.collider().translated(bodyPosition)
                .intersects(localBounds(
                        candidate.parentX(),
                        candidate.parentY(),
                        candidate.parentZ(),
                        candidate.localPosition()));
    }

    public Aabb localBounds(
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition local) {
        Objects.requireNonNull(local, "local");
        ChunkKey key = ChunkKey.fromWorld(parentX, parentZ);
        Vector3f min = Objects.requireNonNull(
                        simulationOrigin.get(), "simulationOrigin value")
                .toLocal(new GlobalPosition(
                        key,
                        ChunkKey.localCoordinate(parentX) + local.x() * QUARTER,
                        parentY + local.y() * QUARTER,
                        ChunkKey.localCoordinate(parentZ) + local.z() * QUARTER));
        return new Aabb(
                min.x,
                min.y,
                min.z,
                min.x + QUARTER,
                min.y + QUARTER,
                min.z + QUARTER);
    }
}
