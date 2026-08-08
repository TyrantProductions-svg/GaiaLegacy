package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.config.GameConfig;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CameraPositionTest {
    @Test
    void setPositionCopiesTheSuppliedVector() {
        Camera camera = new Camera();
        Vector3f supplied = new Vector3f(1.0f, 2.0f, 3.0f);

        camera.setPosition(supplied);
        supplied.zero();

        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), camera.getPosition());
    }

    @Test
    void defaultMouseMovementUsesTheReleaseCandidateSensitivity() {
        assertEquals(0.1f, GameConfig.Input.MOUSE_SENSITIVITY, 0.0f);
        Camera camera = new Camera();

        camera.processMouseMovement(10.0f, 0.0f);

        assertEquals(-89.0f, camera.getYaw(), 1.0e-6f);
    }
}
