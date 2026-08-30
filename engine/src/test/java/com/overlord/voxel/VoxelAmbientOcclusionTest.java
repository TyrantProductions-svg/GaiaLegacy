package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoxelAmbientOcclusionTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 8;
    private static final float EPSILON = 0.000001f;
    private static final TextureRegion REGION =
            new TextureRegion(
                    ResourceLocation.parse("test:block"),
                    0,
                    0,
                    16,
                    16,
                    16,
                    16);

    @Test
    void mapsThreeSamplesToTheFourRequiredAoLevels() {
        int x = 7;
        int y = 2;
        int z = 7;

        assertEquals(
                1.0f,
                sample(input(new ChunkKey(0, 0)), x, y, z),
                EPSILON);
        assertEquals(
                0.82f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z + 1, 1)),
                        x,
                        y,
                        z),
                EPSILON);
        assertEquals(
                0.65f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 1),
                                new BlockAt(x + 1, y + 1, z + 1, 1)),
                        x,
                        y,
                        z),
                EPSILON);
        assertEquals(
                0.45f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 1),
                                new BlockAt(x, y + 1, z + 1, 1)),
                        x,
                        y,
                        z),
                EPSILON);
        assertEquals(
                0.45f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 1),
                                new BlockAt(x, y + 1, z + 1, 1),
                                new BlockAt(x + 1, y + 1, z + 1, 1)),
                        x,
                        y,
                        z),
                EPSILON);
    }

    @Test
    void airAndTransparentAndNonRenderableBlocksDoNotOcclude() {
        int x = 7;
        int y = 2;
        int z = 7;

        assertEquals(
                1.0f,
                VoxelAmbientOcclusion.sample(
                        input(new ChunkKey(0, 0)),
                        ignored -> {
                            throw new AssertionError("air must not resolve");
                        },
                        x,
                        y,
                        z,
                        BlockFace.UP,
                        1,
                        1),
                EPSILON);
        assertEquals(
                1.0f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 3),
                                new BlockAt(x, y + 1, z + 1, 3),
                                new BlockAt(x + 1, y + 1, z + 1, 3)),
                        x,
                        y,
                        z),
                EPSILON);
        assertEquals(
                1.0f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 4),
                                new BlockAt(x, y + 1, z + 1, 4),
                                new BlockAt(x + 1, y + 1, z + 1, 4)),
                        x,
                        y,
                        z),
                EPSILON);
    }

    @Test
    void renderableOpaqueAndCutoutBlocksOcclude() {
        int x = 7;
        int y = 2;
        int z = 7;

        assertEquals(
                0.82f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 1)),
                        x,
                        y,
                        z),
                EPSILON);
        assertEquals(
                0.82f,
                sample(
                        input(
                                new ChunkKey(0, 0),
                                new BlockAt(x + 1, y + 1, z, 2)),
                        x,
                        y,
                        z),
                EPSILON);
    }

    @Test
    void quarterGridUsesTheSameThreeSampleAoLevelsForDetailGeometry() {
        assertEquals(1.0f, quarterSample(quarterInput()), EPSILON);
        assertEquals(
                0.82f,
                quarterSample(quarterInput(
                        new QuarterAt(2, 2, 2, 1))),
                EPSILON);
        assertEquals(
                0.65f,
                quarterSample(quarterInput(
                        new QuarterAt(2, 2, 1, 1),
                        new QuarterAt(2, 2, 2, 1))),
                EPSILON);
        assertEquals(
                0.45f,
                quarterSample(quarterInput(
                        new QuarterAt(2, 2, 1, 1),
                        new QuarterAt(1, 2, 2, 2))),
                EPSILON);
    }

    @Test
    void quarterGridAoIgnoresTransparentAndNonRenderableDetailMaterials() {
        assertEquals(
                1.0f,
                quarterSample(quarterInput(
                        new QuarterAt(2, 2, 1, 3),
                        new QuarterAt(1, 2, 2, 4),
                        new QuarterAt(2, 2, 2, 3))),
                EPSILON);
        assertEquals(
                0.82f,
                quarterSample(quarterInput(
                        new QuarterAt(2, 2, 1, 3),
                        new QuarterAt(1, 2, 2, 4),
                        new QuarterAt(2, 2, 2, 1))),
                EPSILON);
    }

    @Test
    void rejectsTangentSignsOtherThanNegativeOrPositiveOne() {
        ChunkMeshInput input = input(new ChunkKey(0, 0));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VoxelAmbientOcclusion.sample(
                                input,
                                VoxelAmbientOcclusionTest::resolve,
                                7,
                                2,
                                7,
                                BlockFace.NORTH,
                                0,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VoxelAmbientOcclusion.sample(
                                input,
                                VoxelAmbientOcclusionTest::resolve,
                                7,
                                2,
                                7,
                                BlockFace.NORTH,
                                -1,
                                2));
    }

    @Test
    void usesTheExactTangentBasisForEveryFaceAndCorner() {
        Orientation[] orientations = {
            new Orientation(BlockFace.NORTH, 0, 0, -1, 1, 0, 0, 0, 1, 0),
            new Orientation(BlockFace.SOUTH, 0, 0, 1, 1, 0, 0, 0, 1, 0),
            new Orientation(BlockFace.UP, 0, 1, 0, 1, 0, 0, 0, 0, 1),
            new Orientation(BlockFace.DOWN, 0, -1, 0, 1, 0, 0, 0, 0, 1),
            new Orientation(BlockFace.WEST, -1, 0, 0, 0, 0, 1, 0, 1, 0),
            new Orientation(BlockFace.EAST, 1, 0, 0, 0, 0, 1, 0, 1, 0)
        };
        int x = 7;
        int y = 3;
        int z = 7;

        for (Orientation orientation : orientations) {
            for (int signA : new int[] {-1, 1}) {
                for (int signB : new int[] {-1, 1}) {
                    BlockAt expectedCorner =
                            new BlockAt(
                                    x
                                            + orientation.normalX()
                                            + signA * orientation.tangentAX()
                                            + signB * orientation.tangentBX(),
                                    y
                                            + orientation.normalY()
                                            + signA * orientation.tangentAY()
                                            + signB * orientation.tangentBY(),
                                    z
                                            + orientation.normalZ()
                                            + signA * orientation.tangentAZ()
                                            + signB * orientation.tangentBZ(),
                                    1);

                    assertEquals(
                            0.82f,
                            VoxelAmbientOcclusion.sample(
                                    input(new ChunkKey(0, 0), expectedCorner),
                                    VoxelAmbientOcclusionTest::resolve,
                                    x,
                                    y,
                                    z,
                                    orientation.face(),
                                    signA,
                                    signB),
                            EPSILON,
                            orientation.face()
                                    + " corner "
                                    + signA
                                    + ","
                                    + signB);
                }
            }
        }
    }

    @Test
    void samplesEveryDiagonalSnapshotForPositiveAndNegativeCenterKeys() {
        Diagonal[] diagonals = {
            new Diagonal(0, 0, -1, -1),
            new Diagonal(CHUNK_SIZE - 1, 0, 1, -1),
            new Diagonal(CHUNK_SIZE - 1, CHUNK_SIZE - 1, 1, 1),
            new Diagonal(0, CHUNK_SIZE - 1, -1, 1)
        };

        for (ChunkKey centerKey
                : new ChunkKey[] {
                    new ChunkKey(4, 6), new ChunkKey(-4, -6)
                }) {
            for (Diagonal diagonal : diagonals) {
                int y = 2;
                BlockAt corner =
                        new BlockAt(
                                diagonal.blockX() + diagonal.signA(),
                                y + 1,
                                diagonal.blockZ() + diagonal.signB(),
                                1);

                assertEquals(
                        0.82f,
                        VoxelAmbientOcclusion.sample(
                                input(centerKey, corner),
                                VoxelAmbientOcclusionTest::resolve,
                                diagonal.blockX(),
                                y,
                                diagonal.blockZ(),
                                BlockFace.UP,
                                diagonal.signA(),
                                diagonal.signB()),
                        EPSILON,
                        centerKey + " diagonal " + diagonal);
            }
        }
    }

    private static float sample(
            ChunkMeshInput input, int x, int y, int z) {
        return VoxelAmbientOcclusion.sample(
                input,
                VoxelAmbientOcclusionTest::resolve,
                x,
                y,
                z,
                BlockFace.UP,
                1,
                1);
    }

    private static float quarterSample(ChunkMeshInput input) {
        return VoxelAmbientOcclusion.sampleQuarter(
                new QuarterVoxelSampler(input),
                VoxelAmbientOcclusionTest::resolve,
                7,
                2,
                7,
                1,
                1,
                1,
                BlockFace.UP,
                1,
                1);
    }

    private static ChunkMeshInput quarterInput(QuarterAt... cells) {
        byte[] blockIds = new byte[DetailCellState.CELL_COUNT];
        long occupancy = 0L;
        for (QuarterAt cell : cells) {
            int subIndex = cell.x() + 4 * cell.y() + 16 * cell.z();
            occupancy |= 1L << subIndex;
            blockIds[subIndex] = (byte) cell.id();
        }
        ChunkKey key = new ChunkKey(0, 0);
        if (occupancy == 0L) {
            return input(key);
        }
        int parentIndex = 7 + 2 * CHUNK_SIZE + 7 * CHUNK_SIZE * WORLD_HEIGHT;
        ChunkSnapshot center = ChunkSnapshot.of(
                key,
                1,
                WORLD_HEIGHT,
                new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE],
                DetailChunkSnapshot.of(
                        new int[] {parentIndex},
                        new long[] {occupancy},
                        blockIds));
        return new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);
    }

    private static BlockRenderInfo resolve(int blockId) {
        return switch (blockId) {
            case 1 -> renderInfo(RenderType.OPAQUE, true);
            case 2 -> renderInfo(RenderType.CUTOUT, true);
            case 3 -> renderInfo(RenderType.TRANSPARENT, true);
            case 4 -> renderInfo(RenderType.OPAQUE, false);
            default -> throw new AssertionError("unexpected block id: " + blockId);
        };
    }

    private static BlockRenderInfo renderInfo(
            RenderType renderType, boolean renderable) {
        MaterialDefinition material =
                new MaterialDefinition(
                        ResourceLocation.of(
                                "test", renderType.name().toLowerCase()),
                        ResourceLocation.parse("test:blocks"),
                        renderType,
                        0.5f,
                        REGION.id());
        if (!renderable) {
            return BlockRenderInfo.nonRenderable(material, REGION);
        }
        Map<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, REGION);
        }
        return new BlockRenderInfo(material, regions, true);
    }

    private static ChunkMeshInput input(
            ChunkKey centerKey, BlockAt... blocks) {
        byte[][] snapshotBlocks = new byte[9][];
        for (int index = 0; index < snapshotBlocks.length; index++) {
            snapshotBlocks[index] =
                    new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE];
        }
        for (BlockAt block : blocks) {
            int offsetX = Math.floorDiv(block.x(), CHUNK_SIZE);
            int offsetZ = Math.floorDiv(block.z(), CHUNK_SIZE);
            if (Math.abs(offsetX) > 1 || Math.abs(offsetZ) > 1) {
                throw new IllegalArgumentException("block exceeds test halo");
            }
            int snapshotIndex = (offsetZ + 1) * 3 + offsetX + 1;
            int localX = Math.floorMod(block.x(), CHUNK_SIZE);
            int localZ = Math.floorMod(block.z(), CHUNK_SIZE);
            int blockIndex =
                    localX
                            + block.y() * CHUNK_SIZE
                            + localZ * CHUNK_SIZE * WORLD_HEIGHT;
            snapshotBlocks[snapshotIndex][blockIndex] = (byte) block.id();
        }

        ChunkSnapshot[] snapshots = new ChunkSnapshot[9];
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                int snapshotIndex = (offsetZ + 1) * 3 + offsetX + 1;
                snapshots[snapshotIndex] =
                        ChunkSnapshot.of(
                                new ChunkKey(
                                        centerKey.x() + offsetX,
                                        centerKey.z() + offsetZ),
                                1,
                                WORLD_HEIGHT,
                                snapshotBlocks[snapshotIndex]);
            }
        }
        return new ChunkMeshInput(
                snapshots[4],
                snapshots[1],
                snapshots[2],
                snapshots[5],
                snapshots[8],
                snapshots[7],
                snapshots[6],
                snapshots[3],
                snapshots[0]);
    }

    private record BlockAt(int x, int y, int z, int id) {}

    private record QuarterAt(int x, int y, int z, int id) {}

    private record Diagonal(
            int blockX, int blockZ, int signA, int signB) {}

    private record Orientation(
            BlockFace face,
            int normalX,
            int normalY,
            int normalZ,
            int tangentAX,
            int tangentAY,
            int tangentAZ,
            int tangentBX,
            int tangentBY,
            int tangentBZ) {}
}
