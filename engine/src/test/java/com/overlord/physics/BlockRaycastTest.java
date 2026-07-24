package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.util.List;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlockRaycastTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void returnsNearestHitWithExactPointNormalAdjacentAndBlockId() {
        World world = worldWithBlock(2, 1, 0, (byte) 7);
        world.setBlock(4, 1, 0, (byte) 8);
        Vector3f origin = new Vector3f(0.5f, 1.5f, 0.5f);
        Vector3f direction = new Vector3f(2, 0, 0);

        BlockRaycastHit hit =
                raycastFor(world)
                        .cast(origin, direction, 5)
                        .orElseThrow();

        assertEquals(2, hit.blockX());
        assertEquals(1, hit.blockY());
        assertEquals(0, hit.blockZ());
        assertEquals(1, hit.adjacentX());
        assertEquals(1, hit.adjacentY());
        assertEquals(0, hit.adjacentZ());
        assertEquals(7, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(-1.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
        assertEquals(2.0f, hit.pointX(), EPSILON);
        assertEquals(1.5f, hit.pointY(), EPSILON);
        assertEquals(0.5f, hit.pointZ(), EPSILON);
        assertEquals(1.5f, hit.distance(), EPSILON);
        assertEquals(
                new Vector3f(-1, 0, 0),
                hit.normal(new Vector3f()));
        assertEquals(
                new Vector3f(2, 1.5f, 0.5f),
                hit.point(new Vector3f()));
        assertEquals(new Vector3f(0.5f, 1.5f, 0.5f), origin);
        assertEquals(new Vector3f(2, 0, 0), direction);
    }

    @Test
    void exactSubShapeMissesEmptyHalfOfVoxel() {
        BlockCollisionShape upperHalf =
                BlockCollisionShape.of(
                        List.of(new Aabb(0, 0.5f, 0, 1, 1, 1)));

        assertTrue(
                raycastFor(
                                worldWithBlock(1, 0, 0),
                                blockId ->
                                        blockId == 0
                                                ? BlockCollisionShape.empty()
                                                : upperHalf)
                        .cast(
                                new Vector3f(0, 0.25f, 0.5f),
                                new Vector3f(1, 0, 0),
                                3)
                        .isEmpty());
    }

    @Test
    void testsEverySubShapeAndSelectsTheNearestExactHit() {
        Aabb farther = new Aabb(0.75f, 0, 0, 1, 1, 1);
        Aabb nearer = new Aabb(0.25f, 0, 0, 0.5f, 1, 1);
        BlockCollisionShape shape =
                BlockCollisionShape.of(List.of(farther, nearer));

        BlockRaycastHit hit =
                raycastFor(
                                worldWithBlock(1, 0, 0, (byte) 2),
                                blockId ->
                                        blockId == 2
                                                ? shape
                                                : BlockCollisionShape.empty())
                        .cast(
                                new Vector3f(0, 0.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                3)
                        .orElseThrow();

        assertEquals(1.25f, hit.pointX(), EPSILON);
        assertEquals(1.25f, hit.distance(), EPSILON);
        assertEquals(-1.0f, hit.normalX());
    }

    @Test
    void crossesPositiveChunkBoundaryWithoutAllocatingMissingChunks() {
        World world = worldWithBlock(16, 1, 0);
        Set<ChunkKey> chunksBefore = world.chunks().keys();

        BlockRaycastHit hit =
                raycastFor(world)
                        .cast(
                                new Vector3f(14.5f, 1.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                4)
                        .orElseThrow();

        assertEquals(16, hit.blockX());
        assertEquals(1.5f, hit.distance(), EPSILON);
        assertEquals(chunksBefore, world.chunks().keys());
    }

    @Test
    void crossesNegativeChunkBoundaryUsingFloorCoordinates() {
        World world = worldWithBlock(-17, 1, -1);
        Set<ChunkKey> chunksBefore = world.chunks().keys();

        BlockRaycastHit hit =
                raycastFor(world)
                        .cast(
                                new Vector3f(-14.5f, 1.5f, -0.5f),
                                new Vector3f(-1, 0, 0),
                                4)
                        .orElseThrow();

        assertEquals(-17, hit.blockX());
        assertEquals(-1, hit.blockZ());
        assertEquals(1.0f, hit.normalX());
        assertEquals(-16, hit.adjacentX());
        assertEquals(1.5f, hit.distance(), EPSILON);
        assertEquals(chunksBefore, world.chunks().keys());
    }

    @Test
    void exactSlabsPreserveUnitBlockPrecisionAboveFloatIntegerRange() {
        int blockX = 16_777_217;

        BlockRaycastHit hit =
                raycastFor(worldWithBlock(blockX, 0, 0))
                        .cast(
                                new Vector3f(16_777_216f, 0.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                2)
                        .orElseThrow();

        assertEquals(blockX, hit.blockX());
        assertEquals(1.0f, hit.distance(), EPSILON);
    }

    @Test
    void adjacentCoordinateOverflowFailsExplicitly() {
        BlockRaycast raycast =
                raycastFor(worldWithBlock(Integer.MIN_VALUE, 0, 0));

        assertThrows(
                ArithmeticException.class,
                () ->
                        raycast.cast(
                                new Vector3f(
                                        (float) Integer.MIN_VALUE,
                                        0.5f,
                                        0.5f),
                                new Vector3f(1, 0, 0),
                                0));
    }

    @Test
    void unrepresentableTiedStepStillChecksRepresentableIncidentCell() {
        BlockRaycastHit hit =
                raycastFor(
                                worldWithBlock(
                                        Integer.MIN_VALUE,
                                        0,
                                        -1))
                        .cast(
                                new Vector3f(
                                        (float) Integer.MIN_VALUE,
                                        0.5f,
                                        0),
                                new Vector3f(-1, 0, -1),
                                0)
                        .orElseThrow();

        assertEquals(Integer.MIN_VALUE, hit.blockX());
        assertEquals(-1, hit.blockZ());
        assertEquals(0.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(1.0f, hit.normalZ());
    }

    @Test
    void originInsideShapeReturnsZeroWithDominantDirectionTiePriority() {
        Vector3f origin = new Vector3f(0.25f, 0.5f, 0.75f);

        BlockRaycastHit hit =
                raycastFor(worldWithBlock(0, 0, 0, (byte) 0xFE))
                        .cast(origin, new Vector3f(3, 3, 3), 0)
                        .orElseThrow();

        assertEquals(0.0f, hit.distance());
        assertEquals(origin, hit.point(new Vector3f()));
        assertEquals(0.0f, hit.normalX());
        assertEquals(-1.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
        assertEquals(0, hit.adjacentX());
        assertEquals(-1, hit.adjacentY());
        assertEquals(0, hit.adjacentZ());
        assertEquals(254, Byte.toUnsignedInt(hit.blockId()));
    }

    @Test
    void originOnMinimumFaceMovingOutwardIsNotInsideOrAHit() {
        assertTrue(
                raycastFor(worldWithBlock(0, 0, 0))
                        .cast(
                                new Vector3f(0, 0.5f, 0.5f),
                                new Vector3f(-1, 0, 0),
                                1)
                        .isEmpty());
    }

    @Test
    void originOnMinimumFaceMovingInwardIsAZeroDistanceSlabHit() {
        BlockRaycastHit hit =
                raycastFor(worldWithBlock(0, 0, 0))
                        .cast(
                                new Vector3f(0, 0.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                1)
                        .orElseThrow();

        assertEquals(0.0f, hit.distance());
        assertEquals(-1.0f, hit.normalX());
    }

    @Test
    void originOnMaximumFaceMovingOutwardIsNotInsideOrAHit() {
        assertTrue(
                raycastFor(worldWithBlock(0, 0, 0))
                        .cast(
                                new Vector3f(1, 0.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                1)
                        .isEmpty());
    }

    @Test
    void originOnMaximumFaceMovingInwardIsAZeroDistanceSlabHit() {
        BlockRaycastHit hit =
                raycastFor(worldWithBlock(0, 0, 0))
                        .cast(
                                new Vector3f(1, 0.5f, 0.5f),
                                new Vector3f(-1, 0, 0),
                                1)
                        .orElseThrow();

        assertEquals(0.0f, hit.distance());
        assertEquals(1.0f, hit.normalX());
    }

    @Test
    void tiedEntryAxesPreferYThenXThenZ() {
        BlockRaycastHit xyzTie =
                raycastFor(worldWithBlock(1, 1, 1))
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(1, 1, 1),
                                2)
                        .orElseThrow();

        assertEquals(0.0f, xyzTie.normalX());
        assertEquals(-1.0f, xyzTie.normalY());
        assertEquals(0.0f, xyzTie.normalZ());

        BlockRaycastHit xzTie =
                raycastFor(worldWithBlock(1, 0, 1))
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(1, 0, 1),
                                2)
                        .orElseThrow();

        assertEquals(-1.0f, xzTie.normalX());
        assertEquals(0.0f, xzTie.normalY());
        assertEquals(0.0f, xzTie.normalZ());
    }

    @Test
    void edgeTouchChecksTheDeterministicXAxisSideCell() {
        BlockRaycastHit hit =
                raycastFor(worldWithBlock(-1, 0, 0))
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(-1, 0, -1),
                                2)
                        .orElseThrow();

        assertEquals(-1, hit.blockX());
        assertEquals(0, hit.blockZ());
        assertEquals(1.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
    }

    @Test
    void tiedCellsUseAscendingBlockCoordinatesAfterAxisPriority() {
        World world = worldWithBlock(-1, 0, 0);
        world.setBlock(-1, 0, -1, (byte) 2);

        BlockRaycastHit hit =
                raycastFor(world)
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(-1, 0, -1),
                                2)
                        .orElseThrow();

        assertEquals(-1, hit.blockX());
        assertEquals(-1, hit.blockZ());
        assertEquals(2, Byte.toUnsignedInt(hit.blockId()));
        assertEquals(1.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
    }

    @Test
    void accumulatedDdaRoundingKeepsMathematicalEdgeEventTied() {
        BlockRaycastHit hit =
                raycastFor(worldWithBlock(0, 0, 2))
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(1, 0, 3),
                                2)
                        .orElseThrow();

        assertEquals(0, hit.blockX());
        assertEquals(2, hit.blockZ());
        assertEquals(0.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(-1.0f, hit.normalZ());
        assertEquals(
                (float) (Math.sqrt(10.0) / 2.0),
                hit.distance(),
                EPSILON);
    }

    @Test
    void hitAtMaximumDistanceIsIncludedButFartherHitIsMissed() {
        BlockRaycast raycast = raycastFor(worldWithBlock(2, 0, 0));
        Vector3f origin = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f direction = new Vector3f(1, 0, 0);

        assertEquals(
                1.5f,
                raycast.cast(origin, direction, 1.5f)
                        .orElseThrow()
                        .distance(),
                EPSILON);
        assertTrue(
                raycast.cast(origin, direction, 1.499f)
                        .isEmpty());
    }

    @Test
    void synchronousDistanceSafetyLimitIsPublicAndInclusive() {
        assertEquals(4096.0f, BlockRaycast.MAX_DISTANCE);

        assertEquals(
                0.0f,
                raycastFor(worldWithBlock(0, 0, 0))
                        .cast(
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                BlockRaycast.MAX_DISTANCE)
                        .orElseThrow()
                        .distance());
    }

    @Test
    void rejectsDistanceAboveSynchronousSafetyLimit() {
        BlockRaycast raycast = raycastFor(new World());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                new Vector3f(1, 0, 0),
                                Math.nextUp(
                                        BlockRaycast.MAX_DISTANCE)));
    }

    @Test
    void returnsEmptyWhenNoShapeIntersectsTheFiniteRay() {
        World world = worldWithBlock(3, 0, 0);
        Set<ChunkKey> chunksBefore = world.chunks().keys();

        assertTrue(
                raycastFor(world)
                        .cast(
                                new Vector3f(0.5f, 2.5f, 0.5f),
                                new Vector3f(1, 0, 0),
                                100)
                        .isEmpty());
        assertEquals(chunksBefore, world.chunks().keys());
    }

    @Test
    void rejectsZeroAndNonFiniteDirections() {
        BlockRaycast raycast = raycastFor(new World());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                new Vector3f(),
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                new Vector3f(Float.NaN, 0, 0),
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                new Vector3f(
                                        0,
                                        Float.POSITIVE_INFINITY,
                                        0),
                                1));
    }

    @Test
    void rejectsNonFiniteOriginsAndInvalidMaximumDistances() {
        BlockRaycast raycast = raycastFor(new World());
        Vector3f direction = new Vector3f(1, 0, 0);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(Float.NaN, 0, 0),
                                direction,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(
                                        0,
                                        0,
                                        Float.NEGATIVE_INFINITY),
                                direction,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                direction,
                                -1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                direction,
                                Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        raycast.cast(
                                new Vector3f(),
                                direction,
                                Float.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNullInputsAndDependencies() {
        BlockRaycast raycast = raycastFor(new World());

        assertThrows(
                NullPointerException.class,
                () -> raycast.cast(null, new Vector3f(1, 0, 0), 1));
        assertThrows(
                NullPointerException.class,
                () -> raycast.cast(new Vector3f(), null, 1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockRaycast(
                                null,
                                BlockCollisionShapeResolver
                                        .fullCubesForNonAir()));
        assertThrows(
                NullPointerException.class,
                () -> new BlockRaycast(new World(), null));
    }

    private static BlockRaycast raycastFor(World world) {
        return raycastFor(
                world,
                BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static BlockRaycast raycastFor(
            World world, BlockCollisionShapeResolver resolver) {
        return new BlockRaycast(world, resolver);
    }

    private static World worldWithBlock(int x, int y, int z) {
        return worldWithBlock(x, y, z, (byte) 1);
    }

    private static World worldWithBlock(
            int x, int y, int z, byte blockId) {
        World world = new World();
        assertTrue(world.setBlock(x, y, z, blockId));
        return world;
    }
}
