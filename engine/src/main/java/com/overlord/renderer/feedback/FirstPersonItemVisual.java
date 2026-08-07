package com.overlord.renderer.feedback;

import java.util.Objects;

/** Small canonical held-block representation anchored in view space. */
public record FirstPersonItemVisual(
        WorldItemFaceRegions faces,
        VisualTransform transform) {
    public FirstPersonItemVisual {
        Objects.requireNonNull(faces, "faces");
        Objects.requireNonNull(transform, "transform");
    }
}
