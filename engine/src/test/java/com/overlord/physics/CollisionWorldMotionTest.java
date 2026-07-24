package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CollisionWorldMotionTest {
    private static final float PLAYER_HALF_WIDTH = 0.3f;
    private static final Aabb PLAYER_BOX =
            new Aabb(
                    -PLAYER_HALF_WIDTH,
                    0,
                    -PLAYER_HALF_WIDTH,
                    PLAYER_HALF_WIDTH,
                    1.8f,
                    PLAYER_HALF_WIDTH);

    @Test
    void diagonalMotionSlidesAlongWall() {
        World world = worldWithBlock(2, 1, 0);

        MotionResult result =
                fullCubeWorld(world)
                        .moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(1.5f, 1, 0.5f),
                                new Vector3f(2, 0, 2),
                                4);

        assertTrue(result.x() < 2.0f - PLAYER_HALF_WIDTH);
        assertTrue(result.z() > 2.0f);
        assertEquals(-1.0f, result.contacts().get(0).normalX());
        assertEquals(2.0f, result.appliedZ(), 0.000001f);
    }

    @Test
    void diagonalMotionStopsAtTwoWallCorner() {
        World world = worldWithBlock(2, 1, 1);
        setBlock(world, 1, 1, 2);

        MotionResult result =
                fullCubeWorld(world)
                        .moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(1.5f, 1, 1.5f),
                                new Vector3f(2, 0, 2),
                                4);

        assertTrue(result.x() < 2.0f - PLAYER_HALF_WIDTH);
        assertTrue(result.z() < 2.0f - PLAYER_HALF_WIDTH);
        assertEquals(2, result.contacts().size());
        assertEquals(-1.0f, result.contacts().get(0).normalX());
        assertEquals(-1.0f, result.contacts().get(1).normalZ());
    }

    @Test
    void zeroDisplacementLeavesPositionUnchangedWithoutContacts() {
        Vector3f position = new Vector3f(1.5f, 2, -3.5f);

        MotionResult result =
                fullCubeWorld(new World())
                        .moveAndSlide(
                                PLAYER_BOX,
                                position,
                                new Vector3f(),
                                4);

        assertEquals(position.x, result.x());
        assertEquals(position.y, result.y());
        assertEquals(position.z, result.z());
        assertEquals(0.0f, result.appliedX());
        assertEquals(0.0f, result.appliedY());
        assertEquals(0.0f, result.appliedZ());
        assertTrue(result.contacts().isEmpty());
    }

    @Test
    void motionNeverExceedsConfiguredFourContacts() {
        World world = worldWithBlock(2, 1, 1);
        setBlock(world, 1, 1, 2);
        setBlock(world, 1, 0, 1);

        MotionResult result =
                fullCubeWorld(world)
                        .moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(1.5f, 2, 1.5f),
                                new Vector3f(2, -2, 2),
                                4);

        assertTrue(result.contacts().size() <= 4);
        assertEquals(3, result.contacts().size());
        assertEquals(-1.0f, result.contacts().get(0).normalX());
        assertEquals(-1.0f, result.contacts().get(1).normalZ());
        assertEquals(1.0f, result.contacts().get(2).normalY());
    }

    @Test
    void overlapsSolidRequiresStrictPositiveOverlap() {
        CollisionWorld collisions =
                fullCubeWorld(worldWithBlock(0, 0, 0));

        assertTrue(
                collisions.overlapsSolid(
                        new Aabb(0.25f, 0.25f, 0.25f, 1.25f, 1.25f, 1.25f)));
        assertFalse(
                collisions.overlapsSolid(
                        new Aabb(1, 0, 0, 2, 1, 1)));
        assertFalse(
                fullCubeWorld(new World())
                        .overlapsSolid(new Aabb(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void depenetrationPrefersUpwardOnEqualDepth() {
        CollisionWorld collisions =
                fullCubeWorld(worldWithBlock(0, 0, 0));
        Aabb centeredCube =
                new Aabb(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);

        Vector3f recovered =
                collisions
                        .depenetrate(
                                centeredCube,
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                8)
                        .orElseThrow();

        assertEquals(0.5f, recovered.x);
        assertTrue(recovered.y > 1.0f - 0.0001f);
        assertEquals(0.5f, recovered.z);
        assertFalse(collisions.overlapsSolid(centeredCube.translated(recovered)));
    }

    @Test
    void depenetrationReturnsEmptyWhenBoundCannotClearOverlap() {
        World world = worldWithBlock(0, 0, 0);
        setBlock(world, 0, 1, 0);
        CollisionWorld collisions = fullCubeWorld(world);
        Aabb centeredCube =
                new Aabb(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);

        assertTrue(
                collisions
                        .depenetrate(
                                centeredCube,
                                new Vector3f(0.5f, 0.5f, 0.5f),
                                1)
                        .isEmpty());
    }

    @Test
    void motionAndDepenetrationRejectInvalidInputs() {
        CollisionWorld collisions = fullCubeWorld(new World());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(),
                                new Vector3f(1, 0, 0),
                                -1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(Float.NaN, 0, 0),
                                new Vector3f(1, 0, 0),
                                4));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.moveAndSlide(
                                PLAYER_BOX,
                                new Vector3f(),
                                new Vector3f(0, Float.POSITIVE_INFINITY, 0),
                                4));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.depenetrate(
                                PLAYER_BOX, new Vector3f(), -1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        collisions.depenetrate(
                                PLAYER_BOX,
                                new Vector3f(0, 0, Float.NaN),
                                4));
    }

    private static CollisionWorld fullCubeWorld(World world) {
        return new CollisionWorld(
                world,
                BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static World worldWithBlock(int x, int y, int z) {
        World world = new World();
        setBlock(world, x, y, z);
        return world;
    }

    private static void setBlock(World world, int x, int y, int z) {
        assertTrue(world.setBlock(x, y, z, (byte) 1));
    }
}
