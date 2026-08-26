package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.gaia.inventory.BodyInventoryCanonicalSnapshot;
import com.gaia.inventory.BodyInventoryRestoreResult;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.EncodedSaveSection;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.session.GameSessionState;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionPersistenceTestFixture;
import com.gaia.session.SessionSaveCaptureResult;
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
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SaveLoadCanonicalRoundTripTest {
    @Test
    void actualProductionRestoreAcceptsSparseSnapshotWithoutRestoreTimeGenerationFallback() {
        SaveGameSnapshot canonical =
                Gate14BCanonicalFixture.independentExpectedSnapshot();
        ChunkKey missing = new ChunkKey(
                Gate14BCanonicalFixture.CHUNK_RADIUS,
                Gate14BCanonicalFixture.CHUNK_RADIUS);
        List<ChunkSnapshot> remaining = canonical.chunks().chunks().stream()
                .filter(chunk -> !chunk.key().equals(missing))
                .toList();
        ChunkRepositorySnapshot partialChunks = new ChunkRepositorySnapshot(
                canonical.chunks().worldHeight(),
                canonical.chunks().revisionHighWater(),
                remaining);
        ChunkSectionCodec genericCodec = new ChunkSectionCodec();
        ChunkRepositorySnapshot genericRoundTrip =
                genericCodec.decode(genericCodec.encode(partialChunks));
        SaveGameSnapshot partial = new SaveGameSnapshot(
                canonical.metadata(),
                canonical.fixedTick(),
                genericRoundTrip,
                canonical.player(),
                canonical.inventory(),
                canonical.worldItems());

        assertAll(
                () -> assertEquals(80, genericRoundTrip.chunks().size()),
                () -> assertFalse(genericRoundTrip.chunks().stream()
                        .map(ChunkSnapshot::key)
                        .anyMatch(missing::equals)),
                () -> assertEquals(partialChunks, genericRoundTrip));
        var attempt = GameSessionPersistenceTestFixture
                .attemptActualProductionRestore(partial);
        try {
            Optional<Throwable> failure = attempt.restoreAndDriveToReady();
            assertAll(
                    () -> assertEquals(Optional.empty(), failure),
                    () -> assertEquals(
                            Optional.of(GameSessionState.READY),
                            attempt.sessionState()),
                    () -> assertEquals(0, attempt.generationInvocationCount()));
        } finally {
            attempt.close();
        }
        assertEquals(0, attempt.liveWorkerCount());
    }

    @Test
    void actualProductionRestoreRejectsPlayerValuesThatCannotRoundTripThroughFloatOwners() {
        SaveGameSnapshot canonical =
                Gate14BCanonicalFixture.independentExpectedSnapshot();
        PlayerSaveSnapshot lossyPlayer = new PlayerSaveSnapshot(
                canonical.player().owner(),
                0.1,
                0.1,
                0.1,
                0.1,
                0.1,
                0.1,
                0.1,
                0.1,
                canonical.player().gameMode(),
                canonical.player().noclip());
        SaveGameSnapshot lossy = new SaveGameSnapshot(
                canonical.metadata(),
                canonical.fixedTick(),
                canonical.chunks(),
                lossyPlayer,
                canonical.inventory(),
                canonical.worldItems());
        SaveSnapshotCodec codecs = Gate14BCanonicalFixture.codecs();
        EncodedSaveGame encoded = codecs.encode(
                lossy, Gate14BCanonicalFixture.MODIFIED);
        SaveGameSnapshot decoded = codecs.decode(
                encoded.manifest(), Gate14BCanonicalFixture.payloads(encoded));

        assertAll(
                () -> assertEquals(lossy, decoded),
                () -> assertNotEquals(
                        lossyPlayer.feetPositionX(),
                        (double) (float) lossyPlayer.feetPositionX()),
                () -> assertNotEquals(
                        lossyPlayer.velocityX(),
                        (double) (float) lossyPlayer.velocityX()),
                () -> assertNotEquals(
                        lossyPlayer.yaw(),
                        (double) (float) lossyPlayer.yaw()),
                () -> assertNotEquals(
                        lossyPlayer.pitch(),
                        (double) (float) lossyPlayer.pitch()));
        assertActualProductionRestoreRejectedBeforePublication(decoded);
    }

    @Test
    void representativeLiveCaptureRoundTripsCodecsAndFreshRestoreRecapturesBoundedExactState() {
        Gate14BCanonicalFixture.LiveCapture live =
                Gate14BCanonicalFixture.representativeLiveCapture();
        SaveGameSnapshot captured = live.capture().snapshot().orElseThrow();
        SaveGameSnapshot expected =
                Gate14BCanonicalFixture.independentExpectedSnapshot();
        assertIndependentCaptureOracle(expected, captured);

        SaveSnapshotCodec codecs = Gate14BCanonicalFixture.codecs();
        EncodedSaveGame first = codecs.encode(captured, Gate14BCanonicalFixture.MODIFIED);
        EncodedSaveGame repeated = codecs.encode(captured, Gate14BCanonicalFixture.MODIFIED);
        SaveGameSnapshot decoded = codecs.decode(
                first.manifest(), Gate14BCanonicalFixture.payloads(first));

        assertAll(
                () -> assertEquals(81, captured.chunks().chunks().size()),
                () -> assertEquals(3, captured.inventory().stacks().size()),
                () -> assertEquals(14, Gate14BCanonicalFixture.inventoryCount(captured)),
                () -> assertEquals(4, captured.worldItems().entries().size()),
                () -> assertEquals(captured, decoded),
                () -> assertEquals(captured.hashCode(), decoded.hashCode()),
                () -> assertEquals(first.manifest(), repeated.manifest()),
                () -> Gate14BCanonicalFixture.assertSectionBytesEqual(first, repeated));

        String worldItemsJson = new String(
                Gate14BCanonicalFixture.section(first, SaveSectionId.WORLD_ITEMS).bytes(),
                StandardCharsets.UTF_8);
        assertAll(
                () -> assertFalse(worldItemsJson.contains("PhysicsBody")),
                () -> assertFalse(worldItemsJson.contains("reservation")),
                () -> assertFalse(worldItemsJson.contains("projection")));

        try (var restored = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(decoded)) {
            restored.driveToReady();
            SaveGameSnapshot recaptured = restored.captureAndMarkSaved();

            assertAll(
                    () -> assertEquals(GameSessionState.READY, restored.state()),
                    () -> assertEquals(0, restored.generationInvocationCount()),
                    () -> assertEquals(1, restored.readyPublicationCount()),
                    () -> assertEquals(2, restored.capturedFrameCount()),
                    () -> assertEquals(0, restored.transientPresentationCount()),
                    () -> assertEquals(3, restored.physicsBodyCount()),
                    () -> assertEquals(0, restored.inventoryPendingReservations()),
                    () -> assertEquals(0, restored.worldItemPendingReservations()),
                    () -> assertTrue(restored.liveWorkerCount() >= 0),
                    () -> assertTrue(restored.capturePaused().renderInput()
                            .feedback().transientBlocks().isEmpty()),
                    () -> assertEquals(25, recaptured.chunks().chunks().size()),
                    () -> assertEquals(decoded.chunks().revisionHighWater(),
                            recaptured.chunks().revisionHighWater()),
                    () -> assertTrue(decoded.chunks().chunks().containsAll(
                            recaptured.chunks().chunks())),
                    () -> assertEquals(decoded.metadata(), recaptured.metadata()),
                    () -> assertEquals(decoded.fixedTick(), recaptured.fixedTick()),
                    () -> assertEquals(decoded.player(), recaptured.player()),
                    () -> assertEquals(decoded.inventory(), recaptured.inventory()),
                    () -> assertEquals(decoded.worldItems(), recaptured.worldItems()),
                    () -> assertEquals(1, restored.authorizationEntryCount()));
        }
    }

    @Test
    void shuffledSourcesAndPayloadInsertionOrderEncodeAndDecodeCanonically() {
        SaveGameSnapshot canonical =
                Gate14BCanonicalFixture.independentExpectedSnapshot();
        SaveGameSnapshot shuffled =
                Gate14BCanonicalFixture.shuffledEquivalentSnapshot(canonical);
        SaveSnapshotCodec codecs = Gate14BCanonicalFixture.codecs();

        EncodedSaveGame canonicalEncoded =
                codecs.encode(canonical, Gate14BCanonicalFixture.MODIFIED);
        EncodedSaveGame shuffledEncoded =
                codecs.encode(shuffled, Gate14BCanonicalFixture.MODIFIED);

        assertAll(
                () -> assertEquals(
                        canonicalEncoded.manifest().sections(),
                        shuffledEncoded.manifest().sections()),
                () -> Gate14BCanonicalFixture.assertSectionBytesEqual(
                        canonicalEncoded, shuffledEncoded));

        SaveGameSnapshot canonicalDecoded = codecs.decode(
                canonicalEncoded.manifest(),
                Gate14BCanonicalFixture.reversedPayloads(canonicalEncoded));
        SaveGameSnapshot shuffledDecoded = codecs.decode(
                shuffledEncoded.manifest(),
                Gate14BCanonicalFixture.reversedPayloads(shuffledEncoded));
        assertAll(
                () -> assertEquals(canonical, canonicalDecoded),
                () -> assertEquals(canonicalDecoded, shuffledDecoded),
                () -> assertEquals(canonicalDecoded.hashCode(), shuffledDecoded.hashCode()));
    }

    @Test
    void actualProductionSessionKeepsCheckpointLedgerBoundedAndDoesNotRetainSnapshots() {
        SaveSnapshotCodec codecs = Gate14BCanonicalFixture.codecs();
        EncodedSaveGame encoded = codecs.encode(
                Gate14BCanonicalFixture.independentExpectedSnapshot(),
                Gate14BCanonicalFixture.MODIFIED);
        SaveGameSnapshot decoded = codecs.decode(
                encoded.manifest(), Gate14BCanonicalFixture.payloads(encoded));

        try (var restored = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(decoded)) {
            restored.driveToReady();

            SessionSaveCaptureResult oldCapture = restored.captureSave();
            SessionPersistenceRevision oldToken =
                    oldCapture.persistenceRevision().orElseThrow();
            SessionSaveCaptureResult latestCapture = restored.captureSave();
            SessionPersistenceRevision latestToken =
                    latestCapture.persistenceRevision().orElseThrow();

            assertAll(
                    () -> assertEquals(oldToken.value(), latestToken.value()),
                    () -> assertNotSame(oldToken, latestToken),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> restored.markSaved(oldToken)),
                    () -> assertTrue(restored.authorizationEntryCount() <= 2));

            restored.markSaved(latestToken);
            restored.markSaved(latestToken);
            SessionPersistenceRevision nextLatest = restored.captureSave()
                    .persistenceRevision()
                    .orElseThrow();
            restored.markSaved(latestToken);
            restored.markSaved(nextLatest);
            restored.markSaved(nextLatest);

            for (int capture = 0; capture < 512; capture++) {
                SessionPersistenceRevision token = restored.captureSave()
                        .persistenceRevision()
                        .orElseThrow();
                restored.markSaved(token);
                assertTrue(restored.authorizationEntryCount() <= 2);
            }

            WeakReference<SaveGameSnapshot> unretained =
                    captureWithoutRetainingSnapshot(restored);
            awaitWeakCollection(unretained);
            assertAll(
                    () -> assertNull(unretained.get()),
                    () -> assertTrue(restored.authorizationEntryCount() <= 2));
        }
    }

    @Test
    void separateTwoHandedInventoryRoundTripsActualCodecAndFreshRestore() {
        InventorySaveSnapshot twoHanded = new InventorySaveSnapshot(
                Gate14BCanonicalFixture.OWNER,
                Map.of(
                        BodySlot.LEFT_HAND,
                        new ItemStack(Gate14BCanonicalFixture.HEAVY_ID, 1)),
                BodySlot.LEFT_HAND,
                true,
                21L);
        InventorySectionCodec codec = new InventorySectionCodec();

        InventorySaveSnapshot decoded = codec.decode(codec.encode(twoHanded));
        BodyInventoryService target = Gate14BCanonicalFixture.freshInventory();
        BodyInventoryRestoreResult restored = target.restoreCanonical(
                Gate14BCanonicalFixture.OWNER,
                decoded.canonicalSnapshot());

        assertAll(
                () -> assertEquals(BodyInventoryRestoreResult.Status.RESTORED,
                        restored.status()),
                () -> assertEquals(twoHanded.canonicalSnapshot(),
                        target.canonicalSnapshot(Gate14BCanonicalFixture.OWNER)),
                () -> assertTrue(target.canonicalSnapshot(Gate14BCanonicalFixture.OWNER)
                        .twoHandedHandsOccupied()),
                () -> assertFalse(target.canonicalSnapshot(Gate14BCanonicalFixture.OWNER)
                        .stacks().containsKey(BodySlot.RIGHT_HAND)));
    }

    private static void assertActualProductionRestoreRejectedBeforePublication(
            SaveGameSnapshot snapshot) {
        try (var attempt = GameSessionPersistenceTestFixture
                .attemptActualProductionRestore(snapshot)) {
            Optional<Throwable> failure = attempt.restoreAndDriveToReady();
            assertAll(
                    () -> assertInstanceOf(
                            IllegalArgumentException.class,
                            failure.orElse(null)),
                    () -> assertEquals(Optional.empty(), attempt.sessionState()),
                    () -> assertEquals(0, attempt.generationInvocationCount()),
                    () -> assertEquals(0, attempt.readyPublicationCount()),
                    () -> assertEquals(0, attempt.capturedFrameCount()),
                    () -> assertEquals(0, attempt.liveWorkerCount()));
        }
    }

    private static void assertIndependentCaptureOracle(
            SaveGameSnapshot expected, SaveGameSnapshot captured) {
        assertEquals(expected, captured);
        assertEquals(81, captured.chunks().chunks().size());
        assertEquals(95L, captured.chunks().revisionHighWater());
        for (int index = 0; index < 81; index++) {
            ChunkSnapshot expectedChunk = expected.chunks().chunks().get(index);
            ChunkSnapshot actualChunk = captured.chunks().chunks().get(index);
            assertEquals(expectedChunk.key(), actualChunk.key());
            assertEquals(index + 1L, actualChunk.revision());
            assertEquals(Gate14BCanonicalFixture.WORLD_HEIGHT, actualChunk.worldHeight());
            assertArrayEquals(expectedChunk.copyBlocks(), actualChunk.copyBlocks());
        }
        assertEquals(1, Byte.toUnsignedInt(captured.chunks().chunks().get(0)
                .getBlock(1, 5, 2)));
        assertEquals(2, Byte.toUnsignedInt(captured.chunks().chunks().get(80)
                .getBlock(14, 9, 13)));

        PlayerSaveSnapshot player = captured.player();
        assertAll(
                () -> assertEquals(Gate14BCanonicalFixture.OWNER, player.owner()),
                () -> assertEquals(1.25, player.feetPositionX()),
                () -> assertEquals(32.0, player.feetPositionY()),
                () -> assertEquals(-3.5, player.feetPositionZ()),
                () -> assertEquals(0.5, player.velocityX()),
                () -> assertEquals(-0.25, player.velocityY()),
                () -> assertEquals(1.75, player.velocityZ()),
                () -> assertEquals(721.25, player.yaw()),
                () -> assertEquals(-22.5, player.pitch()),
                () -> assertEquals(GameMode.SURVIVAL, player.gameMode()),
                () -> assertTrue(player.noclip()));

        InventorySaveSnapshot inventory = captured.inventory();
        assertAll(
                () -> assertEquals(Gate14BCanonicalFixture.OWNER, inventory.owner()),
                () -> assertEquals(
                        new ItemStack(Gate14BCanonicalFixture.DIRT_ID, 5),
                        inventory.stacks().get(BodySlot.LEFT_HAND)),
                () -> assertEquals(
                        new ItemStack(Gate14BCanonicalFixture.STONE_ID, 7),
                        inventory.stacks().get(BodySlot.RIGHT_HAND)),
                () -> assertEquals(
                        new ItemStack(Gate14BCanonicalFixture.LEAVES_ID, 2),
                        inventory.stacks().get(BodySlot.MOUTH)),
                () -> assertEquals(BodySlot.RIGHT_HAND, inventory.activeSlot()),
                () -> assertFalse(inventory.twoHandedHandsOccupied()),
                () -> assertEquals(17L, inventory.revision()));

        assertEquals(Gate14BCanonicalFixture.FIXED_TICK, captured.fixedTick());
        assertEquals(Gate14BCanonicalFixture.FIXED_TICK,
                captured.worldItems().fixedTick());
        assertEquals(100L, captured.worldItems().nextItemId());
        assertFalse(captured.worldItems().itemIdsExhausted());
        assertEquals(4, captured.worldItems().entries().size());
        for (int index = 0; index < 4; index++) {
            WorldItemRestoreEntry expectedEntry =
                    expected.worldItems().entries().get(index);
            WorldItemRestoreEntry actualEntry =
                    captured.worldItems().entries().get(index);
            WorldItemRuntimeSnapshot expectedRuntime = expectedEntry.runtime();
            WorldItemRuntimeSnapshot actualRuntime = actualEntry.runtime();
            WorldItemSnapshot expectedItem = expectedRuntime.item();
            WorldItemSnapshot actualItem = actualRuntime.item();
            assertAll(
                    () -> assertEquals(expectedEntry.physicalState(),
                            actualEntry.physicalState()),
                    () -> assertEquals(expectedItem.id(), actualItem.id()),
                    () -> assertEquals(expectedItem.stack(), actualItem.stack()),
                    () -> assertEquals(expectedItem.positionX(), actualItem.positionX()),
                    () -> assertEquals(expectedItem.positionY(), actualItem.positionY()),
                    () -> assertEquals(expectedItem.positionZ(), actualItem.positionZ()),
                    () -> assertEquals(expectedItem.velocityX(), actualItem.velocityX()),
                    () -> assertEquals(expectedItem.velocityY(), actualItem.velocityY()),
                    () -> assertEquals(expectedItem.velocityZ(), actualItem.velocityZ()),
                    () -> assertEquals(expectedItem.revision(), actualItem.revision()),
                    () -> assertEquals(expectedRuntime.source(), actualRuntime.source()),
                    () -> assertEquals(expectedRuntime.spawnTick(), actualRuntime.spawnTick()),
                    () -> assertEquals(expectedRuntime.pickupAvailableTick(),
                            actualRuntime.pickupAvailableTick()));
        }
    }

    private static WeakReference<SaveGameSnapshot> captureWithoutRetainingSnapshot(
            GameSessionPersistenceTestFixture.ActualProductionSession restored) {
        SessionSaveCaptureResult captured = restored.captureSave();
        WeakReference<SaveGameSnapshot> unretained =
                new WeakReference<>(captured.snapshot().orElseThrow());
        restored.markSaved(captured.persistenceRevision().orElseThrow());
        return unretained;
    }

    private static void awaitWeakCollection(
            WeakReference<SaveGameSnapshot> reference) {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            byte[][] pressure = new byte[8][];
            for (int index = 0; index < pressure.length; index++) {
                pressure[index] = new byte[256 * 1024];
            }
        }
    }
}

