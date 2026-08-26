package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.inventory.InventoryDropLocation;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.world.streaming.ChunkStreamingPipeline;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemDropKinematics;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.PlayerController;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.RenderOrigin;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ChunkStreamingCanonicalGlobalIntegrationTest {
    private static final ChunkKey REBASED = new ChunkKey(1, -1);
    private static final GlobalPosition GLOBAL_FEET =
            new GlobalPosition(REBASED, 2.25, 2.0, 2.5);

    @Test
    void productionCaptureUsesCanonicalGlobalFeetAfterCommittedRebase()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(snapshotWithInventory());
        try {
            driveToReady(session);
            Object runtime = runtime(session);
            rebasePlayer(runtime, GLOBAL_FEET);

            PlayerSaveSnapshot player = session.captureSave().snapshot()
                    .orElseThrow().player();

            assertEquals(REBASED.worldOriginX() + GLOBAL_FEET.localX(),
                    player.feetPositionX());
            assertEquals(GLOBAL_FEET.y(), player.feetPositionY());
            assertEquals(REBASED.worldOriginZ() + GLOBAL_FEET.localZ(),
                    player.feetPositionZ());
        } finally {
            session.close();
        }
    }

    @Test
    void qDropAfterRebasePublishesCanonicalGlobalPositionAndLocalVelocity()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(snapshotWithInventory());
        try {
            driveToReady(session);
            Object runtime = runtime(session);
            rebasePlayer(runtime, GLOBAL_FEET);
            PlayerController dropPlayer = field(
                    runtime, "playerController", PlayerController.class);
            dropPlayer.setNoclip(true);
            dropPlayer.body().setLinearVelocity(new Vector3f());
            Object environment = field(runtime, "environment", Object.class);
            Method cameraMethod = environment.getClass().getDeclaredMethod("camera");
            cameraMethod.setAccessible(true);
            Camera camera = (Camera) cameraMethod.invoke(environment);
            Vector3f forward = camera.getForward(new Vector3f());
            Vector3f right = camera.getRight(new Vector3f());
            InventoryDropLocation expectedLocal = WorldItemDropKinematics.qDrop(
                    new Vector3f(
                            (float) GLOBAL_FEET.localX(),
                            (float) (GLOBAL_FEET.y() + GameConfig.Player.EYE_HEIGHT),
                            (float) GLOBAL_FEET.localZ()),
                    forward,
                    right,
                    0L);
            GlobalPosition expectedGlobal = new SimulationOrigin(REBASED).toGlobal(
                    new Vector3f(
                            (float) expectedLocal.positionX(),
                            (float) expectedLocal.positionY(),
                            (float) expectedLocal.positionZ()));
            InventoryDropLocation expected = new InventoryDropLocation(
                    expectedGlobal.chunkKey().worldOriginX()
                            + expectedGlobal.localX(),
                    expectedGlobal.y(),
                    expectedGlobal.chunkKey().worldOriginZ()
                            + expectedGlobal.localZ(),
                    expectedLocal.velocityX(), expectedLocal.velocityY(),
                    expectedLocal.velocityZ());
            Method runFixedStep = runtime.getClass().getDeclaredMethod(
                    "runFixedStep", InputSnapshot.class);
            runFixedStep.setAccessible(true);
            runFixedStep.invoke(runtime, new InputSnapshot(
                    Set.of(GameConfig.Input.KEY_DROP),
                    Set.of(GameConfig.Input.KEY_DROP)));
            LogicalWorldItemService logical = field(
                    runtime, "worldItems", LogicalWorldItemService.class);
            assertEquals(1, logical.snapshots().size(),
                    "the real Q owner path must commit one canonical item");
            var item = logical.snapshots().get(0);
            assertEquals(expected.positionX(), item.positionX());
            assertEquals(expected.positionY(), item.positionY());
            assertEquals(expected.positionZ(), item.positionZ());
            assertEquals(expected.velocityX(), item.velocityX());
            assertEquals(expected.velocityY(), item.velocityY());
            assertEquals(expected.velocityZ(), item.velocityZ());
        } finally {
            session.close();
        }
    }

    @Test
    void streamedLiveItemUnloadCheckpointUsesTheSameCanonicalGlobalPlayer()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(snapshotWithInventory());
        try {
            driveToReady(session);
            Object runtime = runtime(session);
            rebasePlayer(runtime, GLOBAL_FEET);
            ChunkKey unloadKey = new ChunkKey(0, 0);
            MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
            LogicalWorldItemService logical =
                    ChunkStreamingSessionIntegrationTest.pagedLogicalService(
                            guard,
                            new SaveIdentity(UUID.fromString(
                                    snapshotWithInventory().metadata()
                                            .saveGameId().value())));
            var spawned = logical.spawn(new WorldItemSpawnRequest(
                    new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                    0.5, 2.0, 0.5,
                    0.0, 0.0, 0.0,
                    Optional.empty(), 0L)).item().orElseThrow();
            PhysicsWorld physics = new PhysicsWorld(
                    new CollisionWorld(
                            new com.overlord.voxel.World(),
                            BlockCollisionShapeResolver.fullCubesForNonAir()),
                    new Vector3f());
            PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                    logical, physics, guard, new WorldItemPhysicsConfig(0.50f, 2));
            physical.reconcileRestoredCanonicalState(0L);
            ChunkRepository repository = new ChunkRepository(
                    8, new com.overlord.voxel.ChunkDirtyTracker());
            repository.generate(unloadKey, ignored -> {});
            var repositoryPreparation = repository.prepareStreamingUnload(unloadKey);
            ChunkStreamingPipeline.UnloadLifecycle lifecycle = productionUnloadLifecycle(
                    snapshotWithInventory().metadata(), logical, physical,
                    () -> session.captureSave().snapshot().orElseThrow());

            var prepared = lifecycle.prepare(repositoryPreparation);
            PlayerSaveSnapshot checkpoint = prepared.plan().sessionCheckpoint()
                    .orElseThrow().player();

            assertEquals(REBASED.worldOriginX() + GLOBAL_FEET.localX(),
                    checkpoint.feetPositionX());
            assertEquals(GLOBAL_FEET.y(), checkpoint.feetPositionY());
            assertEquals(REBASED.worldOriginZ() + GLOBAL_FEET.localZ(),
                    checkpoint.feetPositionZ());
            lifecycle.cancel(prepared);
            repository.cancelStreamingUnload(
                    repositoryPreparation.ticket().orElseThrow());
            assertEquals(spawned.id(), logical.snapshot(spawned.id()).orElseThrow().id());
            physical.close();
            logical.close();
        } finally {
            session.close();
        }
    }

    @Test
    void distantRestartChoosesSafeOriginAndRoundTripsExactGlobalFeetBeforeReady() {
        ChunkKey distant = new ChunkKey(100_000_000, -100_000_000);
        GlobalPosition global = new GlobalPosition(distant, 2.25, 2.0, 2.5);
        SaveGameSnapshot snapshot = shiftedSnapshot(distant, global);
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(snapshot);
        try {
            driveToReady(session);
            PlayerSaveSnapshot restored = session.captureSave().snapshot()
                    .orElseThrow().player();
            assertEquals(distant.worldOriginX() + global.localX(),
                    restored.feetPositionX());
            assertEquals(global.y(), restored.feetPositionY());
            assertEquals(distant.worldOriginZ() + global.localZ(),
                    restored.feetPositionZ());
        } finally {
            session.close();
        }
    }

    private static void rebasePlayer(Object runtime, GlobalPosition global)
            throws Exception {
        PlayerController player = field(runtime, "playerController", PlayerController.class);
        player.body().teleport(new Vector3f(
                (float) (global.chunkKey().worldOriginX() + global.localX()),
                (float) global.y(),
                (float) (global.chunkKey().worldOriginZ() + global.localZ())));
        var coordinator = field(runtime, "originCoordinator",
                com.gaia.session.streaming.SimulationOriginCoordinator.class);
        assertEquals(true, coordinator.rebase(
                new SimulationOrigin(global.chunkKey()),
                new RenderOrigin(global.chunkKey())));
    }

    private static SaveGameSnapshot snapshotWithInventory() {
        SaveGameSnapshot base = ChunkStreamingSessionIntegrationTest.productionSnapshot();
        return new SaveGameSnapshot(
                base.metadata(), base.fixedTick(), base.chunks(), base.player(),
                new InventorySaveSnapshot(
                        base.player().owner(),
                        Map.of(BodySlot.LEFT_HAND,
                                new ItemStack(ResourceLocation.parse("gaia:dirt"), 2)),
                        BodySlot.LEFT_HAND, false, 0L),
                base.worldItems());
    }

    private static SaveGameSnapshot shiftedSnapshot(
            ChunkKey center, GlobalPosition player) {
        SaveGameSnapshot base = snapshotWithInventory();
        List<ChunkSnapshot> chunks = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(center.x() + x, center.z() + z),
                        1L,
                        base.metadata().worldHeight(),
                        new byte[16 * base.metadata().worldHeight() * 16]));
            }
        }
        return new SaveGameSnapshot(
                base.metadata(),
                base.fixedTick(),
                new ChunkRepositorySnapshot(
                        base.metadata().worldHeight(), 1L, chunks),
                new PlayerSaveSnapshot(
                        base.player().owner(),
                        center.worldOriginX() + player.localX(),
                        player.y(),
                        center.worldOriginZ() + player.localZ(),
                        0.25, 0.0, -0.5,
                        base.player().yaw(), base.player().pitch(),
                        base.player().gameMode(), base.player().noclip()),
                base.inventory(), base.worldItems());
    }

    private static Object runtime(GameSession session) throws Exception {
        return rawField(session, "runtime");
    }

    private static ChunkStreamingPipeline.UnloadLifecycle productionUnloadLifecycle(
            SaveGameSnapshot.StaticMetadata metadata,
            LogicalWorldItemService logical,
            PhysicalWorldItemSystem physical,
            java.util.function.Supplier<SaveGameSnapshot> capture) throws Exception {
        Class<?> type = Class.forName(
                "com.gaia.session.GameSessionFactory$ProductionUnloadLifecycle");
        var constructor = type.getDeclaredConstructor(
                SaveGameSnapshot.StaticMetadata.class,
                LogicalWorldItemService.class,
                PhysicalWorldItemSystem.class,
                GameSessionFactory.UnloadSessionCapture.class);
        constructor.setAccessible(true);
        GameSessionFactory.UnloadSessionCapture sessionCapture = () -> {
            SaveGameSnapshot snapshot = capture.get();
            return new GameSessionFactory.UnloadSessionState(
                    snapshot.player(), snapshot.inventory());
        };
        return (ChunkStreamingPipeline.UnloadLifecycle) constructor.newInstance(
                metadata, logical, physical, sessionCapture);
    }

    private static <T> T field(Object target, String name, Class<T> type)
            throws Exception {
        return type.cast(rawField(target, name));
    }

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void driveToReady(GameSession session) {
        for (int poll = 0;
                poll < 100_000 && session.state() == GameSessionState.LOADING;
                poll++) {
            session.pollLoad();
            if (session.state() == GameSessionState.LOADING) {
                Thread.yield();
            }
        }
        assertEquals(GameSessionState.READY, session.state());
    }
}
