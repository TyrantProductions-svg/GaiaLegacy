package com.gaia.save.session;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.session.SessionPersistenceTestFixture;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SessionSaveCaptureTest {
    private static final EntityRef OWNER = new EntityRef(0);
    private static final ResourceLocation DIRT_ID =
            ResourceLocation.parse("gaia:dirt");
    private static final ItemStack DIRT_THREE =
            new ItemStack(DIRT_ID, 3);
    private static final long FIXED_TICK = 19L;
    private static final SaveGameSnapshot.StaticMetadata METADATA =
            new SaveGameSnapshot.StaticMetadata(
                    SaveFormatVersion.CURRENT,
                    "0.2.0-test",
                    SaveGameId.parse(
                            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                    "Capture Contract",
                    Instant.parse("2026-08-10T12:00:00Z"),
                    8675309L,
                    "gaia-v2",
                    "0".repeat(64),
                    2,
                    GameConfig.Chunk.MAX_HEIGHT,
                    Optional.of("capture fixture"));

    @Test
    void capturesOneExactImmutableAggregateAtOneRevisionWithoutMutation() {
        CaptureFixture fixture = populatedFixture(() -> 41L);
        var chunksBefore = fixture.world().chunks().canonicalSnapshot();
        var inventoryBefore = fixture.inventory().canonicalSnapshot(OWNER);
        var worldItemsBefore = fixture.worldItems().canonicalSnapshot();

        SessionSaveCaptureResult result =
                GameSessionPersistenceTestFixture.capture(fixture.source());

        assertEquals(SessionSaveCaptureResult.Status.CAPTURED, result.status());
        assertEquals(41L, result.capturedRevision().orElseThrow());
        assertEquals(
                41L,
                result.persistenceRevision().orElseThrow().value());
        SaveGameSnapshot snapshot = result.snapshot().orElseThrow();
        assertAll(
                () -> assertEquals(METADATA, snapshot.metadata()),
                () -> assertEquals(FIXED_TICK, snapshot.fixedTick()),
                () -> assertEquals(1, snapshot.chunks().chunks().size()),
                () ->
                        assertEquals(
                                (byte) 7,
                                snapshot.chunks().chunks().get(0)
                                        .getBlock(1, 2, 3)),
                () -> assertEquals(OWNER, snapshot.player().owner()),
                () -> assertEquals(2.25, snapshot.player().feetPositionX()),
                () -> assertEquals(7.5, snapshot.player().feetPositionY()),
                () -> assertEquals(-3.75, snapshot.player().feetPositionZ()),
                () -> assertEquals(0.5, snapshot.player().velocityX()),
                () -> assertEquals(-1.25, snapshot.player().velocityY()),
                () -> assertEquals(2.0, snapshot.player().velocityZ()),
                () -> assertEquals(725.5, snapshot.player().yaw()),
                () -> assertEquals(-22.25, snapshot.player().pitch()),
                () -> assertEquals(GameMode.CREATIVE, snapshot.player().gameMode()),
                () -> assertTrue(snapshot.player().noclip()),
                () ->
                        assertEquals(
                                Map.of(BodySlot.LEFT_HAND, DIRT_THREE),
                                snapshot.inventory().stacks()),
                () -> assertEquals(BodySlot.RIGHT_HAND, snapshot.inventory().activeSlot()),
                () -> assertEquals(1L, snapshot.inventory().revision()),
                () -> assertEquals(1, snapshot.worldItems().entries().size()),
                () ->
                        assertEquals(
                                DIRT_THREE,
                                snapshot.worldItems().entries().get(0)
                                        .runtime().item().stack()),
                () ->
                        assertEquals(
                                12L,
                                snapshot.worldItems().entries().get(0)
                                        .runtime().spawnTick()));

        assertAll(
                () -> assertEquals(chunksBefore, fixture.world().chunks().canonicalSnapshot()),
                () -> assertEquals(inventoryBefore, fixture.inventory().canonicalSnapshot(OWNER)),
                () -> assertEquals(worldItemsBefore, fixture.worldItems().canonicalSnapshot()),
                () -> assertEquals(2.25f, fixture.playerBody().position(new Vector3f()).x),
                () -> assertEquals(-1.25f, fixture.playerBody().linearVelocity(new Vector3f()).y));
    }

    @Test
    void pendingInventoryOrWorldItemReservationReturnsClosedPendingResult() {
        CaptureFixture fixture = populatedFixture(() -> 52L);
        var canonicalInventory = fixture.inventory().canonicalSnapshot(OWNER);
        var canonicalWorldItems = fixture.worldItems().canonicalSnapshot();

        var inventoryReservation =
                fixture.inventory().reserve(
                        new InventoryReservationRequest(
                                OWNER,
                                BodySlot.RIGHT_HAND,
                                InventoryReservationOperation.INSERT,
                                new ItemStack(DIRT_ID, 1)));
        assertEquals(InventoryReserveResult.Status.RESERVED, inventoryReservation.status());

        assertPending(GameSessionPersistenceTestFixture.capture(fixture.source()));
        fixture.inventory().rollback(
                inventoryReservation.reservation().orElseThrow().id());

        var spawnReservation =
                fixture.worldItems().reserveSpawn(
                        new WorldItemSpawnRequest(
                                new ItemStack(DIRT_ID, 1),
                                4.0,
                                8.0,
                                4.0,
                                0.0,
                                0.0,
                                0.0,
                                Optional.empty(),
                                FIXED_TICK));
        assertEquals(
                WorldItemSpawnReserveResult.Status.RESERVED,
                spawnReservation.status());

        assertPending(GameSessionPersistenceTestFixture.capture(fixture.source()));
        fixture.worldItems().rollbackSpawn(
                spawnReservation.reservation().orElseThrow().id());

        assertEquals(canonicalInventory, fixture.inventory().canonicalSnapshot(OWNER));
        var worldItemsAfter = fixture.worldItems().canonicalSnapshot();
        assertAll(
                () -> assertEquals(canonicalWorldItems.entries(), worldItemsAfter.entries()),
                () ->
                        assertEquals(
                                canonicalWorldItems.nextItemId() + 1L,
                                worldItemsAfter.nextItemId()),
                () ->
                        assertEquals(
                                canonicalWorldItems.itemIdsExhausted(),
                                worldItemsAfter.itemIdsExhausted()));
    }

    @Test
    void revisionChangeDuringCaptureReturnsInconsistentWithoutCaptureValues() {
        AtomicLong revisionReads = new AtomicLong(70L);
        CaptureFixture fixture = populatedFixture(revisionReads::getAndIncrement);
        var chunksBefore = fixture.world().chunks().canonicalSnapshot();
        var inventoryBefore = fixture.inventory().canonicalSnapshot(OWNER);
        var worldItemsBefore = fixture.worldItems().canonicalSnapshot();

        SessionSaveCaptureResult result =
                GameSessionPersistenceTestFixture.capture(fixture.source());

        assertAll(
                () ->
                        assertEquals(
                                SessionSaveCaptureResult.Status.INCONSISTENT_REVISION,
                                result.status()),
                () -> assertTrue(result.snapshot().isEmpty()),
                () -> assertTrue(result.capturedRevision().isEmpty()),
                () -> assertTrue(result.persistenceRevision().isEmpty()),
                () -> assertEquals(chunksBefore, fixture.world().chunks().canonicalSnapshot()),
                () -> assertEquals(inventoryBefore, fixture.inventory().canonicalSnapshot(OWNER)),
                () -> assertEquals(worldItemsBefore, fixture.worldItems().canonicalSnapshot()));
    }

    @Test
    void fixedStepAndRevisionReservationsRejectMaxBeforeCanonicalMutation() {
        var fixedTickMaximum =
                SessionPersistenceTestFixture.clockHarness(
                        Long.MAX_VALUE, 7L);
        var revisionMaximum =
                SessionPersistenceTestFixture.clockHarness(
                        19L, Long.MAX_VALUE);
        AtomicInteger canonicalMutations = new AtomicInteger();

        assertThrows(
                ArithmeticException.class,
                () -> {
                    fixedTickMaximum.reserveFixedStepThenRun(
                            canonicalMutations::incrementAndGet);
                });
        assertThrows(
                ArithmeticException.class,
                () -> {
                    revisionMaximum.reserveRevisionThenRun(
                            canonicalMutations::incrementAndGet);
                });

        assertAll(
                () -> assertEquals(0, canonicalMutations.get()),
                () -> assertEquals(Long.MAX_VALUE, fixedTickMaximum.fixedTick()),
                () -> assertEquals(7L, fixedTickMaximum.revision()),
                () -> assertEquals(19L, revisionMaximum.fixedTick()),
                () -> assertEquals(Long.MAX_VALUE, revisionMaximum.revision()));
    }

    private static void assertPending(SessionSaveCaptureResult result) {
        assertAll(
                () ->
                        assertEquals(
                                SessionSaveCaptureResult.Status.PENDING_TRANSACTION,
                                result.status()),
                () -> assertTrue(result.snapshot().isEmpty()),
                () -> assertTrue(result.capturedRevision().isEmpty()),
                () -> assertTrue(result.persistenceRevision().isEmpty()));
    }

    private static CaptureFixture populatedFixture(LongSupplier revision) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        World world = new World();
        world.generate(
                new ChunkKey(0, 0),
                chunk -> chunk.setBlock(1, 2, 3, (byte) 7));

        BodyInventoryService inventory =
                new BodyInventoryService(
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
        inventory.insert(OWNER, DIRT_THREE);
        inventory.selectActiveSlot(OWNER, BodySlot.RIGHT_HAND);

        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(guard, 16, 5);
        worldItems.spawn(
                new WorldItemSpawnRequest(
                        DIRT_THREE,
                        5.5,
                        9.0,
                        6.5,
                        0.25,
                        -0.5,
                        0.75,
                        Optional.of(OWNER),
                        12L));

        PhysicsBody playerBody =
                new PhysicsBody(
                        new Aabb(-0.3f, 0.0f, -0.3f, 0.3f, 1.8f, 0.3f),
                        MassProperties.dynamic(1.0f));
        PlayerController playerController =
                new PlayerController(
                        playerBody,
                        new CollisionWorld(
                                world,
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        5.0f,
                        12.0f,
                        8.0f,
                        -25.0f,
                        -60.0f);
        playerController.teleport(new Vector3f(2.25f, 7.5f, -3.75f));
        assertTrue(playerController.setNoclip(true));
        playerBody.setLinearVelocity(new Vector3f(0.5f, -1.25f, 2.0f));

        Camera camera = new Camera();
        camera.setYaw(725.5f);
        camera.setPitch(-22.25f);
        GameModeManager gameModes =
                new GameModeManager(GameMode.CREATIVE, ignored -> {});

        var source =
                new GameSessionPersistenceTestFixture.CaptureSource(
                        METADATA,
                        revision,
                        () -> FIXED_TICK,
                        world,
                        OWNER,
                        inventory,
                        worldItems,
                        playerController,
                        camera,
                        gameModes);
        return new CaptureFixture(
                world,
                inventory,
                worldItems,
                playerBody,
                source);
    }

    private record CaptureFixture(
            World world,
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems,
            PhysicsBody playerBody,
            GameSessionPersistenceTestFixture.CaptureSource source) {}
}
