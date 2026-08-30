package com.gaia.tools;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.debug.DetailFixturePattern;
import com.gaia.save.streaming.DetailBlocksCodec;
import com.gaia.save.streaming.StreamedChunkPayload;
import com.overlord.assets.ResourceLocation;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.DetailCollisionBoxMerger;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshInput;
import com.overlord.voxel.ChunkMeshOutputLimitExceededException;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelVertexFormat;
import com.overlord.voxel.World;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector3f;

/** Deterministic bounded measurements over the production Phase 16 algorithms. */
public final class DetailVoxelPerformanceFixture {
    private static final int WORLD_HEIGHT = 4;
    private static final int FULL_BYTES = 16 * WORLD_HEIGHT * 16;
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final BlockRegistry BLOCKS = registry();
    private static final DetailBlocksCodec CODEC = new DetailBlocksCodec();
    private static final BlockRenderInfo RENDER_INFO = renderInfo();

    private DetailVoxelPerformanceFixture() {}

    public static Measurement measure(
            int detailParents, DetailFixturePattern pattern) {
        if (detailParents < 1 || detailParents > Chunk.MAX_DETAIL_PARENTS_PER_CHUNK) {
            throw new IllegalArgumentException("detailParents must be within 1..1024");
        }
        DetailCellState state = pattern.state((byte) 1, (byte) 2);
        long captureStart = System.nanoTime();
        DetailChunkSnapshot details = details(detailParents, state);
        ChunkSnapshot snapshot = ChunkSnapshot.of(
                new ChunkKey(0, 0),
                1L,
                WORLD_HEIGHT,
                new byte[FULL_BYTES],
                details);
        long captureNanos = System.nanoTime() - captureStart;

        DetailCollisionBoxMerger merger = new DetailCollisionBoxMerger();
        long collisionStart = System.nanoTime();
        long collisionBoxes = 0L;
        for (int parent = 0; parent < detailParents; parent++) {
            collisionBoxes += merger.merge(state).boxes().size();
        }
        long collisionNanos = System.nanoTime() - collisionStart;

        ChunkMeshInput input = input(snapshot);
        long meshStart = System.nanoTime();
        String meshStatus = "SUCCESS";
        String meshHash;
        long vertices;
        long facelets;
        long meshBytes;
        long requiredMeshBytes;
        try {
            ChunkMeshData mesh = new ChunkMeshBuilder(ignored -> RENDER_INFO).build(input);
            vertices = mesh.vertexCount();
            facelets = vertices / 6L;
            meshBytes = Math.multiplyExact(
                    Math.multiplyExact(vertices, VoxelVertexFormat.FLOATS_PER_VERTEX),
                    Float.BYTES);
            requiredMeshBytes = meshBytes;
            meshHash = HexFormat.of().formatHex(mesh.canonicalHash());
        } catch (ChunkMeshOutputLimitExceededException bounded) {
            meshStatus = bounded.code().name();
            facelets = bounded.acceptedByteCount()
                    / (6L * VoxelVertexFormat.FLOATS_PER_VERTEX * Float.BYTES);
            vertices = facelets * 6L;
            meshBytes = bounded.acceptedByteCount();
            requiredMeshBytes = bounded.requiredByteCount();
            meshHash = "NONE";
        }
        long meshNanos = System.nanoTime() - meshStart;

        long encodeStart = System.nanoTime();
        StreamedChunkPayload.ExtensionDescriptor extension =
                CODEC.encode(snapshot, BLOCKS).orElseThrow();
        long encodeNanos = System.nanoTime() - encodeStart;
        long decodeStart = System.nanoTime();
        DetailBlocksCodec.DecodeResult decoded = CODEC.decode(
                extension, WORLD_HEIGHT, new byte[FULL_BYTES], BLOCKS);
        long decodeNanos = System.nanoTime() - decodeStart;
        if (decoded.status() != DetailBlocksCodec.DecodeResult.Status.VALID
                || !details.equals(decoded.details().orElseThrow())) {
            throw new IllegalStateException("production DETAIL codec round trip failed");
        }

        RepresentativeCosts representative = representativeCosts(true);
        return new Measurement(
                pattern.name(),
                detailParents,
                Math.multiplyExact((long) detailParents, Long.bitCount(state.occupancyMask())),
                Math.addExact(256L, Math.multiplyExact(96L, detailParents)),
                Math.addExact(256L, Math.multiplyExact(74L, detailParents)),
                representative.mutationNanos(),
                captureNanos,
                representative.raycastNanos(),
                collisionBoxes,
                collisionNanos,
                facelets,
                vertices,
                meshBytes,
                requiredMeshBytes,
                meshNanos,
                meshStatus,
                meshHash,
                extension.copyBytes().length,
                FULL_BYTES + extension.copyBytes().length,
                encodeNanos,
                decodeNanos);
    }

