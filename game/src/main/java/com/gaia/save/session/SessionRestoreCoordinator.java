package com.gaia.save.session;

import com.gaia.interaction.GameModeManager;
import com.gaia.interaction.GameMode;
import com.gaia.inventory.BodyInventoryRestoreResult;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.overlord.config.GameConfig;
import com.overlord.interaction.api.EntityRef;
import com.overlord.physics.Aabb;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.joml.Vector3f;

/** Owner-thread coordinator for installing one validated save into fresh services. */
public final class SessionRestoreCoordinator {
    private static final Consumer<RestoreStage> NO_STAGE_OBSERVER =
            ignored -> {};

    private final ChunkRepository chunks;
    private final BodyInventoryService inventory;
    private final EntityRef inventoryOwner;
    private final LogicalWorldItemService worldItems;
    private final PlayerController playerController;
    private final BiConsumer<Float, Float> cameraOrientationStager;
    private final GameModeManager gameModes;
    private final PhysicalWorldItemSystem physicalWorldItems;
    private final LongConsumer fixedTickRestorer;
    private final Consumer<List<ChunkKey>> meshReadiness;
    private final Consumer<RestoreStage> stageObserver;
    private final Thread ownerThread;

    public SessionRestoreCoordinator(
            ChunkRepository chunks,
            BodyInventoryService inventory,
            EntityRef inventoryOwner,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            PhysicalWorldItemSystem physicalWorldItems,
            LongConsumer fixedTickRestorer,
            Consumer<List<ChunkKey>> meshReadiness) {
        this(
                chunks,
                inventory,
                inventoryOwner,
                worldItems,
                playerController,
                camera,
                gameModes,
                physicalWorldItems,
                fixedTickRestorer,
                meshReadiness,
                immediateCameraOrientation(camera),
                NO_STAGE_OBSERVER);
    }

    public SessionRestoreCoordinator(
            ChunkRepository chunks,
            BodyInventoryService inventory,
            EntityRef inventoryOwner,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            PhysicalWorldItemSystem physicalWorldItems,
            LongConsumer fixedTickRestorer,
            Consumer<List<ChunkKey>> meshReadiness,
            BiConsumer<Float, Float> cameraOrientationStager) {
        this(
                chunks,
                inventory,
                inventoryOwner,
                worldItems,
                playerController,
                camera,
                gameModes,
                physicalWorldItems,
                fixedTickRestorer,
                meshReadiness,
                cameraOrientationStager,
                NO_STAGE_OBSERVER);
    }

    SessionRestoreCoordinator(
            ChunkRepository chunks,
            BodyInventoryService inventory,
            EntityRef inventoryOwner,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            PhysicalWorldItemSystem physicalWorldItems,
            LongConsumer fixedTickRestorer,
            Consumer<List<ChunkKey>> meshReadiness,
            Consumer<RestoreStage> stageObserver) {
        this(
                chunks,
                inventory,
                inventoryOwner,
                worldItems,
                playerController,
                camera,
                gameModes,
                physicalWorldItems,
                fixedTickRestorer,
                meshReadiness,
                immediateCameraOrientation(camera),
                stageObserver);
    }

