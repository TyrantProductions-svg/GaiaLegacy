package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.Node;
import static com.gaia.tools.model.HandToolProfile.RIGID_TRANSFORM_EPSILON;

/** Validates authored transforms; never normalizes, snaps or rewrites them. */
final class RigidTransform {
    private final double[] matrix;

    private RigidTransform(double[] owned) { matrix = owned; }

    static RigidTransform from(Node node) {
        double[] authored = node.getMatrix();
        if (authored != null) {
            require(node.getTranslation() == null && node.getRotation() == null && node.getScale() == null);
            finite(authored, 16);
            double[] m = authored.clone();
            near(m[3], 0); near(m[7], 0); near(m[11], 0); near(m[15], 1);
            for (int col = 0; col < 3; col++) {
                int a = col * 4;
                near(Math.hypot(Math.hypot(m[a], m[a+1]), m[a+2]), 1);
                for (int other = col + 1; other < 3; other++) {
                    int b = other * 4;
                    near(m[a]*m[b] + m[a+1]*m[b+1] + m[a+2]*m[b+2], 0);
                }
            }
            near(m[0]*(m[5]*m[10]-m[9]*m[6]) - m[4]*(m[1]*m[10]-m[9]*m[2])
                    + m[8]*(m[1]*m[6]-m[5]*m[2]), 1);
            return new RigidTransform(m);
        }
        double[] t = node.getTranslation() == null ? new double[]{0,0,0} : node.getTranslation();
        double[] q = node.getRotation() == null ? new double[]{0,0,0,1} : node.getRotation();
        double[] s = node.getScale() == null ? new double[]{1,1,1} : node.getScale();
        finite(t,3); finite(q,4); finite(s,3);
        for (double v : s) { near(v,1); }
        near(Math.hypot(Math.hypot(q[0],q[1]), Math.hypot(q[2],q[3])),1);
        double x=q[0], y=q[1], z=q[2], w=q[3];
        double[] m = {
                (1-2*(y*y+z*z))*s[0], 2*(x*y+z*w)*s[0], 2*(x*z-y*w)*s[0], 0,
                2*(x*y-z*w)*s[1], (1-2*(x*x+z*z))*s[1], 2*(y*z+x*w)*s[1], 0,
                2*(x*z+y*w)*s[2], 2*(y*z-x*w)*s[2], (1-2*(x*x+y*y))*s[2], 0,
                t[0],t[1],t[2],1};
        finite(m,16);
        return new RigidTransform(m);
    }

    RigidTransform compose(RigidTransform child) {
        double[] result = new double[16];
        for (int c=0;c<4;c++) {
            for (int r=0;r<4;r++) {
                for (int k=0;k<4;k++) { result[c*4+r] += matrix[k*4+r]*child.matrix[c*4+k]; }
            }
        }
        // Authored nodes were independently checked; do not reclassify accumulated
        // permitted serialization noise or normalize the composed matrix.
        finite(result,16);
        return new RigidTransform(result);
    }

    double[] point(double x, double y, double z) {
        double[] result = {matrix[0]*x+matrix[4]*y+matrix[8]*z+matrix[12],
                matrix[1]*x+matrix[5]*y+matrix[9]*z+matrix[13],
                matrix[2]*x+matrix[6]*y+matrix[10]*z+matrix[14]};
        finite(result,3);
        return result;
    }

    double[] values() { return matrix.clone(); }

    private static void finite(double[] values, int size) {
        require(values.length == size);
        for (double value : values) { require(Double.isFinite(value)); }
    }
    private static void near(double actual, double expected) {
        require(Double.isFinite(actual) && Math.abs(actual-expected) <= RIGID_TRANSFORM_EPSILON);
    }
    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException("INVALID_RIGID_TRANSFORM"); }
    }
}
