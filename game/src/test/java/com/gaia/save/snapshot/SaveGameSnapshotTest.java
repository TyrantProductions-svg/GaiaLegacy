package com.gaia.save.snapshot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SaveGameSnapshotTest {
    private static final int WORLD_HEIGHT = 16;
    private static final long FIXED_TICK = 10;
    private static final EntityRef OWNER = new EntityRef(7);
    private static final EntityRef OTHER_OWNER = new EntityRef(8);
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 3);

    @Test
    void equivalentAggregatesUseSemanticChunkContentEquality() {
        MutableFixtureInputs firstInputs = mutableFixtureInputs();
        MutableFixtureInputs secondInputs = mutableFixtureInputs();
        SaveGameSnapshot first = fixture(firstInputs);
        SaveGameSnapshot second = fixture(secondInputs);

        assertEquals(second, first);
        assertEquals(second.hashCode(), first.hashCode());

        firstInputs.mutateAllOriginalInputs();
        secondInputs.mutateAllOriginalInputs();

        assertEquals(fixture(mutableFixtureInputs()), first);
        assertEquals(fixture(mutableFixtureInputs()).hashCode(), first.hashCode());
        assertEquals(fixture(mutableFixtureInputs()), second);
        assertEquals(fixture(mutableFixtureInputs()).hashCode(), second.hashCode());
    }

    @Test
    void aggregateDoesNotRetainMutableVectorsArraysOrLists() {
        byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
        blocks[0] = 7;
        List<ChunkSnapshot> chunkInputs = new ArrayList<>();
        chunkInputs.add(ChunkSnapshot.of(new ChunkKey(2, -1), 4, WORLD_HEIGHT, blocks));
        ChunkRepositorySnapshot chunks =
                new ChunkRepositorySnapshot(WORLD_HEIGHT, 4, chunkInputs);

        EnumMap<BodySlot, ItemStack> stackInputs = new EnumMap<>(BodySlot.class);
        stackInputs.put(BodySlot.LEFT_HAND, DIRT);
        InventorySaveSnapshot inventory = new InventorySaveSnapshot(
                OWNER, stackInputs, BodySlot.RIGHT_HAND, false, 6);

        List<WorldItemRestoreEntry> worldItemInputs = new ArrayList<>();
        worldItemInputs.add(worldItem(3, FIXED_TICK, FIXED_TICK + 5, 2));
        WorldItemsSaveSnapshot worldItems =
                new WorldItemsSaveSnapshot(FIXED_TICK, worldItemInputs, 4, false);

        SaveGameSnapshot snapshot = new SaveGameSnapshot(
                metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "  World One  ",
                        "v1", "a".repeat(64), 4, WORLD_HEIGHT,
                        Optional.of("A valid world")),
                FIXED_TICK,
                chunks,
                player(OWNER),
                inventory,
                worldItems);

        blocks[0] = 99;
        chunkInputs.clear();
        stackInputs.clear();
        worldItemInputs.clear();

        assertAll(
                () -> assertEquals("World One", snapshot.metadata().displayName()),
                () -> assertEquals(1080.25, snapshot.player().yaw()),
                () -> assertEquals(7, snapshot.chunks().chunks().get(0).getBlock(0, 0, 0)),
                () -> assertEquals(Map.of(BodySlot.LEFT_HAND, DIRT), snapshot.inventory().stacks()),
                () -> assertEquals(1, snapshot.worldItems().entries().size()),
                () -> assertEquals(FIXED_TICK + 5,
                        snapshot.worldItems().entries().get(0).runtime().pickupAvailableTick()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.chunks().chunks().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.inventory().stacks().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.worldItems().entries().clear()));

        assertAll(
                "player values are finite and pitch uses Camera's closed range",
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(Double.NaN, 2, 3, 4, 5, 6, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, Double.POSITIVE_INFINITY, 3, 4, 5, 6, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, Double.NEGATIVE_INFINITY, 4, 5, 6, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, Double.NaN, 5, 6, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, Double.POSITIVE_INFINITY, 6, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, 5, Double.NEGATIVE_INFINITY, 7, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, 5, 6, Double.NaN, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, 5, 6, 7, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, 5, 6, 7, -89.0001)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> playerWithState(1, 2, 3, 4, 5, 6, 7, 89.0001)),
                () -> assertThrows(NullPointerException.class,
                        () -> new PlayerSaveSnapshot(
                                OWNER, 1, 2, 3, 4, 5, 6, 7, 8, null, false)));

        assertAll(
                "section and aggregate invariants reject impossible save shapes",
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new InventorySaveSnapshot(
                                OWNER, Map.of(), BodySlot.LEFT_HAND, false, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                -1, List.of(), 0, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK,
                                List.of(worldItem(3, FIXED_TICK + 1, FIXED_TICK + 2, 0)),
                                4,
                                false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK, List.of(), -1, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK, List.of(), 4, true)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK,
                                List.of(
                                        worldItem(3, FIXED_TICK, FIXED_TICK + 5, 0),
                                        worldItem(3, FIXED_TICK, FIXED_TICK + 5, 1)),
                                4,
                                false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK,
                                List.of(worldItem(4, FIXED_TICK, FIXED_TICK + 5, 0)),
                                4,
                                false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WorldItemsSaveSnapshot(
                                FIXED_TICK,
                                List.of(worldItem(5, FIXED_TICK, FIXED_TICK + 5, 0)),
                                4,
                                false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), -1, snapshot.chunks(), snapshot.player(),
                                snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK,
                                new ChunkRepositorySnapshot(WORLD_HEIGHT, -1, List.of()),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK,
                                new ChunkRepositorySnapshot(
                                        WORLD_HEIGHT,
                                        4,
                                        List.of(ChunkSnapshot.empty(
                                                new ChunkKey(0, 0), -1, WORLD_HEIGHT))),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK,
                                new ChunkRepositorySnapshot(
                                        WORLD_HEIGHT,
                                        4,
                                        List.of(
                                                ChunkSnapshot.empty(
                                                        new ChunkKey(0, 0), 3, WORLD_HEIGHT),
                                                ChunkSnapshot.empty(
                                                        new ChunkKey(0, 0), 4, WORLD_HEIGHT))),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK,
                                new ChunkRepositorySnapshot(
                                        WORLD_HEIGHT,
                                        4,
                                        List.of(ChunkSnapshot.empty(
                                                new ChunkKey(0, 0), 5, WORLD_HEIGHT))),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK,
                                new ChunkRepositorySnapshot(
                                        WORLD_HEIGHT, Long.MAX_VALUE, List.of()),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK + 1, snapshot.chunks(),
                                snapshot.player(), snapshot.inventory(), snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                snapshot.metadata(), FIXED_TICK, snapshot.chunks(),
                                snapshot.player(),
                                new InventorySaveSnapshot(
                                        OTHER_OWNER, Map.of(), BodySlot.LEFT_HAND, false, 0),
                                snapshot.worldItems())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SaveGameSnapshot(
                                metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                        "v1", "a".repeat(64), 4, WORLD_HEIGHT + 1,
                                        Optional.empty()),
                                FIXED_TICK, snapshot.chunks(), snapshot.player(),
                                snapshot.inventory(), snapshot.worldItems())));

        assertAll(
                "static metadata stays coherent with the manifest v1 contract",
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(new SaveFormatVersion(2), "0.2.0-alpha.1", "World One",
                                "v1", "a".repeat(64), 4, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, " ", "World One",
                                "v1", "a".repeat(64), 4, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "bad/name",
                                "v1", "a".repeat(64), 4, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                " ", "a".repeat(64), 4, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                "v1", "A".repeat(64), 4, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                "v1", "a".repeat(64), 1, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                "v1", "a".repeat(64), 9, WORLD_HEIGHT, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                "v1", "a".repeat(64), 4, 0, Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> metadata(SaveFormatVersion.CURRENT, "0.2.0-alpha.1", "World One",
                                "v1", "a".repeat(64), 4, WORLD_HEIGHT,
                                Optional.of("x".repeat(281)))));

        WorldItemsSaveSnapshot exhaustedAllocator = new WorldItemsSaveSnapshot(
                FIXED_TICK,
                List.of(worldItem(
                        Long.MAX_VALUE, FIXED_TICK, FIXED_TICK + 5, 0)),
                Long.MAX_VALUE,
                true);
        assertAll(
                "valid boundary states remain constructible",
                () -> assertEquals(Long.MAX_VALUE, exhaustedAllocator.nextItemId()),
                () -> assertEquals(Long.MAX_VALUE,
                        exhaustedAllocator.entries().get(0).runtime().item().id().value()),
                () -> assertEquals(FIXED_TICK + 5,
                        exhaustedAllocator.entries().get(0).runtime().pickupAvailableTick()));
    }

    private static MutableFixtureInputs mutableFixtureInputs() {
        byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
        blocks[0] = 7;
        List<ChunkSnapshot> chunks = new ArrayList<>();
        chunks.add(ChunkSnapshot.of(
                new ChunkKey(2, -1), 4, WORLD_HEIGHT, blocks));

        EnumMap<BodySlot, ItemStack> stacks = new EnumMap<>(BodySlot.class);
        stacks.put(BodySlot.LEFT_HAND, DIRT);

        List<WorldItemRestoreEntry> worldItems = new ArrayList<>();
        worldItems.add(worldItem(3, FIXED_TICK, FIXED_TICK + 5, 2));
        return new MutableFixtureInputs(blocks, chunks, stacks, worldItems);
    }

    private static SaveGameSnapshot fixture(MutableFixtureInputs inputs) {
        return new SaveGameSnapshot(
                metadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        "World One",
                        "v1",
                        "a".repeat(64),
                        4,
                        WORLD_HEIGHT,
                        Optional.of("A valid world")),
                FIXED_TICK,
                new ChunkRepositorySnapshot(WORLD_HEIGHT, 4, inputs.chunks()),
                player(OWNER),
                new InventorySaveSnapshot(
                        OWNER,
                        inputs.stacks(),
                        BodySlot.RIGHT_HAND,
                        false,
                        6),
                new WorldItemsSaveSnapshot(
                        FIXED_TICK,
                        inputs.worldItems(),
                        4,
                        false));
    }

    private static PlayerSaveSnapshot player(EntityRef owner) {
        return new PlayerSaveSnapshot(
                owner,
                1.25, 2.5, -3.75,
                0.1, -0.2, 0.3,
                1080.25, -12.5,
                GameMode.SURVIVAL,
                true);
    }

    private static PlayerSaveSnapshot playerWithState(
            double feetPositionX,
            double feetPositionY,
            double feetPositionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            double yaw,
            double pitch) {
        return new PlayerSaveSnapshot(
                OWNER,
                feetPositionX, feetPositionY, feetPositionZ,
                velocityX, velocityY, velocityZ,
                yaw, pitch,
                GameMode.CREATIVE,
                false);
    }

    private static WorldItemRestoreEntry worldItem(
            long id, long spawnTick, long pickupAvailableTick, long revision) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                DIRT,
                4.25, 5.5, -6.75,
                0.4, -0.5, 0.6,
                revision);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        item, Optional.of(OWNER), spawnTick, pickupAvailableTick),
                WorldItemPhysicalState.SLEEPING);
    }

    private static SaveGameSnapshot.StaticMetadata metadata(
            SaveFormatVersion formatVersion,
            String gameVersion,
            String displayName,
            String generatorVersion,
            String fingerprint,
            int chunkRadius,
            int worldHeight,
            Optional<String> summary) {
        return new SaveGameSnapshot.StaticMetadata(
                formatVersion,
                gameVersion,
                SAVE_ID,
                displayName,
                Instant.parse("2026-08-10T12:00:00Z"),
                12345L,
                generatorVersion,
                fingerprint,
                chunkRadius,
                worldHeight,
                summary);
    }

    private record MutableFixtureInputs(
            byte[] blocks,
            List<ChunkSnapshot> chunks,
            EnumMap<BodySlot, ItemStack> stacks,
            List<WorldItemRestoreEntry> worldItems) {
        private void mutateAllOriginalInputs() {
            blocks[0] = 99;
            chunks.clear();
            stacks.clear();
            worldItems.clear();
        }
    }
}