final class Gate14BCanonicalFixture {
    static final int CHUNK_RADIUS = 4;
    static final int WORLD_HEIGHT = GameConfig.Chunk.MAX_HEIGHT;
    static final long FIXED_TICK = 420L;
    static final long WORLD_SEED = -9_223_372_036_854_775_000L;
    static final EntityRef OWNER = new EntityRef(0);
    static final ResourceLocation DIRT_ID = ResourceLocation.parse("gaia:dirt");
    static final ResourceLocation STONE_ID = ResourceLocation.parse("gaia:stone");
    static final ResourceLocation LEAVES_ID = ResourceLocation.parse("gaia:oak_leaves");
    static final ResourceLocation HEAVY_ID = ResourceLocation.parse("gaia:heavy-tool");
    static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    static final Instant MODIFIED = Instant.parse("2026-08-11T01:02:03Z");
    static final SaveGameSnapshot.StaticMetadata METADATA =
            new SaveGameSnapshot.StaticMetadata(
                    SaveFormatVersion.CURRENT,
                    "0.2.0-alpha.1",
                    SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000"),
                    "Gate 14B World",
                    CREATED,
                    WORLD_SEED,
                    "gaia-v1",
                    "a".repeat(64),
                    CHUNK_RADIUS,
                    WORLD_HEIGHT,
                    Optional.of("Representative 81 chunk in-memory round trip"));

