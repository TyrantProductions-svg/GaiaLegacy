package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.time.FixedStepClock;
import com.overlord.voxel.World;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class PhysicsDeterminismTest {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final float EPSILON = 1.0e-4f;
    private static final Aabb PLAYER_COLLIDER =
            new Aabb(
                    -GameConfig.Player.WIDTH / 2.0f,
                    0.0f,
                    -GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.WIDTH / 2.0f,
                    GameConfig.Player.HEIGHT,
                    GameConfig.Player.WIDTH / 2.0f);

    @ParameterizedTest
    @ValueSource(ints = {10, 60, 144, 240})
    void movementAndJumpMatchSixtyFps(int renderFps) {
        SimulationResult reference = simulate(60, 10.0);
        SimulationResult actual = simulate(renderFps, 10.0);

        assertEquals(600, actual.fixedSteps());
        assertEquals(reference.fixedSteps(), actual.fixedSteps());
        assertEquals(
                reference.horizontalDistance(),
                actual.horizontalDistance(),
                EPSILON);
        assertEquals(
                reference.jumpApex(), actual.jumpApex(), EPSILON);
        assertEquals(
                reference.landingY(), actual.landingY(), EPSILON);
        assertEquals(reference.grounded(), actual.grounded());
        assertTrue(actual.grounded());
    }

    @Test
    void tenthSecondFrameStillSweepsHighSpeedFallToGround() {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerController player =
                controller(
                        world,
                        GameConfig.Player.MOVEMENT_SPEED,
                        GameConfig.Physics.GRAVITY,
                        -500.0f);
        player.teleport(new Vector3f(0.5f, 20.0f, 0.5f));
        player.body()
                .setLinearVelocity(new Vector3f(0.0f, -200.0f, 0.0f));
        FixedStepClock clock =
                new FixedStepClock(FIXED_STEP_SECONDS, 8);

        int steps = clock.advance(0.1);
        for (int step = 0; step < steps; step++) {
            player.fixedUpdate(
                    clock.fixedStepSeconds(),
                    0.0f,
                    0.0f,
                    false,
                    false,
                    false);
        }

        assertEquals(6, steps);
        assertEquals(
                1.0f,
                player.body().position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertTrue(player.isGrounded());
        assertFalse(player.overlapsSolid());
    }

    private static SimulationResult simulate(
            int renderFps, double wallDurationSeconds) {
        World world = new World();
        fillFloor(world, -2, 60, -1, 1);
        PlayerController player =
                controller(
                        world,
                        GameConfig.Player.MOVEMENT_SPEED,
                        GameConfig.Physics.GRAVITY,
                        GameConfig.Physics.TERMINAL_VELOCITY);
        Vector3f start = new Vector3f(0.5f, 1.0f, 0.5f);
        player.teleport(start);
        player.fixedUpdate(
                (float) FIXED_STEP_SECONDS,
                0.0f,
                0.0f,
                false,
                false,
                false);
        assertTrue(player.isGrounded());

        FixedStepClock clock =
                new FixedStepClock(FIXED_STEP_SECONDS, 8);
        double frameSeconds = 1.0 / renderFps;
        double elapsedSeconds = 0.0;
        int fixedSteps = 0;
        boolean jumpEdgePending = true;
        boolean airborneAfterJump = false;
        boolean landedAfterJump = false;
        float jumpApex = start.y;
        float landingY = Float.NaN;

        while (elapsedSeconds < wallDurationSeconds) {
            double nextElapsed =
                    Math.min(
                            wallDurationSeconds,
                            elapsedSeconds + frameSeconds);
            double frameDelta = nextElapsed - elapsedSeconds;
            elapsedSeconds = nextElapsed;

            int frameSteps = clock.advance(frameDelta);
            for (int step = 0; step < frameSteps; step++) {
                boolean jumpPressed =
                        jumpEdgePending && step == 0;
                player.fixedUpdate(
                        clock.fixedStepSeconds(),
                        1.0f,
                        0.0f,
                        jumpPressed,
                        false,
                        false);
                fixedSteps++;

                float currentY =
                        player.body().position(new Vector3f()).y;
                jumpApex = Math.max(jumpApex, currentY);
                if (!player.isGrounded()) {
                    airborneAfterJump = true;
                } else if (airborneAfterJump
                        && !landedAfterJump) {
                    landedAfterJump = true;
                    landingY = currentY;
                }
            }
            if (frameSteps > 0) {
                jumpEdgePending = false;
            }
        }

        assertTrue(airborneAfterJump);
        assertTrue(landedAfterJump);
        Vector3f end = player.body().position(new Vector3f());
        return new SimulationResult(
                fixedSteps,
                end.x - start.x,
                jumpApex,
                landingY,
                player.isGrounded());
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

    private static void fillFloor(
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                assertTrue(world.setBlock(x, 0, z, (byte) 1));
            }
        }
    }

    private record SimulationResult(
            int fixedSteps,
            float horizontalDistance,
            float jumpApex,
            float landingY,
            boolean grounded) {}
}
