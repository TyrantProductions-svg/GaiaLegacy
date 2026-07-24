package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldTest {
    @Test
    void defaultWorldOwnsRepositoryAndDelegatesBlockAccess() {
        World world = new World();

        assertTrue(world.setBlock(3, 4, -2, (byte) 7));

        ChunkKey key = ChunkKey.fromWorld(3, -2);
        assertTrue(world.chunks().contains(key));
        assertEquals(7, Byte.toUnsignedInt(world.getBlock(3, 4, -2)));
        assertFalse(world.setBlock(3, 4, -2, (byte) 7));
    }

    @Test
    void injectedRepositoryIsUsedForReadsAndWrites() {
        ChunkRepository repository = new ChunkRepository();
        World world = new World(repository);

        assertSame(repository, world.chunks());
        assertTrue(world.setBlock(18, 5, 3, (byte) 9));

        assertEquals(9, Byte.toUnsignedInt(repository.getBlock(18, 5, 3)));
        assertEquals(
                ChunkState.DIRTY,
                repository.state(ChunkKey.fromWorld(18, 3)));
    }

    @Test
    void generationDelegatesToInjectedRepository() {
        ChunkRepository repository = new ChunkRepository();
        World world = new World(repository);
        ChunkKey key = new ChunkKey(-2, 3);

        world.generate(
                key, chunk -> chunk.setBlock(2, 6, 4, (byte) 5));

        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(
                5,
                Byte.toUnsignedInt(
                        world.getBlock(
                                key.worldOriginX() + 2,
                                6,
                                key.worldOriginZ() + 4)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedChunkAccessUsesRepositoryOwnedStorage() {
        ChunkRepository repository = new ChunkRepository();
        World world = new World(repository);
        ChunkKey key = new ChunkKey(2, -1);

        Chunk chunk = world.getChunk(key.x(), key.z());
        chunk.setBlock(3, 4, 5, (byte) 6);

        assertTrue(repository.contains(key));
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertEquals(
                6,
                Byte.toUnsignedInt(
                        repository.getBlock(
                                key.worldOriginX() + 3,
                                4,
                                key.worldOriginZ() + 5)));
        assertSame(chunk, world.getChunk(key.x(), key.z()));
    }

    @Test
    void injectedRepositoryMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> new World(null));
    }
}
