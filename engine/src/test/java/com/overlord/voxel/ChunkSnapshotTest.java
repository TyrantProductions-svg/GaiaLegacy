package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class ChunkSnapshotTest {
    @Test
    void ownsDefensiveCopyOfFullChunkBytes() {
        int worldHeight = 32;
        int x = 4;
        int y = 17;
        int z = 6;
        int index =
                x
                        + y * GameConfig.Chunk.SIZE
                        + z * GameConfig.Chunk.SIZE * worldHeight;
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * worldHeight
                                * GameConfig.Chunk.SIZE];
        blocks[index] = 11;

        ChunkKey key = new ChunkKey(-2, 3);
        ChunkSnapshot snapshot =
                ChunkSnapshot.of(key, 9L, worldHeight, blocks);
        blocks[index] = 27;

        assertEquals(key, snapshot.key());
        assertEquals(9L, snapshot.revision());
        assertEquals(worldHeight, snapshot.worldHeight());
        assertEquals(11, Byte.toUnsignedInt(snapshot.getBlock(x, y, z)));
    }

    @Test
    void outOfRangeCoordinatesReadAsAir() {
        ChunkSnapshot snapshot =
                ChunkSnapshot.empty(new ChunkKey(0, 0), 1L, 32);

        assertEquals(0, snapshot.getBlock(0, -1, 0));
        assertEquals(0, snapshot.getBlock(0, 32, 0));
        assertEquals(0, snapshot.getBlock(-1, 0, 0));
        assertEquals(0, snapshot.getBlock(GameConfig.Chunk.SIZE, 0, 0));
        assertEquals(0, snapshot.getBlock(0, 0, -1));
        assertEquals(0, snapshot.getBlock(0, 0, GameConfig.Chunk.SIZE));
    }

    @Test
    void emptySnapshotContainsOnlyAir() {
        ChunkSnapshot snapshot =
                ChunkSnapshot.empty(new ChunkKey(2, -4), 3L, 32);

        assertEquals(0, snapshot.getBlock(0, 0, 0));
        assertEquals(
                0,
                snapshot.getBlock(
                        GameConfig.Chunk.SIZE - 1,
                        snapshot.worldHeight() - 1,
                        GameConfig.Chunk.SIZE - 1));
    }
}
