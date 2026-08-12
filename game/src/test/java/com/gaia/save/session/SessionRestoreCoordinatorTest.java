package com.gaia.save.session;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.PhysicalWorldItemRestoreTestFixture;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.time.Instant;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SessionRestoreCoordinatorTest {
    private static final int WORLD_HEIGHT = 32;
    private static final long FIXED_TICK = 37L;
    private static final EntityRef OWNER = new EntityRef(0);
    private static final ResourceLocation DIRT_ID =
            ResourceLocation.parse("gaia:dirt");
    private static final ItemStack DIRT_FIVE =
            new ItemStack(DIRT_ID, 5);
    private static final ChunkKey CHUNK_KEY = new ChunkKey(0, 0);
    private static final SaveGameSnapshot SNAPSHOT = snapshot();

    @Test
    void restoresExactCanonicalStateInApprovedOrderBeforeMeshPublication() {
        RestoreFixture fixture = new RestoreFixture(null);

        fixture.coordinator().restore(SNAPSHOT);

        assertAll(
                () -> assertEquals(SNAPSHOT.chunks(), fixture.chunks().canonicalSnapshot()),
                () ->
                        assertEquals(
                                SNAPSHOT.inventory().canonicalSnapshot(),
                                fixture.inventory().canonicalSnapshot(OWNER)),
                () ->
                        assertEquals(
                                SNAPSHOT.worldItems().logicalSnapshot(),
                                fixture.worldItems().canonicalSnapshot()),
                () ->
                        assertEquals(
                                new Vector3f(2.5f, 8.0f, 3.5f),
                                fixture.playerBody().position(new Vector3f())),
                () ->
                        assertEquals(
                                new Vector3f(0.25f, -0.5f, 1.5f),
                                fixture.playerBody().linearVelocity(new Vector3f())),
                () -> assertTrue(fixture.playerController().isNoclip()),
                () -> assertEquals(540.5f, fixture.camera().getYaw()),
                () -> assertEquals(-18.25f, fixture.camera().getPitch()),
                () -> assertEquals(GameMode.SURVIVAL, fixture.gameModes().mode()),
                () -> assertEquals(FIXED_TICK, fixture.restoredTick().get()),
                () -> assertEquals(List.of(CHUNK_KEY), fixture.meshChunks().get()),
                () -> assertEquals(1, fixture.physicsWorld().bodies().size()),
                () ->
                        assertEquals(
                                new WorldItemId(7),
                                fixture.physicalWorldItems()
                                        .presentationSnapshots().get(0).id()),
                () ->
                        assertEquals(
                                List.of(
                                        SessionRestoreCoordinator.RestoreStage.CHUNKS,
                                        SessionRestoreCoordinator.RestoreStage.INVENTORY,
                                        SessionRestoreCoordinator.RestoreStage.WORLD_ITEMS,
                                        SessionRestoreCoordinator.RestoreStage.PLAYER,
                                        SessionRestoreCoordinator.RestoreStage.PROJECTIONS,
                                        SessionRestoreCoordinator.RestoreStage.MESH_READINESS),
                                fixture.stages()));

        fixture.physicalWorldItems().close();
    }

    @Test
    void restoresValidMaximumFixedTickExactlyWithoutNarrowing() {
        SaveGameSnapshot maximumTick =
                new SaveGameSnapshot(
                        SNAPSHOT.metadata(),
                        Long.MAX_VALUE,
                        SNAPSHOT.chunks(),
                        SNAPSHOT.player(),
                        SNAPSHOT.inventory(),
                        new WorldItemsSaveSnapshot(
                                Long.MAX_VALUE,
                                List.of(),
                                0L,
                                false));
        RestoreFixture fixture = new RestoreFixture(null);

        fixture.coordinator().restore(maximumTick);

        assertEquals(Long.MAX_VALUE, fixture.restoredTick().get());
        fixture.physicalWorldItems().close();
    }

    @ParameterizedTest(name = "restore failure before {0} never publishes mesh readiness")
    @EnumSource(SessionRestoreCoordinator.RestoreStage.class)
    void failureAtEveryStageStopsBeforeMeshReadinessPublication(
            SessionRestoreCoordinator.RestoreStage failedStage) {
        RestoreFixture fixture = new RestoreFixture(failedStage);

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> fixture.coordinator().restore(SNAPSHOT));

        assertSame(fixture.injectedFailure(), thrown);
        assertEquals(
                Arrays.stream(SessionRestoreCoordinator.RestoreStage.values())
                        .limit(failedStage.ordinal() + 1L)
                        .toList(),
                fixture.stages());
        assertTrue(fixture.meshChunks().get() == null);
        fixture.physicalWorldItems().close();
    }

    @Test
    void playerCanonicalRestorePrevalidatesEveryValueBeforeMutation() {
        World world = new World();
        PhysicsBody body = playerBody();
        PlayerController controller = playerController(world, body);
        assertTrue(controller.setNoclip(true));
        body.teleport(new Vector3f(1.0f, 2.0f, 3.0f));
        body.setLinearVelocity(new Vector3f(4.0f, 5.0f, 6.0f));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.restoreCanonical(
                                new Vector3f(Float.NaN, 20.0f, 30.0f),
                                new Vector3f(7.0f, 8.0f, 9.0f),
                                false,
                                WORLD_HEIGHT));
        assertControllerState(
                controller,
                body,
                new Vector3f(1.0f, 2.0f, 3.0f),
                new Vector3f(4.0f, 5.0f, 6.0f),
                true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.restoreCanonical(
                                new Vector3f(10.0f, 20.0f, 30.0f),
                                new Vector3f(7.0f, Float.POSITIVE_INFINITY, 9.0f),
                                false,
                                WORLD_HEIGHT));
        assertControllerState(
                controller,
                body,
                new Vector3f(1.0f, 2.0f, 3.0f),
                new Vector3f(4.0f, 5.0f, 6.0f),
                true);

        controller.restoreCanonical(
                new Vector3f(10.0f, 20.0f, 30.0f),
                new Vector3f(7.0f, 8.0f, 9.0f),
                false,
                WORLD_HEIGHT);
        assertControllerState(
                controller,
                body,
                new Vector3f(10.0f, 20.0f, 30.0f),
                new Vector3f(7.0f, 8.0f, 9.0f),
                false);
    }

    @Test
    void unboundedThreeArgumentPlayerRestoreIsNotPublicPersistenceAuthority() {
        try {
            Method unbounded =
                    PlayerController.class.getDeclaredMethod(
                            "restoreCanonical",
                            Vector3fc.class,
                            Vector3fc.class,
                            boolean.class);
            assertFalse(Modifier.isPublic(unbounded.getModifiers()));
        } catch (NoSuchMethodException absent) {
            // Preferred closed surface: persistence has no unbounded overload.
        }
    }

    @Test
    void boundedPlayerRestoreCannotBypassSavedHeightOrSolidRecoveryPolicy() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks =
                new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
        assertEquals(
                com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED,
                chunks.restoreCanonical(solidChunks()).status());
        PhysicsBody body = playerBody();
        PlayerController controller = playerController(new World(chunks), body);
        body.teleport(new Vector3f(20.0f, 8.0f, 20.0f));
        body.setLinearVelocity(new Vector3f(4.0f, 5.0f, 6.0f));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.restoreCanonical(
                                new Vector3f(5.5f, WORLD_HEIGHT, 5.5f),
                                new Vector3f(0.25f, -0.5f, 1.5f),
                                false,
                                WORLD_HEIGHT));
        assertControllerState(
                controller,
                body,
                new Vector3f(20.0f, 8.0f, 20.0f),
                new Vector3f(4.0f, 5.0f, 6.0f),
                false);

        assertThrows(
                IllegalStateException.class,
                () ->
                        controller.restoreCanonical(
                                new Vector3f(5.5f, 6.0f, 5.5f),
                                new Vector3f(0.25f, -0.5f, 1.5f),
                                false,
                                WORLD_HEIGHT));
        assertControllerState(
                controller,
                body,
                new Vector3f(20.0f, 8.0f, 20.0f),
                new Vector3f(4.0f, 5.0f, 6.0f),
                false);
    }

    @Test
    void playerWorldHeightAndSavedRadiusBoundsRejectBeforeAnyRestoreMutation() {
        double outsideRadius =
                Math.multiplyExact(
                        SNAPSHOT.metadata().chunkRadius() + 1,
                        GameConfig.Chunk.SIZE);
        List<PlayerSaveSnapshot> invalidPlayers =
                List.of(
                        playerSnapshot(
                                2.5,
                                SNAPSHOT.chunks().worldHeight(),
                                3.5,
                                false),
                        playerSnapshot(
                                outsideRadius,
                                8.0,
                                3.5,
                                false));

        for (PlayerSaveSnapshot invalidPlayer : invalidPlayers) {
            RestoreFixture fixture = new RestoreFixture(null);
            var chunksBefore = fixture.chunks().canonicalSnapshot();
            var inventoryBefore = fixture.inventory().canonicalSnapshot(OWNER);
            var worldItemsBefore = fixture.worldItems().canonicalSnapshot();
            Vector3f playerBefore =
                    fixture.playerBody().position(new Vector3f());
            float yawBefore = fixture.camera().getYaw();
            float pitchBefore = fixture.camera().getPitch();

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            fixture.coordinator()
                                    .restore(withPlayer(SNAPSHOT, invalidPlayer)));

            assertAll(
                    () -> assertEquals(chunksBefore, fixture.chunks().canonicalSnapshot()),
                    () ->
                            assertEquals(
                                    inventoryBefore,
                                    fixture.inventory().canonicalSnapshot(OWNER)),
                    () ->
                            assertEquals(
                                    worldItemsBefore,
                                    fixture.worldItems().canonicalSnapshot()),
                    () ->
                            assertEquals(
                                    playerBefore,
                                    fixture.playerBody().position(new Vector3f())),
                    () -> assertEquals(yawBefore, fixture.camera().getYaw()),
                    () -> assertEquals(pitchBefore, fixture.camera().getPitch()),
                    () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                    () -> assertTrue(fixture.meshChunks().get() == null));
            fixture.physicalWorldItems().close();
        }
    }

    @Test
    void noclipRestoreInsideSolidPreservesExactPositionAndVelocityWithoutRecovery() {
        ChunkRepositorySnapshot chunks = chunksWithOneSolidBlock();
        PlayerSaveSnapshot noclipPlayer =
                playerSnapshot(5.5, 6.0, 5.5, true);
        SaveGameSnapshot snapshot =
                snapshotWith(
                        chunks,
                        noclipPlayer,
                        new WorldItemsSaveSnapshot(
                                FIXED_TICK, List.of(), 0L, false));
        RestoreFixture fixture = new RestoreFixture(null);

        fixture.coordinator().restore(snapshot);

        assertAll(
                () ->
                        assertEquals(
                                new Vector3f(5.5f, 6.0f, 5.5f),
                                fixture.playerBody().position(new Vector3f())),
                () ->
                        assertEquals(
                                new Vector3f(0.25f, -0.5f, 1.5f),
                                fixture.playerBody().linearVelocity(new Vector3f())),
                () -> assertTrue(fixture.playerController().isNoclip()));
        fixture.physicalWorldItems().close();
    }

    @Test
    void nonNoclipDepenetrationPreservesSavedVelocity() {
        ChunkRepositorySnapshot chunks = chunksWithOneSolidBlock();
        PlayerSaveSnapshot collidingPlayer =
                playerSnapshot(5.5, 6.0, 5.5, false);
        SaveGameSnapshot snapshot =
                snapshotWith(
                        chunks,
                        collidingPlayer,
                        new WorldItemsSaveSnapshot(
                                FIXED_TICK, List.of(), 0L, false));
        RestoreFixture fixture = new RestoreFixture(null);

        fixture.coordinator().restore(snapshot);

        assertAll(
                () -> assertFalse(fixture.playerController().overlapsSolid()),
                () ->
                        assertEquals(
                                new Vector3f(0.25f, -0.5f, 1.5f),
                                fixture.playerBody().linearVelocity(new Vector3f())),
                () -> assertFalse(fixture.playerController().isNoclip()));
        fixture.physicalWorldItems().close();
    }

    @Test
    void failureAfterPlayerStageRollsBackSharedCameraAndPublishesNoMeshReadiness() {
        Camera sharedCamera = new Camera();
        sharedCamera.setYaw(-90.0f);
        sharedCamera.setPitch(0.0f);
        AtomicReference<List<ChunkKey>> meshChunks = new AtomicReference<>();
        IllegalStateException meshFailure =
                new IllegalStateException("injected real mesh-readiness failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("injected real restore cleanup failure");
        AtomicInteger closeCalls = new AtomicInteger();
        RestoreFixture fixture =
                new RestoreFixture(
                        null,
                        sharedCamera,
                        keys -> {
                            meshChunks.set(List.copyOf(keys));
                            throw meshFailure;
                        });

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GameSessionPersistenceTestFixture
                                        .restoreThroughFactory(
                                                SNAPSHOT,
                                                () -> fixture.coordinator().restore(SNAPSHOT),
                                                () -> {
                                                    closeCalls.incrementAndGet();
                                                    fixture.physicalWorldItems().close();
                                                    throw cleanupFailure;
                                                }));

        assertAll(
                () -> assertSame(meshFailure, thrown),
                () -> assertEquals(-90.0f, sharedCamera.getYaw()),
                () -> assertEquals(0.0f, sharedCamera.getPitch()),
                () -> assertEquals(List.of(CHUNK_KEY), meshChunks.get()),
                () -> assertEquals(1, closeCalls.get()),
                () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                () ->
                        assertEquals(
                                SNAPSHOT.inventory().canonicalSnapshot(),
                                fixture.inventory().canonicalSnapshot(OWNER)),
                () ->
                        assertEquals(
                                SNAPSHOT.worldItems().logicalSnapshot(),
                                fixture.worldItems().canonicalSnapshot()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(cleanupFailure, thrown.getSuppressed()[0]));

        RestoreFixture retry =
                new RestoreFixture(null, sharedCamera, ignored -> {});
        retry.coordinator().restore(SNAPSHOT);
        assertAll(
                () -> assertEquals(SNAPSHOT.player().yaw(), sharedCamera.getYaw()),
                () -> assertEquals(SNAPSHOT.player().pitch(), sharedCamera.getPitch()));
        retry.physicalWorldItems().close();
    }

    @Test
    void unrecoverableNonNoclipPlayerFailsBeforeProjectionOrMeshPublication() {
        SaveGameSnapshot snapshot =
                snapshotWith(
                        solidChunks(),
                        playerSnapshot(5.5, 6.0, 5.5, false),
                        new WorldItemsSaveSnapshot(
                                FIXED_TICK, List.of(), 0L, false));
        RestoreFixture fixture = new RestoreFixture(null);
        IllegalStateException cleanupFailure =
                new IllegalStateException(
                        "injected unrecoverable-player cleanup failure");
        AtomicInteger closeCalls = new AtomicInteger();

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GameSessionPersistenceTestFixture
                                        .restoreThroughFactory(
                                                snapshot,
                                                () ->
                                                        fixture.coordinator()
                                                                .restore(snapshot),
                                                () -> {
                                                    closeCalls.incrementAndGet();
                                                    fixture.physicalWorldItems().close();
                                                    throw cleanupFailure;
                                                }));

        assertAll(
                () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                () -> assertTrue(fixture.meshChunks().get() == null),
                () -> assertEquals(1, closeCalls.get()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(cleanupFailure, thrown.getSuppressed()[0]),
                () -> fixture.inventory().canonicalSnapshot(OWNER),
                () -> fixture.worldItems().canonicalSnapshot());

        RestoreFixture retry = new RestoreFixture(null);
        retry.coordinator().restore(SNAPSHOT);
        assertEquals(List.of(CHUNK_KEY), retry.meshChunks().get());
        retry.physicalWorldItems().close();
    }

    @Test
    void realCanonicalTargetFailuresStopAtChunksInventoryAndWorldItems() {
        for (SessionRestoreCoordinator.RestoreStage failedStage :
                List.of(
                        SessionRestoreCoordinator.RestoreStage.CHUNKS,
                        SessionRestoreCoordinator.RestoreStage.INVENTORY,
                        SessionRestoreCoordinator.RestoreStage.WORLD_ITEMS)) {
            RestoreFixture fixture = new RestoreFixture(null);
            if (failedStage
                    == SessionRestoreCoordinator.RestoreStage.CHUNKS) {
                assertEquals(
                        com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED,
                        fixture.chunks().restoreCanonical(emptyChunks()).status());
            } else if (failedStage
                    == SessionRestoreCoordinator.RestoreStage.INVENTORY) {
                fixture.inventory().insert(OWNER, DIRT_FIVE);
            } else {
                fixture.worldItems()
                        .spawn(
                                new WorldItemSpawnRequest(
                                        DIRT_FIVE,
                                        5.5,
                                        6.0,
                                        5.5,
                                        0.0,
                                        0.0,
                                        0.0,
                                Optional.of(OWNER),
                                1L));
            }
            IllegalStateException cleanupFailure =
                    new IllegalStateException(
                            "injected actual-stage cleanup failure "
                                    + failedStage);
            AtomicInteger closeCalls = new AtomicInteger();

            IllegalStateException thrown =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    GameSessionPersistenceTestFixture
                                            .restoreThroughFactory(
                                                    SNAPSHOT,
                                                    () ->
                                                            fixture.coordinator()
                                                                    .restore(SNAPSHOT),
                                                    () -> {
                                                        closeCalls.incrementAndGet();
                                                        fixture.physicalWorldItems().close();
                                                        throw cleanupFailure;
                                                    }));

            assertAll(
                    () ->
                            assertEquals(
                                    failedStage.ordinal() + 1,
                                    fixture.stages().size()),
                    () -> assertEquals(failedStage, fixture.stages().get(failedStage.ordinal())),
                    () -> assertTrue(fixture.meshChunks().get() == null),
                    () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                    () -> assertEquals(1, closeCalls.get()),
                    () -> assertEquals(1, thrown.getSuppressed().length),
                    () -> assertSame(cleanupFailure, thrown.getSuppressed()[0]),
                    () -> fixture.inventory().canonicalSnapshot(OWNER),
                    () -> fixture.worldItems().canonicalSnapshot());

            RestoreFixture retry = new RestoreFixture(null);
            retry.coordinator().restore(SNAPSHOT);
            assertEquals(List.of(CHUNK_KEY), retry.meshChunks().get());
            retry.physicalWorldItems().close();
        }
    }

    @Test
    void physicalReconciliationIsOwnerThreadIdempotentAndKeepsCanonicalState()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
        assertEquals(
                com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED,
                chunks.restoreCanonical(SNAPSHOT.chunks()).status());
        World world = new World(chunks);
        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(guard, 16, 5);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                worldItems.restoreCanonical(SNAPSHOT.worldItems().logicalSnapshot()).status());
        PhysicsWorld physics =
                new PhysicsWorld(
                        new CollisionWorld(
                                world,
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f(0.0f, -25.0f, 0.0f));
        PhysicalWorldItemSystem physical =
                new PhysicalWorldItemSystem(
                        worldItems,
                        physics,
                        chunks,
                        guard,
                        WorldItemPhysicsConfig.production());
        var canonicalBefore = worldItems.canonicalSnapshot();

        physical.reconcileRestoredCanonicalState(FIXED_TICK);
        PhysicsBody firstBody = physics.bodies().get(0);
        physical.reconcileRestoredCanonicalState(FIXED_TICK);

        assertAll(
                () -> assertEquals(1, physics.bodies().size()),
                () -> assertSame(firstBody, physics.bodies().get(0)),
                () -> assertEquals(canonicalBefore, worldItems.canonicalSnapshot()));

        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                physical.reconcileRestoredCanonicalState(FIXED_TICK);
                            } catch (Throwable failure) {
                                workerFailure.set(failure);
                            }
                        },
                        "restore-reconcile-worker");
        worker.start();
        worker.join();
        assertTrue(workerFailure.get() instanceof IllegalStateException);
        physical.close();
    }

    @Test
    void activeItemWithoutLoadedCollisionDataRemainsExactlyCanonicalAndUnprojected() {
        WorldItemRestoreEntry activeUnloaded =
                worldItem(7L, 40.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE);
        PhysicalRestoreFixture fixture =
                physicalFixture(
                        emptyChunks(),
                        List.of(activeUnloaded),
                        physicsConfig(4),
                        null);
        var canonicalBefore = fixture.worldItems().canonicalSnapshot();

        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);
        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);

        assertAll(
                () -> assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot()),
                () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                () -> assertTrue(fixture.system().presentationSnapshots().isEmpty()));
        fixture.system().close();
    }

    @Test
    void frozenItemWithLoadedCollisionDataKeepsCanonicalRevisionAndOneStableBody() {
        WorldItemRestoreEntry frozenLoaded =
                worldItem(
                        7L,
                        5.5,
                        6.0,
                        5.5,
                        WorldItemPhysicalState.FROZEN_UNLOADED);
        PhysicalRestoreFixture fixture =
                physicalFixture(
                        emptyChunks(),
                        List.of(frozenLoaded),
                        physicsConfig(4),
                        null);
        var canonicalBefore = fixture.worldItems().canonicalSnapshot();

        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);
        PhysicsBody firstBody = fixture.physicsWorld().bodies().get(0);
        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);

        assertAll(
                () -> assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot()),
                () -> assertEquals(1, fixture.physicsWorld().bodies().size()),
                () -> assertSame(firstBody, fixture.physicsWorld().bodies().get(0)),
                () ->
                        assertEquals(
                                new WorldItemId(7L),
                                fixture.system().presentationSnapshots().get(0).id()));
        fixture.system().close();
    }

    @Test
    void restoreProjectionCapacityAdmitsStableLowestIdWithoutDuplicates() {
        PhysicalRestoreFixture fixture =
                physicalFixture(
                        emptyChunks(),
                        List.of(
                                worldItem(8L, 6.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE),
                                worldItem(7L, 5.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE)),
                        physicsConfig(1),
                        null);
        var canonicalBefore = fixture.worldItems().canonicalSnapshot();

        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);
        PhysicsBody firstBody = fixture.physicsWorld().bodies().get(0);
        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);

        assertAll(
                () -> assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot()),
                () -> assertEquals(1, fixture.physicsWorld().bodies().size()),
                () -> assertSame(firstBody, fixture.physicsWorld().bodies().get(0)),
                () ->
                        assertEquals(
                                List.of(new WorldItemId(7L)),
                                fixture.system().presentationSnapshots().stream()
                                        .map(snapshot -> snapshot.id())
                                        .toList()));
        fixture.system().close();
    }

    @Test
    void unrecoverableRestoredProjectionOverlapFailsAndRollsBackEveryBody() {
        PhysicalRestoreFixture fixture =
                physicalFixture(
                        solidChunks(),
                        List.of(
                                worldItem(7L, 5.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE)),
                        physicsConfig(4),
                        null);
        var canonicalBefore = fixture.worldItems().canonicalSnapshot();
        IllegalStateException cleanupFailure =
                new IllegalStateException(
                        "injected projection-stage cleanup failure");
        AtomicInteger closeCalls = new AtomicInteger();

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GameSessionPersistenceTestFixture
                                        .restoreThroughFactory(
                                                SNAPSHOT,
                                                () ->
                                                        fixture.system()
                                                                .reconcileRestoredCanonicalState(
                                                                        FIXED_TICK),
                                                () -> {
                                                    closeCalls.incrementAndGet();
                                                    fixture.system().close();
                                                    throw cleanupFailure;
                                                }));

        assertAll(
                () -> assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot()),
                () -> assertTrue(fixture.physicsWorld().bodies().isEmpty()),
                () -> assertTrue(fixture.system().presentationSnapshots().isEmpty()),
                () -> assertEquals(1, closeCalls.get()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(cleanupFailure, thrown.getSuppressed()[0]));

        PhysicalRestoreFixture retry =
                physicalFixture(
                        emptyChunks(),
                        List.of(
                                worldItem(
                                        7L,
                                        5.5,
                                        6.0,
                                        5.5,
                                        WorldItemPhysicalState.ACTIVE)),
                        physicsConfig(4),
                        null);
        retry.system().reconcileRestoredCanonicalState(FIXED_TICK);
        assertEquals(1, retry.physicsWorld().bodies().size());
        retry.system().close();
    }

    @Test
    void admittedProjectionFailureRollsBackAllBodiesAndRetryBuildsExactlyOneEach() {
        AtomicBoolean failSecond = new AtomicBoolean(true);
        WorldItemPhysicsConfig config = physicsConfig(4);
        PhysicalRestoreFixture fixture =
                physicalFixture(
                        emptyChunks(),
                        List.of(
                                worldItem(7L, 5.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE),
                                worldItem(8L, 6.5, 6.0, 5.5, WorldItemPhysicalState.ACTIVE)),
                        config,
                        snapshot -> {
                            if (snapshot.id().value() == 8L && failSecond.get()) {
                                throw new IllegalStateException(
                                        "injected restored projection failure");
                            }
                            return PhysicalWorldItemRestoreTestFixture.productionBody(
                                    snapshot, config);
                        });
        var canonicalBefore = fixture.worldItems().canonicalSnapshot();

        assertThrows(
                IllegalStateException.class,
                () -> fixture.system().reconcileRestoredCanonicalState(FIXED_TICK));
        assertTrue(fixture.physicsWorld().bodies().isEmpty());
        assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot());

        failSecond.set(false);
        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);
        List<PhysicsBody> firstBodies = List.copyOf(fixture.physicsWorld().bodies());
        fixture.system().reconcileRestoredCanonicalState(FIXED_TICK);

        assertAll(
                () -> assertEquals(2, fixture.physicsWorld().bodies().size()),
                () -> assertSame(firstBodies.get(0), fixture.physicsWorld().bodies().get(0)),
                () -> assertSame(firstBodies.get(1), fixture.physicsWorld().bodies().get(1)),
                () -> assertEquals(canonicalBefore, fixture.worldItems().canonicalSnapshot()));
        fixture.system().close();
    }

    private static PhysicalRestoreFixture physicalFixture(
            ChunkRepositorySnapshot chunkSnapshot,
            List<WorldItemRestoreEntry> entries,
            WorldItemPhysicsConfig config,
            PhysicalWorldItemSystem.ProjectionFactory projectionFactory) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks =
                new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
        assertEquals(
                com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED,
                chunks.restoreCanonical(chunkSnapshot).status());
        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(guard, 16, 5);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                worldItems
                        .restoreCanonical(
                                new WorldItemsSaveSnapshot(
                                                FIXED_TICK,
                                                entries,
                                                9L,
                                                false)
                                        .logicalSnapshot())
                        .status());
        PhysicsWorld physicsWorld =
                new PhysicsWorld(
                        new CollisionWorld(
                                new World(chunks),
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f(0.0f, -25.0f, 0.0f));
        PhysicalWorldItemSystem system =
                projectionFactory == null
                        ? new PhysicalWorldItemSystem(
                                worldItems,
                                physicsWorld,
                                chunks,
                                guard,
                                config)
                        : PhysicalWorldItemRestoreTestFixture.create(
                                worldItems,
                                physicsWorld,
                                chunks,
                                guard,
                                config,
                                projectionFactory);
        return new PhysicalRestoreFixture(worldItems, physicsWorld, system);
    }

    private static SaveGameSnapshot withPlayer(
            SaveGameSnapshot snapshot,
            PlayerSaveSnapshot player) {
        return new SaveGameSnapshot(
                snapshot.metadata(),
                snapshot.fixedTick(),
                snapshot.chunks(),
                player,
                snapshot.inventory(),
                snapshot.worldItems());
    }

    private static SaveGameSnapshot snapshotWith(
            ChunkRepositorySnapshot chunks,
            PlayerSaveSnapshot player,
            WorldItemsSaveSnapshot worldItems) {
        return new SaveGameSnapshot(
                SNAPSHOT.metadata(),
                FIXED_TICK,
                chunks,
                player,
                SNAPSHOT.inventory(),
                worldItems);
    }

    private static PlayerSaveSnapshot playerSnapshot(
            double x,
            double y,
            double z,
            boolean noclip) {
        return new PlayerSaveSnapshot(
                OWNER,
                x,
                y,
                z,
                0.25,
                -0.5,
                1.5,
                540.5,
                -18.25,
                GameMode.SURVIVAL,
                noclip);
    }

    private static ChunkRepositorySnapshot chunksWithOneSolidBlock() {
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * WORLD_HEIGHT
                                * GameConfig.Chunk.SIZE];
        int index =
                5
                        + 6 * GameConfig.Chunk.SIZE
                        + 5 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[index] = 1;
        return new ChunkRepositorySnapshot(
                WORLD_HEIGHT,
                1L,
                List.of(
                        ChunkSnapshot.of(
                                CHUNK_KEY, 1L, WORLD_HEIGHT, blocks)));
    }

    private static ChunkRepositorySnapshot emptyChunks() {
        return new ChunkRepositorySnapshot(
                WORLD_HEIGHT,
                1L,
                List.of(
                        ChunkSnapshot.empty(
                                CHUNK_KEY, 1L, WORLD_HEIGHT)));
    }

    private static ChunkRepositorySnapshot solidChunks() {
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * WORLD_HEIGHT
                                * GameConfig.Chunk.SIZE];
        Arrays.fill(blocks, (byte) 1);
        return new ChunkRepositorySnapshot(
                WORLD_HEIGHT,
                1L,
                List.of(
                        ChunkSnapshot.of(
                                CHUNK_KEY, 1L, WORLD_HEIGHT, blocks)));
    }

    private static WorldItemRestoreEntry worldItem(
            long id,
            double x,
            double y,
            double z,
            WorldItemPhysicalState state) {
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                DIRT_FIVE,
                                x,
                                y,
                                z,
                                0.0,
                                0.0,
                                0.0,
                                4L),
                        Optional.of(OWNER),
                        31L,
                        36L),
                state);
    }

    private static WorldItemPhysicsConfig physicsConfig(int capacity) {
        WorldItemPhysicsConfig production =
                WorldItemPhysicsConfig.production();
        return new WorldItemPhysicsConfig(
                production.edgeLength(),
                production.maximumFallSpeed(),
                production.restitution(),
                production.friction(),
                production.groundProbeDistance(),
                production.sleepSpeedThreshold(),
                production.sleepStableSteps(),
                production.depenetrationIterations(),
                WORLD_HEIGHT,
                production.pickupReach(),
                capacity);
    }

    private record PhysicalRestoreFixture(
            LogicalWorldItemService worldItems,
            PhysicsWorld physicsWorld,
            PhysicalWorldItemSystem system) {}

    private static void assertControllerState(
            PlayerController controller,
            PhysicsBody body,
            Vector3f position,
            Vector3f velocity,
            boolean noclip) {
        assertAll(
                () -> assertEquals(position, body.position(new Vector3f())),
                () -> assertEquals(velocity, body.linearVelocity(new Vector3f())),
                () -> assertEquals(noclip, controller.isNoclip()),
                () -> assertFalse(controller.isGrounded()));
    }

    private static SaveGameSnapshot snapshot() {
        ChunkRepositorySnapshot chunks =
                new ChunkRepositorySnapshot(
                        WORLD_HEIGHT,
                        1L,
                        List.of(
                                ChunkSnapshot.empty(
                                        CHUNK_KEY,
                                        1L,
                                        WORLD_HEIGHT)));
        PlayerSaveSnapshot player =
                new PlayerSaveSnapshot(
                        OWNER,
                        2.5,
                        8.0,
                        3.5,
                        0.25,
                        -0.5,
                        1.5,
                        540.5,
                        -18.25,
                        GameMode.SURVIVAL,
                        true);
        InventorySaveSnapshot inventory =
                new InventorySaveSnapshot(
                        OWNER,
                        Map.of(BodySlot.LEFT_HAND, DIRT_FIVE),
                        BodySlot.RIGHT_HAND,
                        false,
                        12L);
        WorldItemSnapshot item =
                new WorldItemSnapshot(
                        new WorldItemId(7),
                        DIRT_FIVE,
                        5.5,
                        6.0,
                        5.5,
                        0.0,
                        0.0,
                        0.0,
                        4L);
        WorldItemsSaveSnapshot worldItems =
                new WorldItemsSaveSnapshot(
                        FIXED_TICK,
                        List.of(
                                new WorldItemRestoreEntry(
                                        new WorldItemRuntimeSnapshot(
                                                item,
                                                Optional.of(OWNER),
                                                31L,
                                                36L),
                                        WorldItemPhysicalState.ACTIVE)),
                        19L,
                        false);
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-test",
                        SaveGameId.parse(
                                "11111111-2222-4333-8444-555555555555"),
                        "Restore Contract",
                        Instant.parse("2026-08-10T12:30:00Z"),
                        123456789L,
                        "gaia-v2",
                        "1".repeat(64),
                        2,
                        WORLD_HEIGHT,
                        Optional.empty()),
                FIXED_TICK,
                chunks,
                player,
                inventory,
                worldItems);
    }

    private static BodyInventoryService inventory(MainThreadGuard guard) {
        return new BodyInventoryService(
                OWNER,
                itemId ->
                        itemId.equals(DIRT_ID)
                                ? Optional.of(
                                        new ItemFormDefinition(
                                                DIRT_ID,
                                                64,
                                                false,
                                                false))
                                : Optional.empty(),
                guard,
                ignored -> {});
    }

    private static PhysicsBody playerBody() {
        return new PhysicsBody(
                new Aabb(-0.3f, 0.0f, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1.0f));
    }

    private static PlayerController playerController(World world, PhysicsBody body) {
        return new PlayerController(
                body,
                new CollisionWorld(
                        world,
                        BlockCollisionShapeResolver.fullCubesForNonAir()),
                5.0f,
                12.0f,
                8.0f,
                -25.0f,
                -60.0f);
    }

    private static final class RestoreFixture {
        private final MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        private final ChunkRepository chunks =
                new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
        private final World world = new World(chunks);
        private final BodyInventoryService inventory =
                SessionRestoreCoordinatorTest.inventory(guard);
        private final LogicalWorldItemService worldItems =
                new LogicalWorldItemService(guard, 16, 5);
        private final PhysicsBody playerBody =
                SessionRestoreCoordinatorTest.playerBody();
        private final PlayerController playerController =
                SessionRestoreCoordinatorTest.playerController(
                        world, playerBody);
        private final Camera camera;
        private final GameModeManager gameModes =
                new GameModeManager(GameMode.CREATIVE, ignored -> {});
        private final PhysicsWorld physicsWorld =
                new PhysicsWorld(
                        new CollisionWorld(
                                world,
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f(0.0f, -25.0f, 0.0f));
        private final PhysicalWorldItemSystem physicalWorldItems =
                new PhysicalWorldItemSystem(
                        worldItems,
                        physicsWorld,
                        chunks,
                        guard,
                        WorldItemPhysicsConfig.production());
        private final AtomicLong restoredTick = new AtomicLong(-1L);
        private final AtomicReference<List<ChunkKey>> meshChunks =
                new AtomicReference<>();
        private final List<SessionRestoreCoordinator.RestoreStage> stages =
                new ArrayList<>();
        private final IllegalStateException injectedFailure =
                new IllegalStateException("injected restore-stage failure");
        private final SessionRestoreCoordinator coordinator;

        private RestoreFixture(
                SessionRestoreCoordinator.RestoreStage failedStage) {
            this(failedStage, new Camera(), ignored -> {});
        }

        private RestoreFixture(
                SessionRestoreCoordinator.RestoreStage failedStage,
                Camera camera,
                Consumer<List<ChunkKey>> meshReadiness) {
            this.camera = Objects.requireNonNull(camera, "camera");
            Consumer<List<ChunkKey>> additionalMeshReadiness =
                    Objects.requireNonNull(meshReadiness, "meshReadiness");
            coordinator =
                    new SessionRestoreCoordinator(
                            chunks,
                            inventory,
                            OWNER,
                            worldItems,
                            playerController,
                            camera,
                            gameModes,
                            physicalWorldItems,
                            restoredTick::set,
                            keys -> {
                                List<ChunkKey> copy = List.copyOf(keys);
                                meshChunks.set(copy);
                                additionalMeshReadiness.accept(copy);
                            },
                            stage -> {
                                stages.add(stage);
                                if (stage == failedStage) {
                                    throw injectedFailure;
                                }
                            });
        }

        private List<SessionRestoreCoordinator.RestoreStage> stages() {
            return List.copyOf(stages);
        }

        private ChunkRepository chunks() {
            return chunks;
        }

        private BodyInventoryService inventory() {
            return inventory;
        }

        private LogicalWorldItemService worldItems() {
            return worldItems;
        }

        private PhysicsBody playerBody() {
            return playerBody;
        }

        private PlayerController playerController() {
            return playerController;
        }

        private Camera camera() {
            return camera;
        }

        private GameModeManager gameModes() {
            return gameModes;
        }

        private PhysicsWorld physicsWorld() {
            return physicsWorld;
        }

        private PhysicalWorldItemSystem physicalWorldItems() {
            return physicalWorldItems;
        }

        private AtomicLong restoredTick() {
            return restoredTick;
        }

        private AtomicReference<List<ChunkKey>> meshChunks() {
            return meshChunks;
        }

        private IllegalStateException injectedFailure() {
            return injectedFailure;
        }

        private SessionRestoreCoordinator coordinator() {
            return coordinator;
        }
    }
}
