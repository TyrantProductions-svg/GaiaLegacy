package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot.Bounds;
import java.util.List;
import java.util.Optional;

/** Finite diagnostic line geometry derived only from validated model bounds. */
public final class ViewerGeometry {
    public static final int MAX_GRID_VERTICES = 84;
    private static final float[] GRID_COLOR = {0.22f, 0.27f, 0.31f};
    private static final float[] BOUNDS_COLOR = {0.95f, 0.72f, 0.18f};

    public record Lines(float[] positions, double[] worldTransform, float[] color) {
        public Lines {
            positions = positions.clone();
            worldTransform = worldTransform.clone();
            color = color.clone();
            if (positions.length % 6 != 0 || worldTransform.length != 16 || color.length != 3) {
                throw new IllegalArgumentException("invalid line batch");
            }
            for (float value : positions) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("line positions must be finite");
                }
            }
            for (double value : worldTransform) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("line transform must be finite");
                }
            }
            for (float value : color) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("line color must be finite");
                }
            }
        }
        @Override public float[] positions() { return positions.clone(); }
        @Override public double[] worldTransform() { return worldTransform.clone(); }
        @Override public float[] color() { return color.clone(); }
        public int vertexCount() { return positions.length / 3; }
    }

    private ViewerGeometry() { }

    public static Lines grid(Bounds bounds) {
        CenteredBounds centered = centered(bounds);
        double extent = Math.max(1.0, Math.max(centered.halfX, centered.halfZ) * 1.5);
        float packedExtent = ViewerCpuModel.gpuFloat(extent);
        int subdivisions = 10;
        float[] positions = new float[(subdivisions + 1) * 12];
        int cursor = 0;
        for (int index = 0; index <= subdivisions; index++) {
            double offsetValue = extent * (2.0 * index / subdivisions - 1.0);
            float offset = ViewerCpuModel.gpuFloat(offsetValue);
            cursor = line(positions, cursor, -packedExtent, 0, offset, packedExtent, 0, offset);
            cursor = line(positions, cursor, offset, 0, -packedExtent, offset, 0, packedExtent);
        }
        return new Lines(positions, translation(centered.x, 0.0, centered.z), GRID_COLOR);
    }

    /** Optional diagnostic overlay: an unsafe grid never invalidates the validated model. */
    public static Optional<Lines> gridIfSafe(Bounds bounds) {
        try {
            return Optional.of(grid(bounds));
        } catch (IllegalArgumentException unsafePresentationRange) {
            return Optional.empty();
        }
    }

    public static List<Lines> axes(double length) {
        float packed = ViewerCpuModel.gpuFloat(length);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("axis length must be positive");
        }
        double[] identity = translation(0, 0, 0);
        return List.of(
                new Lines(new float[]{0, 0, 0, packed, 0, 0}, identity,
                        new float[]{1.0f, 0.18f, 0.18f}),
                new Lines(new float[]{0, 0, 0, 0, packed, 0}, identity,
                        new float[]{0.18f, 1.0f, 0.18f}),
                new Lines(new float[]{0, 0, 0, 0, 0, packed}, identity,
                        new float[]{0.25f, 0.55f, 1.0f}));
    }

    public static Lines bounds(Bounds bounds) {
        CenteredBounds centered = centered(bounds);
        float x = ViewerCpuModel.gpuFloat(centered.halfX);
        float y = ViewerCpuModel.gpuFloat(centered.halfY);
        float z = ViewerCpuModel.gpuFloat(centered.halfZ);
        float[][] corners = {
            {-x, -y, -z}, {x, -y, -z}, {x, y, -z}, {-x, y, -z},
            {-x, -y, z}, {x, -y, z}, {x, y, z}, {-x, y, z}
        };
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        float[] positions = new float[edges.length * 6];
        int cursor = 0;
        for (int[] edge : edges) {
            float[] first = corners[edge[0]];
            float[] second = corners[edge[1]];
            cursor = line(positions, cursor,
                    first[0], first[1], first[2], second[0], second[1], second[2]);
        }
        return new Lines(positions, translation(centered.x, centered.y, centered.z), BOUNDS_COLOR);
    }

    private static CenteredBounds centered(Bounds bounds) {
        if (bounds == null) throw new IllegalArgumentException("bounds must not be null");
        double[] min = bounds.min();
        double[] max = bounds.max();
        double dx = max[0] - min[0];
        double dy = max[1] - min[1];
        double dz = max[2] - min[2];
        double x = min[0] + dx * 0.5;
        double y = min[1] + dy * 0.5;
        double z = min[2] + dz * 0.5;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || dx < 0 || dy < 0 || dz < 0) {
            throw new IllegalArgumentException("bounds must be finite and ordered");
        }
        return new CenteredBounds(x, y, z, dx * 0.5, dy * 0.5, dz * 0.5);
    }

    private static int line(float[] target, int cursor,
            float ax, float ay, float az, float bx, float by, float bz) {
        target[cursor++] = ax; target[cursor++] = ay; target[cursor++] = az;
        target[cursor++] = bx; target[cursor++] = by; target[cursor++] = bz;
        return cursor;
    }

    private static double[] translation(double x, double y, double z) {
        return new double[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            x, y, z, 1
        };
    }

    private record CenteredBounds(double x, double y, double z,
            double halfX, double halfY, double halfZ) { }
}
