package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