    public SessionRestoreCoordinator(
            ChunkRepository chunks,
            BodyInventoryService inventory,
            EntityRef inventoryOwner,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            PhysicalWorldItemSystem physicalWorldItems,
            LongConsumer fixedTickRestorer,
            Consumer<List<ChunkKey>> meshReadiness,
            BiConsumer<Float, Float> cameraOrientationStager,
            Consumer<RestoreStage> stageObserver) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.inventoryOwner =
                Objects.requireNonNull(inventoryOwner, "inventoryOwner");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
        this.playerController =
                Objects.requireNonNull(playerController, "playerController");
        Objects.requireNonNull(camera, "camera");
        this.cameraOrientationStager =
                Objects.requireNonNull(
                        cameraOrientationStager,
                        "cameraOrientationStager");
        this.gameModes = Objects.requireNonNull(gameModes, "gameModes");
        this.physicalWorldItems =
                Objects.requireNonNull(
                        physicalWorldItems, "physicalWorldItems");
        this.fixedTickRestorer =
                Objects.requireNonNull(
                        fixedTickRestorer, "fixedTickRestorer");
        this.meshReadiness =
                Objects.requireNonNull(meshReadiness, "meshReadiness");
        this.stageObserver =
                Objects.requireNonNull(stageObserver, "stageObserver");
        ownerThread = Thread.currentThread();
    }

    public void restore(SaveGameSnapshot snapshot) {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Canonical session restore must run on its owner thread");
        }
        SaveGameSnapshot validated =
                Objects.requireNonNull(snapshot, "snapshot");
        PlayerRestorePlan playerPlan = planPlayerRestore(validated);

        before(RestoreStage.CHUNKS);
        ChunkRepositoryRestoreResult chunksResult =
                chunks.restoreCanonical(validated.chunks());
        requireRestored(
                "chunks", chunksResult.status(),
                ChunkRepositoryRestoreResult.Status.RESTORED);

        before(RestoreStage.INVENTORY);
        BodyInventoryRestoreResult inventoryResult =
                inventory.restoreCanonical(
                        inventoryOwner,
                        validated.inventory().canonicalSnapshot());
        requireRestored(
                "inventory", inventoryResult.status(),
                BodyInventoryRestoreResult.Status.RESTORED);

        before(RestoreStage.WORLD_ITEMS);
        WorldItemRestoreResult worldItemsResult =
                worldItems.restoreCanonical(
                        validated.worldItems().logicalSnapshot());
        requireRestored(
                "world items", worldItemsResult.status(),
                WorldItemRestoreResult.Status.RESTORED);

        before(RestoreStage.PLAYER);
        restorePlayer(playerPlan, validated.fixedTick());

        before(RestoreStage.PROJECTIONS);
        physicalWorldItems.reconcileRestoredCanonicalState(
                validated.fixedTick());

        before(RestoreStage.MESH_READINESS);
        meshReadiness.accept(
                validated.chunks().chunks().stream()
                        .map(ChunkSnapshot::key)
                        .toList());
        cameraOrientationStager.accept(
                playerPlan.yaw(), playerPlan.pitch());
    }

    private void restorePlayer(
            PlayerRestorePlan plan,
            long fixedTick) {
        playerController.restoreCanonical(
                plan.position(),
                plan.velocity(),
                plan.noclip(),
                plan.worldHeight());
        gameModes.setMode(plan.gameMode(), fixedTick);
        fixedTickRestorer.accept(fixedTick);
    }

    private PlayerRestorePlan planPlayerRestore(SaveGameSnapshot snapshot) {
        int radius = snapshot.metadata().chunkRadius();
        PlayerSaveSnapshot player = snapshot.player();
        Vector3f position =
                new Vector3f(
                        finiteFloat(
                                player.feetPositionX(),
                                "feetPositionX"),
                        finiteFloat(
                                player.feetPositionY(),
                                "feetPositionY"),
                        finiteFloat(
                                player.feetPositionZ(),
                                "feetPositionZ"));
        Vector3f velocity =
                new Vector3f(
                        finiteFloat(player.velocityX(), "velocityX"),
                        finiteFloat(player.velocityY(), "velocityY"),
                        finiteFloat(player.velocityZ(), "velocityZ"));
        float yaw = finiteFloat(player.yaw(), "yaw");
        float pitch = finiteFloat(player.pitch(), "pitch");

        double minimumWorldXz =
                -(double) radius * GameConfig.Chunk.SIZE;
        double maximumWorldXz =
                (double) (radius + 1) * GameConfig.Chunk.SIZE;
        Aabb collider = playerController.body().collider();
        requireWithin(
                position.x + collider.minX(),
                position.x + collider.maxX(),
                minimumWorldXz,
                maximumWorldXz,
                "feetPositionX");
        requireWithin(
                position.z + collider.minZ(),
                position.z + collider.maxZ(),
                minimumWorldXz,
                maximumWorldXz,
                "feetPositionZ");
        requireWithin(
                position.y + collider.minY(),
                position.y + collider.maxY(),
                0.0,
                snapshot.chunks().worldHeight(),
                "feetPositionY");

        return new PlayerRestorePlan(
                position,
                velocity,
                yaw,
                pitch,
                player.gameMode(),
                player.noclip(),
                snapshot.chunks().worldHeight());
    }

    private static void requireWithin(
            double minimum,
            double maximum,
            double allowedMinimum,
            double allowedMaximum,
            String field) {
        if (minimum < allowedMinimum || maximum > allowedMaximum) {
            throw new IllegalArgumentException(
                    field + " collider is outside the saved world bounds");
        }
    }

    private void before(RestoreStage stage) {
        stageObserver.accept(stage);
    }

    private static BiConsumer<Float, Float> immediateCameraOrientation(
            Camera camera) {
        Camera sharedCamera = Objects.requireNonNull(camera, "camera");
        return (yaw, pitch) -> {
            sharedCamera.setYaw(yaw);
            sharedCamera.setPitch(pitch);
        };
    }

    private static <T> void requireRestored(
            String section, T actual, T expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "canonical "
                            + section
                            + " restore failed with status "
                            + actual);
        }
    }

    private static float finiteFloat(double value, String field) {
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalArgumentException(
                    field + " must be representable as a finite float");
        }
        if (Double.doubleToRawLongBits(value)
                != Double.doubleToRawLongBits((double) converted)) {
            throw new IllegalArgumentException(
                    field + " must round-trip exactly through its float owner");
        }
        return converted;
    }

    private record PlayerRestorePlan(
            Vector3f position,
            Vector3f velocity,
            float yaw,
            float pitch,
            GameMode gameMode,
            boolean noclip,
            int worldHeight) {}

    public enum RestoreStage {
        CHUNKS,
        INVENTORY,
        WORLD_ITEMS,
        PLAYER,
        PROJECTIONS,
        MESH_READINESS
    }
}
