package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot.Bounds;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Deterministic, UI-agnostic orbit camera for an immutable validated model. */
public final class OrbitCamera {
    private static final double DEFAULT_YAW = 0.6;
    private static final double DEFAULT_PITCH = 0.35;
    private static final double PITCH_LIMIT = Math.PI / 2.0 - 0.001;
    private static final double FIELD_OF_VIEW = Math.toRadians(45.0);
    private static final double FIT_MARGIN = 1.08;
    private static final double ZOOM_PER_STEP = 0.12;

    private Bounds framedBounds;
    private final Vector3d target = new Vector3d();
    private int width;
    private int height;
    private double yaw;
    private double pitch;
    private double distance;
    private double near;
    private double far;
    private double minDistance;
    private double maxDistance;

    public OrbitCamera(Bounds bounds, int width, int height) {
        resizeDimensions(width, height);
        frame(bounds);
    }

    /** Resets orientation and fits the provided immutable geometry bounds. */
    public void frame(Bounds bounds) {
        framedBounds = requireBounds(bounds);
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        setTargetToCenter(framedBounds);
        updateDistanceLimits();
        distance = fitDistance();
        updateClipPlanes();
    }

    public void frameCurrent() {
        if (framedBounds == null) throw new IllegalStateException("camera has no framed bounds");
        frame(framedBounds);
    }

    /** Re-fits the current framed bounds when the viewport aspect changes. */
    public void resize(int width, int height) {
        resizeDimensions(width, height);
        if (framedBounds != null) {
            setTargetToCenter(framedBounds);
            updateDistanceLimits();
            distance = fitDistance();
            updateClipPlanes();
        }
    }

    public void orbit(double deltaYawRadians, double deltaPitchRadians) {
        if (!Double.isFinite(deltaYawRadians) || !Double.isFinite(deltaPitchRadians)) {
            return;
        }
        yaw = wrapRadians(yaw + deltaYawRadians);
        pitch = clamp(pitch + deltaPitchRadians, -PITCH_LIMIT, PITCH_LIMIT);
        updateClipPlanes();
    }

    public void pan(double logicalDx, double logicalDy, int logicalHeight) {
        if (!Double.isFinite(logicalDx) || !Double.isFinite(logicalDy) || logicalHeight <= 0) {
            return;
        }
        Vector3d offset = cameraOffset();
        Vector3d forward = offset.negate(new Vector3d()).normalize();
        Vector3d right = forward.cross(0.0, 1.0, 0.0, new Vector3d()).normalize();
        Vector3d up = right.cross(forward, new Vector3d()).normalize();
        double unitsPerLogicalPixel = 2.0 * distance * Math.tan(FIELD_OF_VIEW / 2.0) / logicalHeight;
        Vector3d candidate = new Vector3d(target)
                .fma(-logicalDx * unitsPerLogicalPixel, right)
                .fma(logicalDy * unitsPerLogicalPixel, up);
        if (!Double.isFinite(candidate.x) || !Double.isFinite(candidate.y)
                || !Double.isFinite(candidate.z)) {
            return;
        }
        target.set(candidate);
        updateClipPlanes();
    }

    public void zoom(double wheelSteps) {
        if (!Double.isFinite(wheelSteps)) {
            return;
        }
        double factor = Math.exp(clamp(-wheelSteps * ZOOM_PER_STEP, -700.0, 700.0));
        distance = clamp(distance * factor, minDistance, maxDistance);
        updateClipPlanes();
    }

    public Matrix4d view() {
        Vector3d eye = target.add(cameraOffset(), new Vector3d());
        return new Matrix4d().lookAt(eye, target, new Vector3d(0.0, 1.0, 0.0));
    }

    public Matrix4d projection() {
        return new Matrix4d().perspective(FIELD_OF_VIEW, (double) width / height, near, far);
    }

    public Vector3d target() { return new Vector3d(target); }
    public double yaw() { return yaw; }
    public double pitch() { return pitch; }
    public double distance() { return distance; }
    public double near() { return near; }
    public double far() { return far; }

