package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkMeshBuilderTest {
    private static final float EPSILON = 0.000001f;
    private static final int WORLD_HEIGHT = GameConfig.Chunk.SIZE;
    private static final BlockFace[] FACE_ORDER = {
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.WEST,
        BlockFace.EAST
    };
    private static final float[][] FACE_NORMALS = {
        {0.0f, 0.0f, -1.0f},
        {0.0f, 0.0f, 1.0f},
        {0.0f, 1.0f, 0.0f},
        {0.0f, -1.0f, 0.0f},
        {-1.0f, 0.0f, 0.0f},
        {1.0f, 0.0f, 0.0f}
    };
    private static final float[] FACE_LIGHTS = {
        15.0f, 31.0f, 47.0f, 63.0f, 79.0f, 95.0f
    };

    @Test
    void resolvesDistinctAtlasRegionForEachBlockFace() {
        BlockRenderInfo renderInfo = renderInfo();
        AtomicInteger resolvedId = new AtomicInteger(-1);
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(unsignedBlockId -> {
                    resolvedId.set(unsignedBlockId);
                    return renderInfo;
                });

        ChunkMeshData data =
                meshBuilder.build(singleBlockInput(1, 1, 1, (byte) 0xFF));
        float[] vertices = data.vertices();

        assertEquals(255, resolvedId.get());
        assertEquals(360, vertices.length);
        for (int face = 0; face < FACE_ORDER.length; face++) {
            assertFaceUBounds(
                    vertices,
                    face * 60,
                    face * 16.0f / 96.0f,
                    (face + 1) * 16.0f / 96.0f);
            assertFaceVertexData(
                    vertices,
                    face * 60,
                    FACE_ORDER[face],
                    FACE_NORMALS[face][0],
                    FACE_NORMALS[face][1],
                    FACE_NORMALS[face][2],
                    FACE_LIGHTS[face]);
        }
    }

    @Test
    void skipsNonRenderableNonZeroStoredBlock() {
        MaterialDefinition material = material();
        TextureRegion fallback = region("fallback", 0);
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(
                        unsignedBlockId ->
                                BlockRenderInfo.nonRenderable(
                                        material, fallback));

        ChunkMeshData data =
                meshBuilder.build(singleBlockInput(1, 1, 1, (byte) 7));

        assertTrue(data.isEmpty());
    }

    @Test
    void nonRenderableNonZeroNeighborDoesNotOccludeFace() {
        BlockRenderInfo solid = renderInfo();
        BlockRenderInfo nonRenderable =
                BlockRenderInfo.nonRenderable(
                        material(), region("fallback", 0));
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(
                        unsignedBlockId ->
                                unsignedBlockId == 1
                                        ? solid
                                        : nonRenderable);
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey,
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        2,
                        (byte) 1);
        ChunkSnapshot east =
                snapshotWithBlock(
                        centerKey.east(), 1, 0, 1, 2, (byte) 7);

        ChunkMeshData data =
                meshBuilder.build(
                        meshInput(center, null, east, null, null));

        assertEquals(360, data.vertices().length);
    }

    @Test
    void usesNeighborSnapshotToHideEastBoundaryFace() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey,
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        2,
                        (byte) 1);
        ChunkSnapshot east =
                snapshotWithBlock(
                        centerKey.east(), 1, 0, 1, 2, (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, east, null, null));

        assertEquals(300, data.vertices().length);
    }

    @Test
    void usesNeighborSnapshotToHideWestBoundaryFace() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey, 1, 0, 1, 2, (byte) 1);
        ChunkSnapshot west =
                snapshotWithBlock(
                        centerKey.west(),
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        2,
                        (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, null, null, west));

        assertEquals(300, data.vertices().length);
    }

    @Test
    void usesNeighborSnapshotToHideNorthBoundaryFace() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey, 1, 2, 1, 0, (byte) 1);
        ChunkSnapshot north =
                snapshotWithBlock(
                        centerKey.north(),
                        1,
                        2,
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, north, null, null, null));

        assertEquals(300, data.vertices().length);
    }

    @Test
    void usesNeighborSnapshotToHideSouthBoundaryFace() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey,
                        1,
                        2,
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 1);
        ChunkSnapshot south =
                snapshotWithBlock(
                        centerKey.south(), 1, 2, 1, 0, (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, null, south, null));

        assertEquals(300, data.vertices().length);
    }

    @Test
    void missingNeighborsBehaveAsAir() {
        ChunkSnapshot center =
                snapshotWithBlock(
                        new ChunkKey(0, 0),
                        1,
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        2,
                        (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, null, null, null));

        assertEquals(360, data.vertices().length);
    }

    @Test
    void emptyCenterSnapshotProducesEmptyMesh() {
        ChunkSnapshot center =
                ChunkSnapshot.empty(
                        new ChunkKey(3, -2), 9, WORLD_HEIGHT);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, null, null, null));

        assertTrue(data.isEmpty());
        assertTrue(data.localBounds().isEmpty());
    }

    @Test
    void emittedVerticesAreChunkLocalAndBoundsAreLocal() {
        ChunkMeshData data =
                builder().build(
                        singleBlockInput(
                                GameConfig.Chunk.SIZE - 1,
                                4,
                                3,
                                (byte) 1));

        assertEquals(
                new AxisAlignedBounds(
                        GameConfig.Chunk.SIZE - 1,
                        4,
                        3,
                        GameConfig.Chunk.SIZE,
                        5,
                        4),
                data.localBounds().orElseThrow());
        assertTrue(
                maxPositionX(data.vertices())
                        <= GameConfig.Chunk.SIZE);
    }

    @Test
    void propagatesCenterKeyAndRevision() {
        ChunkKey key = new ChunkKey(-4, 7);
        ChunkSnapshot center =
                snapshotWithBlock(
                        key, 42, 1, 2, 3, (byte) 1);

        ChunkMeshData data =
                builder().build(
                        meshInput(center, null, null, null, null));

        assertEquals(key, data.key());
        assertEquals(42, data.revision());
    }

    @Test
    void rejectsMissingCenterSnapshot() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkMeshInput(
                        null, null, null, null, null, null, null,
                        null, null));
    }

    @Test
    void rejectsNeighborWithWrongCardinalKey() {
        ChunkSnapshot center =
                ChunkSnapshot.empty(
                        new ChunkKey(0, 0), 1, WORLD_HEIGHT);
        ChunkSnapshot wrongEast =
                ChunkSnapshot.empty(
                        new ChunkKey(2, 0), 1, WORLD_HEIGHT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkMeshInput(
                        center, null, null, wrongEast, null, null,
                        null, null, null));
    }

    @Test
    void rejectsNeighborWithDifferentWorldHeight() {
        ChunkSnapshot center =
                ChunkSnapshot.empty(
                        new ChunkKey(0, 0), 1, WORLD_HEIGHT);
        ChunkSnapshot east =
                ChunkSnapshot.empty(
                        center.key().east(), 1, WORLD_HEIGHT + 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkMeshInput(
                        center, null, null, east, null, null, null,
                        null, null));
    }

    private static ChunkMeshBuilder builder() {
        return new ChunkMeshBuilder(ignored -> renderInfo());
    }

    private static ChunkMeshInput singleBlockInput(
            int x, int y, int z, byte block) {
        ChunkSnapshot center =
                snapshotWithBlock(
                        new ChunkKey(0, 0), 1, x, y, z, block);
        return meshInput(center, null, null, null, null);
    }

    @Test
    void preservesLegacyCubeGeometryAndWritesAoOnlyAtOffsetNine() {
        ChunkKey centerKey = new ChunkKey(-4, 6);
        ChunkSnapshot center =
                snapshotWithBlock(
                        centerKey,
                        11,
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        0,
                        (byte) 1);
        ChunkSnapshot northEast =
                snapshotWithBlock(
                        centerKey.northEast(),
                        3,
                        0,
                        2,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 1);

        ChunkMeshData data =
                builder()
                        .build(
                                new ChunkMeshInput(
                                        center,
                                        null,
                                        northEast,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null));
        float[] vertices = data.vertices();

        assertEquals(36, data.vertexCount());
        assertEquals(
                36 * VoxelVertexFormat.FLOATS_PER_VERTEX,
                vertices.length);
        float[] expectedPositions = {
            15, 1, 0, 16, 1, 0, 16, 2, 0,
            16, 2, 0, 15, 2, 0, 15, 1, 0,
            15, 1, 1, 16, 1, 1, 16, 2, 1,
            16, 2, 1, 15, 2, 1, 15, 1, 1,
            15, 2, 1, 16, 2, 1, 16, 2, 0,
            16, 2, 0, 15, 2, 0, 15, 2, 1,
            15, 1, 0, 16, 1, 0, 16, 1, 1,
            16, 1, 1, 15, 1, 1, 15, 1, 0,
            15, 1, 0, 15, 1, 1, 15, 2, 1,
            15, 2, 1, 15, 2, 0, 15, 1, 0,
            16, 1, 1, 16, 1, 0, 16, 2, 0,
            16, 2, 0, 16, 2, 1, 16, 1, 1
        };
        for (int face = 0; face < FACE_ORDER.length; face++) {
            float uMin = (float) (face * 16) / 96;
            float uMax = (float) ((face + 1) * 16) / 96;
            float v0 = face == 2 ? 0.0f : 1.0f;
            float v1 = face == 2 ? 1.0f : 0.0f;
            float[] expectedUv = {
                uMin, v0, uMax, v0, uMax, v1,
                uMax, v1, uMin, v1, uMin, v0
            };
            for (int vertex = 0; vertex < 6; vertex++) {
                int offset =
                        (face * 6 + vertex)
                                * VoxelVertexFormat.FLOATS_PER_VERTEX;
                int positionOffset = (face * 6 + vertex) * 3;
                assertFloatBits(
                        expectedPositions[positionOffset],
                        vertices[offset]);
                assertFloatBits(
                        expectedPositions[positionOffset + 1],
                        vertices[offset + 1]);
                assertFloatBits(
                        expectedPositions[positionOffset + 2],
                        vertices[offset + 2]);
                assertFloatBits(
                        expectedUv[vertex * 2], vertices[offset + 3]);
                assertFloatBits(
                        expectedUv[vertex * 2 + 1],
                        vertices[offset + 4]);
                assertFloatBits(
                        FACE_NORMALS[face][0], vertices[offset + 5]);
                assertFloatBits(
                        FACE_NORMALS[face][1], vertices[offset + 6]);
                assertFloatBits(
                        FACE_NORMALS[face][2], vertices[offset + 7]);
                assertFloatBits(FACE_LIGHTS[face], vertices[offset + 8]);

                float expectedAo =
                        (face == 0 || face == 2 || face == 5)
                                        && (vertex == 2 || vertex == 3)
                                ? 0.82f
                                : 1.0f;
                assertFloatBits(expectedAo, vertices[offset + 9]);
            }
        }
    }

    private static ChunkMeshInput meshInput(
            ChunkSnapshot center,
            ChunkSnapshot north,
            ChunkSnapshot east,
            ChunkSnapshot south,
            ChunkSnapshot west) {
        return new ChunkMeshInput(
                center, north, null, east, null, south, null, west,
                null);
    }

    private static ChunkSnapshot snapshotWithBlock(
            ChunkKey key,
            long revision,
            int x,
            int y,
            int z,
            byte block) {
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * WORLD_HEIGHT
                                * GameConfig.Chunk.SIZE];
        int index =
                x
                        + y * GameConfig.Chunk.SIZE
                        + z * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[index] = block;
        return ChunkSnapshot.of(
                key, revision, WORLD_HEIGHT, blocks);
    }

    private static BlockRenderInfo renderInfo() {
        Map<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (int face = 0; face < FACE_ORDER.length; face++) {
            regions.put(
                    FACE_ORDER[face],
                    region(
                            FACE_ORDER[face]
                                    .name()
                                    .toLowerCase(),
                            face));
        }
        return new BlockRenderInfo(material(), regions, true);
    }

    private static MaterialDefinition material() {
        return new MaterialDefinition(
                ResourceLocation.parse("test:opaque"),
                ResourceLocation.parse("test:blocks"),
                RenderType.OPAQUE,
                0.5f,
                ResourceLocation.parse("test:missing"));
    }

    private static TextureRegion region(
            String name, int column) {
        return new TextureRegion(
                ResourceLocation.of("test", name),
                column * 16,
                0,
                16,
                16,
                96,
                16);
    }

    private static void assertFaceUBounds(
            float[] vertices,
            int faceOffset,
            float expectedMin,
            float expectedMax) {
        float actualMin = Float.POSITIVE_INFINITY;
        float actualMax = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < 6; vertex++) {
            float u = vertices[faceOffset + vertex * 10 + 3];
            actualMin = Math.min(actualMin, u);
            actualMax = Math.max(actualMax, u);
        }
        assertEquals(expectedMin, actualMin, EPSILON);
        assertEquals(expectedMax, actualMax, EPSILON);
    }

    private static float maxPositionX(float[] vertices) {
        float maximum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < vertices.length; offset += 10) {
            maximum = Math.max(maximum, vertices[offset]);
        }
        return maximum;
    }

    private static void assertFaceVertexData(
            float[] vertices,
            int faceOffset,
            BlockFace face,
            float normalX,
            float normalY,
            float normalZ,
            float faceLight) {
        for (int vertex = 0; vertex < 6; vertex++) {
            int offset = faceOffset + vertex * 10;
            assertEquals(normalX, vertices[offset + 5], EPSILON);
            assertEquals(normalY, vertices[offset + 6], EPSILON);
            assertEquals(normalZ, vertices[offset + 7], EPSILON);
            assertEquals(
                    faceLight,
                    vertices[offset + 8],
                    EPSILON);
            assertEquals(1.0f, vertices[offset + 9], EPSILON);
        }
    }

    private static void assertFloatBits(
            float expected, float actual) {
        assertEquals(
                Float.floatToIntBits(expected),
                Float.floatToIntBits(actual));
    }
}
