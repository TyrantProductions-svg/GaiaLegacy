package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PlayerControllerTraversalTest {
    private static final float FIXED_STEP = 1.0f / 60.0f;
    private static final float EPSILON = 2.0e-3f;
    private static final Aabb PLAYER_COLLIDER =
            new Aabb(
                    -GameConfig.Player.WIDTH / 2.0f,
                    0,
                    -GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.HEIGHT,
                    GameConfig.Player.WIDTH / 2.0f);

    @Test
    void walksUpOneBlockAndPreservesHorizontalProgress() {
        World world = new World();
        fillFloor(world, 0, 5, 0, 0);
        fillBlocks(world, 2, 5, 1, 1, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));

        advance(player, 45, 1, 0);

        Vector3f feet = player.body().position(new Vector3f());
        assertTrue(feet.x > 1.5f);
        assertEquals(2.0f, feet.y, EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void lowCeilingPreventsStepUp() {
        World world = new World();
        fillFloor(world, 0, 4, 0, 0);
        fillBlocks(world, 2, 4, 1, 1, 0, 0);
        fillBlocks(world, 2, 4, 3, 3, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));

        advance(player, 30, 1, 0);

        Vector3f feet = player.body().position(new Vector3f());
        assertTrue(
                feet.x
                        < 2.0f - GameConfig.Player.WIDTH / 2.0f);
        assertEquals(1.0f, feet.y, EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    @Test
    void walkingDownOneBlockUsesGroundSnapWithoutAirborneFrame() {
        World world = new World();
        setBlock(world, 0, 0, 0);
        setBlock(world, 0, 1, 0);
        fillBlocks(world, 1, 4, 0, 0, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 2, 0.5f));
        player.fixedUpdate(
                FIXED_STEP, 0, 0, false, false, false);
        assertTrue(player.isGrounded());

        for (int step = 0; step < 30; step++) {
            player.fixedUpdate(
                    FIXED_STEP, 1, 0, false, false, false);
            assertTrue(player.isGrounded(), "step " + step);
        }

        assertEquals(
                1.0f,
                player.body().position(new Vector3f()).y,
                EPSILON);
        assertFalse(player.overlapsSolid());
    }

    @Test
    void jumpDoesNotSnapDownFromPlatform() {
        World world = new World();
        setBlock(world, 0, 0, 0);
        setBlock(world, 0, 1, 0);
        fillBlocks(world, 1, 4, 0, 0, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 2, 0.5f));
        player.fixedUpdate(
                FIXED_STEP, 0, 0, false, false, false);

        player.fixedUpdate(
                FIXED_STEP, 1, 0, true, false, false);
        advance(player, 10, 1, 0);

        assertTrue(
                player.body().position(new Vector3f()).y > 2.0f);
        assertTrue(
                player.body().linearVelocity(new Vector3f()).y > 0);
        assertFalse(player.isGrounded());
    }

    @Test
    void upwardVelocityDoesNotSnapDownFromPlatform() {
        World world = new World();
        setBlock(world, 0, 0, 0);
        setBlock(world, 0, 1, 0);
        fillBlocks(world, 1, 3, 0, 0, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(1.25f, 2, 0.5f));
        player.fixedUpdate(
                FIXED_STEP, 0, 0, false, false, false);
        assertTrue(player.isGrounded());
        player.body().setLinearVelocity(new Vector3f(0, 2, 0));

        player.fixedUpdate(
                FIXED_STEP, 1, 0, false, false, false);

        assertTrue(
                player.body().position(new Vector3f()).y > 2.0f);
        assertTrue(
                player.body().linearVelocity(new Vector3f()).y > 0);
        assertFalse(player.isGrounded());
    }

    @Test
    void spawnOverlapRecoversAndSynchronizesBodyState() {
        World world = new World();
        setBlock(world, 0, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 0, 0.5f));

        assertTrue(player.recoverFromPenetration());

        Vector3f recovered =
                player.body().position(new Vector3f());
        assertEquals(
                recovered,
                player.body().previousPosition(new Vector3f()));
        assertFalse(player.overlapsSolid());
        assertEquals(
                0.0f,
                player.body().linearVelocity(new Vector3f()).length(),
                EPSILON);
    }

    @Test
    void recoveryScansUpwardAfterLocalIterationBound() {
        World world = new World();
        fillBlocks(world, 0, 9, 0, 9, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 0, 0.5f));

        assertTrue(player.recoverFromPenetration());

        assertEquals(
                10.0f,
                player.body().position(new Vector3f()).y,
                EPSILON);
        assertFalse(player.overlapsSolid());
    }

    @Test
    void noclipMovementIsNormalizedAndIgnoresGravityAndCollision() {
        World world = new World();
        fillBlocks(world, 1, 1, 0, 12, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));

        assertTrue(player.setNoclip(true));
        player.fixedUpdate(1.0f, 1, 0, false, true, false);

        float axisDistance =
                GameConfig.Player.NOCLIP_SPEED
                        / (float) Math.sqrt(2);
        assertEquals(
                0.5f + axisDistance,
                player.body().position(new Vector3f()).x,
                EPSILON);
        assertEquals(
                1.0f + axisDistance,
                player.body().position(new Vector3f()).y,
                EPSILON);
        assertEquals(
                0.5f,
                player.body().position(new Vector3f()).z,
                EPSILON);
        assertTrue(player.isNoclip());
        assertFalse(player.isGrounded());

        player.fixedUpdate(
                0.5f, 0, 0, false, false, true);

        assertEquals(
                1.0f + axisDistance
                        - GameConfig.Player.NOCLIP_SPEED * 0.5f,
                player.body().position(new Vector3f()).y,
                EPSILON);
    }

    @Test
    void enteringNoclipClearsGravityVelocityAndGroundedState() {
        World world = new World();
        fillFloor(world, 0, 1, 0, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        player.fixedUpdate(
                FIXED_STEP, 0, 0, false, false, false);
        assertTrue(player.isGrounded());
        player.body().setLinearVelocity(new Vector3f(1, -4, 2));

        assertTrue(player.setNoclip(true));

        assertTrue(player.isNoclip());
        assertFalse(player.isGrounded());
        Vector3f velocity =
                player.body().linearVelocity(new Vector3f());
        assertEquals(1.0f, velocity.x, EPSILON);
        assertEquals(0.0f, velocity.y, EPSILON);
        assertEquals(2.0f, velocity.z, EPSILON);
    }

    @Test
    void settingNoclipToCurrentModeDoesNotResetFlightMotion() {
        PlayerController player = controller(new World());
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        assertTrue(player.setNoclip(true));
        player.fixedUpdate(
                FIXED_STEP, 1, 0, false, true, false);
        Vector3f position =
                player.body().position(new Vector3f());
        Vector3f velocity =
                player.body().linearVelocity(new Vector3f());

        assertTrue(player.setNoclip(true));

        assertEquals(
                position,
                player.body().position(new Vector3f()));
        assertEquals(
                velocity,
                player.body().linearVelocity(new Vector3f()));
    }

    @Test
    void noclipExitInsideWallMovesToNearestSafeSpace() {
        World world = new World();
        setBlock(world, 0, 0, 0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 0, 0.5f));
        assertTrue(player.setNoclip(true));

        assertTrue(player.setNoclip(false));

        assertFalse(player.isNoclip());
        assertFalse(player.overlapsSolid());
        assertEquals(
                player.body().position(new Vector3f()),
                player.body().previousPosition(new Vector3f()));
    }

    @Test
    void failedNoclipExitRemainsNoclipAndKeepsPosition() {
        World world = new World();
        fillBlocks(
                world,
                0,
                9,
                0,
                GameConfig.Chunk.MAX_HEIGHT - 1,
                0,
                0);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 0, 0.5f));
        assertTrue(player.setNoclip(true));
        Vector3f embedded =
                player.body().position(new Vector3f());

        assertFalse(player.setNoclip(false));

        assertTrue(player.isNoclip());
        assertEquals(
                embedded,
                player.body().position(new Vector3f()));
    }

    @Test
    void firstCeilingContactClearsUpwardVelocityImmediately() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        fillBlocks(world, -1, 1, 3, 3, -1, 1);
        PlayerController player = controller(world);
        player.teleport(new Vector3f(0.5f, 1, 0.5f));
        player.fixedUpdate(
                FIXED_STEP, 0, 0, false, false, false);
        player.fixedUpdate(
                FIXED_STEP, 0, 0, true, false, false);

        float previousVelocity =
                player.body().linearVelocity(new Vector3f()).y;
        boolean contacted = false;
        for (int step = 0; step < 30; step++) {
            player.fixedUpdate(
                    FIXED_STEP, 0, 0, false, false, false);
            float velocity =
                    player.body().linearVelocity(new Vector3f()).y;
            if (previousVelocity > 0 && velocity <= 0) {
                assertEquals(0.0f, velocity, 1.0e-4f);
                contacted = true;
                break;
            }
            previousVelocity = velocity;
        }
        assertTrue(contacted);
        assertFalse(player.overlapsSolid());
    }

    private static PlayerController controller(World world) {
        PhysicsBody body =
                new PhysicsBody(
                        PLAYER_COLLIDER,
                        MassProperties.dynamic(1.0f));
        return new PlayerController(
                body,
                new CollisionWorld(
                        world,
                        BlockCollisionShapeResolver
                                .fullCubesForNonAir()),
                GameConfig.Player.MOVEMENT_SPEED,
                GameConfig.Player.NOCLIP_SPEED,
                GameConfig.Player.JUMP_VELOCITY,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
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
        fillBlocks(world, minX, maxX, 0, 0, minZ, maxZ);
    }

    private static void fillBlocks(
            World world,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    setBlock(world, x, y, z);
                }
            }
        }
    }

    private static void setBlock(
            World world, int x, int y, int z) {
        if (world.getBlock(x, y, z) == 0) {
            assertTrue(world.setBlock(x, y, z, (byte) 1));
        }
    }
}
