package com.overlord.renderer.feedback;

import java.util.Objects;

/** Immutable presentation proxy for a committed block transition. */
public record TransientBlockVisual(
        BlockVisualCoordinate coordinate,
        WorldItemFaceRegions faces,
        Type type,
        long eventIdentity,
        VisualTransform transform) {
    public TransientBlockVisual {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(faces, "faces");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(transform, "transform");
    }

    public enum Type {
        PLACEMENT,
        BREAK,
        PREVIEW
    }
}
