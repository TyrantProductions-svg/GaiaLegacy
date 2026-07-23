package com.overlord.core;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Camera;
import java.util.Objects;
import org.joml.Vector3f;

public class PlayerManager {
    private final Camera camera;
    private final PhysicsManager physicsManager;
    private final Vector3f forward = new Vector3f();
    private final Vector3f right = new Vector3f();
    private final Vector3f horizontalForward = new Vector3f();
    private final Vector3f horizontalRight = new Vector3f();
    private final Vector3f movement = new Vector3f();

    public PlayerManager(Camera camera, PhysicsManager physicsManager) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.physicsManager = Objects.requireNonNull(physicsManager, "physicsManager");
    }

    public void applyLook(MouseDelta delta) {
        Objects.requireNonNull(delta, "delta");
        camera.processMouseMovement((float) delta.x(), (float) delta.y());
    }

    public void fixedUpdate(float fixedDeltaSeconds, InputSnapshot input) {
        Objects.requireNonNull(input, "input");
        if (fixedDeltaSeconds <= 0.0f) {
            return;
        }

        camera.getForward(forward);
        camera.getRight(right);

        horizontalForward.set(forward.x, 0.0f, forward.z);
        if (horizontalForward.lengthSquared() > 0.0f) {
            horizontalForward.normalize();
        }
        horizontalRight.set(right.x, 0.0f, right.z);
        if (horizontalRight.lengthSquared() > 0.0f) {
            horizontalRight.normalize();
        }

        movement.zero();
        if (input.isKeyDown(GameConfig.Input.KEY_FORWARD)) {
            movement.add(horizontalForward);
        }
        if (input.isKeyDown(GameConfig.Input.KEY_BACKWARD)) {
            movement.sub(horizontalForward);
        }
        if (input.isKeyDown(GameConfig.Input.KEY_LEFT)) {
            movement.sub(horizontalRight);
        }
        if (input.isKeyDown(GameConfig.Input.KEY_RIGHT)) {
            movement.add(horizontalRight);
        }

        if (movement.lengthSquared() > 0.0f) {
            movement.normalize().mul(GameConfig.Player.MOVEMENT_SPEED * fixedDeltaSeconds);
        }

        physicsManager.update(fixedDeltaSeconds, movement.x, movement.z);
        if (input.isKeyPressed(GameConfig.Input.KEY_JUMP)) {
            physicsManager.jump(GameConfig.Player.JUMP_VELOCITY);
        }
    }
}
