package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot.Bounds;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbitCameraTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void framesEveryLiteralCornerOfWideBounds() {
        Bounds bounds = bounds(-50, -1, -2, 50, 1, 2);
        OrbitCamera camera = new OrbitCamera(bounds, 1280, 720);

        assertAllCornersInClipSpace(camera, bounds);
        assertEquals(0.0, camera.target().x, EPSILON);
        assertEquals(0.0, camera.target().y, EPSILON);
        assertEquals(0.0, camera.target().z, EPSILON);
        assertTrue(camera.near() > 0.0);
        assertTrue(camera.far() > camera.near());
    }

    @Test
    void resizeAndFrameKeepTallBoundsInView() {
        Bounds tall = bounds(100, -1000, -20, 102, 1000, 20);
        OrbitCamera camera = new OrbitCamera(tall, 1600, 900);

        camera.resize(320, 1600);
        assertAllCornersInClipSpace(camera, tall);
        camera.frame(tall);
        assertAllCornersInClipSpace(camera, tall);
    }

    @Test
    void framesTinyTranslatedAndUnitBounds() {
        for (Bounds bounds : new Bounds[]{
                bounds(-1.0e-12, -2.0e-12, -3.0e-12, 1.0e-12, 2.0e-12, 3.0e-12),
                bounds(1_000_000, -2_000_000, 3_000_000, 1_000_001, -1_999_998, 3_000_004),
                bounds(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)}) {
            OrbitCamera camera = new OrbitCamera(bounds, 1024, 768);
            assertAllCornersInClipSpace(camera, bounds);
            assertTrue(camera.near() > 0.0 && camera.far() > camera.near());
        }
    }

    @Test
    void orbitWrapsYawClampsPitchAndIgnoresNonFiniteDeltas() {
        OrbitCamera camera = new OrbitCamera(bounds(-1, -1, -1, 1, 1, 1), 800, 600);
        double initialYaw = camera.yaw();
        double initialPitch = camera.pitch();

        camera.orbit(Math.PI * 5.0, 100.0);
        assertTrue(camera.yaw() >= -Math.PI && camera.yaw() < Math.PI);
        assertTrue(camera.pitch() < Math.PI / 2.0);
        assertTrue(camera.pitch() > -Math.PI / 2.0);
        camera.orbit(Double.NaN, Double.POSITIVE_INFINITY);
        assertFalse(Double.isNaN(camera.yaw()));
        assertFalse(Double.isNaN(camera.pitch()));
        assertFalse(Double.isInfinite(camera.yaw()));
        assertFalse(Double.isInfinite(camera.pitch()));
        assertTrue(initialYaw != camera.yaw() || initialPitch != camera.pitch());
    }

    @Test
    void orbitPreservesUserPanAndZoomUntilAnExplicitFrame() {
        OrbitCamera camera = new OrbitCamera(bounds(-4, -2, -1, 4, 2, 1), 800, 600);
        camera.pan(80.0, -50.0, 600);
        camera.zoom(3.0);
        Vector3d pannedTarget = camera.target();
        double zoomedDistance = camera.distance();

        camera.orbit(0.2, -0.1);

        assertEquals(pannedTarget.x, camera.target().x, EPSILON);
        assertEquals(pannedTarget.y, camera.target().y, EPSILON);
        assertEquals(pannedTarget.z, camera.target().z, EPSILON);
        assertEquals(zoomedDistance, camera.distance(), EPSILON);
    }

    @Test
    void pannedThenOrbitedVisibleBoundsRemainInsideDepthPlanes() {
        Bounds cube=bounds(-1,-1,-1,1,1,1);
        OrbitCamera camera=new OrbitCamera(cube,800,600);
        camera.pan(1000,0,600);
        camera.orbit(-Math.PI/2,0);
        assertAllCornersInClipSpace(camera,cube);
    }

    @Test
    void zoomHasPositiveFiniteBoundsAndCannotCrossTarget() {
        OrbitCamera camera = new OrbitCamera(bounds(10, 20, 30, 10, 20, 30), 800, 600);
        double startDistance = camera.distance();

        camera.zoom(1_000_000.0);
        assertTrue(camera.distance() > 0.0);
        assertTrue(Double.isFinite(camera.distance()));
        double minimumDistance = camera.distance();
        camera.zoom(-1_000_000.0);
        assertTrue(camera.distance() > minimumDistance);
        assertTrue(Double.isFinite(camera.distance()));
        camera.zoom(Double.NaN);
        assertTrue(camera.distance() >= minimumDistance);
        assertTrue(startDistance > 0.0);
    }

    @Test
    void panUsesLogicalHeightAndKeepsFiniteDefensiveState() {
        OrbitCamera camera = new OrbitCamera(bounds(-2, -3, -4, 2, 3, 4), 1200, 900);
        Vector3d before = camera.target();

        camera.pan(120.0, -90.0, 900);
        Vector3d moved = camera.target();
        assertTrue(before.distance(moved) > 0.0);
        moved.set(Double.NaN, Double.NaN, Double.NaN);
        assertTrue(Double.isFinite(camera.target().x));
        camera.pan(Double.NaN, 1.0, 900);
        assertTrue(Double.isFinite(camera.target().x));
        assertTrue(Double.isFinite(camera.target().y));
        assertTrue(Double.isFinite(camera.target().z));
    }

    @Test
    void ignoresFiniteGesturesWhoseCalculationWouldOverflow() {
        OrbitCamera camera = new OrbitCamera(bounds(-1, -1, -1, 1, 1, 1), 800, 600);
        camera.zoom(-1_000_000.0);
        Vector3d before = camera.target();

        camera.pan(Double.MAX_VALUE, Double.MAX_VALUE, 1);
        camera.orbit(Double.MAX_VALUE, Double.MAX_VALUE);

        assertEquals(before.x, camera.target().x, EPSILON);
        assertEquals(before.y, camera.target().y, EPSILON);
        assertEquals(before.z, camera.target().z, EPSILON);
        assertTrue(Double.isFinite(camera.yaw()));
        assertTrue(Double.isFinite(camera.pitch()));
    }

    private static Bounds bounds(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return new Bounds(new double[]{minX, minY, minZ}, new double[]{maxX, maxY, maxZ});
    }

    private static void assertAllCornersInClipSpace(OrbitCamera camera, Bounds bounds) {
        Matrix4d clip = camera.projection().mul(camera.view());
        double[] min = bounds.min();
        double[] max = bounds.max();
        for (double x : new double[]{min[0], max[0]}) {
            for (double y : new double[]{min[1], max[1]}) {
                for (double z : new double[]{min[2], max[2]}) {
                    Vector3d projected = new Vector3d(x, y, z).mulProject(clip);
                    assertTrue(projected.x >= -1.0 && projected.x <= 1.0,
                            "x clip for " + projected);
                    assertTrue(projected.y >= -1.0 && projected.y <= 1.0,
                            "y clip for " + projected);
                    assertTrue(projected.z >= -1.0 && projected.z <= 1.0,
                            "z clip for " + projected);
                }
            }
        }
    }
}
