package com.overlord.renderer.frustum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.AxisAlignedBounds;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class FrustumTest {
    private static final float TOLERANCE = 0.0001f;

    @Test
    void planeNormalizesFiniteCoefficientsAndComputesSignedDistance() {
        FrustumPlane plane = new FrustumPlane(3.0f, 0.0f, 4.0f, 10.0f);

        assertEquals(0.6f, plane.normalX(), TOLERANCE);
        assertEquals(0.0f, plane.normalY(), TOLERANCE);
        assertEquals(0.8f, plane.normalZ(), TOLERANCE);
        assertEquals(2.0f, plane.distance(), TOLERANCE);
        assertEquals(5.0f, plane.signedDistance(5.0f, 0.0f, 0.0f), TOLERANCE);
        assertTrue(Float.isFinite(plane.normalX()));
        assertTrue(Float.isFinite(plane.normalY()));
        assertTrue(Float.isFinite(plane.normalZ()));
        assertTrue(Float.isFinite(plane.distance()));
    }

    @Test
    void planeRejectsZeroAndNonFiniteCoefficientsWithFieldSpecificMessages() {
        assertInvalidPlane("normal", 0.0f, 0.0f, 0.0f, 1.0f);
        assertInvalidPlane("normalX", Float.NaN, 1.0f, 0.0f, 1.0f);
        assertInvalidPlane("normalY", 1.0f, Float.POSITIVE_INFINITY, 0.0f, 1.0f);
        assertInvalidPlane("normalZ", 1.0f, 0.0f, Float.NEGATIVE_INFINITY, 1.0f);
        assertInvalidPlane("distance", 1.0f, 0.0f, 0.0f, Float.NaN);
    }

    @Test
    void perspectiveFrustumAcceptsInsideIntersectingAndConservativeBoundaryBounds() {
        Frustum frustum = standardFrustum(new Matrix4f());

        assertTrue(frustum.intersects(bounds(-0.5f, -0.5f, -2.0f, 0.5f, 0.5f, -1.5f)));
        assertTrue(frustum.intersects(bounds(-3.0f, -0.5f, -2.0f, 0.0f, 0.5f, -1.5f)));
        assertTrue(frustum.intersects(bounds(-0.25f, -0.25f, -1.0f, 0.25f, 0.25f, -1.0f)));
        assertTrue(frustum.intersects(bounds(-0.25f, -0.25f, -0.995f, 0.25f, 0.25f, -0.995f)));
        assertFalse(frustum.intersects(bounds(-0.25f, -0.25f, -0.98f, 0.25f, 0.25f, -0.98f)));
    }

    @Test
    void perspectiveFrustumRejectsBoundsOutsideEveryPlane() {
        Frustum frustum = standardFrustum(new Matrix4f());

        for (AxisAlignedBounds outside :
                List.of(
                        bounds(-4.0f, -0.25f, -2.0f, -3.0f, 0.25f, -1.5f),
                        bounds(3.0f, -0.25f, -2.0f, 4.0f, 0.25f, -1.5f),
                        bounds(-0.25f, -4.0f, -2.0f, 0.25f, -3.0f, -1.5f),
                        bounds(-0.25f, 3.0f, -2.0f, 0.25f, 4.0f, -1.5f),
                        bounds(-0.25f, -0.25f, 0.0f, 0.25f, 0.25f, 0.5f),
                        bounds(-0.25f, -0.25f, -12.0f, 0.25f, 0.25f, -11.0f))) {
            assertFalse(frustum.intersects(outside), () -> "Expected outside: " + outside);
        }
    }

    @Test
    void rotatingTheViewExchangesFrontAndBackVisibility() {
        AxisAlignedBounds front = bounds(-0.5f, -0.5f, -4.0f, 0.5f, 0.5f, -3.0f);
        AxisAlignedBounds back = bounds(-0.5f, -0.5f, 3.0f, 0.5f, 0.5f, 4.0f);

        Frustum forward = standardFrustum(new Matrix4f());
        Frustum backward = standardFrustum(new Matrix4f().rotateY((float) Math.PI));

        assertTrue(forward.intersects(front));
        assertFalse(forward.intersects(back));
        assertFalse(backward.intersects(front));
        assertTrue(backward.intersects(back));
    }

    private static Frustum standardFrustum(Matrix4f view) {
        Matrix4f projection =
                new Matrix4f()
                        .perspective(
                                (float) Math.toRadians(90.0),
                                1.0f,
                                1.0f,
                                10.0f);
        return Frustum.from(projection, view);
    }

    private static AxisAlignedBounds bounds(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        return new AxisAlignedBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void assertInvalidPlane(
            String expectedField,
            float normalX,
            float normalY,
            float normalZ,
            float distance) {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FrustumPlane(normalX, normalY, normalZ, distance));
        assertTrue(failure.getMessage().contains(expectedField), failure::getMessage);
    }
}
