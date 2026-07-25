package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.generation.WorldGenerationConfig;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkGenerationResult;
import com.overlord.voxel.ChunkGenerationTicket;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.util.Optional;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SafeSpawnSelectorTest {
    private static final byte SOLID = 1;

    @Test
    void requiresSupportAndConfiguredEmptyHeadCells() {
        World world = new World();
        ChunkKey key = new ChunkKey(0, 0);
        byte[] blocks = emptyBlocks();
        set(blocks, 0, 0, 0, SOLID);
        fillToTop(blocks, 0, 1, 0, SOLID);
        set(blocks, 1, 0, 0, SOLID);
        commit(world, key, blocks);

        Optional<Vector3f> spawn =
                new SafeSpawnSelector()
                        .find(
                                world,
                                Set.of(key),
                                WorldGenerationConfig.defaults());

        Vector3f feet = spawn.orElseThrow();
        int x = (int) StrictMath.floor(feet.x);
        int y = (int) StrictMath.floor(feet.y);
        int z = (int) StrictMath.floor(feet.z);
        assertEquals(1, x);
        assertNotEquals(0, world.getBlock(x, y - 1, z));
        assertEquals(0, world.getBlock(x, y, z));
        assertEquals(0, world.getBlock(x, y + 1, z));
    }

    @Test
    void ordersEqualDistanceCandidatesByWorldXThenZThenFeetY() {
        World world = new World();
        ChunkKey west = new ChunkKey(-1, 0);
        ChunkKey center = new ChunkKey(0, 0);
        byte[] westBlocks = emptyBlocks();
        byte[] centerBlocks = emptyBlocks();
        set(westBlocks, 15, 3, 0, SOLID);
        set(centerBlocks, 1, 0, 0, SOLID);
        commit(world, west, westBlocks);
        commit(world, center, centerBlocks);

        Vector3f feet =
                new SafeSpawnSelector()
                        .find(
                                world,
                                Set.of(center, west),
                                WorldGenerationConfig.defaults())
                        .orElseThrow();

        assertEquals(new Vector3f(-0.5f, 4.0f, 0.5f), feet);
    }

    @Test
    void scansOnlyProvidedCommittedKeys() {
        World world = new World();
        ChunkKey center = new ChunkKey(0, 0);
        byte[] blocks = emptyBlocks();
        set(blocks, 0, 0, 0, SOLID);
        commit(world, center, blocks);

        Optional<Vector3f> spawn =
                new SafeSpawnSelector()
                        .find(
                                world,
                                Set.of(new ChunkKey(1, 0)),
                                WorldGenerationConfig.defaults());

        assertTrue(spawn.isEmpty());
    }

    @Test
    void respectsMaximumBlockSearchRadius() {
        World world = new World();
        ChunkKey center = new ChunkKey(0, 0);
        byte[] blocks = emptyBlocks();
        set(blocks, 2, 0, 0, SOLID);
        commit(world, center, blocks);
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        defaults.seed(),
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        defaults.cave(),
                        defaults.surface(),
                        defaults.decoration(),
                        new WorldGenerationConfig.SpawnSettings(1, 2));

        Optional<Vector3f> spawn =
                new SafeSpawnSelector()
                        .find(world, Set.of(center), config);

        assertTrue(spawn.isEmpty());
    }

    @Test
    void extremeCoordinatesCannotOverflowIntoSearchRadius() {
        World world = new World();
        ChunkKey extreme =
                new ChunkKey(
                        Integer.MIN_VALUE / GameConfig.Chunk.SIZE,
                        Integer.MIN_VALUE / GameConfig.Chunk.SIZE);
        byte[] blocks = emptyBlocks();
        set(blocks, 0, 0, 0, SOLID);
        commit(world, extreme, blocks);
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        defaults.seed(),
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        defaults.cave(),
                        defaults.surface(),
                        defaults.decoration(),
                        new WorldGenerationConfig.SpawnSettings(
                                Integer.MAX_VALUE, 2));

        Optional<Vector3f> spawn =
                new SafeSpawnSelector()
                        .find(world, Set.of(extreme), config);

        assertTrue(spawn.isEmpty());
    }

    @Test
    void rejectsColumnWithoutFullConfiguredClearance() {
        World world = new World();
        ChunkKey center = new ChunkKey(0, 0);
        byte[] blocks = emptyBlocks();
        set(blocks, 0, 0, 0, SOLID);
        fillToTop(blocks, 0, 3, 0, SOLID);
        commit(world, center, blocks);
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        defaults.seed(),
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        defaults.cave(),
                        defaults.surface(),
                        defaults.decoration(),
                        new WorldGenerationConfig.SpawnSettings(1, 3));

        Optional<Vector3f> spawn =
                new SafeSpawnSelector()
                        .find(world, Set.of(center), config);

        assertTrue(spawn.isEmpty());
    }

    private static byte[] emptyBlocks() {
        return new byte[
                GameConfig.Chunk.SIZE
                        * GameConfig.Chunk.MAX_HEIGHT
                        * GameConfig.Chunk.SIZE];
    }

    private static void set(
            byte[] blocks, int x, int y, int z, byte value) {
        blocks[
                        x
                                + y * GameConfig.Chunk.SIZE
                                + z
                                        * GameConfig.Chunk.SIZE
                                        * GameConfig.Chunk.MAX_HEIGHT] =
                value;
    }

    private static void fillToTop(
            byte[] blocks, int x, int firstY, int z, byte value) {
        for (int y = firstY;
                y < GameConfig.Chunk.MAX_HEIGHT;
                y++) {
            set(blocks, x, y, z, value);
        }
    }

    private static void commit(
            World world, ChunkKey key, byte[] blocks) {
        ChunkGenerationTicket ticket =
                world.chunks()
                        .beginGeneration(
                                key, ChunkGenerationMode.INITIAL);
        ChunkGenerationResult result =
                world.chunks()
                        .commitGeneration(
                                ticket,
                                new ChunkGenerationData(
                                        key,
                                        GameConfig.Chunk.MAX_HEIGHT,
                                        blocks));
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
    }
}