    public static Measurement measureFullOnly() {
        byte[] full = new byte[FULL_BYTES];
        full[1] = 1;
        ChunkSnapshot snapshot = ChunkSnapshot.of(
                new ChunkKey(0, 0), 1L, WORLD_HEIGHT, full);
        long meshStart = System.nanoTime();
        ChunkMeshData mesh = new ChunkMeshBuilder(ignored -> RENDER_INFO).build(input(snapshot));
        long meshNanos = System.nanoTime() - meshStart;
        long vertices = mesh.vertexCount();
        RepresentativeCosts representative = representativeCosts(false);
        return new Measurement(
                "FULL_ONLY",
                0,
                0,
                0,
                0,
                representative.mutationNanos(),
                0,
                representative.raycastNanos(),
                0,
                0,
                vertices / 6L,
                vertices,
                Math.multiplyExact(
                        Math.multiplyExact(vertices, VoxelVertexFormat.FLOATS_PER_VERTEX),
                        Float.BYTES),
                Math.multiplyExact(
                        Math.multiplyExact(vertices, VoxelVertexFormat.FLOATS_PER_VERTEX),
                        Float.BYTES),
                meshNanos,
                "SUCCESS",
                HexFormat.of().formatHex(mesh.canonicalHash()),
                0,
                FULL_BYTES,
                0,
                0);
    }

    public static void main(String[] args) {
        int warmups = args.length == 0 ? 1 : Integer.parseInt(args[0]);
        for (int warmup = 0; warmup < warmups; warmup++) {
            measure(1, DetailFixturePattern.CHECKERBOARD);
        }
        print(measureFullOnly());
        for (int count : new int[] {1, 64, 256, 1024}) {
            print(measure(count, DetailFixturePattern.UNIFORM_FULL));
            print(measure(count, DetailFixturePattern.STAIRCASE));
            print(measure(count, DetailFixturePattern.CHECKERBOARD));
            print(measure(count, DetailFixturePattern.MIXED_MATERIAL));
        }
    }

