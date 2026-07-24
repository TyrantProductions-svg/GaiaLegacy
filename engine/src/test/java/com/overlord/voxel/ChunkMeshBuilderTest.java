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
            assertFaceUBounds(
                    vertices,
                    face * 30,
                    face * 16.0f / 96.0f,
                    (face + 1) * 16.0f / 96.0f);
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

    private static float maxPositionX(float[] vertices) {
        float maximum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < vertices.length; offset += 5) {
            maximum = Math.max(maximum, vertices[offset]);
        }
        return maximum;
    }
}
