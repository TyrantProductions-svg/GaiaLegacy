package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class QuarterVoxelSamplerTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 4;

    @Test
    void fullParentExpandsToSixtyFourOccupiedSamplesWithoutDetailAllocation() {
        ChunkKey key = new ChunkKey(0, 0);
        ChunkSnapshot center = fullSnapshot(key, 2, 1, 3, (byte) 17);
        QuarterVoxelSampler sampler = new QuarterVoxelSampler(input(center));

        for (int subZ = 0; subZ < 4; subZ++) {
            for (int subY = 0; subY < 4; subY++) {
                for (int subX = 0; subX < 4; subX++) {
                    QuarterVoxelSample sample = sampler.sample(
                            2, 1, 3, subX, subY, subZ);
                    assertTrue(sample.occupied());
                    assertEquals(17, Byte.toUnsignedInt(sample.blockId()));
                    assertEquals(
                            QuarterVoxelSample.ParentRepresentation.FULL,
                            sample.parentRepresentation());
                }
            }
        }
        assertTrue(center.details().isEmpty());
    }

    @Test
    void fullAirAndDetailGapRemainDistinctTypedEmptySamples() {
        ChunkKey key = new ChunkKey(0, 0);
        ChunkSnapshot center = detailSnapshot(
                key,
                1,
                1,
                1,
                bit(0, 0, 0) | bit(3, 2, 1),
                new CellMaterial(0, 0, 0, 7),
                new CellMaterial(3, 2, 1, 9));
        QuarterVoxelSampler sampler = new QuarterVoxelSampler(input(center));

        QuarterVoxelSample air = sampler.sample(0, 1, 0, 2, 2, 2);
        QuarterVoxelSample gap = sampler.sample(1, 1, 1, 1, 1, 1);
        QuarterVoxelSample first = sampler.sample(1, 1, 1, 0, 0, 0);
        QuarterVoxelSample second = sampler.sample(1, 1, 1, 3, 2, 1);

        assertFalse(air.occupied());
        assertEquals(
                QuarterVoxelSample.ParentRepresentation.FULL,
                air.parentRepresentation());
        assertFalse(gap.occupied());
        assertEquals(
                QuarterVoxelSample.ParentRepresentation.DETAIL,
                gap.parentRepresentation());
        assertEquals(7, Byte.toUnsignedInt(first.blockId()));
        assertEquals(9, Byte.toUnsignedInt(second.blockId()));
    }

    @Test
    void subcoordinatesWrapThroughEveryHorizontalNeighborSnapshot() {
        ChunkKey centerKey = new ChunkKey(-8, 11);
        ChunkSnapshot center = ChunkSnapshot.empty(centerKey, 1, WORLD_HEIGHT);
        ChunkMeshInput input = new ChunkMeshInput(
                center,
                markerDetail(centerKey.north(), 5, 2, 15, 2, 2, 3, 11),
                markerDetail(centerKey.northEast(), 0, 2, 15, 0, 2, 3, 12),
                markerDetail(centerKey.east(), 0, 2, 5, 0, 2, 2, 13),
                markerDetail(centerKey.southEast(), 0, 2, 0, 0, 2, 0, 14),
                markerDetail(centerKey.south(), 5, 2, 0, 2, 2, 0, 15),
                markerDetail(centerKey.southWest(), 15, 2, 0, 3, 2, 0, 16),
                markerDetail(centerKey.west(), 15, 2, 5, 3, 2, 2, 17),
                markerDetail(centerKey.northWest(), 15, 2, 15, 3, 2, 3, 18));
        QuarterVoxelSampler sampler = new QuarterVoxelSampler(input);

        assertMarker(sampler.sample(5, 2, 0, 2, 2, -1), 11);
        assertMarker(sampler.sample(15, 2, 0, 4, 2, -1), 12);
        assertMarker(sampler.sample(15, 2, 5, 4, 2, 2), 13);
        assertMarker(sampler.sample(15, 2, 15, 4, 2, 4), 14);
        assertMarker(sampler.sample(5, 2, 15, 2, 2, 4), 15);
        assertMarker(sampler.sample(0, 2, 15, -1, 2, 4), 16);
        assertMarker(sampler.sample(0, 2, 5, -1, 2, 2), 17);
        assertMarker(sampler.sample(0, 2, 0, -1, 2, -1), 18);
    }

    @Test
    void verticalWrappingUsesCenterHeightAndReturnsTypedFullAirOutsideIt() {
        ChunkKey key = new ChunkKey(-3, -7);
        ChunkSnapshot center = detailSnapshot(
                key,
                0,
                WORLD_HEIGHT - 1,
                0,
                bit(1, 3, 1),
                new CellMaterial(1, 3, 1, 21));
        QuarterVoxelSampler sampler = new QuarterVoxelSampler(input(center));

        assertMarker(sampler.sample(0, WORLD_HEIGHT - 2, 0, 1, 7, 1), 21);
        assertTypedAir(sampler.sample(0, 0, 0, 1, -1, 1));
        assertTypedAir(sampler.sample(
                0, WORLD_HEIGHT - 1, 0, 1, 4, 1));
    }

    @Test
    void samplerOwnsOnlyDetachedMeshInputAndInputRemainsNineSnapshots() {
        Set<Class<?>> dependencies = Arrays.stream(
                        QuarterVoxelSampler.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .collect(Collectors.toSet());

        assertEquals(Set.of(ChunkMeshInput.class), dependencies);
        assertTrue(Modifier.isFinal(QuarterVoxelSampler.class.getModifiers()));
        assertEquals(9, ChunkMeshInput.class.getRecordComponents().length);
        assertTrue(Arrays.stream(ChunkMeshInput.class.getRecordComponents())
                .allMatch(component -> component.getType() == ChunkSnapshot.class));
    }

    private static void assertMarker(QuarterVoxelSample sample, int expected) {
        assertTrue(sample.occupied(), sample::toString);
        assertEquals(expected, Byte.toUnsignedInt(sample.blockId()));
        assertEquals(
                QuarterVoxelSample.ParentRepresentation.DETAIL,
                sample.parentRepresentation());
    }

    private static void assertTypedAir(QuarterVoxelSample sample) {
        assertFalse(sample.occupied());
        assertEquals(0, sample.blockId());
        assertEquals(
                QuarterVoxelSample.ParentRepresentation.FULL,
                sample.parentRepresentation());
    }

    private static ChunkMeshInput input(ChunkSnapshot center) {
        return new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);
    }

    private static ChunkSnapshot fullSnapshot(
            ChunkKey key, int x, int y, int z, byte blockId) {
        byte[] blocks = new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE];
        blocks[parentIndex(x, y, z)] = blockId;
        return ChunkSnapshot.of(key, 1, WORLD_HEIGHT, blocks);
    }

    private static ChunkSnapshot markerDetail(
            ChunkKey key,
            int parentX,
            int parentY,
            int parentZ,
            int subX,
            int subY,
            int subZ,
            int blockId) {
        return detailSnapshot(
                key,
                parentX,
                parentY,
                parentZ,
                bit(subX, subY, subZ),
                new CellMaterial(subX, subY, subZ, blockId));
    }

    private static ChunkSnapshot detailSnapshot(
            ChunkKey key,
            int parentX,
            int parentY,
            int parentZ,
            long occupancy,
            CellMaterial... materials) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        for (CellMaterial material : materials) {
            ids[index(material.x(), material.y(), material.z())] =
                    (byte) material.blockId();
        }
        return ChunkSnapshot.of(
                key,
                1,
                WORLD_HEIGHT,
                new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE],
                DetailChunkSnapshot.of(
                        new int[] {parentIndex(parentX, parentY, parentZ)},
                        new long[] {occupancy},
                        ids));
    }

    private static int parentIndex(int x, int y, int z) {
        return x + y * CHUNK_SIZE + z * CHUNK_SIZE * WORLD_HEIGHT;
    }

    private static long bit(int x, int y, int z) {
        return 1L << index(x, y, z);
    }

    private static int index(int x, int y, int z) {
        return x + 4 * y + 16 * z;
    }

    private record CellMaterial(int x, int y, int z, int blockId) {}
}
