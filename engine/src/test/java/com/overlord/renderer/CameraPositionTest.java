package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rebaseMovesOnlyLocalPositionAndRejectsNonFiniteOffsetsWithoutMutation() {
        Camera camera = new Camera();
        camera.setPosition(new Vector3f(20.0f, 7.0f, -4.0f));
        camera.setYaw(-42.0f);
        camera.setPitch(18.0f);
        Vector3f forward = camera.getForward(new Vector3f());
        Vector3f right = camera.getRight(new Vector3f());

        camera.rebase(new Vector3f(-16.0f, 0.0f, 32.0f));

        assertEquals(new Vector3f(4.0f, 7.0f, 28.0f), camera.getPosition());
        assertEquals(-42.0f, camera.getYaw());
        assertEquals(18.0f, camera.getPitch());
        assertEquals(forward, camera.getForward(new Vector3f()));
        assertEquals(right, camera.getRight(new Vector3f()));
        assertThrows(
                IllegalArgumentException.class,
                () -> camera.rebase(new Vector3f(Float.NaN, 0.0f, 0.0f)));
        assertEquals(new Vector3f(4.0f, 7.0f, 28.0f), camera.getPosition());
    }

    @Test
    void preparedOriginRebasePrecomputesWithoutMutationThenPublishesPositionOnly() {
        Camera camera = new Camera();
        camera.setPosition(new Vector3f(20, 7, -4));
        camera.setYaw(-42);
        camera.setPitch(18);

        Camera.PreparedOriginRebase prepared =
                camera.prepareOriginRebase(new Vector3f(-16, 0, 32));

        assertEquals(new Vector3f(20, 7, -4), camera.getPosition());
        prepared.commit();
        assertEquals(new Vector3f(4, 7, 28), camera.getPosition());
        assertEquals(-42, camera.getYaw());
        assertEquals(18, camera.getPitch());
    }
}
