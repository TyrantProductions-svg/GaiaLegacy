package com.gaia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.save.streaming.DetailBlocksCodec;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class HybridMeshOutputCanonicalSafetyTest {
    private static final int WORLD_HEIGHT = 4;
    private static final ChunkKey KEY = new ChunkKey(3, -2);

    @Test
    void outputLimitFailureLeavesCanonicalDetailPersistenceByteExact() {
        BlockRegistry blocks = registry();
        ChunkSnapshot original = fragmentedSnapshot();
        DetailBlocksCodec codec = new DetailBlocksCodec();
        byte[] encodedBefore = codec.encode(original, blocks)
                .orElseThrow()
                .copyBytes();
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        assertEquals(
                ChunkRepositoryRestoreResult.Status.RESTORED,
                repository.restoreCanonical(new ChunkRepositorySnapshot(
                        WORLD_HEIGHT, original.revision(), List.of(original)))
                        .status());
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                new ChunkMeshBuilder(blocks),
                executor,
                new RejectUnexpectedUploadBackend(),
                MainThreadGuard.captureCurrentThread(),
                2);

        assertEquals(1, manager.scheduleEligible());
        executor.runAll();
        assertEquals(1, manager.drainCompletedCpuWork());

        assertTrue(manager.outputLimitDiagnostic(KEY).isPresent());
        assertEquals(
                ChunkAvailability.AVAILABLE,
                repository.observeCell(
                        KEY.worldOriginX(), 0, KEY.worldOriginZ()).status());
        ChunkSnapshot after = repository.canonicalSnapshot()
                .chunks().get(0);
        assertEquals(original, after);
        assertArrayEquals(
                encodedBefore,
                codec.encode(after, blocks).orElseThrow().copyBytes());
    }

    private static ChunkSnapshot fragmentedSnapshot() {
        int parents = 256;
        long checkerboard = checkerboardMask();
        int[] parentIndices = new int[parents];
        long[] masks = new long[parents];
        byte[] ids = new byte[parents * DetailCellState.CELL_COUNT];
        for (int parent = 0; parent < parents; parent++) {
            parentIndices[parent] = parent;
            masks[parent] = checkerboard;
            for (int cell = 0; cell < DetailCellState.CELL_COUNT; cell++) {
                if ((checkerboard & (1L << cell)) != 0L) {
                    ids[parent * DetailCellState.CELL_COUNT + cell] = 1;
                }
            }
        }
        return ChunkSnapshot.of(
                KEY,
                12L,
                WORLD_HEIGHT,
                new byte[16 * WORLD_HEIGHT * 16],
                DetailChunkSnapshot.of(parentIndices, masks, ids));
    }

    private static long checkerboardMask() {
        long mask = 0L;
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            int x = index & 3;
            int y = (index >>> 2) & 3;
            int z = index >>> 4;
            if (((x + y + z) & 1) == 0) {
                mask |= 1L << index;
            }
        }
        return mask;
    }

    private static BlockRegistry registry() {
        ResourceLocation air = ResourceLocation.parse("gaia:air");
        ResourceLocation stone = ResourceLocation.parse("gaia:stone");
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                air);
        TextureRegion region = new TextureRegion(
                stone, 0, 0, 16, 16, 16, 16);
        EnumMap<BlockFace, TextureRegion> faces =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return BlockRegistry.create(
                List.of(
                        new BlockDefinition(
                                0, air, material.id(), Map.of(),
                                0, 0, 0, false, false, 0, null),
                        new BlockDefinition(
                                1, stone, material.id(), Map.of(),
                                1, 1, 1, false, false, 1, null)),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region),
                        1, new BlockRenderInfo(material, faces, true)));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }

    private static final class RejectUnexpectedUploadBackend
            implements ChunkRenderBackend {
        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            throw new AssertionError(
                    "complexity-rejected mesh must not reach GPU upload");
        }

        @Override
        public void release(ChunkRenderObject object) {
        }
    }
}
