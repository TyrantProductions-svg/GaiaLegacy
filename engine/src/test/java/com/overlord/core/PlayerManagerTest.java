package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Camera;
import com.overlord.voxel.World;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlayerManagerTest {
    private static final float FIXED_DELTA = 1.0f / 60.0f;

    @Test
    void appliesMouseLookUsingCameraSensitivity() {
        Camera camera = new Camera();
        PlayerManager player = new PlayerManager(camera, new PhysicsManager(camera, new World()));

        player.applyLook(new MouseDelta(10.0, 0.0));

        assertEquals(-89.0f, camera.getYaw(), 1.0e-6f);
    }

    @Test
    void movesForwardBySpeedTimesFixedDelta() {
        Camera camera = new Camera();
        camera.setPosition(new org.joml.Vector3f(0.0f, 10.0f, 3.0f));
        PlayerManager player = new PlayerManager(camera, new PhysicsManager(camera, new World()));
        InputSnapshot input = new InputSnapshot(Set.of(GLFW_KEY_W), Set.of());

        player.fixedUpdate(FIXED_DELTA, input);

        assertEquals(
                3.0f - GameConfig.Player.MOVEMENT_SPEED * FIXED_DELTA,
                camera.getPosition().z,
                1.0e-5f);
    }

    @Test
    void forwardsOnlyPressedJumpEdgesToPhysics() {
        Camera camera = new Camera();
        RecordingPhysicsManager physics = new RecordingPhysicsManager(camera);
        PlayerManager player = new PlayerManager(camera, physics);

        player.fixedUpdate(
                FIXED_DELTA, new InputSnapshot(Set.of(GLFW_KEY_SPACE), Set.of(GLFW_KEY_SPACE)));
        player.fixedUpdate(FIXED_DELTA, new InputSnapshot(Set.of(GLFW_KEY_SPACE), Set.of()));

        assertEquals(1, physics.jumpCount);
    }

    private static final class RecordingPhysicsManager extends PhysicsManager {
        private int jumpCount;

        private RecordingPhysicsManager(Camera camera) {
            super(camera, new World());
        }

        @Override
        public void update(float deltaTime, float moveX, float moveZ) {}

        @Override
        public void jump(float jumpVelocity) {
            jumpCount++;
        }
    }
}
