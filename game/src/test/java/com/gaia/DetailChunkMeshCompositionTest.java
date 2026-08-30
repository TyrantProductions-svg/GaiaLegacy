package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshInput;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailChunkSnapshot;
import com.overlord.voxel.VoxelVertexFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailChunkMeshCompositionTest {
    private static final ResourceLocation MATERIAL_ID =
            ResourceLocation.parse("gaia:test_opaque");
    private static final ResourceLocation ATLAS =
            ResourceLocation.parse("gaia:blocks");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");

    @Test
    void productionRegistryMapsDetailRuntimeIdThroughExistingAtlasPipeline() {
        BlockRegistry blocks = registry();
        int parentIndex = 1 + 1 * 16 + 1 * 16 * 4;
        int subIndex = 2 + 4 * 1 + 16 * 3;
        byte[] detailIds = new byte[64];
        detailIds[subIndex] = 2;
        ChunkSnapshot center = ChunkSnapshot.of(
                new ChunkKey(0, 0),
                9,
                4,
                new byte[16 * 4 * 16],
                DetailChunkSnapshot.of(
                        new int[] {parentIndex},
                        new long[] {1L << subIndex},
                        detailIds));

        ChunkMeshData mesh = new ChunkMeshBuilder(blocks).build(
                new ChunkMeshInput(
                        center, null, null, null, null, null, null, null, null));

        assertEquals(36, mesh.vertexCount());
        TextureRegion detailRegion = blocks.resolve(2).region(BlockFace.NORTH);
        float[] vertices = mesh.vertices();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int offset = vertex * VoxelVertexFormat.FLOATS_PER_VERTEX;
            float u = vertices[offset + 3];
            float v = vertices[offset + 4];
            assertEquals(true,
                    u == detailRegion.uMin() || u == detailRegion.uMax());
            assertEquals(true,
                    v == detailRegion.vMin() || v == detailRegion.vMax());
        }
    }

    private static BlockRegistry registry() {
        MaterialDefinition material = new MaterialDefinition(
                MATERIAL_ID, ATLAS, RenderType.OPAQUE, 0.5f, MISSING);
        TextureRegion airRegion = new TextureRegion(MISSING, 0, 0, 1, 1, 2, 1);
        TextureRegion stoneRegion = new TextureRegion(
                ResourceLocation.parse("gaia:stone"), 0, 0, 1, 1, 2, 1);
        TextureRegion detailRegion = new TextureRegion(
                ResourceLocation.parse("gaia:detail"), 1, 0, 1, 1, 2, 1);
        return BlockRegistry.create(
                List.of(
                        definition(0, "gaia:air"),
                        definition(1, "gaia:stone"),
                        definition(2, "gaia:detail")),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, airRegion),
                        1, renderInfo(material, stoneRegion),
                        2, renderInfo(material, detailRegion)));
    }

    private static BlockDefinition definition(int id, String name) {
        EnumMap<BlockFace, ResourceLocation> textures =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, ResourceLocation.parse(name));
        }
        return new BlockDefinition(
                id,
                ResourceLocation.parse(name),
                MATERIAL_ID,
                textures,
                id == 0 ? 0.0f : 1.0f,
                id == 0 ? 0.0f : 1.0f,
                id == 0 ? 0.0f : 1.0f,
                false,
                false,
                id == 0 ? 0.0f : 1.0f,
                id == 0
                        ? null
                        : new ItemFormDefinition(
                                ResourceLocation.parse(name), 64, false, false));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material, TextureRegion region) {
        EnumMap<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new BlockRenderInfo(material, regions, true);
    }
}
