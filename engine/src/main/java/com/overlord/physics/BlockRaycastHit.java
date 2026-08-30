package com.overlord.physics;

import java.util.Objects;
import org.joml.Vector3f;

public record BlockRaycastHit(
        int blockX,
        int blockY,
        int blockZ,
        int adjacentX,
        int adjacentY,
        int adjacentZ,
        byte blockId,
        float normalX,
        float normalY,
        float normalZ,
        float pointX,
        float pointY,
        float pointZ,
        float distance,
        double worldPointX,
        double worldPointY,
        double worldPointZ,
        long chunkRevision,
        RaycastCellTarget target) {
    public BlockRaycastHit {
        target = Objects.requireNonNull(target, "target");
        if (!Double.isFinite(worldPointX)
                || !Double.isFinite(worldPointY)
                || !Double.isFinite(worldPointZ)) {
            throw new IllegalArgumentException(
                    "canonical world hit point must be finite");
        }
        if (chunkRevision < 0L) {
            throw new IllegalArgumentException(
                    "chunkRevision must be nonnegative");
        }
        if (target instanceof DetailRaycastTarget
                && chunkRevision == 0L) {
            throw new IllegalArgumentException(
                    "DETAIL hit requires an observed positive Chunk revision");
        }
    }

    /** FULL-only source-compatible constructor for legacy fixtures and adapters. */
    public BlockRaycastHit(
            int blockX,
            int blockY,
            int blockZ,
            int adjacentX,
            int adjacentY,
            int adjacentZ,
            byte blockId,
            float normalX,
            float normalY,
            float normalZ,
            float pointX,
            float pointY,
            float pointZ,
            float distance) {
        this(
                blockX, blockY, blockZ,
                adjacentX, adjacentY, adjacentZ,
                blockId,
                normalX, normalY, normalZ,
                pointX, pointY, pointZ, distance,
                pointX, pointY, pointZ,
                0L, FullRaycastTarget.INSTANCE);
    }

    public Vector3f normal(Vector3f destination) {
        return destination.set(normalX, normalY, normalZ);
    }

    public Vector3f point(Vector3f destination) {
        return destination.set(pointX, pointY, pointZ);
    }
}
