package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkRepositoryTest {
    @Test
    void generationTransitionsOnceAndSnapshotOwnsBytes() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);

        repository.generate(
                key, chunk -> chunk.setBlock(1, 18, 3, (byte) 7));

        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(1L, repository.revision(key));
        ChunkSnapshot snapshot = repository.snapshot(key).orElseThrow();

        repository.setBlock(1, 18, 3, (byte) 9);

        assertEquals(7, Byte.toUnsignedInt(snapshot.getBlock(1, 18, 3)));
        assertEquals(9, Byte.toUnsignedInt(repository.getBlock(1, 18, 3)));
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertEquals(2L, repository.revision(key));
    }

    @Test
    void missingReadsAndAirWritesDoNotCreateEntries() {
        ChunkRepository repository = new ChunkRepository();

        assertEquals(0, repository.getBlock(20, 5, -2));
        assertFalse(repository.setBlock(20, 5, -2, (byte) 0));
        assertTrue(repository.keys().isEmpty());
    }

    @Test
    void nonAirMutationExplicitlyCreatesDirtyEntry() {
        ChunkRepository repository = new ChunkRepository();

        assertTrue(repository.setBlock(20, 5, -2, (byte) 3));

        ChunkKey key = ChunkKey.fromWorld(20, -2);
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertEquals(1L, repository.revision(key));
        assertEquals(3, Byte.toUnsignedInt(repository.getBlock(20, 5, -2)));
    }

    @Test
    void outOfRangeYDoesNotCreateOrMutateEntries() {
        ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());

        assertEquals(0, repository.getBlock(1, -1, 1));
        assertEquals(0, repository.getBlock(1, 32, 1));
        assertFalse(repository.setBlock(1, -1, 1, (byte) 4));
        assertFalse(repository.setBlock(1, 32, 1, (byte) 4));
        assertTrue(repository.keys().isEmpty());
    }

    @Test
    void duplicateGenerationIsRejectedWithoutChangingEntry() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(2, -3);
        repository.generate(
                key, chunk -> chunk.setBlock(4, 5, 6, (byte) 8));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> repository.generate(key, chunk -> {}));

        assertTrue(failure.getMessage().contains(key.toString()));
        assertTrue(failure.getMessage().contains(ChunkState.GENERATED.toString()));
        assertTrue(failure.getMessage().contains(ChunkState.GENERATING.toString()));
        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(1L, repository.revision(key));
        assertEquals(
                8,
                Byte.toUnsignedInt(
                        repository.getBlock(
                                key.worldOriginX() + 4,
                                5,
                                key.worldOriginZ() + 6)));
    }

    @Test
    void generatorFailureRemovesEntryAndRethrowsFailure() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(1, 1);
        IllegalArgumentException expected =
                new IllegalArgumentException("generation failed");

        IllegalArgumentException actual =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                repository.generate(
                                        key,
                                        chunk -> {
                                            chunk.setBlock(1, 1, 1, (byte) 5);
                                            throw expected;
                                        }));

        assertSame(expected, actual);
        assertFalse(repository.contains(key));
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertEquals(0L, repository.revision(key));
        assertTrue(repository.snapshot(key).isEmpty());
    }

    @Test
    void keysAreImmutableAndDetachedFromLaterRepositoryChanges() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(1, 0);
        repository.setBlock(1, 1, 1, (byte) 1);
        Set<ChunkKey> keys = repository.keys();

        assertThrows(UnsupportedOperationException.class, () -> keys.add(second));

        repository.setBlock(17, 1, 1, (byte) 1);
        assertEquals(Set.of(first), keys);
        assertEquals(Set.of(first, second), repository.keys());
    }

    @Test
    void generatedChunksAreNotRenderableBeforeUploadLifecycleCompletes() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);

        assertFalse(repository.isRenderable(key));
        repository.generate(key, chunk -> {});
        assertFalse(repository.isRenderable(key));
    }
}
