package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.util.List;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CollisionWorldSweepTest {
    private static final Aabb PLAYER_BOX =
            new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);

    @Test
    void downwardSweepHitsFloor() {
        CollisionWorld collisions =
                fullCubeWorld(worldWithBlock(0, 0, 0));

        SweepResult hit =
                collisions
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 2, 0.5f),
                                new Vector3f(0, -2, 0))
                        .orElseThrow();

        assertEquals(0.5f, hit.fraction());
        assertEquals(0.0f, hit.normalX());
        assertEquals(1.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
        assertEquals(0.5f, hit.pointX());
        assertEquals(1.0f, hit.pointY());
        assertEquals(0.5f, hit.pointZ());
        assertEquals(0, hit.blockX());
        assertEquals(0, hit.blockY());
        assertEquals(0, hit.blockZ());
        assertEquals(new Aabb(0, 0, 0, 1, 1, 1), hit.blockShape());
    }

    @Test
    void horizontalSweepHitsWall() {
        SweepResult hit =
                fullCubeWorld(worldWithBlock(2, 1, 0))
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.5f),
                                new Vector3f(2, 0, 0))
                        .orElseThrow();

        assertEquals(0.6f, hit.fraction(), 0.000001f);
        assertEquals(-1.0f, hit.normalX());
        assertEquals(0.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
        assertEquals(2, hit.blockX());
    }

    @Test
    void upwardSweepHitsCeiling() {
        SweepResult hit =
                fullCubeWorld(worldWithBlock(0, 3, 0))
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.5f),
                                new Vector3f(0, 1, 0))
                        .orElseThrow();

        assertEquals(0.2f, hit.fraction(), 0.000001f);
        assertEquals(-1.0f, hit.normalY());
        assertEquals(3, hit.blockY());
    }

    @Test
    void highSpeedFallHitsFloorBeforeEndpoint() {
        World world = worldWithBlock(0, 0, 0);
        CollisionWorld collisions = fullCubeWorld(world);
        Aabb body = new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);

        SweepResult hit =
                collisions
                        .sweep(
                                body,
                                new Vector3f(0.5f, 20, 0.5f),
                                new Vector3f(0, -40, 0))
                        .orElseThrow();

        assertEquals(1.0f, hit.normalY());
        assertTrue(hit.fraction() > 0.0f && hit.fraction() < 1.0f);
        assertEquals(0, hit.blockY());
    }

    @Test
    void sweepChecksEverySubBoxAndReturnsTheTranslatedHitShape() {
        World world = worldWithBlock(1, 1, 0, (byte) 2);
        Aabb farBox = new Aabb(0.75f, 0, 0, 1, 1, 1);
        Aabb nearBox = new Aabb(0, 0, 0, 0.25f, 1, 1);
        BlockCollisionShape multiBox =
                BlockCollisionShape.of(List.of(farBox, nearBox));
        CollisionWorld collisions =
                new CollisionWorld(
                        world,
                        blockId ->
                                blockId == 2
                                        ? multiBox
                                        : BlockCollisionShape.empty());

        SweepResult hit =
                collisions
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.5f),
                                new Vector3f(2, 0, 0))
                        .orElseThrow();

        assertEquals(0.1f, hit.fraction(), 0.000001f);
        assertEquals(
                new Aabb(1, 1, 0, 1.25f, 2, 1),
                hit.blockShape());
    }

    @Test
    void equalSubBoxContactsUseDeclaredOrder() {
        World world = worldWithBlock(1, 1, 0, (byte) 2);
        Aabb first = new Aabb(0, 0, 0, 0.25f, 1, 1);
        Aabb second = new Aabb(0, 0.25f, 0, 0.5f, 0.75f, 1);
        BlockCollisionShape shape =
                BlockCollisionShape.of(List.of(first, second));
        CollisionWorld collisions =
                new CollisionWorld(
                        world,
                        blockId ->
                                blockId == 2
                                        ? shape
                                        : BlockCollisionShape.empty());

        SweepResult hit =
                collisions
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.5f),
                                new Vector3f(1, 0, 0))
                        .orElseThrow();

        assertEquals(new Aabb(1, 1, 0, 1.25f, 2, 1), hit.blockShape());
    }

    @Test
    void negativeCoordinatesUseFloorBasedBlockRanges() {
        SweepResult hit =
                fullCubeWorld(worldWithBlock(-1, 1, -1))
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, -0.5f),
                                new Vector3f(-1, 0, 0))
                        .orElseThrow();

        assertEquals(0.2f, hit.fraction(), 0.000001f);
        assertEquals(1.0f, hit.normalX());
        assertEquals(-1, hit.blockX());
        assertEquals(-1, hit.blockZ());
    }

    @Test
    void sweepCrossesChunkBoundaryAtSixteenWithoutAllocatingMissingChunks() {
        World world = worldWithBlock(16, 1, 0);
        Set<ChunkKey> chunksBefore = world.chunks().keys();

        SweepResult hit =
                fullCubeWorld(world)
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(15.2f, 1, 0.5f),
                                new Vector3f(2, 0, 0))
                        .orElseThrow();

        assertEquals(16, hit.blockX());
        assertEquals(-1.0f, hit.normalX());
        assertEquals(chunksBefore, world.chunks().keys());
    }

    @Test
    void touchingFaceOnlyHitsWhenMovingIntoIt() {
        CollisionWorld collisions =
                fullCubeWorld(worldWithBlock(1, 1, 0));
        Vector3f touchingPosition = new Vector3f(0.7f, 1, 0.5f);

        SweepResult into =
                collisions
                        .sweep(
                                PLAYER_BOX,
                                touchingPosition,
                                new Vector3f(1, 0, 0))
                        .orElseThrow();

        assertEquals(0.0f, into.fraction());
        assertEquals(-1.0f, into.normalX());
        assertFalse(
                collisions
                        .sweep(
                                PLAYER_BOX,
                                touchingPosition,
                                new Vector3f(-1, 0, 0))
                        .isPresent());
    }

    @Test
    void touchingOnStationaryAxisIsNotOverlap() {
        CollisionWorld collisions =
                fullCubeWorld(worldWithBlock(1, 1, 1));

        assertFalse(
                collisions
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.7f),
                                new Vector3f(2, 0, 0))
                        .isPresent());
    }

    @Test
    void tiedEntryAxesPreferYThenXThenZ() {
        Aabb halfCube =
                new Aabb(-0.25f, 0, -0.25f, 0.25f, 0.5f, 0.25f);
        SweepResult hit =
                fullCubeWorld(worldWithBlock(1, 1, 0))
                        .sweep(
                                halfCube,
                                new Vector3f(0.25f, 0, 0.5f),
                                new Vector3f(1, 1, 0))
                        .orElseThrow();

        assertEquals(0.5f, hit.fraction());
        assertEquals(0.0f, hit.normalX());
        assertEquals(-1.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
    }

    @Test
    void signedZeroFractionsStillUseAxisPriority() {
        World world = worldWithBlock(-1, 1, 0);
        world.setBlock(0, 3, 0, (byte) 1);

        SweepResult hit =
                fullCubeWorld(world)
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.3f, 1.2f, 0.5f),
                                new Vector3f(-1, 1, 0))
                        .orElseThrow();

        assertEquals(0.0f, hit.fraction());
        assertEquals(0.0f, hit.normalX());
        assertEquals(-1.0f, hit.normalY());
        assertEquals(0.0f, hit.normalZ());
        assertEquals(3, hit.blockY());
    }

    @Test
    void tiedBlocksUseAscendingBlockCoordinates() {
        Aabb wideBody =
                new Aabb(-0.6f, 0, -0.3f, 0.6f, 1, 0.3f);
        World world = worldWithBlock(1, 0, 0);
        world.setBlock(0, 0, 0, (byte) 1);

        SweepResult hit =
                fullCubeWorld(world)
                        .sweep(
                                wideBody,
                                new Vector3f(1, 2, 0.5f),
                                new Vector3f(0, -2, 0))
                        .orElseThrow();

        assertEquals(0, hit.blockX());
        assertEquals(0, hit.blockY());
        assertEquals(0, hit.blockZ());
    }

    @Test
    void zeroDisplacementHasNoSweepContactOrChunkSideEffects() {
        World world = worldWithBlock(0, 0, 0);
        Set<ChunkKey> chunksBefore = world.chunks().keys();

        assertFalse(
                fullCubeWorld(world)
                        .sweep(
                                PLAYER_BOX,
                                new Vector3f(0.5f, 1, 0.5f),
                                new Vector3f())
                        .isPresent());
        assertEquals(chunksBefore, world.chunks().keys());
    }

    @Test
    void sweepRejectsNullAndNonFiniteInputs() {
        CollisionWorld collisions = fullCubeWorld(new World());

        assertThrows(
                NullPointerException.class,
                () ->
                        collisions.sweep(
                                null, new Vector3f(), new Vector3f(1, 0, 0)));
        assertThrows(
                NullPointerException.class,
                () ->
                        collisions.sweep(
                                PLAYER_BOX, null, new Vector3f(1, 0, 0)));
        assertThrows(
                NullPointerException.class,
                () ->
                        collisions.sweep(
                                PLAYER_BOX, new Vector3f(), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.sweep(
                                PLAYER_BOX,
                                new Vector3f(Float.NaN, 0, 0),
                                new Vector3f(1, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.sweep(
                                PLAYER_BOX,
                                new Vector3f(),
                                new Vector3f(0, Float.POSITIVE_INFINITY, 0)));
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new CollisionWorld(
                                null,
                                BlockCollisionShapeResolver
                                        .fullCubesForNonAir()));
        assertThrows(
                NullPointerException.class,
                () -> new CollisionWorld(new World(), null));
    }

    private static CollisionWorld fullCubeWorld(World world) {
        return new CollisionWorld(
                world,
                BlockCollisionShapeResolver.fullCubesForNonAir());
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