    private Gate14BCanonicalFixture() {}

    static SaveSnapshotCodec codecs() {
        return new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                new PlayerSectionCodec(),
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
    }

    static LiveCapture representativeLiveCapture() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository chunks = new ChunkRepository(WORLD_HEIGHT);
        ChunkRepositoryRestoreResult chunkRestore =
                chunks.restoreCanonical(representativeChunks());
        if (chunkRestore.status() != ChunkRepositoryRestoreResult.Status.RESTORED) {
            throw new AssertionError("representative chunks did not restore");
        }
        World world = new World(chunks);

        BodyInventoryService inventory = freshInventory();
        BodyInventoryCanonicalSnapshot inventorySnapshot =
                new BodyInventoryCanonicalSnapshot(
                        OWNER,
                        directInventory(),
                        BodySlot.RIGHT_HAND,
                        false,
                        17L);
        if (inventory.restoreCanonical(OWNER, inventorySnapshot).status()
                != BodyInventoryRestoreResult.Status.RESTORED) {
            throw new AssertionError("representative inventory did not restore");
        }

        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(guard, 32, 5);
        if (worldItems.restoreCanonical(seedWorldItems()).status()
                != WorldItemRestoreResult.Status.RESTORED) {
            throw new AssertionError("representative world items did not restore");
        }
        WorldItemReservationResult partial =
                worldItems.reserve(new WorldItemId(7), 4);
        if (partial.status() != WorldItemReservationResult.Status.PARTIALLY_RESERVED
                || worldItems.commit(partial.reservation().orElseThrow().id()).status()
                        != WorldItemReservationResult.Status.COMMITTED) {
            throw new AssertionError("partial remainder fixture mutation failed");
        }
        WorldItemReservationResult deletedHigh =
                worldItems.reserve(new WorldItemId(99), 1);
        if (deletedHigh.status() != WorldItemReservationResult.Status.RESERVED
                || worldItems.commit(deletedHigh.reservation().orElseThrow().id()).status()
                        != WorldItemReservationResult.Status.COMMITTED) {
            throw new AssertionError("deleted high stable-ID fixture mutation failed");
        }

