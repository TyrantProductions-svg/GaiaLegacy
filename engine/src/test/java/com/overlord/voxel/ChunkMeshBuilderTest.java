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
        assertEquals(180, vertices.length);
        for (int face = 0; face < FACE_ORDER.length; face++) {
            assertFaceUBoundsWithVariant(
                    vertices,
                    face * 30,
                    FACE_ORDER[face],
                    1, 1, 1);
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
                        new ChunkMeshInput(
                                center, null, null, null, east));

        assertEquals(180, data.vertices().length);
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
                        new ChunkMeshInput(
                                center, null, null, null, east));

        assertEquals(150, data.vertices().length);
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
                        new ChunkMeshInput(
                                center, null, null, west, null));

        assertEquals(150, data.vertices().length);
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
                        new ChunkMeshInput(
                                center, north, null, null, null));

        assertEquals(150, data.vertices().length);
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
                        new ChunkMeshInput(
                                center, null, south, null, null));

        assertEquals(150, data.vertices().length);
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
                        new ChunkMeshInput(
                                center, null, null, null, null));

        assertEquals(180, data.vertices().length);
    }

    @Test
    void emptyCenterSnapshotProducesEmptyMesh() {
        ChunkSnapshot center =
                ChunkSnapshot.empty(
                        new ChunkKey(3, -2), 9, WORLD_HEIGHT);

        ChunkMeshData data =
                builder().build(
                        new ChunkMeshInput(
                                center, null, null, null, null));

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
                        new ChunkMeshInput(
                                center, null, null, null, null));

        assertEquals(key, data.key());
        assertEquals(42, data.revision());
    }

    @Test
    void rejectsMissingCenterSnapshot() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkMeshInput(
                        null, null, null, null, null));
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
                        center, null, null, null, wrongEast));
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
                        center, null, null, null, east));
    }

    private static ChunkMeshBuilder builder() {
        return new ChunkMeshBuilder(ignored -> renderInfo());
    }

    private static ChunkMeshInput singleBlockInput(
            int x, int y, int z, byte block) {
        ChunkSnapshot center =
                snapshotWithBlock(
                        new ChunkKey(0, 0), 1, x, y, z, block);
        return new ChunkMeshInput(
                center, null, null, null, null);
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
            float u = vertices[faceOffset + vertex * 5 + 3];
            actualMin = Math.min(actualMin, u);
            actualMax = Math.max(actualMax, u);
        }
        assertEquals(expectedMin, actualMin, EPSILON);
        assertEquals(expectedMax, actualMax, EPSILON);
    }

    private static void assertFaceUBoundsWithVariant(
            float[] vertices,
            int faceOffset,
            BlockFace face,
            int x, int y, int z) {
        int variant = computeExpectedVariant(x, y, z);
        float baseUMin = face.ordinal() * 16.0f / 96.0f;
        float baseUMax = (face.ordinal() + 1) * 16.0f / 96.0f;
        float variantWidth = (baseUMax - baseUMin) / 4.0f;
        float expectedMin = baseUMin + variant * variantWidth;
        float expectedMax = baseUMin + (variant + 1) * variantWidth;
        
        float actualMin = Float.POSITIVE_INFINITY;
        float actualMax = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < 6; vertex++) {
            float u = vertices[faceOffset + vertex * 5 + 3];
            actualMin = Math.min(actualMin, u);
            actualMax = Math.max(actualMax, u);
        }
        assertEquals(expectedMin, actualMin, EPSILON);
        assertEquals(expectedMax, actualMax, EPSILON);
    }

    private static int computeExpectedVariant(int x, int y, int z) {
        return 0;
    }

    private static float maxPositionX(float[] vertices) {
        float maximum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < vertices.length; offset += 5) {
            maximum = Math.max(maximum, vertices[offset]);
        }
        return maximum;
    }

    @Test
    void rendersSmallerBlockWithScaledVertices() {
        BlockRenderInfo renderInfo = renderInfo();
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(ignored -> renderInfo);

        ChunkKey centerKey = new ChunkKey(0, 0);
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        BlockSize[] blockSizes = new BlockSize[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        int index = 2 + 3 * GameConfig.Chunk.SIZE + 4 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[index] = 1;
        blockSizes[index] = BlockSize.SIZE_8;

        ChunkSnapshot center = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data = meshBuilder.build(new ChunkMeshInput(center, null, null, null, null));

        float[] vertices = data.vertices();
        assertEquals(180, vertices.length);

        for (int offset = 0; offset < vertices.length; offset += 5) {
            float px = vertices[offset];
            float py = vertices[offset + 1];
            float pz = vertices[offset + 2];
            assertTrue(px >= 2.0f && px <= 2.5f, "X should be in [2.0, 2.5] but was " + px);
            assertTrue(py >= 3.0f && py <= 3.5f, "Y should be in [3.0, 3.5] but was " + py);
            assertTrue(pz >= 4.0f && pz <= 4.5f, "Z should be in [4.0, 4.5] but was " + pz);
        }
    }

    @Test
    void rendersMultipleBlockSizesInSameChunk() {
        BlockRenderInfo renderInfo = renderInfo();
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(ignored -> renderInfo);

        ChunkKey centerKey = new ChunkKey(0, 0);
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        BlockSize[] blockSizes = new BlockSize[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];

        int idx1 = 0 + 0 * GameConfig.Chunk.SIZE + 0 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx1] = 1;
        blockSizes[idx1] = BlockSize.SIZE_16;

        int idx2 = 1 + 0 * GameConfig.Chunk.SIZE + 0 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx2] = 1;
        blockSizes[idx2] = BlockSize.SIZE_8;

        ChunkSnapshot center = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data = meshBuilder.build(new ChunkMeshInput(center, null, null, null, null));

        float[] vertices = data.vertices();
        assertTrue(vertices.length > 0, "Should produce vertices for both blocks");
        assertTrue(vertices.length < 360, "Should have fewer than 12 faces due to occlusion");

        boolean foundSmallBlock = false;
        boolean foundLargeBlock = false;
        for (int offset = 0; offset < vertices.length; offset += 30) {
            float minX = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            for (int v = 0; v < 6; v++) {
                float x = vertices[offset + v * 5];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
            if (maxX - minX <= 0.5f && minX >= 1.0f) {
                foundSmallBlock = true;
            }
            if (maxX - minX >= 0.9f && minX < 0.1f) {
                foundLargeBlock = true;
            }
        }
        assertTrue(foundSmallBlock, "Should render the SIZE_8 block at position 1");
        assertTrue(foundLargeBlock, "Should render the SIZE_16 block at position 0");
    }

    @Test
    void largerNeighborOccludesSmallerBlockFace() {
        BlockRenderInfo renderInfo = renderInfo();
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(ignored -> renderInfo);

        ChunkKey centerKey = new ChunkKey(0, 0);
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        BlockSize[] blockSizes = new BlockSize[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];

        int idx1 = 1 + 1 * GameConfig.Chunk.SIZE + 1 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx1] = 1;
        blockSizes[idx1] = BlockSize.SIZE_8;

        int idx2 = 2 + 1 * GameConfig.Chunk.SIZE + 1 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx2] = 1;
        blockSizes[idx2] = BlockSize.SIZE_16;

        ChunkSnapshot center = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data = meshBuilder.build(new ChunkMeshInput(center, null, null, null, null));

        float[] vertices = data.vertices();
        assertEquals(330, vertices.length);

        int smallerBlockFaces = 0;
        int largerBlockFaces = 0;
        for (int offset = 0; offset < vertices.length; offset += 30) {
            float px = vertices[offset];
            if (px >= 1.0f && px <= 1.5f) {
                smallerBlockFaces++;
            } else if (px >= 2.0f && px <= 3.0f) {
                largerBlockFaces++;
            }
        }
        assertEquals(5, smallerBlockFaces, "Smaller block should have 5 faces (east occluded by larger)");
        assertEquals(6, largerBlockFaces, "Larger block should have 6 faces (smaller doesn't occlude)");
    }

    @Test
    void smallerNeighborDoesNotOccludeLargerBlockFace() {
        BlockRenderInfo renderInfo = renderInfo();
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(ignored -> renderInfo);

        ChunkKey centerKey = new ChunkKey(0, 0);
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        BlockSize[] blockSizes = new BlockSize[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];

        int idx1 = 1 + 1 * GameConfig.Chunk.SIZE + 1 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx1] = 1;
        blockSizes[idx1] = BlockSize.SIZE_16;

        int idx2 = 2 + 1 * GameConfig.Chunk.SIZE + 1 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx2] = 1;
        blockSizes[idx2] = BlockSize.SIZE_8;

        ChunkSnapshot center = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data = meshBuilder.build(new ChunkMeshInput(center, null, null, null, null));

        float[] vertices = data.vertices();
        assertTrue(vertices.length > 0, "Should produce vertices for both blocks");

        boolean foundLargeBlock = false;
        for (int offset = 0; offset < vertices.length; offset += 30) {
            float minX = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            for (int v = 0; v < 6; v++) {
                float x = vertices[offset + v * 5];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
            if (maxX - minX >= 0.9f && minX >= 0.9f && minX <= 1.1f) {
                foundLargeBlock = true;
                break;
            }
        }
        assertTrue(foundLargeBlock, "Larger block should render even with smaller neighbor");
    }

    @Test
    void positionBasedTextureVariantsAreDeterministic() {
        BlockRenderInfo renderInfo = renderInfo();
        ChunkMeshBuilder meshBuilder =
                new ChunkMeshBuilder(ignored -> renderInfo);

        ChunkKey centerKey = new ChunkKey(0, 0);
        byte[] blocks = new byte[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];
        BlockSize[] blockSizes = new BlockSize[GameConfig.Chunk.SIZE * WORLD_HEIGHT * GameConfig.Chunk.SIZE];

        int idx1 = 0 + 0 * GameConfig.Chunk.SIZE + 0 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx1] = 1;
        blockSizes[idx1] = BlockSize.SIZE_16;

        int idx2 = 1 + 0 * GameConfig.Chunk.SIZE + 0 * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
        blocks[idx2] = 1;
        blockSizes[idx2] = BlockSize.SIZE_16;

        ChunkSnapshot center = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data1 = meshBuilder.build(new ChunkMeshInput(center, null, null, null, null));

        ChunkSnapshot center2 = ChunkSnapshot.of(centerKey, 1, WORLD_HEIGHT, blocks, blockSizes);
        ChunkMeshData data2 = meshBuilder.build(new ChunkMeshInput(center2, null, null, null, null));

        assertEquals(data1.vertices().length, data2.vertices().length);
        for (int i = 0; i < data1.vertices().length; i++) {
            assertEquals(data1.vertices()[i], data2.vertices()[i], EPSILON);
        }
    }
}