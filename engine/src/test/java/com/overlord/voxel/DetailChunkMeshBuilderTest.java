package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class DetailChunkMeshBuilderTest {
    private static final float EPSILON = 0.000001f;
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 4;

    @Test
    void detailBackingAirEmitsOneExactQuarterCubeWithSixFaces() {
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.detail(1, 1, 1, state(bit(2, 1, 3), 1));

        ChunkMeshData mesh = build(input(center.build()));

        assertEquals(6, quads(mesh).size());
        assertEquals(36, mesh.vertexCount());
        assertEquals(
                new AxisAlignedBounds(
                        1.5f, 1.25f, 1.75f,
                        1.75f, 1.5f, 2.0f),
                mesh.localBounds().orElseThrow());
    }

    @Test
    void internalFacesAreEliminatedForCanonicalOccupancyFixtures() {
        assertFacelets(bit(0, 0, 0) | bit(1, 0, 0), 10);
        assertFacelets(mask((x, y, z) -> y == 0 && z == 0), 18);
        assertFacelets(mask((x, y, z) -> x < 2 && y < 2 && z == 0), 16);
        assertFacelets(-1L, 96);
        assertFacelets(mask((x, y, z) ->
                x == 0 || x == 3 || y == 0 || y == 3 || z == 0 || z == 3),
                120);
        assertFacelets(mask((x, y, z) -> ((x + y + z) & 1) == 0), 192);
    }

    @Test
    void adjacentMixedMaterialsHideInternalFaceButKeepDistinctAtlasRegions() {
        long occupancy = bit(0, 0, 0) | bit(1, 0, 0);
        byte[] ids = new byte[64];
        ids[index(0, 0, 0)] = 1;
        ids[index(1, 0, 0)] = 2;
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.detail(1, 1, 1, new DetailCellState(occupancy, ids));

        List<Quad> quads = quads(build(input(center.build())));

        assertEquals(10, quads.size());
        assertTrue(quads.stream().anyMatch(quad ->
                close(quad.minU(), region(1).uMin())
                        && close(quad.maxU(), region(1).uMax())));
        assertTrue(quads.stream().anyMatch(quad ->
                close(quad.minU(), region(2).uMin())
                        && close(quad.maxU(), region(2).uMax())));
        assertEquals(0, countOnPlaneX(quads, 1.25f, 1.0f, 1.125f, 1.125f));
        assertEquals(0, countOnPlaneX(quads, 1.25f, -1.0f, 1.125f, 1.125f));
    }

    @Test
    void fullDetailSeamEmitsExactlyComplementaryQuarterCoverage() {
        long boundary = 0L;
        for (int subY = 0; subY < 4; subY++) {
            for (int subZ = 0; subZ < 4; subZ++) {
                if (!((subY == 0 && subZ == 1)
                        || (subY == 2 && subZ == 3)
                        || (subY == 3 && subZ == 0))) {
                    boundary |= bit(0, subY, subZ);
                }
            }
        }
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.full(0, 1, 1, 1);
        center.detail(1, 1, 1, state(boundary, 2));

        List<Quad> quads = quads(build(input(center.build())));

        for (int subY = 0; subY < 4; subY++) {
            for (int subZ = 0; subZ < 4; subZ++) {
                boolean detailOccupied = (boundary & bit(0, subY, subZ)) != 0L;
                float sampleY = 1 + (subY + 0.5f) * 0.25f;
                float sampleZ = 1 + (subZ + 0.5f) * 0.25f;
                int positive = countOnPlaneX(quads, 1.0f, 1.0f, sampleY, sampleZ);
                int negative = countOnPlaneX(quads, 1.0f, -1.0f, sampleY, sampleZ);
                assertEquals(detailOccupied ? 0 : 1, positive,
                        "FULL complement at " + subY + "," + subZ);
                assertEquals(0, negative,
                        "DETAIL face hidden by FULL at " + subY + "," + subZ);
            }
        }
    }

    @Test
    void detailDetailSeamUsesSingleSidedQuarterOwnership() {
        long westMask = bit(3, 0, 0) | bit(3, 1, 1) | bit(3, 2, 2);
        long eastMask = bit(0, 1, 1) | bit(0, 2, 3) | bit(0, 3, 0);
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.detail(0, 1, 1, state(westMask, 1));
        center.detail(1, 1, 1, state(eastMask, 2));

        List<Quad> quads = quads(build(input(center.build())));

        for (int subY = 0; subY < 4; subY++) {
            for (int subZ = 0; subZ < 4; subZ++) {
                boolean west = (westMask & bit(3, subY, subZ)) != 0L;
                boolean east = (eastMask & bit(0, subY, subZ)) != 0L;
                float sampleY = 1 + (subY + 0.5f) * 0.25f;
                float sampleZ = 1 + (subZ + 0.5f) * 0.25f;
                assertEquals(west && !east ? 1 : 0,
                        countOnPlaneX(quads, 1.0f, 1.0f, sampleY, sampleZ));
                assertEquals(!west && east ? 1 : 0,
                        countOnPlaneX(quads, 1.0f, -1.0f, sampleY, sampleZ));
            }
        }
    }

    @Test
    void detailFaceTreatsAirAndNonRenderableAsVisibleButRenderableTransparentAsHidden() {
        SnapshotBuilder air = new SnapshotBuilder(new ChunkKey(0, 0));
        air.detail(0, 1, 1, state(bit(3, 1, 1), 1));
        assertEquals(1, seamEastFaceCount(build(input(air.build()))));

        SnapshotBuilder nonRenderable = new SnapshotBuilder(new ChunkKey(0, 0));
        nonRenderable.detail(0, 1, 1, state(bit(3, 1, 1), 1));
        nonRenderable.full(1, 1, 1, 4);
        assertEquals(1, seamEastFaceCount(build(input(nonRenderable.build()))));

        SnapshotBuilder transparent = new SnapshotBuilder(new ChunkKey(0, 0));
        transparent.detail(0, 1, 1, state(bit(3, 1, 1), 1));
        transparent.full(1, 1, 1, 3);
        assertEquals(0, seamEastFaceCount(build(input(transparent.build()))));
    }

    @Test
    void negativeChunkEastBoundaryUsesOnlyImmutableNeighborSnapshot() {
        ChunkKey key = new ChunkKey(-2, -3);
        SnapshotBuilder center = new SnapshotBuilder(key);
        center.detail(15, 1, 2, state(bit(3, 1, 2), 1));
        SnapshotBuilder occupiedEast = new SnapshotBuilder(key.east());
        occupiedEast.detail(0, 1, 2, state(bit(0, 1, 2), 2));

        List<Quad> hidden = quads(build(input(
                center.build(), null, occupiedEast.build(), null, null)));
        List<Quad> visible = quads(build(input(center.build())));

        float sampleY = 1.375f;
        float sampleZ = 2.625f;
        assertEquals(0, countOnPlaneX(
                hidden, 16.0f, 1.0f, sampleY, sampleZ));
        assertEquals(1, countOnPlaneX(
                visible, 16.0f, 1.0f, sampleY, sampleZ));
    }

    @Test
    void allCardinalChunkBoundariesCullAgainstImmutableDetailNeighbors() {
        ChunkKey key = new ChunkKey(-2, -3);
        SnapshotBuilder center = new SnapshotBuilder(key);
        center.detail(0, 1, 2, state(bit(0, 1, 2), 1));
        center.detail(15, 1, 3, state(bit(3, 1, 1), 1));
        center.detail(4, 1, 0, state(bit(2, 1, 0), 1));
        center.detail(5, 1, 15, state(bit(1, 1, 3), 1));
        SnapshotBuilder north = new SnapshotBuilder(key.north());
        north.detail(4, 1, 15, state(bit(2, 1, 3), 2));
        SnapshotBuilder east = new SnapshotBuilder(key.east());
        east.detail(0, 1, 3, state(bit(0, 1, 1), 2));
        SnapshotBuilder south = new SnapshotBuilder(key.south());
        south.detail(5, 1, 0, state(bit(1, 1, 0), 2));
        SnapshotBuilder west = new SnapshotBuilder(key.west());
        west.detail(15, 1, 2, state(bit(3, 1, 2), 2));

        List<Quad> quads = quads(build(input(
                center.build(),
                north.build(),
                east.build(),
                south.build(),
                west.build())));

        assertEquals(0, countOnPlaneX(quads, 0.0f, -1.0f, 1.375f, 2.625f));
        assertEquals(0, countOnPlaneX(quads, 16.0f, 1.0f, 1.375f, 3.375f));
        assertEquals(0, countOnPlaneZ(quads, 0.0f, -1.0f, 4.625f, 1.375f));
        assertEquals(0, countOnPlaneZ(quads, 16.0f, 1.0f, 5.375f, 1.375f));
    }

    @Test
    void clippedFullFaceDoesNotMergeQuarterPatchesWithDifferentAo() {
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.full(0, 1, 1, 1);
        long eastBoundary = -1L;
        eastBoundary &= ~bit(0, 0, 0);
        eastBoundary &= ~bit(0, 1, 0);
        center.detail(1, 1, 1, state(eastBoundary, 2));
        center.detail(0, 0, 1, state(bit(3, 3, 0), 2));

        List<Quad> seam = quads(build(input(center.build()))).stream()
                .filter(quad -> close(quad.minX(), 1.0f)
                        && close(quad.maxX(), 1.0f)
                        && close(quad.normalX(), 1.0f)
                        && quad.minY() >= 1.0f
                        && quad.minZ() >= 1.0f)
                .toList();

        assertEquals(2, seam.size());
        assertTrue(seam.stream().allMatch(quad ->
                close(quad.maxY() - quad.minY(), 0.25f)
                        && close(quad.maxZ() - quad.minZ(), 0.25f)));
    }

    @Test
    void detailUsesFullTileUvAndClippedFullUsesOriginalUvSubregion() {
        SnapshotBuilder detailCenter = new SnapshotBuilder(new ChunkKey(0, 0));
        detailCenter.detail(1, 1, 1, state(bit(2, 1, 3), 2));
        Quad detailUp = quads(build(input(detailCenter.build()))).stream()
                .filter(quad -> close(quad.normalY(), 1.0f))
                .findFirst()
                .orElseThrow();

        assertEquals(region(2).uMin(), detailUp.minU(), EPSILON);
        assertEquals(region(2).uMax(), detailUp.maxU(), EPSILON);
        assertEquals(region(2).vMin(), detailUp.minV(), EPSILON);
        assertEquals(region(2).vMax(), detailUp.maxV(), EPSILON);

        int gapX = 1;
        int gapY = 2;
        long occupiedBoundary = 0L;
        for (int subX = 0; subX < 4; subX++) {
            for (int subY = 0; subY < 4; subY++) {
                if (subX != gapX || subY != gapY) {
                    occupiedBoundary |= bit(subX, subY, 0);
                }
            }
        }
        SnapshotBuilder clippedCenter = new SnapshotBuilder(new ChunkKey(0, 0));
        clippedCenter.full(1, 1, 0, 1);
        clippedCenter.detail(1, 1, 1, state(occupiedBoundary, 2));
        float sampleX = 1 + (gapX + 0.5f) * 0.25f;
        float sampleY = 1 + (gapY + 0.5f) * 0.25f;
        Quad clipped = quads(build(input(clippedCenter.build()))).stream()
                .filter(quad -> close(quad.normalZ(), 1.0f)
                        && close(quad.minZ(), 1.0f)
                        && contains(quad.minX(), quad.maxX(), sampleX)
                        && contains(quad.minY(), quad.maxY(), sampleY))
                .findFirst()
                .orElseThrow();
        TextureRegion fullRegion = region(1);
        float uSpan = fullRegion.uMax() - fullRegion.uMin();
        float vSpan = fullRegion.vMax() - fullRegion.vMin();

        assertEquals(fullRegion.uMin() + uSpan * gapX / 4.0f,
                clipped.minU(), EPSILON);
        assertEquals(fullRegion.uMin() + uSpan * (gapX + 1) / 4.0f,
                clipped.maxU(), EPSILON);
        assertEquals(fullRegion.vMax() - vSpan * (gapY + 1) / 4.0f,
                clipped.minV(), EPSILON);
        assertEquals(fullRegion.vMax() - vSpan * gapY / 4.0f,
                clipped.maxV(), EPSILON);
    }

    @Test
    void identicalCanonicalDetailProducesIdenticalCpuMeshHash() {
        SnapshotBuilder first = new SnapshotBuilder(new ChunkKey(-4, 2));
        first.detail(3, 1, 5, mixedState());
        first.full(4, 1, 5, 3);
        SnapshotBuilder second = new SnapshotBuilder(new ChunkKey(-4, 2));
        second.full(4, 1, 5, 3);
        second.detail(3, 1, 5, mixedState());

        ChunkMeshData firstMesh = build(input(first.build()));
        ChunkMeshData secondMesh = build(input(second.build()));

        assertArrayEquals(firstMesh.vertices(), secondMesh.vertices());
        assertArrayEquals(firstMesh.canonicalHash(), secondMesh.canonicalHash());
    }

    private static DetailCellState mixedState() {
        long occupancy = bit(0, 0, 0) | bit(1, 0, 0) | bit(3, 2, 1);
        byte[] ids = new byte[64];
        ids[index(0, 0, 0)] = 1;
        ids[index(1, 0, 0)] = 2;
        ids[index(3, 2, 1)] = 1;
        return new DetailCellState(occupancy, ids);
    }

    private static void assertFacelets(long occupancy, int expected) {
        SnapshotBuilder center = new SnapshotBuilder(new ChunkKey(0, 0));
        center.detail(1, 1, 1, state(occupancy, 1));
        assertEquals(expected, quads(build(input(center.build()))).size());
    }

    private static int seamEastFaceCount(ChunkMeshData mesh) {
        return countOnPlaneX(
                quads(mesh), 1.0f, 1.0f, 1.375f, 1.375f);
    }

    private static ChunkMeshData build(ChunkMeshInput input) {
        return new ChunkMeshBuilder(DetailChunkMeshBuilderTest::resolve)
                .build(input);
    }

    private static BlockRenderInfo resolve(int blockId) {
        RenderType renderType = blockId == 3
                ? RenderType.TRANSPARENT
                : RenderType.OPAQUE;
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.of("test", "material_" + blockId),
                ResourceLocation.parse("test:blocks"),
                renderType,
                0.5f,
                ResourceLocation.parse("test:missing"));
        TextureRegion region = region(blockId);
        if (blockId == 4) {
            return BlockRenderInfo.nonRenderable(material, region);
        }
        Map<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new BlockRenderInfo(material, regions, true);
    }

    private static TextureRegion region(int blockId) {
        int column = blockId - 1;
        return new TextureRegion(
                ResourceLocation.of("test", "block_" + blockId),
                column * 16,
                0,
                16,
                16,
                64,
                16);
    }

    private static ChunkMeshInput input(ChunkSnapshot center) {
        return input(center, null, null, null, null);
    }

    private static ChunkMeshInput input(
            ChunkSnapshot center,
            ChunkSnapshot north,
            ChunkSnapshot east,
            ChunkSnapshot south,
            ChunkSnapshot west) {
        return new ChunkMeshInput(
                center, north, null, east, null, south, null, west, null);
    }

    private static List<Quad> quads(ChunkMeshData mesh) {
        float[] vertices = mesh.vertices();
        assertEquals(0, mesh.vertexCount() % 6);
        List<Quad> quads = new ArrayList<>();
        int stride = VoxelVertexFormat.FLOATS_PER_VERTEX;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex += 6) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float minU = Float.POSITIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            int first = vertex * stride;
            float normalX = vertices[first + 5];
            float normalY = vertices[first + 6];
            float normalZ = vertices[first + 7];
            for (int offsetVertex = 0; offsetVertex < 6; offsetVertex++) {
                int offset = (vertex + offsetVertex) * stride;
                minX = Math.min(minX, vertices[offset]);
                minY = Math.min(minY, vertices[offset + 1]);
                minZ = Math.min(minZ, vertices[offset + 2]);
                maxX = Math.max(maxX, vertices[offset]);
                maxY = Math.max(maxY, vertices[offset + 1]);
                maxZ = Math.max(maxZ, vertices[offset + 2]);
                minU = Math.min(minU, vertices[offset + 3]);
                minV = Math.min(minV, vertices[offset + 4]);
                maxU = Math.max(maxU, vertices[offset + 3]);
                maxV = Math.max(maxV, vertices[offset + 4]);
                assertEquals(normalX, vertices[offset + 5], EPSILON);
                assertEquals(normalY, vertices[offset + 6], EPSILON);
                assertEquals(normalZ, vertices[offset + 7], EPSILON);
            }
            quads.add(new Quad(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    minU, minV, maxU, maxV,
                    normalX, normalY, normalZ));
        }
        return quads;
    }

    private static int countOnPlaneX(
            List<Quad> quads,
            float plane,
            float normalX,
            float sampleY,
            float sampleZ) {
        return (int) quads.stream()
                .filter(quad -> close(quad.minX(), plane)
                        && close(quad.maxX(), plane)
                        && close(quad.normalX(), normalX)
                        && contains(quad.minY(), quad.maxY(), sampleY)
                        && contains(quad.minZ(), quad.maxZ(), sampleZ))
                .count();
    }

    private static int countOnPlaneZ(
            List<Quad> quads,
            float plane,
            float normalZ,
            float sampleX,
            float sampleY) {
        return (int) quads.stream()
                .filter(quad -> close(quad.minZ(), plane)
                        && close(quad.maxZ(), plane)
                        && close(quad.normalZ(), normalZ)
                        && contains(quad.minX(), quad.maxX(), sampleX)
                        && contains(quad.minY(), quad.maxY(), sampleY))
                .count();
    }

    private static boolean contains(float minimum, float maximum, float value) {
        return value > minimum - EPSILON && value < maximum + EPSILON;
    }

    private static boolean close(float first, float second) {
        return Math.abs(first - second) <= EPSILON;
    }

    private static DetailCellState state(long occupancy, int blockId) {
        byte[] ids = new byte[64];
        for (int cell = 0; cell < ids.length; cell++) {
            if ((occupancy & (1L << cell)) != 0L) {
                ids[cell] = (byte) blockId;
            }
        }
        return new DetailCellState(occupancy, ids);
    }

    private static long mask(CellPredicate predicate) {
        long occupancy = 0L;
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    if (predicate.test(x, y, z)) {
                        occupancy |= bit(x, y, z);
                    }
                }
            }
        }
        return occupancy;
    }

    private static long bit(int x, int y, int z) {
        return 1L << index(x, y, z);
    }

    private static int index(int x, int y, int z) {
        return x + 4 * y + 16 * z;
    }

    private record Quad(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float minU,
            float minV,
            float maxU,
            float maxV,
            float normalX,
            float normalY,
            float normalZ) {}

    @FunctionalInterface
    private interface CellPredicate {
        boolean test(int x, int y, int z);
    }

    private static final class SnapshotBuilder {
        private final ChunkKey key;
        private final byte[] blocks =
                new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE];
        private final TreeMap<Integer, DetailCellState> details = new TreeMap<>();

        private SnapshotBuilder(ChunkKey key) {
            this.key = key;
        }

        private void full(int x, int y, int z, int blockId) {
            int parentIndex = parentIndex(x, y, z);
            if (details.containsKey(parentIndex)) {
                throw new IllegalStateException("parent is already DETAIL");
            }
            blocks[parentIndex] = (byte) blockId;
        }

        private void detail(int x, int y, int z, DetailCellState state) {
            int parentIndex = parentIndex(x, y, z);
            if (blocks[parentIndex] != 0) {
                throw new IllegalStateException("DETAIL backing must be AIR");
            }
            details.put(parentIndex, state);
        }

        private ChunkSnapshot build() {
            if (details.isEmpty()) {
                return ChunkSnapshot.of(key, 7, WORLD_HEIGHT, blocks);
            }
            int[] parentIndices = new int[details.size()];
            long[] masks = new long[details.size()];
            byte[] ids = new byte[details.size() * 64];
            int entry = 0;
            for (Map.Entry<Integer, DetailCellState> detail : details.entrySet()) {
                parentIndices[entry] = detail.getKey();
                masks[entry] = detail.getValue().occupancyMask();
                System.arraycopy(
                        detail.getValue().copyBlockIds(),
                        0,
                        ids,
                        entry * 64,
                        64);
                entry++;
            }
            return ChunkSnapshot.of(
                    key,
                    7,
                    WORLD_HEIGHT,
                    blocks,
                    DetailChunkSnapshot.of(parentIndices, masks, ids));
        }

        private static int parentIndex(int x, int y, int z) {
            return x + y * CHUNK_SIZE + z * CHUNK_SIZE * WORLD_HEIGHT;
        }
    }
}