        PhysicsBody playerBody = playerBody();
        PlayerController playerController = playerController(world, playerBody);
        playerController.restoreCanonical(
                new Vector3f(1.25f, 32.0f, -3.5f),
                new Vector3f(0.5f, -0.25f, 1.75f),
                true,
                WORLD_HEIGHT);
        Camera camera = new Camera();
        camera.setYaw(721.25f);
        camera.setPitch(-22.5f);
        GameModeManager gameModes =
                new GameModeManager(GameMode.SURVIVAL, ignored -> {});
        SessionPersistenceTestFixture.ClockHarness clock =
                SessionPersistenceTestFixture.clockHarness(FIXED_TICK, 71L);
        return new LiveCapture(
                world,
                inventory,
                worldItems,
                playerController,
                camera,
                gameModes,
                clock);
    }

    static ChunkRepositorySnapshot representativeChunks() {
        List<ChunkSnapshot> chunks = new ArrayList<>(81);
        long revision = 0L;
        for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) {
            for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) {
                byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT
                        * GameConfig.Chunk.SIZE];
                if (x == -CHUNK_RADIUS && z == -CHUNK_RADIUS) {
                    blocks[blockIndex(1, 5, 2)] = 1;
                }
                if (x == CHUNK_RADIUS && z == CHUNK_RADIUS) {
                    blocks[blockIndex(14, 9, 13)] = 2;
                }
                revision++;
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(x, z), revision, WORLD_HEIGHT, blocks));
            }
        }
        return new ChunkRepositorySnapshot(WORLD_HEIGHT, 95L, chunks);
    }

    static SaveGameSnapshot independentExpectedSnapshot() {
        return new SaveGameSnapshot(
                METADATA,
                FIXED_TICK,
                independentExpectedChunks(),
                new PlayerSaveSnapshot(
                        OWNER,
                        1.25,
                        32.0,
                        -3.5,
                        0.5,
                        -0.25,
                        1.75,
                        721.25,
                        -22.5,
                        GameMode.SURVIVAL,
                        true),
                new InventorySaveSnapshot(
                        OWNER,
                        Map.of(
                                BodySlot.LEFT_HAND,
                                new ItemStack(DIRT_ID, 5),
                                BodySlot.RIGHT_HAND,
                                new ItemStack(STONE_ID, 7),
                                BodySlot.MOUTH,
                                new ItemStack(LEAVES_ID, 2)),
                        BodySlot.RIGHT_HAND,
                        false,
                        17L),
                new WorldItemsSaveSnapshot(
                        FIXED_TICK,
                        List.of(
                                oracleEntry(
                                        3, DIRT_ID, 4,
                                        1.5, 20.0, 1.5,
                                        0.5, 0.25, -0.5,
                                        2, Optional.of(OWNER),
                                        400, 405,
                                        WorldItemPhysicalState.ACTIVE),
                                oracleEntry(
                                        7, STONE_ID, 6,
                                        3.5, 20.0, 3.5,
                                        -0.25, 0.0, 0.75,
                                        5, Optional.of(new EntityRef(17)),
                                        410, 425,
                                        WorldItemPhysicalState.GROUNDED),
                                oracleEntry(
                                        11, LEAVES_ID, 2,
                                        -3.5, 20.0, -3.5,
                                        0.0, 0.0, 0.0,
                                        8, Optional.empty(),
                                        300, 301,
                                        WorldItemPhysicalState.SLEEPING),
                                oracleEntry(
                                        50, DIRT_ID, 1,
                                        80.5, 20.0, 1.5,
                                        1.0, 0.0, -1.0,
                                        6, Optional.of(OWNER),
                                        350, 355,
                                        WorldItemPhysicalState.FROZEN_UNLOADED)),
                        100L,
                        false));
    }

    private static ChunkRepositorySnapshot independentExpectedChunks() {
        List<ChunkSnapshot> expected = new ArrayList<>(81);
        long expectedRevision = 1L;
        for (int expectedX = -4; expectedX <= 4; expectedX++) {
            for (int expectedZ = -4; expectedZ <= 4; expectedZ++) {
                byte[] expectedBlocks = new byte[
                        GameConfig.Chunk.SIZE
                                * GameConfig.Chunk.MAX_HEIGHT
                                * GameConfig.Chunk.SIZE];
                if (expectedX == -4 && expectedZ == -4) {
                    expectedBlocks[1
                            + 5 * GameConfig.Chunk.SIZE
                            + 2 * GameConfig.Chunk.SIZE * GameConfig.Chunk.MAX_HEIGHT] = 1;
                }
                if (expectedX == 4 && expectedZ == 4) {
                    expectedBlocks[14
                            + 9 * GameConfig.Chunk.SIZE
                            + 13 * GameConfig.Chunk.SIZE * GameConfig.Chunk.MAX_HEIGHT] = 2;
                }
                expected.add(ChunkSnapshot.of(
                        new ChunkKey(expectedX, expectedZ),
                        expectedRevision,
                        GameConfig.Chunk.MAX_HEIGHT,
                        expectedBlocks));
                expectedRevision++;
            }
        }
        return new ChunkRepositorySnapshot(
                GameConfig.Chunk.MAX_HEIGHT, 95L, expected);
    }

    private static WorldItemRestoreEntry oracleEntry(
            long id,
            ResourceLocation itemId,
            int count,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long revision,
            Optional<EntityRef> source,
            long spawnTick,
            long pickupTick,
            WorldItemPhysicalState state) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(itemId, count),
                positionX,
                positionY,
                positionZ,
                velocityX,
                velocityY,
                velocityZ,
                revision);
        WorldItemRuntimeSnapshot runtime = new WorldItemRuntimeSnapshot(
                item, source, spawnTick, pickupTick);
        return new WorldItemRestoreEntry(runtime, state);
    }

    static SaveGameSnapshot shuffledEquivalentSnapshot(
            SaveGameSnapshot canonical) {
        List<ChunkSnapshot> reversedChunks =
                new ArrayList<>(canonical.chunks().chunks());
        Collections.reverse(reversedChunks);
        List<WorldItemRestoreEntry> items = canonical.worldItems().entries();
        List<WorldItemRestoreEntry> shuffledItems = List.of(
                items.get(2), items.get(0), items.get(3), items.get(1));
        return new SaveGameSnapshot(
                canonical.metadata(),
                canonical.fixedTick(),
                new ChunkRepositorySnapshot(
                        canonical.chunks().worldHeight(),
                        canonical.chunks().revisionHighWater(),
                        reversedChunks),
                canonical.player(),
                canonical.inventory(),
                new WorldItemsSaveSnapshot(
                        canonical.worldItems().fixedTick(),
                        shuffledItems,
                        canonical.worldItems().nextItemId(),
                        canonical.worldItems().itemIdsExhausted()));
    }

    static BodyInventoryService freshInventory() {
        return new BodyInventoryService(
                OWNER,
                itemId -> {
                    if (itemId.equals(DIRT_ID) || itemId.equals(STONE_ID)) {
                        return Optional.of(new ItemFormDefinition(itemId, 64, false, false));
                    }
                    if (itemId.equals(LEAVES_ID)) {
                        return Optional.of(new ItemFormDefinition(itemId, 64, true, false));
                    }
                    if (itemId.equals(HEAVY_ID)) {
                        return Optional.of(new ItemFormDefinition(itemId, 1, false, true));
                    }
                    return Optional.empty();
                },
                MainThreadGuard.captureCurrentThread(),
                ignored -> {});
    }

    static Map<BodySlot, ItemStack> directInventory() {
        EnumMap<BodySlot, ItemStack> stacks = new EnumMap<>(BodySlot.class);
        stacks.put(BodySlot.LEFT_HAND, new ItemStack(DIRT_ID, 5));
        stacks.put(BodySlot.RIGHT_HAND, new ItemStack(STONE_ID, 7));
        stacks.put(BodySlot.MOUTH, new ItemStack(LEAVES_ID, 2));
        return stacks;
    }

    static LogicalWorldItemSnapshot seedWorldItems() {
        return new LogicalWorldItemSnapshot(
                List.of(
                        entry(3, DIRT_ID, 4, 1.5, 20.0, 1.5,
                                0.5, 0.25, -0.5, 2, Optional.of(OWNER),
                                400, 405, WorldItemPhysicalState.ACTIVE),
                        entry(7, STONE_ID, 10, 3.5, 20.0, 3.5,
                                -0.25, 0.0, 0.75, 4, Optional.of(new EntityRef(17)),
                                410, 425, WorldItemPhysicalState.GROUNDED),
                        entry(11, LEAVES_ID, 2, -3.5, 20.0, -3.5,
                                0.0, 0.0, 0.0, 8, Optional.empty(),
                                300, 301, WorldItemPhysicalState.SLEEPING),
                        entry(50, DIRT_ID, 1, 80.5, 20.0, 1.5,
                                1.0, 0.0, -1.0, 6, Optional.of(OWNER),
                                350, 355, WorldItemPhysicalState.FROZEN_UNLOADED),
                        entry(99, STONE_ID, 1, 5.5, 20.0, 5.5,
                                0.0, 0.0, 0.0, 1, Optional.empty(),
                                419, 420, WorldItemPhysicalState.ACTIVE)),
                100L,
                false);
    }

    static WorldItemRestoreEntry entry(
            long id,
            ResourceLocation itemId,
            int count,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long revision,
            Optional<EntityRef> source,
            long spawnTick,
            long pickupTick,
            WorldItemPhysicalState state) {
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                new ItemStack(itemId, count),
                                positionX,
                                positionY,
                                positionZ,
                                velocityX,
                                velocityY,
                                velocityZ,
                                revision),
                        source,
                        spawnTick,
                        pickupTick),
                state);
    }

    static Map<SaveSectionId, byte[]> payloads(EncodedSaveGame encoded) {
        LinkedHashMap<SaveSectionId, byte[]> payloads = new LinkedHashMap<>();
        for (EncodedSaveSection section : encoded.sections()) {
            payloads.put(section.descriptor().sectionId(), section.bytes());
        }
        return payloads;
    }

    static Map<SaveSectionId, byte[]> reversedPayloads(
            EncodedSaveGame encoded) {
        List<EncodedSaveSection> reversed =
                new ArrayList<>(encoded.sections());
        Collections.reverse(reversed);
        LinkedHashMap<SaveSectionId, byte[]> payloads = new LinkedHashMap<>();
        reversed.forEach(section -> payloads.put(
                section.descriptor().sectionId(), section.bytes()));
        return payloads;
    }

    static EncodedSaveSection section(EncodedSaveGame encoded, SaveSectionId id) {
        return encoded.sections().stream()
                .filter(section -> section.descriptor().sectionId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    static void assertSectionBytesEqual(EncodedSaveGame expected, EncodedSaveGame actual) {
        assertEquals(expected.manifest().sections(), actual.manifest().sections());
        for (int index = 0; index < expected.sections().size(); index++) {
            assertArrayEquals(
                    expected.sections().get(index).bytes(),
                    actual.sections().get(index).bytes());
        }
    }

    static int inventoryCount(SaveGameSnapshot snapshot) {
        return snapshot.inventory().stacks().values().stream()
                .mapToInt(ItemStack::count)
                .sum();
    }

    static PhysicsBody playerBody() {
        return new PhysicsBody(
                new Aabb(-0.3f, 0.0f, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1.0f));
    }

    static PlayerController playerController(World world, PhysicsBody body) {
        return new PlayerController(
                body,
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                5.0f,
                12.0f,
                8.0f,
                -25.0f,
                -60.0f);
    }

    private static int blockIndex(int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
    }

    record LiveCapture(
            World world,
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            SessionPersistenceTestFixture.ClockHarness clock) {
        SessionSaveCaptureResult capture() {
            return GameSessionPersistenceTestFixture.capture(
                    new GameSessionPersistenceTestFixture.CaptureSource(
                            METADATA,
                            clock::revision,
                            clock::fixedTick,
                            world,
                            OWNER,
                            inventory,
                            worldItems,
                            playerController,
                            camera,
                            gameModes));
        }

        void mutateInteriorBlock() {
            clock.reserveRevisionThenRun(() -> {
                if (!world.chunks().setBlock(1, 3, 1, (byte) 4)) {
                    throw new AssertionError("interior block mutation was a no-op");
                }
            });
        }
    }

}
