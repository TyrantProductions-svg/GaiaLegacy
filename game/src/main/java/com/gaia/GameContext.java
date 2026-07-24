package com.gaia;

import com.gaia.world.WorldLoadResult;
import com.overlord.core.Engine;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.time.FixedStepClock;
import com.overlord.core.time.FrameClock;
import com.overlord.physics.PhysicsManager;
import com.overlord.voxel.ChunkMeshManager;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public record GameContext(
        Engine engine,
        InputManager inputManager,
        PlayerManager playerManager,
        PhysicsManager physicsManager,
        FrameClock frameClock,
        FixedStepClock fixedStepClock,
        ChunkMeshManager chunkMeshes,
        CompletableFuture<WorldLoadResult> worldLoad,
        ShutdownCoordinator shutdownCoordinator) {
    public GameContext {
        engine = Objects.requireNonNull(engine, "engine");
        inputManager = Objects.requireNonNull(inputManager, "inputManager");
        playerManager = Objects.requireNonNull(playerManager, "playerManager");
        physicsManager = Objects.requireNonNull(physicsManager, "physicsManager");
        frameClock = Objects.requireNonNull(frameClock, "frameClock");
        fixedStepClock = Objects.requireNonNull(fixedStepClock, "fixedStepClock");
        chunkMeshes = Objects.requireNonNull(chunkMeshes, "chunkMeshes");
        worldLoad = Objects.requireNonNull(worldLoad, "worldLoad");
        shutdownCoordinator =
                Objects.requireNonNull(shutdownCoordinator, "shutdownCoordinator");
    }
}
