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
    void worldWritesArePublishedAsImmutableSnapshots() {
        ChunkRepository repository = new ChunkRepository();
        World world = new World(repository);
        ChunkKey key = new ChunkKey(2, -1);
        int worldX = key.worldOriginX() + 3;
        int worldZ = key.worldOriginZ() + 5;

        assertTrue(world.setBlock(worldX, 4, worldZ, (byte) 6));
        ChunkSnapshot snapshot =
                repository.snapshot(key).orElseThrow();

        assertTrue(repository.contains(key));
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertEquals(
                6,
                Byte.toUnsignedInt(
                        snapshot.getBlock(3, 4, 5)));

        assertTrue(world.setBlock(worldX, 4, worldZ, (byte) 8));

        assertEquals(
                6,
                Byte.toUnsignedInt(
                        snapshot.getBlock(3, 4, 5)));
        assertEquals(
                8,
                Byte.toUnsignedInt(
                        repository
                                .snapshot(key)
                                .orElseThrow()
                                .getBlock(3, 4, 5)));
    }

    @Test
    void injectedRepositoryMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> new World(null));
    }
}
