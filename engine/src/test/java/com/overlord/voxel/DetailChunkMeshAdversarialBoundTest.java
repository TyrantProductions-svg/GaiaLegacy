package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailChunkMeshAdversarialBoundTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 4;

    @Test
    void productionMesherRejectsTheMaximumFragmentedDetailParentFixture() {
        int detailCount = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
        int[] parentIndices = new int[detailCount];
        long[] masks = new long[detailCount];
        byte[] blockIds = new byte[detailCount * DetailCellState.CELL_COUNT];
        long checkerboard = checkerboardMask();
        for (int parent = 0; parent < detailCount; parent++) {
            parentIndices[parent] = parent;
            masks[parent] = checkerboard;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((checkerboard & (1L << cell)) != 0L) {
                    blockIds[parent * DetailCellState.CELL_COUNT + cell] = 1;
                }
            }
        }
        ChunkSnapshot center = ChunkSnapshot.of(
                new ChunkKey(-7, 5),
                12,
                WORLD_HEIGHT,
                new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE],
                DetailChunkSnapshot.of(parentIndices, masks, blockIds));
        ChunkMeshInput input = new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);

        ChunkMeshBuilder builder = new ChunkMeshBuilder(ignored -> renderInfo());

        assertThrows(RuntimeException.class, () -> builder.build(input));
    }

    private static long checkerboardMask() {
        long mask = 0L;
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    if (((x + y + z) & 1) == 0) {
                        mask |= 1L << (x + 4 * y + 16 * z);
                    }
                }
            }
        }
        return mask;
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
}
