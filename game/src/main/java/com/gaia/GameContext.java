package com.gaia;

import com.gaia.inventory.BodyInventoryInputController;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.InventoryDebugCommands;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.feedback.InteractionBlockState;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoader;
import com.overlord.core.Engine;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.time.FixedStepClock;
import com.overlord.core.time.FrameClock;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.PlayerController;
import com.overlord.interaction.api.EntityRef;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public record GameContext(
        Engine engine,
        InputManager inputManager,
        PlayerManager playerManager,
        PhysicsWorld physicsWorld,
        CollisionWorld collisionWorld,
        PlayerController playerController,
        BlockRaycast blockRaycast,
        FrameClock frameClock,
        FixedStepClock fixedStepClock,
        ChunkMeshManager chunkMeshes,
        WorldLoader worldLoader,
        CompletableFuture<WorldLoadResult> worldLoad,
        EntityRef inventoryOwner,
        BodyInventoryService inventoryService,
        BodyInventoryInputController inventoryInput,
        InventoryDebugCommands inventoryDebugCommands,
        boolean inventoryDebugShortcuts,
        BlockInteractionController blockInteraction,
        LogicalWorldItemService worldItems,
        InteractionFeedbackCoordinator interactionFeedback,
        InteractionBlockState interactionBlockState,
        ShutdownCoordinator shutdownCoordinator,
        RenderMetricsConsoleReporter renderMetricsReporter) {
    public GameContext {
        engine = Objects.requireNonNull(engine, "engine");
        renderMetricsReporter = Objects.requireNonNull(renderMetricsReporter, "renderMetricsReporter");
        inputManager = Objects.requireNonNull(inputManager, "inputManager");
        playerManager = Objects.requireNonNull(playerManager, "playerManager");
        physicsWorld = Objects.requireNonNull(physicsWorld, "physicsWorld");
        collisionWorld =
                Objects.requireNonNull(collisionWorld, "collisionWorld");
        playerController =
                Objects.requireNonNull(playerController, "playerController");
        blockRaycast = Objects.requireNonNull(blockRaycast, "blockRaycast");
        frameClock = Objects.requireNonNull(frameClock, "frameClock");
        fixedStepClock = Objects.requireNonNull(fixedStepClock, "fixedStepClock");
        chunkMeshes = Objects.requireNonNull(chunkMeshes, "chunkMeshes");
        worldLoader = Objects.requireNonNull(worldLoader, "worldLoader");
        worldLoad = Objects.requireNonNull(worldLoad, "worldLoad");
        inventoryOwner = Objects.requireNonNull(inventoryOwner, "inventoryOwner");
        inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        inventoryInput = Objects.requireNonNull(inventoryInput, "inventoryInput");
        inventoryDebugCommands = Objects.requireNonNull(
                inventoryDebugCommands, "inventoryDebugCommands");
        blockInteraction = Objects.requireNonNull(
                blockInteraction, "blockInteraction");
        worldItems = Objects.requireNonNull(worldItems, "worldItems");
        interactionFeedback = Objects.requireNonNull(interactionFeedback, "interactionFeedback");
        interactionBlockState = Objects.requireNonNull(interactionBlockState, "interactionBlockState");
        shutdownCoordinator =
                Objects.requireNonNull(shutdownCoordinator, "shutdownCoordinator");
    }
}
