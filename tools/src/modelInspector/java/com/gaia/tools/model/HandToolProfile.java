package com.gaia.tools.model;

/** Versioned HAND_TOOL_V0 semantic limits; not general Gaia asset budgets. */
public final class HandToolProfile {
    public static final String ID = "GAIA_GLB_HAND_TOOL_V0";
    public static final int VERSION = 0;
    public static final int MAX_NODES = 64, MAX_DEPTH = 16, MAX_MESHES = 8,
            MAX_PRIMITIVES = 16, MAX_VERTICES = 30_000, MAX_TRIANGLES = 10_000,
            WARN_TRIANGLES = 4_000, MAX_MATERIALS = 8, MAX_IMAGES = 4,
            MAX_TEXTURES = 8, MAX_SAMPLERS = 8,
            MAX_IMAGE_DIMENSION = 1024, MAX_RGBA_BYTES = 16_777_216;
    public static final double RIGID_TRANSFORM_EPSILON = 1e-4;
    public static final double NORMAL_LENGTH_EPSILON = 1e-4;
    /** Length of the triangle cross product (twice area), in square meters. */
    public static final double TRIANGLE_AREA_EPSILON = 1e-12;
    public static final int MAX_DIAGNOSTICS = 64, MAX_DIAGNOSTIC_PATH = 160,
            MAX_DIAGNOSTIC_MESSAGE = 240;
    public static final int MAX_PNG_CHUNKS = 256, MAX_JPEG_MARKERS = 256;
    /** Gate A names <=4096 chars, JSON-pointer escaping at most doubles, plus fixed path. */
    public static final int MAX_DIAGNOSTIC_IDENTITY = 2 * 4096 + 256;

    private HandToolProfile() { }

    /** Counts declarations/instances without allocating or deduplicating geometry. */
    static final class GeometryBudget {
        private long uniqueTriangles, uniqueVertices, expandedTriangles, expandedVertices;
        private boolean arithmeticFailure;

        void addUnique(long triangles, long vertices) {
            try {
                nonnegative(triangles, vertices);
                long t = Math.addExact(uniqueTriangles, triangles);
                long v = Math.addExact(uniqueVertices, vertices);
                uniqueTriangles = t; uniqueVertices = v;
            } catch (ArithmeticException rejected) { arithmeticFailure = true; }
        }

        void addExpanded(long triangles, long vertices, long instances) {
            try {
                nonnegative(triangles, vertices, instances);
                long t = Math.addExact(expandedTriangles, Math.multiplyExact(triangles, instances));
                long v = Math.addExact(expandedVertices, Math.multiplyExact(vertices, instances));
                expandedTriangles = t; expandedVertices = v;
            } catch (ArithmeticException rejected) { arithmeticFailure = true; }
        }

        private static void nonnegative(long... values) {
            for (long value : values) {
                if (value < 0) { throw new ArithmeticException(); }
            }
        }

        long uniqueTriangles() { return uniqueTriangles; }
        long uniqueVertices() { return uniqueVertices; }
        long expandedTriangles() { return expandedTriangles; }
        long expandedVertices() { return expandedVertices; }

        ValidationReport report() {
            var log = new ValidationReport.Collector();
            if (arithmeticFailure) { log.error("COUNT_ARITHMETIC", "/", "Invalid or overflowing count"); }
            domain(log, "UNIQUE", uniqueTriangles, uniqueVertices);
            domain(log, "EXPANDED", expandedTriangles, expandedVertices);
            return log.report();
        }

        private static void domain(ValidationReport.Collector log, String domain, long t, long v) {
            if (t > MAX_TRIANGLES) {
                log.error(domain + "_TRIANGLE_LIMIT", "/", "Triangle hard budget exceeded");
            } else if (t > WARN_TRIANGLES) {
                log.warning(domain + "_TRIANGLE_WARNING", "/", "Triangle warning threshold exceeded");
            }
            if (v > MAX_VERTICES) {
                log.error(domain + "_VERTEX_LIMIT", "/", "Vertex hard budget exceeded");
            }
        }
    }
}
