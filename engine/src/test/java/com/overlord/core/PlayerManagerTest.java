package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShape;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.World;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PlayerManagerTest {
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
    void appliesMouseLookUsingCameraSensitivity() {
        PlayerFixture fixture = playerFixture(new World());

        fixture.player().applyLook(new MouseDelta(10.0, 0.0));

        assertEquals(
                -89.0f, fixture.camera().getYaw(), 1.0e-6f);
    }

    @Test
    void derivesNormalizedWorldSpaceMovementFromCameraYaw() {
        PlayerFixture fixture =
                playerFixture(new World(), 0.0f, 0.0f, 0.0f);
        fixture.camera().setYaw(0.0f);
        fixture.controller().teleport(new Vector3f());

        fixture.player()
                .fixedUpdate(
                        1.0f,
                        new InputSnapshot(
                                Set.of(GLFW_KEY_W, GLFW_KEY_D),
                                Set.of()));

        float expectedAxis =
                GameConfig.Player.MOVEMENT_SPEED
                        / (float) Math.sqrt(2.0);
        Vector3f position =
                fixture.controller().body().position(new Vector3f());
        assertEquals(
                expectedAxis, position.x, EPSILON);
        assertEquals(0.0f, position.y, EPSILON);
        assertEquals(
                expectedAxis, position.z, EPSILON);
    }

    @Test
    void secondSpaceWithinFifteenFixedStepsTogglesNoclipOnce() {
        PlayerFixture fixture = playerFixtureOnGround();

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertTrue(
                fixture.controller()
                                .body()
                                .linearVelocity(new Vector3f())
                                .y
                        > 0.0f);
        repeat(
                14,
                () ->
                        fixture.player()
                                .fixedUpdate(
                                        FIXED_STEP, noInput()));

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertTrue(fixture.controller().isNoclip());
        assertEquals(
                0.0f,
                fixture.controller()
                        .body()
                        .linearVelocity(new Vector3f())
                        .y,
                EPSILON);
    }

    @Test
    void pressAfterFifteenElapsedStepsStartsANewJumpWindow() {
        PlayerFixture fixture =
                playerFixtureOnGround(1.0f);
        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());
        repeat(
                15,
                () ->
                        fixture.player()
                                .fixedUpdate(
                                        FIXED_STEP, noInput()));

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertFalse(fixture.controller().isNoclip());
        assertTrue(
                fixture.controller()
                                .body()
                                .linearVelocity(new Vector3f())
                                .y
                        > 0.0f);

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertTrue(fixture.controller().isNoclip());
    }

    @Test
    void catchUpUsesOnePressedEdgeAcrossFullAndHeldOnlySnapshots() {
        PlayerFixture fixture = playerFixtureOnGround();
        InputSnapshot fullSnapshot = spacePressed();

        fixture.player()
                .fixedUpdate(FIXED_STEP, fullSnapshot);
        repeat(
                5,
                () ->
                        fixture.player()
                                .fixedUpdate(
                                        FIXED_STEP,
                                        fullSnapshot.heldOnly()));

        assertFalse(fixture.controller().isNoclip());
    }

    @Test
    void failedNoclipExitConsumesTheSecondPress() {
        World world = new World();
        PlayerFixture fixture =
                playerFixture(
                        world,
                        GameConfig.Player.JUMP_VELOCITY,
                        GameConfig.Physics.GRAVITY,
                        GameConfig.Physics.TERMINAL_VELOCITY,
                        blockId -> BlockCollisionShape.fullCube());
        fixture.controller().teleport(new Vector3f(0.5f, 0, 0.5f));
        assertTrue(fixture.controller().setNoclip(true));

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());
        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertTrue(fixture.controller().isNoclip());
        assertEquals(
                0.0f,
                fixture.controller()
                        .body()
                        .linearVelocity(new Vector3f())
                        .y,
                EPSILON);

        fixture.player().fixedUpdate(FIXED_STEP, spacePressed());

        assertTrue(fixture.controller().isNoclip());
        assertTrue(
                fixture.controller()
                                .body()
                                .linearVelocity(new Vector3f())
                                .y
                        > 0.0f);
    }

    @Test
    void heldSpaceAndShiftControlNoclipVerticalIntent() {
        PlayerFixture fixture =
                playerFixture(new World(), 0.0f, 0.0f, 0.0f);
        fixture.controller().teleport(new Vector3f());
        assertTrue(fixture.controller().setNoclip(true));

        fixture.player()
                .fixedUpdate(
                        1.0f,
                        new InputSnapshot(
                                Set.of(GLFW_KEY_SPACE),
                                Set.of()));
        assertEquals(
                GameConfig.Player.NOCLIP_SPEED,
                fixture.controller()
                        .body()
                        .position(new Vector3f())
                        .y,
                EPSILON);

        fixture.player()
                .fixedUpdate(
                        1.0f,
                        new InputSnapshot(
                                Set.of(GLFW_KEY_LEFT_SHIFT),
                                Set.of()));
        assertEquals(
                0.0f,
                fixture.controller()
                        .body()
                        .position(new Vector3f())
                        .y,
                EPSILON);
    }

    private static PlayerFixture playerFixtureOnGround() {
        return playerFixtureOnGround(
                GameConfig.Player.JUMP_VELOCITY);
    }

    private static PlayerFixture playerFixtureOnGround(
            float jumpVelocity) {
        World world = new World();
        fillFloor(world, -1, 1, -1, 1);
        PlayerFixture fixture =
                playerFixture(
                        world,
                        jumpVelocity,
                        GameConfig.Physics.GRAVITY,
                        GameConfig.Physics.TERMINAL_VELOCITY);
        fixture.controller().teleport(
                new Vector3f(0.5f, 1.0f, 0.5f));
        fixture.controller()
                .fixedUpdate(
                        FIXED_STEP,
                        0,
                        0,
                        false,
                        false,
                        false);
        assertTrue(fixture.controller().isGrounded());
        return fixture;
    }

    private static PlayerFixture playerFixture(World world) {
        return playerFixture(
                world,
                GameConfig.Player.JUMP_VELOCITY,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
    }

    private static PlayerFixture playerFixture(
            World world,
            float jumpVelocity,
            float gravity,
            float terminalVelocity) {
        return playerFixture(
                world,
                jumpVelocity,
                gravity,
                terminalVelocity,
                BlockCollisionShapeResolver
                        .fullCubesForNonAir());
    }

    private static PlayerFixture playerFixture(
            World world,
            float jumpVelocity,
            float gravity,
            float terminalVelocity,
            BlockCollisionShapeResolver shapeResolver) {
        Camera camera = new Camera();
        PhysicsBody body =
                new PhysicsBody(
                        PLAYER_COLLIDER,
                        MassProperties.dynamic(1.0f));
        PlayerController controller =
                new PlayerController(
                        body,
                        new CollisionWorld(world, shapeResolver),
                        GameConfig.Player.MOVEMENT_SPEED,
                        GameConfig.Player.NOCLIP_SPEED,
                        jumpVelocity,
                        gravity,
                        terminalVelocity);
        return new PlayerFixture(
                camera,
                controller,
                new PlayerManager(camera, controller));
    }

    private static InputSnapshot spacePressed() {
        return new InputSnapshot(
                Set.of(GLFW_KEY_SPACE),
                Set.of(GLFW_KEY_SPACE));
    }

    private static InputSnapshot noInput() {
        return new InputSnapshot(Set.of(), Set.of());
    }

    private static void repeat(int count, Runnable action) {
        for (int iteration = 0;
                iteration < count;
                iteration++) {
            action.run();
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
                assertTrue(world.setBlock(x, 0, z, (byte) 1));
            }
        }
    }

    private record PlayerFixture(
            Camera camera,
            PlayerController controller,
            PlayerManager player) {}
}
