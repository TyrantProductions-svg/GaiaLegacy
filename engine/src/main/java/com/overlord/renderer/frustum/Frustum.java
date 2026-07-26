package com.overlord.renderer.frustum;

import com.overlord.renderer.AxisAlignedBounds;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class Frustum {
    private static final float INTERSECTION_EPSILON = 0.01f;

    private final List<FrustumPlane> planes;

    private Frustum(List<FrustumPlane> planes) {
        this.planes = List.copyOf(planes);
    }

    public static Frustum from(Matrix4fc projection, Matrix4fc view) {
        Matrix4f clip =
                new Matrix4f(Objects.requireNonNull(projection, "projection"))
                        .mul(Objects.requireNonNull(view, "view"));

        return new Frustum(
                List.of(
                        new FrustumPlane(
                                clip.m03() + clip.m00(),
                                clip.m13() + clip.m10(),
                                clip.m23() + clip.m20(),
                                clip.m33() + clip.m30()),
                        new FrustumPlane(
                                clip.m03() - clip.m00(),
                                clip.m13() - clip.m10(),
                                clip.m23() - clip.m20(),
                                clip.m33() - clip.m30()),
                        new FrustumPlane(
                                clip.m03() + clip.m01(),
                                clip.m13() + clip.m11(),
                                clip.m23() + clip.m21(),
                                clip.m33() + clip.m31()),
                        new FrustumPlane(
                                clip.m03() - clip.m01(),
                                clip.m13() - clip.m11(),
                                clip.m23() - clip.m21(),
                                clip.m33() - clip.m31()),
                        new FrustumPlane(
                                clip.m03() + clip.m02(),
                                clip.m13() + clip.m12(),
                                clip.m23() + clip.m22(),
                                clip.m33() + clip.m32()),
                        new FrustumPlane(
                                clip.m03() - clip.m02(),
                                clip.m13() - clip.m12(),
                                clip.m23() - clip.m22(),
                                clip.m33() - clip.m32())));
    }

    public boolean intersects(AxisAlignedBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        for (FrustumPlane plane : planes) {
            if (plane.isOutside(bounds, INTERSECTION_EPSILON)) {
                return false;
            }
        }
        return true;
    }
}
