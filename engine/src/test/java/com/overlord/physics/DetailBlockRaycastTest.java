package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.VoxelScale;
import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DetailBlockRaycastTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void detailBackingAirDoesNotHideOccupiedSubVoxel() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 2, 2),
                (byte) 7);

        SpatialQueryResult<BlockRaycastHit> result = raycastFor(world).cast(
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(0.5f, 0.625f, 0.625f),
                new Vector3f(1, 0, 0),
                2);

        assertEquals(SpatialQueryResult.Status.AVAILABLE, result.status());
        BlockRaycastHit hit = result.result().orElseThrow();
        assertEquals(7, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(1.0f, hit.pointX(), EPSILON);
        assertEquals(0.5f, hit.distance(), EPSILON);
        DetailRaycastTarget target = assertInstanceOf(
                DetailRaycastTarget.class, hit.target());
        assertEquals(VoxelScale.DETAIL_4, target.scale());
        assertEquals(new LocalSubVoxelPosition(0, 2, 2), target.position());
        assertEquals(world.chunks().revision(new ChunkKey(0, 0)),
                hit.chunkRevision());
        assertEquals(1.0, hit.worldPointX(), 1.0e-12);
        assertEquals(0.625, hit.worldPointY(), 1.0e-12);
        assertEquals(0.625, hit.worldPointZ(), 1.0e-12);
    }

    @Test
    void detailGapContinuesSameDdaToLaterFullCell() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        assertTrue(world.setBlock(2, 0, 0, (byte) 8));

        BlockRaycastHit hit = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                4);

        assertEquals(2, hit.blockX());
        assertEquals(8, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(1.5f, hit.distance(), EPSILON);
    }

    @Test
    void rayStartingInsideEmptyDetailGapContinuesToLaterFullCell() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        assertTrue(world.setBlock(2, 0, 0, (byte) 8));

        BlockRaycastHit hit = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(1.1f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);

        assertEquals(2, hit.blockX());
        assertEquals(0.9f, hit.distance(), EPSILON);
    }

    @Test
    void detailGapContinuesSameDdaToLaterDetailCell() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        placeDetail(
                world,
                2, 0, 0,
                new LocalSubVoxelPosition(0, 0, 0),
                (byte) 9);

        BlockRaycastHit hit = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                4);

        assertEquals(2, hit.blockX());
        assertEquals(9, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(1.5f, hit.distance(), EPSILON);
    }

    @Test
    void nearestOccupiedSubvoxelWinsIndependentOfInsertionOrder() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(3, 0, 0),
                (byte) 9);
        placeDetail(
                world,
                1, 0, 0,
                new LocalSubVoxelPosition(0, 0, 0),
                (byte) 7);

        BlockRaycastHit hit = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                2);

        assertEquals(7, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(1.0f, hit.pointX(), EPSILON);
    }

    @Test
    void sixSubvoxelFacesUseExistingAxisNormals() {
        LocalSubVoxelPosition center = new LocalSubVoxelPosition(1, 1, 1);
        World world = detailWorld(1, 1, 1, center, (byte) 7);
        SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));

        assertFace(world, simulationOrigin,
                new Vector3f(0.75f, 1.375f, 1.375f),
                new Vector3f(1, 0, 0), -1, 0, 0, 0.5f);
        assertFace(world, simulationOrigin,
                new Vector3f(1.75f, 1.375f, 1.375f),
                new Vector3f(-1, 0, 0), 1, 0, 0, 0.25f);
        assertFace(world, simulationOrigin,
                new Vector3f(1.375f, 0.75f, 1.375f),
                new Vector3f(0, 1, 0), 0, -1, 0, 0.5f);
        assertFace(world, simulationOrigin,
                new Vector3f(1.375f, 1.75f, 1.375f),
                new Vector3f(0, -1, 0), 0, 1, 0, 0.25f);
        assertFace(world, simulationOrigin,
                new Vector3f(1.375f, 1.375f, 0.75f),
                new Vector3f(0, 0, 1), 0, 0, -1, 0.5f);
        assertFace(world, simulationOrigin,
                new Vector3f(1.375f, 1.375f, 1.75f),
                new Vector3f(0, 0, -1), 0, 0, 1, 0.25f);
    }

    @Test
    void edgeAndCornerEntryTiesPreserveYThenXThenZPriority() {
        World world = detailWorld(
                0, 0, 0,
                new LocalSubVoxelPosition(1, 1, 1),
                (byte) 7);
        SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));

        BlockRaycastHit edge = availableHit(
                world, simulationOrigin,
                new Vector3f(0, 0, 0.375f),
                new Vector3f(1, 1, 0), 1);
        BlockRaycastHit corner = availableHit(
                world, simulationOrigin,
                new Vector3f(0, 0, 0),
                new Vector3f(1, 1, 1), 1);

        assertEquals(0.0f, edge.normalX());
        assertEquals(-1.0f, edge.normalY());
        assertEquals(0.0f, edge.normalZ());
        assertEquals(0.0f, corner.normalX());
        assertEquals(-1.0f, corner.normalY());
        assertEquals(0.0f, corner.normalZ());
    }

    @Test
    void originInsideAndOnQuarterSurfaceMatchesFullShapeConvention() {
        World world = detailWorld(
                0, 0, 0,
                new LocalSubVoxelPosition(1, 1, 1),
                (byte) 7);
        SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));

        BlockRaycastHit inside = availableHit(
                world, simulationOrigin,
                new Vector3f(0.375f, 0.375f, 0.375f),
                new Vector3f(1, 1, 1), 0);
        BlockRaycastHit inward = availableHit(
                world, simulationOrigin,
                new Vector3f(0.25f, 0.375f, 0.375f),
                new Vector3f(1, 0, 0), 0);
        SpatialQueryResult<BlockRaycastHit> outward = raycastFor(world).cast(
                simulationOrigin,
                new Vector3f(0.25f, 0.375f, 0.375f),
                new Vector3f(-1, 0, 0), 0);

        assertEquals(0.0f, inside.distance());
        assertEquals(-1.0f, inside.normalY());
        assertEquals(0.0f, inward.distance());
        assertEquals(-1.0f, inward.normalX());
        assertTrue(outward.result().isEmpty());
    }

    @Test
    void negativeParentCoordinatesAndChunkBoundaryRemainCanonical() {
        World negative = detailWorld(
                -1, 0, -1,
                new LocalSubVoxelPosition(3, 0, 3),
                (byte) 7);
        BlockRaycastHit negativeHit = availableHit(
                negative,
                new SimulationOrigin(new ChunkKey(-1, -1)),
                new Vector3f(14.5f, 0.125f, 15.875f),
                new Vector3f(1, 0, 0), 2);

        World boundary = new World();
        boundary.generate(new ChunkKey(0, 0), ignored -> {});
        boundary.generate(new ChunkKey(1, 0), ignored -> {});
        placeDetail(boundary, 16, 0, 0,
                new LocalSubVoxelPosition(0, 0, 0), (byte) 9);
        BlockRaycastHit boundaryHit = availableHit(
                boundary,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);

        assertEquals(-1, negativeHit.blockX());
        assertEquals(-1, negativeHit.blockZ());
        assertEquals(16, boundaryHit.blockX());
        assertEquals(9, Byte.toUnsignedInt(boundaryHit.blockId()));
    }

    @Test
    void nonzeroOriginAndLegalRebaseSelectSameCanonicalDetailGeometry() {
        ChunkKey canonicalKey = new ChunkKey(100_000_001, -100_000_000);
        int parentX = canonicalKey.worldOriginX();
        int parentZ = canonicalKey.worldOriginZ();
        World world = detailWorld(
                parentX, 1, parentZ,
                new LocalSubVoxelPosition(0, 2, 2),
                (byte) 7);
        world.generate(
                new ChunkKey(100_000_000, -100_000_000),
                ignored -> {});

        BlockRaycastHit before = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000)),
                new Vector3f(15.5f, 1.625f, 0.625f),
                new Vector3f(1, 0, 0), 2);
        BlockRaycastHit after = availableHit(
                world,
                new SimulationOrigin(canonicalKey),
                new Vector3f(-0.5f, 1.625f, 0.625f),
                new Vector3f(1, 0, 0), 2);

        assertEquals(parentX, before.blockX());
        assertEquals(parentZ, before.blockZ());
        assertEquals(before.blockX(), after.blockX());
        assertEquals(before.blockY(), after.blockY());
        assertEquals(before.blockZ(), after.blockZ());
        assertEquals(before.blockId(), after.blockId());
        assertEquals(before.distance(), after.distance(), EPSILON);
        assertEquals(before.target(), after.target());
        assertEquals(before.chunkRevision(), after.chunkRevision());
        assertEquals(before.worldPointX(), after.worldPointX(), 1.0e-12);
        assertEquals(before.worldPointY(), after.worldPointY(), 1.0e-12);
        assertEquals(before.worldPointZ(), after.worldPointZ(), 1.0e-12);
    }

    @Test
    void originExactlyOnParentAndChunkBoundaryIsRebaseInvariant() {
        World world = new World();
        world.generate(new ChunkKey(0, 0), ignored -> {});
        world.generate(new ChunkKey(1, 0), ignored -> {});
        placeDetail(
                world,
                16,
                0,
                0,
                new LocalSubVoxelPosition(0, 0, 0),
                (byte) 7);

        BlockRaycastHit before = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(16.0f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                0);
        BlockRaycastHit after = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(1, 0)),
                new Vector3f(0.0f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0),
                0);

        assertEquals(16, before.blockX());
        assertEquals(0.0f, before.distance(), EPSILON);
        assertEquals(-1.0f, before.normalX());
        assertEquals(before.target(), after.target());
        assertEquals(before.chunkRevision(), after.chunkRevision());
        assertEquals(16.0, before.worldPointX(), 1.0e-12);
        assertEquals(before.worldPointX(), after.worldPointX(), 1.0e-12);
    }

    @Test
    void detailGapPropagatesLaterUnknownAndFailedSpace() {
        World unknownWorld = detailWorld(
                15, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        SpatialQueryResult<BlockRaycastHit> unknown = raycastFor(unknownWorld).cast(
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);

        World failedWorld = detailWorld(
                15, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        ChunkKey failedKey = new ChunkKey(1, 0);
        failedWorld.chunks().failGeneration(
                failedWorld.chunks().beginGeneration(
                        failedKey, ChunkGenerationMode.INITIAL),
                new IllegalStateException("failed fixture"));
        SpatialQueryResult<BlockRaycastHit> failed = raycastFor(failedWorld).cast(
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(15.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);

        assertEquals(SpatialQueryResult.Status.UNKNOWN, unknown.status());
        assertEquals(new ChunkKey(1, 0), unknown.unavailableKey().orElseThrow());
        assertEquals(SpatialQueryResult.Status.FAILED, failed.status());
        assertEquals(failedKey, failed.unavailableKey().orElseThrow());
    }

    @Test
    void detailRefinementNeverExtendsOriginalMaximumDistance() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 0, 0),
                (byte) 7);
        SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));
        Vector3f origin = new Vector3f(0.5f, 0.125f, 0.125f);
        Vector3f direction = new Vector3f(1, 0, 0);

        assertEquals(0.5f, availableHit(
                world, simulationOrigin, origin, direction, 0.5f).distance(), EPSILON);
        SpatialQueryResult<BlockRaycastHit> shortRay = raycastFor(world).cast(
                simulationOrigin, origin, direction, Math.nextDown(0.5f));
        assertTrue(shortRay.result().isEmpty());
    }

    @Test
    void detailGapLaterParentHitIsInclusiveOnlyAtOriginalMaximumDistance() {
        World world = detailWorld(
                1, 0, 0,
                new LocalSubVoxelPosition(0, 3, 3),
                (byte) 7);
        assertTrue(world.setBlock(2, 0, 0, (byte) 8));
        SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));
        Vector3f origin = new Vector3f(0.5f, 0.125f, 0.125f);
        Vector3f direction = new Vector3f(1, 0, 0);

        BlockRaycastHit boundary = availableHit(
                world, simulationOrigin, origin, direction, 1.5f);
        SpatialQueryResult<BlockRaycastHit> outside = raycastFor(world).cast(
                simulationOrigin, origin, direction, Math.nextDown(1.5f));

        assertEquals(2, boundary.blockX());
        assertEquals(1.5f, boundary.distance(), EPSILON);
        assertTrue(outside.result().isEmpty());
    }

    @Test
    void staleDetailHitRevisionIsRejectedByRepositoryRevalidation() {
        LocalSubVoxelPosition targetPosition =
                new LocalSubVoxelPosition(0, 0, 0);
        World world = detailWorld(1, 0, 0, targetPosition, (byte) 7);
        ParentCellObservation observed = world.observeCell(1, 0, 0)
                .observation().orElseThrow();
        BlockRaycastHit hit = availableHit(
                world,
                new SimulationOrigin(new ChunkKey(0, 0)),
                new Vector3f(0.5f, 0.125f, 0.125f),
                new Vector3f(1, 0, 0), 2);
        DetailRaycastTarget target = assertInstanceOf(
                DetailRaycastTarget.class, hit.target());
        placeDetail(world, 1, 0, 0,
                new LocalSubVoxelPosition(1, 0, 0), (byte) 8);

        ChunkDetailMutationOutcome stale = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        hit.blockX(), hit.blockY(), hit.blockZ(),
                        hit.chunkRevision(), observed.state(),
                        target.position(), (byte) 0));

        assertEquals(ChunkDetailMutationOutcome.Status.STALE_CHUNK_REVISION,
                stale.status());
        assertEquals((byte) 7, world.observeCell(1, 0, 0)
                .observation().orElseThrow().state()
                instanceof com.overlord.voxel.DetailCellState detail
                        ? detail.blockId(targetPosition)
                        : 0);
    }

    private static BlockRaycast raycastFor(World world) {
        return new BlockRaycast(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static World detailWorld(
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition position,
            byte blockId) {
        World world = new World();
        ChunkKey key = ChunkKey.fromWorld(parentX, parentZ);
        world.generate(key, ignored -> {});
        placeDetail(world, parentX, parentY, parentZ, position, blockId);
        return world;
    }

    private static void placeDetail(
            World world,
            int parentX,
            int parentY,
            int parentZ,
            LocalSubVoxelPosition position,
            byte blockId) {
        ParentCellObservation observation = world.observeCell(
                parentX, parentY, parentZ).observation().orElseThrow();
        ChunkDetailMutationOutcome mutation = world.chunks().mutateDetail(
                new ChunkDetailMutation.SetSubVoxel(
                        parentX,
                        parentY,
                        parentZ,
                        observation.chunkRevision(),
                        observation.state(),
                        position,
                        blockId));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, mutation.status());
    }

    private static BlockRaycastHit availableHit(
            World world,
            SimulationOrigin simulationOrigin,
            Vector3f origin,
            Vector3f direction,
            float maxDistance) {
        SpatialQueryResult<BlockRaycastHit> result = raycastFor(world).cast(
                simulationOrigin, origin, direction, maxDistance);
        assertEquals(SpatialQueryResult.Status.AVAILABLE, result.status());
        return result.result().orElseThrow();
    }

    private static void assertFace(
            World world,
            SimulationOrigin simulationOrigin,
            Vector3f origin,
            Vector3f direction,
            int normalX,
            int normalY,
            int normalZ,
            float distance) {
        BlockRaycastHit hit = availableHit(
                world, simulationOrigin, origin, direction, 1);
        assertEquals(normalX, (int) hit.normalX());
        assertEquals(normalY, (int) hit.normalY());
        assertEquals(normalZ, (int) hit.normalZ());
        assertEquals(distance, hit.distance(), EPSILON);
    }
}
