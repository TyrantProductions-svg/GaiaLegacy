package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PlayerControllerCollisionTest {
    private static final float FIXED_STEP = 1.0f / 60.0f;
    private static final float EPSILON = 1.0e-4f;
    private static final Aabb PLAYER_COLLIDER =
            new Aabb(
                    -GameConfig.Player.WIDTH / 2.0f,
                    0,
                    -GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.HEIGHT,
                    GameConfig.Player.WIDTH / 2.0f);

    @Test
    void diagonalIntentIsNormalizedAndControllerOwnsBodyStepState() {
        PlayerController player =
                controller(new World(), 6, 0, -60);
        player.teleport(new Vector3f(2, 3, 4));

        player.fixedUpdate(0.5f, 1, 1, false, true, true);

        float expectedAxisDistance =
                6 * 0.5f / (float) Math.sqrt(2);
        assertVector(
                player.body().previousPosition(new Vector3f()),
                2,
                3,
                4);
        assertVector(
                player.body().position(new Vector3f()),
                2 + expectedAxisDistance,
                3,
                4 + expectedAxisDistance);
        assertVector(
                player.body().linearVelocity(new Vector3f()),
                6 / (float) Math.sqrt(2),
                0,
                6 / (float) Math.sqrt(2));
    }

    @Test
    void fallingPlayerLandsAndRemainsStablyGrounded() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 5, 0.5f));

        advance(player, 180);

        Vector3f settled = player.body().position(new Vector3f());
        assertEquals(
                1.0f,
                settled.y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertEquals(
                0.0f,
                player.body().linearVelocity(new Vector3f()).y,
                EPSILON);
        assertTrue(player.isGrounded());

        player.fixedUpdate(FIXED_STEP, 0, 0, false, false, false);

        assertEquals(
                settled,
                player.body().previousPosition(new Vector3f()));
        assertEquals(
                settled.y,
                player.body().position(new Vector3f()).y,
                EPSILON);
        assertTrue(player.isGrounded());
    }

    @Test
    void jumpUsesGroundedEdgeOnly() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        player.fixedUpdate(FIXED_STEP, 0, 0, false, false, false);
        assertTrue(player.isGrounded());

        player.fixedUpdate(FIXED_STEP, 0, 0, true, false, false);
        float firstJumpVelocity =
                player.body().linearVelocity(new Vector3f()).y;

        assertTrue(firstJumpVelocity > 0);
        assertFalse(player.isGrounded());

        player.fixedUpdate(FIXED_STEP, 0, 0, true, false, false);

        assertTrue(
                player.body().linearVelocity(new Vector3f()).y
                        < firstJumpVelocity);
        assertFalse(player.isGrounded());
    }

    @Test
    void airborneJumpRequestDoesNotCreateVerticalVelocity() {
        PlayerController player = controller(new World());
        player.teleport(new Vector3f(0.5f, 5, 0.5f));

        player.fixedUpdate(FIXED_STEP, 0, 0, true, false, false);

        assertEquals(
                GameConfig.Physics.GRAVITY * FIXED_STEP,
                player.body().linearVelocity(new Vector3f()).y,
                EPSILON);
        assertFalse(player.isGrounded());
    }

    @Test
    void diagonalWallImpactSlidesAlongWall() {
        World world = new World();
        fillFloor(world, 0, 2, -1, 8);
        fillWallX(world, 2, 1, 2, -1, 8);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(1.5f, 1, 0.5f));

        advance(player, 60, 1, 1);

        Vector3f feet = player.body().position(new Vector3f());
        assertTrue(
                feet.x
                        < 2.0f - GameConfig.Player.WIDTH / 2.0f);
        assertTrue(feet.z > 2.0f);
        assertEquals(
                0.0f,
                player.body().linearVelocity(new Vector3f()).x,
                EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void twoWallCornerRemovesBothInwardVelocityComponents() {
        World world = new World();
        fillFloor(world, 0, 2, 0, 2);
        fillWallX(world, 2, 1, 2, 0, 2);
        fillWallZ(world, 2, 1, 2, 0, 2);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(1.5f, 1, 1.5f));

        advance(player, 30, 1, 1);

        Vector3f feet = player.body().position(new Vector3f());
        Vector3f velocity =
                player.body().linearVelocity(new Vector3f());
        assertTrue(
                feet.x
                        < 2.0f - GameConfig.Player.WIDTH / 2.0f);
        assertTrue(
                feet.z
                        < 2.0f - GameConfig.Player.WIDTH / 2.0f);
        assertEquals(0.0f, velocity.x, EPSILON);
        assertEquals(0.0f, velocity.z, EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void jumpStopsAtCeiling() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        fillCeiling(world, 3, -1, 1, -1, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        player.fixedUpdate(FIXED_STEP, 0, 0, false, false, false);

        player.fixedUpdate(FIXED_STEP, 0, 0, true, false, false);
        advance(player, 30);

        assertTrue(
                player.body().linearVelocity(new Vector3f()).y <= 0);
        assertFalse(player.overlapsSolid());
    }

    @Test
    void highSpeedFallSweepsToGroundWithoutTunneling() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerController player = controller(world, 5, 0, -500);
        player.teleport(new Vector3f(0.5f, 20, 0.5f));
        player.body().setLinearVelocity(new Vector3f(0, -200, 0));

        player.fixedUpdate(0.25f, 0, 0, false, false, false);

        assertEquals(
                1.0f,
                player.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertEquals(
                0.0f,
                player.body().linearVelocity(new Vector3f()).y,
                EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void lowFrameDiagonalMotionSlidesAtChunkBoundary() {
        World world = new World();
        fillFloor(world, 14, 16, 0, 6);
        fillWallX(world, 16, 1, 2, 0, 6);
        PlayerController player =
                controller(
                        world,
                        20,
                        GameConfig.Physics.GRAVITY,
                        -60);
        player.teleport(new Vector3f(15.2f, 1, 0.5f));

        player.fixedUpdate(0.25f, 1, 1, false, false, false);

        Vector3f feet = player.body().position(new Vector3f());
        assertTrue(
                feet.x
                        < 16.0f - GameConfig.Player.WIDTH / 2.0f);
        assertTrue(feet.z > 3.0f);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void terminalVelocityClampsDownwardFall() {
        PlayerController player = controller(new World());
        player.teleport(new Vector3f(0.5f, 200, 0.5f));

        advance(player, 300);

        assertEquals(
                GameConfig.Physics.TERMINAL_VELOCITY,
                player.body().linearVelocity(new Vector3f()).y,
                EPSILON);
        assertFalse(player.isGrounded());
    }

    @Test
    void teleportSynchronizesTransformsAndClearsMotionState() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        player.fixedUpdate(FIXED_STEP, 0, 0, false, false, false);
        assertTrue(player.isGrounded());
        player.body().setLinearVelocity(new Vector3f(1, 2, 3));

        player.teleport(new Vector3f(8, 9, 10));

        assertVector(
                player.body().previousPosition(new Vector3f()),
                8,
                9,
                10);
        assertVector(
                player.body().position(new Vector3f()),
                8,
                9,
                10);
        assertVector(
                player.body().linearVelocity(new Vector3f()),
                0,
                0,
                0);
        assertFalse(player.isGrounded());
        assertFalse(player.isNoclip());
    }

    @Test
    void invalidDeltaAndIntentAreRejectedWithoutChangingBodyState() {
        PlayerController player = controller(new World());
        player.teleport(new Vector3f(1, 2, 3));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                0, 0, 0, false, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                -FIXED_STEP,
                                0,
                                0,
                                false,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                Float.NaN,
                                0,
                                0,
                                false,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                Float.POSITIVE_INFINITY,
                                0,
                                0,
                                false,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                FIXED_STEP,
                                Float.NaN,
                                0,
                                false,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        player.fixedUpdate(
                                FIXED_STEP,
                                0,
                                Float.NEGATIVE_INFINITY,
                                false,
                                false,
                                false));

        assertVector(
                player.body().previousPosition(new Vector3f()),
                1,
                2,
                3);
        assertVector(
                player.body().position(new Vector3f()),
                1,
                2,
                3);
    }

    @Test
    void configurationExposesTaskSevenAndTraversalBounds() {
        assertEquals(1.0f, GameConfig.Player.MAX_STEP_HEIGHT);
        assertEquals(1.0f, GameConfig.Player.GROUND_SNAP_DISTANCE);
        assertEquals(15, GameConfig.Player.NOCLIP_DOUBLE_TAP_STEPS);
        assertEquals(4, GameConfig.Physics.MAX_SLIDE_ITERATIONS);
        assertEquals(8, GameConfig.Physics.MAX_DEPENETRATION_ITERATIONS);
    }

    private static PlayerController controller(World world) {
        return controller(
                world,
                GameConfig.Player.MOVEMENT_SPEED,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
    }

    private static PlayerController controller(
            World world,
            float movementSpeed,
            float gravity,
            float terminalVelocity) {
        PhysicsBody body =
                new PhysicsBody(
                        PLAYER_COLLIDER,
                        MassProperties.dynamic(1.0f));
        CollisionWorld collisions =
                new CollisionWorld(
                        world,
                        BlockCollisionShapeResolver
                                .fullCubesForNonAir());
        return new PlayerController(
                body,
                collisions,
                movementSpeed,
                GameConfig.Player.NOCLIP_SPEED,
                GameConfig.Player.JUMP_VELOCITY,
                gravity,
                terminalVelocity);
    }

    private static void advance(
            PlayerController player, int steps) {
        advance(player, steps, 0, 0);
    }

    private static void advance(
            PlayerController player,
            int steps,
            float moveX,
            float moveZ) {
        for (int step = 0; step < steps; step++) {
            player.fixedUpdate(
                    FIXED_STEP,
                    moveX,
                    moveZ,
                    false,
                    false,
                    false);
        }
    }

    private static void fillFloor(
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(world, x, 0, z);
            }
        }
    }

    private static void fillWallX(
            World world,
            int x,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(world, x, y, z);
            }
        }
    }

    private static void fillWallZ(
            World world,
            int z,
            int minY,
            int maxY,
            int minX,
            int maxX) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                setBlock(world, x, y, z);
            }
        }
    }

    private static void fillCeiling(
            World world,
            int y,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(world, x, y, z);
            }
        }
    }

    private static void setBlock(
            World world, int x, int y, int z) {
        if (world.getBlock(x, y, z) == 0) {
            assertTrue(world.setBlock(x, y, z, (byte) 1));
        }
    }

    private static void assertVector(
            Vector3f actual, float x, float y, float z) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}
