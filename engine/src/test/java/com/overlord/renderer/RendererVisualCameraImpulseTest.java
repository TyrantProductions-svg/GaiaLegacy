package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.feedback.CameraImpulseVisual;
import com.overlord.renderer.feedback.FirstPersonMovementVisual;
import com.overlord.renderer.shader.WorldShaderUniforms;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RendererVisualCameraImpulseTest {
    @Test
    void visualImpulseTransformsOnlyReturnedViewCopy() {
        Matrix4f canonical = new Matrix4f().lookAt(
                1, 2, 3, 1, 2, 2, 0, 1, 0);
        Matrix4f before = new Matrix4f(canonical);

        Matrix4f visual = Renderer.applyVisualCameraImpulse(
                canonical, new CameraImpulseVisual(0.5f, -0.1f, -0.006f));

        assertEquals(before, canonical);
        assertNotEquals(canonical, visual);
        assertEquals(
                canonical,
                Renderer.applyVisualCameraImpulse(
                        canonical, CameraImpulseVisual.identity()));
    }

    @Test
    void movementThenActionComposeWithoutMutatingCanonicalView() {
        Matrix4f canonical = new Matrix4f().lookAt(
                1, 2, 3, 1, 2, 2, 0, 1, 0);
        Matrix4f before = new Matrix4f(canonical);
        FirstPersonMovementVisual movement =
                new FirstPersonMovementVisual(0.012f, -0.025f, 0.18f);
        CameraImpulseVisual action = new CameraImpulseVisual(0.5f, -0.1f, -0.006f);

        Matrix4f composed = Renderer.applyFirstPersonPresentation(
                canonical, movement, action);
        Matrix4f expected = new Matrix4f(canonical)
                .translateLocal(-0.012f, 0.025f, 0.0f)
                .rotateLocalZ((float) Math.toRadians(-0.18f))
                .rotateLocalX((float) Math.toRadians(0.5f))
                .rotateLocalY((float) Math.toRadians(-0.1f))
                .translateLocal(0.0f, -0.006f, 0.0f);

        assertEquals(before, canonical);
        assertEquals(expected, composed);
        assertNotEquals(
                Renderer.applyFirstPersonPresentation(
                        canonical, movement, CameraImpulseVisual.identity()),
                composed);
        assertNotEquals(
                Renderer.applyFirstPersonPresentation(
                        canonical, FirstPersonMovementVisual.identity(), action),
                composed);
    }

    @ParameterizedTest
    @CsvSource({
        "0,0", "90,0", "180,0", "270,0",
        "0,30", "90,30", "180,30", "270,30",
        "0,-30", "90,-30", "180,-30", "270,-30"
    })
    void lateralAndVerticalBobRemainViewLocalAtEveryYawAndPitch(
            float yaw, float pitch) {
        Camera camera = new Camera();
        Vector3f cameraPosition = new Vector3f(7.25f, 41.5f, -13.75f);
        camera.setPosition(cameraPosition);
        camera.setYaw(yaw);
        camera.setPitch(pitch);
        Matrix4f canonical = camera.getViewMatrix();

        Matrix4f visual = Renderer.applyFirstPersonPresentation(
                canonical,
                new FirstPersonMovementVisual(0.25f, -0.125f, 0.0f),
                CameraImpulseVisual.identity());
        Vector3f transformedCamera = visual.transformPosition(
                new Vector3f(cameraPosition));

        assertEquals(-0.25f, transformedCamera.x(), 0.0001f);
        assertEquals(0.125f, transformedCamera.y(), 0.0001f);
        assertEquals(0.0f, transformedCamera.z(), 0.0001f);
        assertEquals(new Matrix4f(canonical), canonical);
    }

    @ParameterizedTest
    @CsvSource({"0", "90", "180", "270"})
    void rollAndActionImpulseUseCameraLocalAxes(float yaw) {
        Camera camera = new Camera();
        camera.setPosition(new Vector3f(-9.5f, 22.0f, 17.0f));
        camera.setYaw(yaw);
        camera.setPitch(0.0f);
        Matrix4f canonical = camera.getViewMatrix();
        FirstPersonMovementVisual movement =
                new FirstPersonMovementVisual(0.012f, -0.025f, 0.18f);
        CameraImpulseVisual action =
                new CameraImpulseVisual(0.5f, -0.1f, -0.006f);

        Matrix4f actual = Renderer.applyFirstPersonPresentation(
                canonical, movement, action);
        Matrix4f expected = new Matrix4f(canonical)
                .translateLocal(-0.012f, 0.025f, 0.0f)
                .rotateLocalZ((float) Math.toRadians(-0.18f))
                .rotateLocalX((float) Math.toRadians(0.5f))
                .rotateLocalY((float) Math.toRadians(-0.1f))
                .translateLocal(0.0f, -0.006f, 0.0f);

        assertEquals(expected, actual);
    }

    @Test
    void worldShaderUsesBoundedRenderOnlyVoxelExclusionAtGlsl410() throws Exception {
        String vertex = resource("assets/overlord/shaders/world.vert");
        String fragment = resource("assets/overlord/shaders/world.frag");

        assertTrue(vertex.startsWith("#version 410 core"));
        assertTrue(fragment.startsWith("#version 410 core"));
        assertTrue(vertex.contains("out vec3 fragmentWorldPosition"));
        assertTrue(fragment.contains("uniform int excludedBlockCount"));
        assertTrue(fragment.contains(
                "uniform vec3 excludedBlockCells["
                        + WorldShaderUniforms.MAX_EXCLUDED_BLOCK_CELLS
                        + "]"));
        assertTrue(fragment.contains("index < excludedBlockCount"));
        assertTrue(fragment.contains("discard"));
    }

    private static String resource(String path) throws Exception {
        try (var input = RendererVisualCameraImpulseTest.class
                .getClassLoader().getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
