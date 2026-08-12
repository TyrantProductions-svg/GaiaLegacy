package com.gaia.worlditem;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkRepository;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import java.util.Objects;

/** Test-only access to the real Chunk-aware projection composition. */
public final class PhysicalWorldItemRestoreTestFixture {
    private PhysicalWorldItemRestoreTestFixture() {}

    public static PhysicalWorldItemSystem create(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            ChunkRepository chunks,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            PhysicalWorldItemSystem.ProjectionFactory projectionFactory) {
        return new PhysicalWorldItemSystem(
                Objects.requireNonNull(runtimeAccess, "runtimeAccess"),
                Objects.requireNonNull(physicsWorld, "physicsWorld"),
                Objects.requireNonNull(chunks, "chunks"),
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard"),
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(projectionFactory, "projectionFactory"),
                ignored -> {});
    }

    public static PhysicsBody productionBody(
            WorldItemPhysicalSnapshot snapshot,
            WorldItemPhysicsConfig config) {
        return PhysicalWorldItemSystem.createDefaultBody(snapshot, config);
    }
}