    private static RepresentativeCosts representativeCosts(boolean detail) {
        ChunkRepository repository = new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
        repository.generate(
                new ChunkKey(0, 0),
                chunk -> {
                    if (!detail) {
                        chunk.setBlock(1, 0, 0, (byte) 1);
                    }
                });
        long mutationNanos = 0L;
        if (detail) {
            long start = System.nanoTime();
            ChunkDetailMutationOutcome result = repository.mutateDetail(
                    new ChunkDetailMutation.SetSubVoxel(
                            1, 0, 0,
                            repository.revision(new ChunkKey(0, 0)),
                            new FullCellState((byte) 0),
                            new LocalSubVoxelPosition(0, 0, 0),
                            (byte) 1));
            mutationNanos = System.nanoTime() - start;
            if (result.status() != ChunkDetailMutationOutcome.Status.APPLIED) {
                throw new IllegalStateException("representative DETAIL mutation failed");
            }
        }
        BlockRaycast raycast = new BlockRaycast(
                new World(repository),
                BlockCollisionShapeResolver.fullCubesForNonAir());
        long rayStart = System.nanoTime();
        Optional<?> hit = raycast.cast(
                new Vector3f(0.01f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                2.0f);
        long raycastNanos = System.nanoTime() - rayStart;
        if (hit.isEmpty()) {
            throw new IllegalStateException("representative raycast missed canonical geometry");
        }
        return new RepresentativeCosts(mutationNanos, raycastNanos);
    }

    private static DetailChunkSnapshot details(int parents, DetailCellState state) {
        int[] parentIndices = new int[parents];
        long[] masks = new long[parents];
        byte[] ids = new byte[parents * DetailCellState.CELL_COUNT];
        byte[] stateIds = state.copyBlockIds();
        for (int parent = 0; parent < parents; parent++) {
            parentIndices[parent] = parent;
            masks[parent] = state.occupancyMask();
            System.arraycopy(stateIds, 0, ids, parent * DetailCellState.CELL_COUNT, stateIds.length);
        }
        return DetailChunkSnapshot.of(parentIndices, masks, ids);
    }

    private static ChunkMeshInput input(ChunkSnapshot center) {
        return new ChunkMeshInput(center, null, null, null, null, null, null, null, null);
    }

    private static void print(Measurement value) {
        System.out.printf(
                Locale.ROOT,
                "%s parents=%d occupied=%d runtimeEstimate=%d snapshotEstimate=%d "
                        + "mutationNs=%d captureNs=%d raycastNs=%d collisionBoxes=%d collisionNs=%d "
                        + "facelets=%d vertices=%d meshBytes=%d requiredMeshBytes=%d "
                        + "meshStatus=%s meshNs=%d codecBytes=%d totalBytes=%d "
                        + "encodeNs=%d decodeNs=%d hash=%s%n",
                value.pattern(), value.detailParents(), value.occupiedSubVoxels(),
                value.runtimeStorageEstimateBytes(), value.snapshotEstimateBytes(),
                value.mutationNanos(), value.snapshotCaptureNanos(), value.raycastNanos(),
                value.collisionBoxes(), value.collisionNanos(), value.facelets(), value.vertices(),
                value.meshOutputBytes(), value.requiredMeshOutputBytes(), value.meshStatus(),
                value.meshNanos(), value.codecBytes(), value.totalPayloadBytes(),
                value.encodeNanos(), value.decodeNanos(), value.meshHash());
    }

    private static BlockRegistry registry() {
        MaterialDefinition material = material();
        ArrayList<BlockDefinition> definitions = new ArrayList<>();
        definitions.add(new BlockDefinition(0, AIR, material.id(), Map.of(), 0, 0, 0,
                false, false, 0, null));
        definitions.add(new BlockDefinition(1, STONE, material.id(), Map.of(), 1, 1, 1,
                false, false, 1, null));
        definitions.add(new BlockDefinition(2, DIRT, material.id(), Map.of(), 1, 1, 1,
                false, false, 1, null));
        return BlockRegistry.create(
                definitions,
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region()),
                        1, RENDER_INFO_SAFE.renderInfo(material),
                        2, RENDER_INFO_SAFE.renderInfo(material)));
    }

    private static BlockRenderInfo renderInfo() {
        return RENDER_INFO_SAFE.renderInfo(material());
    }

    private static MaterialDefinition material() {
        return new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                AIR);
    }

    private static TextureRegion region() {
        return new TextureRegion(AIR, 0, 0, 16, 16, 16, 16);
    }

    private enum RENDER_INFO_SAFE {
        INSTANCE;

        private static BlockRenderInfo renderInfo(MaterialDefinition material) {
            EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
            for (BlockFace face : BlockFace.values()) {
                faces.put(face, region());
            }
            return new BlockRenderInfo(material, faces, true);
        }
    }

    private record RepresentativeCosts(long mutationNanos, long raycastNanos) {}

    public record Measurement(
            String pattern,
            int detailParents,
            long occupiedSubVoxels,
            long runtimeStorageEstimateBytes,
            long snapshotEstimateBytes,
            long mutationNanos,
            long snapshotCaptureNanos,
            long raycastNanos,
            long collisionBoxes,
            long collisionNanos,
            long facelets,
            long vertices,
            long meshOutputBytes,
            long requiredMeshOutputBytes,
            long meshNanos,
            String meshStatus,
            String meshHash,
            int codecBytes,
            int totalPayloadBytes,
            long encodeNanos,
            long decodeNanos) {}
}
