package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkHybridMeshOutputLimitTest {
    private static final int WORLD_HEIGHT = 4;
    private static final ChunkKey KEY = new ChunkKey(-7, 5);

    @Test
    void checkerboardFixturesRejectBeforeOutputCanExceedEightMibibytes() {
        assertLimitExceeded(input(256, Pattern.CHECKERBOARD));
        assertLimitExceeded(input(1_024, Pattern.CHECKERBOARD));
    }

    @Test
    void maximumStaircaseRejectsWhileUniformAndMeasuredMixedRemainAccepted() {
        assertLimitExceeded(input(1_024, Pattern.STAIRCASE));

        ChunkMeshData uniform = builder().build(input(1_024, Pattern.UNIFORM));
        ChunkMeshData mixed = builder().build(input(1_024, Pattern.MIXED));

        assertEquals(2_949_120L, outputBytes(uniform));
        assertEquals(8_110_080L, outputBytes(mixed));
        assertTrue(outputBytes(mixed) < ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
    }

    @Test
    void diagnosticReportsTheLastCompleteFaceletAndFirstRejectedFacelet() {
        ChunkMeshOutputLimitExceededException failure = assertThrows(
                ChunkMeshOutputLimitExceededException.class,
                () -> builder().build(input(1_024, Pattern.CHECKERBOARD)));

        assertEquals(KEY, failure.chunkKey());
        assertEquals(12L, failure.revision());
        assertEquals(8_388_608L, failure.configuredByteLimit());
        assertEquals(8_388_480L, failure.acceptedByteCount());
        assertEquals(8_388_720L, failure.requiredByteCount());
        assertEquals(34_953L, failure.requiredFaceletCount());
        assertEquals(209_718L, failure.requiredVertexCount());
        assertTrue(failure.allocatedCapacityByteCount()
                <= failure.configuredByteLimit());
    }

    @Test
    void fullOnlyLegacyFastPathIsNotSubjectToTheHybridOutputLimit() {
        ChunkMeshData mesh = builder().build(heavyFullInput());

        assertEquals(23_592_960L, outputBytes(mesh));
        assertTrue(outputBytes(mesh) > ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
    }

    @Test
    void countedPreflightReservesThreeExactArraysWithoutGrowthSlack() {
        ChunkMeshMemoryPlan mixed = builder().preflight(
                input(1_024, Pattern.MIXED));
        assertEquals(8_110_080L, mixed.outputBytes());
        assertEquals(24_330_240L, mixed.activeReservationBytes());

        ChunkMeshMemoryPlan full = builder().preflight(heavyFullInput());
        assertEquals(23_592_960L, full.outputBytes());
        assertEquals(70_778_880L, full.activeReservationBytes());
    }

    @Test
    void exactBuilderReservationHandlesEmptyAndSmallMeshesWithoutGrowthSlack() {
        ChunkSnapshot empty = ChunkSnapshot.empty(KEY, 12L, WORLD_HEIGHT);
        ChunkMeshMemoryPlan emptyPlan = builder().preflight(meshInput(empty));
        assertEquals(0L, emptyPlan.outputBytes());
        assertEquals(0L, emptyPlan.activeReservationBytes());

        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT
                * GameConfig.Chunk.SIZE];
        blocks[0] = 1;
        ChunkMeshInput smallInput = meshInput(
                ChunkSnapshot.of(KEY, 12L, WORLD_HEIGHT, blocks));
        ChunkMeshMemoryPlan small = builder().preflight(smallInput);
        assertEquals(1_440L, small.outputBytes());
        assertEquals(4_320L, small.activeReservationBytes());
        assertThrows(
                IllegalStateException.class,
                () -> builder().build(
                        smallInput,
                        new ChunkMeshMemoryPlan(0L, 0L)));
    }

    @Test
    void representativeMeshHashesRemainStableAcrossAllocationOptimization() {
        ChunkMeshBuilder builder = builder();

        assertAll(
                () -> assertEquals(
                        "0069c6080f912f15d513c8eddd64c8206e7babdaafb5bd86aa99d2dc041f11eb",
                        hash(builder.build(heavyFullInput()))),
                () -> assertEquals(
                        "6021f9d07692d41c2780d92bb53c05aa20ee9da902fdf6c67b261e2bc61730d4",
                        hash(builder.build(input(1_024, Pattern.UNIFORM)))),
                () -> assertEquals(
                        "b2156754ceeec203ed75bb0f09bdf68f137a5c74176e09ed88f012cdf1a03dc1",
                        hash(builder.build(input(1_024, Pattern.MIXED)))));
    }

    private static void assertLimitExceeded(ChunkMeshInput input) {
        ChunkSnapshot before = input.center();
        assertThrows(
                ChunkMeshOutputLimitExceededException.class,
                () -> builder().build(input));
        assertEquals(before, input.center());
    }

    private static long outputBytes(ChunkMeshData data) {
        return Math.multiplyExact(
                Math.multiplyExact(
                        (long) data.vertexCount(),
                        VoxelVertexFormat.FLOATS_PER_VERTEX),
                Float.BYTES);
    }

    private static String hash(ChunkMeshData data) {
        return HexFormat.of().formatHex(data.canonicalHash());
    }

    private static ChunkMeshInput heavyFullInput() {
        int height = 128;
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * height
                * GameConfig.Chunk.SIZE];
        for (int index = 0; index < blocks.length; index++) {
            int x = index % GameConfig.Chunk.SIZE;
            int y = (index / GameConfig.Chunk.SIZE) % height;
            int z = index / (GameConfig.Chunk.SIZE * height);
            if (((x + y + z) & 1) == 0) {
                blocks[index] = 1;
            }
        }
        return meshInput(ChunkSnapshot.of(KEY, 12L, height, blocks));
    }

    private static ChunkMeshBuilder builder() {
        return new ChunkMeshBuilder(ignored -> renderInfo());
    }

    private static ChunkMeshInput input(int parentCount, Pattern pattern) {
        int[] parentIndices = new int[parentCount];
        long[] masks = new long[parentCount];
        byte[] blockIds = new byte[parentCount * DetailCellState.CELL_COUNT];
        long mask = pattern.mask();
        for (int parent = 0; parent < parentCount; parent++) {
            parentIndices[parent] = parent;
            masks[parent] = mask;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((mask & (1L << cell)) != 0L) {
                    blockIds[parent * DetailCellState.CELL_COUNT + cell] =
                            pattern.blockId(cell);
                }
            }
        }
        ChunkSnapshot center = ChunkSnapshot.of(
                KEY,
                12L,
                WORLD_HEIGHT,
                new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT
                        * GameConfig.Chunk.SIZE],
                DetailChunkSnapshot.of(parentIndices, masks, blockIds));
        return meshInput(center);
    }

    private static ChunkMeshInput meshInput(ChunkSnapshot center) {
        return new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);
    }

    private static BlockRenderInfo renderInfo() {
        ResourceLocation atlas = ResourceLocation.parse("test:blocks");
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("test:solid"),
                atlas,
                RenderType.OPAQUE,
                0.5f,
                ResourceLocation.parse("test:missing"));
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("test:solid"),
                0,
                0,
                16,
                16,
                16,
                16);
        Map<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new BlockRenderInfo(material, regions, true);
    }

    private enum Pattern {
        CHECKERBOARD,
        STAIRCASE,
        UNIFORM,
        MIXED;

        long mask() {
            long mask = 0L;
            for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
                int x = index & 3;
                int y = (index >>> 2) & 3;
                int z = index >>> 4;
                boolean occupied = switch (this) {
                    case CHECKERBOARD -> ((x + y + z) & 1) == 0;
                    case STAIRCASE -> y <= x;
                    case UNIFORM -> true;
                    case MIXED -> y == 0;
                };
                if (occupied) {
                    mask |= 1L << index;
                }
            }
            return mask;
        }

        byte blockId(int index) {
            if (this != MIXED) {
                return 1;
            }
            int x = index & 3;
            int z = index >>> 4;
            return (byte) (((x + z) & 1) == 0 ? 1 : 2);
        }
    }
}
