package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class ChunkGenerationDataTest {
    @Test
    void generationDataDefensivelyCopiesCanonicalBytes() {
        byte[] bytes = blocks(32);
        BlockSize[] blockSizes = blockSizes(32);
        bytes[canonicalIndex(2, 7, 3, 32)] = 5;
        blockSizes[canonicalIndex(2, 7, 3, 32)] = BlockSize.SIZE_8;
        ChunkGenerationData data =
                new ChunkGenerationData(new ChunkKey(0, 0), 32, bytes, blockSizes);

        bytes[canonicalIndex(2, 7, 3, 32)] = 9;
        blockSizes[canonicalIndex(2, 7, 3, 32)] = BlockSize.SIZE_4;
        assertEquals(5, data.getBlock(2, 7, 3));
        assertEquals(BlockSize.SIZE_8, data.getBlockSize(2, 7, 3));

        byte[] returned = data.copyBlocks();
        returned[canonicalIndex(2, 7, 3, 32)] = 1;
        assertEquals(5, data.getBlock(2, 7, 3));
        
        BlockSize[] returnedSizes = data.copyBlockSizes();
        returnedSizes[canonicalIndex(2, 7, 3, 32)] = BlockSize.SIZE_2;
        assertEquals(BlockSize.SIZE_8, data.getBlockSize(2, 7, 3));
    }

    @Test
    void generationDataExposesKeyAndWorldHeight() {
        ChunkKey key = new ChunkKey(-2, 4);

        ChunkGenerationData data =
                new ChunkGenerationData(key, 32, blocks(32), blockSizes(32));

        assertEquals(key, data.key());
        assertEquals(32, data.worldHeight());
    }

    @Test
    void generationDataRejectsNonPositiveWorldHeight() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), 0, new byte[0], new BlockSize[0]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), -1, new byte[0], new BlockSize[0]));
    }

    @Test
    void generationDataRejectsNonCanonicalByteLength() {
        int expectedLength =
                GameConfig.Chunk.SIZE * 32 * GameConfig.Chunk.SIZE;
        BlockSize[] sizes = new BlockSize[expectedLength];
        java.util.Arrays.fill(sizes, BlockSize.SIZE_16);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0),
                                32,
                                new byte[expectedLength - 1],
                                sizes.clone()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0),
                                32,
                                new byte[expectedLength + 1],
                                sizes.clone()));
    }

    @Test
    void generationDataRejectsNullKeyAndBlocks() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkGenerationData(null, 32, blocks(32), blockSizes(32)));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), 32, null, blockSizes(32)));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), 32, blocks(32), (BlockPlacement[]) null));
    }

    @Test
    void generationDataRejectsOutOfBoundsReads() {
        ChunkGenerationData data =
                new ChunkGenerationData(
                        new ChunkKey(0, 0), 32, blocks(32), blockSizes(32));

        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(-1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(GameConfig.Chunk.SIZE, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(0, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(0, 32, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(0, 0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.getBlock(0, 0, GameConfig.Chunk.SIZE));
    }

    private static byte[] blocks(int worldHeight) {
        return new byte[
                GameConfig.Chunk.SIZE
                        * worldHeight
                        * GameConfig.Chunk.SIZE];
    }

    private static BlockSize[] blockSizes(int worldHeight) {
        int length = GameConfig.Chunk.SIZE * worldHeight * GameConfig.Chunk.SIZE;
        BlockSize[] sizes = new BlockSize[length];
        for (int i = 0; i < length; i++) {
            sizes[i] = BlockSize.SIZE_16;
        }
        return sizes;
    }

    private static int canonicalIndex(
            int localX, int y, int localZ, int worldHeight) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * worldHeight;
    }
}