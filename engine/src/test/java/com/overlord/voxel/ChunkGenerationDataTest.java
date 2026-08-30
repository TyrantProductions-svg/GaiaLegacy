package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ChunkGenerationDataTest {
    @Test
    void generationDataDefensivelyCopiesCanonicalBytes() {
        byte[] bytes = blocks(32);
        bytes[canonicalIndex(2, 7, 3, 32)] = 5;
        ChunkGenerationData data =
                new ChunkGenerationData(new ChunkKey(0, 0), 32, bytes);

        bytes[canonicalIndex(2, 7, 3, 32)] = 9;
        assertEquals(5, data.getBlock(2, 7, 3));

        byte[] returned = data.copyBlocks();
        returned[canonicalIndex(2, 7, 3, 32)] = 1;
        assertEquals(5, data.getBlock(2, 7, 3));
    }

    @Test
    void generationDataExposesKeyAndWorldHeight() {
        ChunkKey key = new ChunkKey(-2, 4);

        ChunkGenerationData data =
                new ChunkGenerationData(key, 32, blocks(32));

        assertEquals(key, data.key());
        assertEquals(32, data.worldHeight());
    }

    @Test
    void generationDataRejectsNonPositiveWorldHeight() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), 0, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), -1, new byte[0]));
    }

    @Test
    void generationDataRejectsNonCanonicalByteLength() {
        int expectedLength =
                GameConfig.Chunk.SIZE * 32 * GameConfig.Chunk.SIZE;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0),
                                32,
                                new byte[expectedLength - 1]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0),
                                32,
                                new byte[expectedLength + 1]));
    }

    @Test
    void generationDataRejectsNullKeyAndBlocks() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkGenerationData(null, 32, blocks(32)));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkGenerationData(
                                new ChunkKey(0, 0), 32, null));
    }

    @Test
    void generationDataRejectsOutOfBoundsReads() {
        ChunkGenerationData data =
                new ChunkGenerationData(
                        new ChunkKey(0, 0), 32, blocks(32));

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

    @Test
    void generationDataCarriesTypedImmutableDetail() {
        int worldHeight = 32;
        int parentIndex = canonicalIndex(2, 7, 3, worldHeight);
        byte[] fullBlocks = blocks(worldHeight);
        byte[] ids = new byte[64];
        ids[0] = 6;
        DetailChunkSnapshot details =
                DetailChunkSnapshot.of(
                        new int[] {parentIndex}, new long[] {1L}, ids);
        ChunkGenerationData data =
                new ChunkGenerationData(
                        new ChunkKey(-2, 4),
                        worldHeight,
                        fullBlocks,
                        details);

        ids[0] = 0;
        DetailCellState state =
                assertInstanceOf(
                        DetailCellState.class,
                        data.cellState(2, 7, 3));
        assertEquals(
                6,
                Byte.toUnsignedInt(
                        state.blockId(new LocalSubVoxelPosition(0, 0, 0))));
        assertThrows(
                IllegalStateException.class,
                () -> data.getBlock(2, 7, 3));
        assertThrows(
                IllegalStateException.class,
                data::copyBlocks);
        assertEquals(0, data.copyFullBlocks()[parentIndex]);
    }

    @Test
    void fullOnlyGenerationDataStoresNoEmptyDetailSentinel() {
        ChunkGenerationData data =
                new ChunkGenerationData(
                        new ChunkKey(0, 0), 32, blocks(32));

        assertNull(internalDetails(data));
        assertEquals(0, data.details().entryCount());
        assertInstanceOf(FullCellState.class, data.cellState(0, 0, 0));
    }

    @Test
    void typedGenerationDataRebindsToSnapshotWithoutRawByteBypass() {
        byte[] ids = new byte[64];
        ids[5] = 7;
        DetailChunkSnapshot details = DetailChunkSnapshot.of(
                new int[] {3}, new long[] {1L << 5}, ids);
        ChunkGenerationData data = new ChunkGenerationData(
                new ChunkKey(2, -3), 1, blocks(1), details);

        ChunkSnapshot snapshot = data.toSnapshot(19L);

        assertEquals(19L, snapshot.revision());
        assertEquals(details, snapshot.details());
        assertThrows(IllegalStateException.class, snapshot::copyBlocks);
        assertThrows(IllegalArgumentException.class, () -> data.toSnapshot(0L));
    }

    private static byte[] blocks(int worldHeight) {
        return new byte[
                GameConfig.Chunk.SIZE
                        * worldHeight
                        * GameConfig.Chunk.SIZE];
    }

    private static int canonicalIndex(
            int localX, int y, int localZ, int worldHeight) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * worldHeight;
    }

    private static Object internalDetails(ChunkGenerationData data) {
        try {
            Field field = ChunkGenerationData.class.getDeclaredField("details");
            field.setAccessible(true);
            return field.get(data);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
