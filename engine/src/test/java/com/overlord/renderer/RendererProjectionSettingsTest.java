package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RendererProjectionSettingsTest {
    @Test
    void fovAcceptsInclusiveBoundsAndRejectsOutOfRangeOrNonFiniteValues() {
        Renderer renderer = new Renderer(
                MainThreadGuard.captureCurrentThread(),
                RenderAssets.missing());

        assertDoesNotThrow(() -> renderer.setFovDegrees(50.0f));
        assertDoesNotThrow(() -> renderer.setFovDegrees(100.0f));

        for (float invalid : new float[] {
            49.99f,
            100.01f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> renderer.setFovDegrees(invalid),
                    () -> "Expected invalid FOV to be rejected: " + invalid);
        }
    }

    @Test
    void projectionChangesFromSeventyToNinetyWithoutChangingCameraForward() {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                960, 540, 1920, 1080, 2.0f, 2.0f);
        Camera camera = new Camera();
        Vector3f forwardBefore = new Vector3f(camera.getForward());

        Matrix4f projection70 = Renderer.projectionFor(surface, 70.0f);
        Matrix4f projection90 = Renderer.projectionFor(surface, 90.0f);

        assertNotEquals(projection70, projection90);
        assertTrue(projection70.m11() > projection90.m11());
        assertEquals(
                Float.floatToIntBits(forwardBefore.x),
                Float.floatToIntBits(camera.getForward().x));
        assertEquals(
                Float.floatToIntBits(forwardBefore.y),
                Float.floatToIntBits(camera.getForward().y));
        assertEquals(
                Float.floatToIntBits(forwardBefore.z),
                Float.floatToIntBits(camera.getForward().z));
    }
}