    private void resizeDimensions(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    private static Bounds requireBounds(Bounds bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        double[] min = bounds.min();
        double[] max = bounds.max();
        if (min.length < 3 || max.length < 3) {
            throw new IllegalArgumentException("bounds require three coordinates");
        }
        for (int index = 0; index < 3; index++) {
            if (!Double.isFinite(min[index]) || !Double.isFinite(max[index])) {
                throw new IllegalArgumentException("bounds coordinates must be finite");
            }
            if (min[index] > max[index]) {
                throw new IllegalArgumentException("bounds minimum exceeds maximum");
            }
        }
        return new Bounds(min, max);
    }

    private void setTargetToCenter(Bounds bounds) {
        double[] min = bounds.min();
        double[] max = bounds.max();
        target.set(min[0] + (max[0] - min[0]) * 0.5,
                min[1] + (max[1] - min[1]) * 0.5,
                min[2] + (max[2] - min[2]) * 0.5);
    }

    private void updateDistanceLimits() {
        double[] dimensions = framedBounds.dimensions();
        double scale = Math.max(1.0e-6, Math.max(dimensions[0], Math.max(dimensions[1], dimensions[2])));
        minDistance = scale * 1.0e-4;
        maxDistance = scale * 1.0e6;
    }

    private double fitDistance() {
        Vector3d offset = cameraOffset(1.0);
        Vector3d forward = offset.negate(new Vector3d()).normalize();
        Vector3d right = forward.cross(0.0, 1.0, 0.0, new Vector3d()).normalize();
        Vector3d up = right.cross(forward, new Vector3d()).normalize();
        double tangentY = Math.tan(FIELD_OF_VIEW / 2.0);
        double tangentX = tangentY * width / height;
        double required = minDistance;
        double[] min = framedBounds.min();
        double[] max = framedBounds.max();
        for (double x : new double[]{min[0], max[0]}) {
            for (double y : new double[]{min[1], max[1]}) {
                for (double z : new double[]{min[2], max[2]}) {
                    Vector3d relative = new Vector3d(x, y, z).sub(target);
                    double depth = forward.dot(relative);
                    required = Math.max(required, depth + Math.abs(right.dot(relative)) / tangentX);
                    required = Math.max(required, depth + Math.abs(up.dot(relative)) / tangentY);
                }
            }
        }
        return clamp(required * FIT_MARGIN, minDistance, maxDistance);
    }

    private void updateClipPlanes() {
        double radius = framedRadius();
        double[] minimum=framedBounds.min(), maximum=framedBounds.max();
        Vector3d center=new Vector3d(minimum[0]*0.5+maximum[0]*0.5,
                minimum[1]*0.5+maximum[1]*0.5,minimum[2]*0.5+maximum[2]*0.5);
        double centerDepth=distance+cameraOffset(1.0).negate().dot(center.sub(target));
        double closest = Math.max(minDistance, centerDepth - radius * 1.1);
        near = Math.max(minDistance * 0.1, closest * 0.25);
        far = Math.max(near * 2.0, centerDepth + radius * 1.5);
    }

    private double framedRadius() {
        if (framedBounds == null) {
            return 1.0;
        }
        double[] dimensions = framedBounds.dimensions();
        return Math.max(minDistance, 0.5 * Math.sqrt(dimensions[0] * dimensions[0]
                + dimensions[1] * dimensions[1] + dimensions[2] * dimensions[2]));
    }

    private Vector3d cameraOffset() { return cameraOffset(distance); }

    private Vector3d cameraOffset(double length) {
        double horizontal = Math.cos(pitch) * length;
        return new Vector3d(Math.sin(yaw) * horizontal, Math.sin(pitch) * length,
                Math.cos(yaw) * horizontal);
    }

    private static double wrapRadians(double value) {
        double wrapped = value % (Math.PI * 2.0);
        return wrapped >= Math.PI ? wrapped - Math.PI * 2.0
                : wrapped < -Math.PI ? wrapped + Math.PI * 2.0 : wrapped;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
