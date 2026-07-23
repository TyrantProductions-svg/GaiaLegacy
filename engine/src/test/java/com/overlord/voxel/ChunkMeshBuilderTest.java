package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkMeshBuilderTest {
    private static final float EPSILON = 0.000001f;
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
        World world = new World();
        world.setBlock(1, 1, 1, (byte) 0xFF);

        float[] vertices =
                meshBuilder.buildChunkMeshData(
                        world.getChunk(0, 0), 0, 0, world);

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
        World world = new World();
        world.setBlock(1, 1, 1, (byte) 7);

        float[] vertices =
                meshBuilder.buildChunkMeshData(
                        world.getChunk(0, 0), 0, 0, world);

        assertEquals(0, vertices.length);
    }

    private static BlockRenderInfo renderInfo() {
        Map<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (int face = 0; face < FACE_ORDER.length; face++) {
            regions.put(
                    FACE_ORDER[face],
                    region(FACE_ORDER[face].name().toLowerCase(), face));
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

    private static TextureRegion region(String name, int column) {
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
}
