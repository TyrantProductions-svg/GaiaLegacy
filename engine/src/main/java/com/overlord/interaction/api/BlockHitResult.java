package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.RaycastCellTarget;
import java.util.Objects;

public record BlockHitResult(
        int blockX,
        int blockY,
        int blockZ,
        int adjacentX,
        int adjacentY,
        int adjacentZ,
        ResourceLocation block,
        int normalX,
        int normalY,
        int normalZ,
        float pointX,
        float pointY,
        float pointZ,
        float distance,
        double worldPointX,
        double worldPointY,
        double worldPointZ,
        long chunkRevision,
        RaycastCellTarget target) {
    public BlockHitResult {
        block = Objects.requireNonNull(block, "block");
        target = Objects.requireNonNull(target, "target");
        boolean xFace =
                (normalX == 1
                                || normalX == -1)
                        && normalY == 0
                        && normalZ == 0;
        boolean yFace =
                normalX == 0
                        && (normalY == 1
                                || normalY == -1)
                        && normalZ == 0;
        boolean zFace =
                normalX == 0
                        && normalY == 0
                        && (normalZ == 1
                                || normalZ == -1);
        if (!(xFace || yFace || zFace)) {
            throw new IllegalArgumentException("normal must identify one axis-aligned face");
        }
        if ((long) adjacentX != (long) blockX + normalX
                || (long) adjacentY != (long) blockY + normalY
                || (long) adjacentZ != (long) blockZ + normalZ) {
            throw new IllegalArgumentException("adjacent coordinates must follow the hit normal");
        }
        if (!Float.isFinite(pointX)
                || !Float.isFinite(pointY)
                || !Float.isFinite(pointZ)
                || !Float.isFinite(distance)
                || distance < 0) {
            throw new IllegalArgumentException("hit point and distance must be finite");
        }
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

    /** FULL-only source-compatible constructor for existing interaction fixtures. */
    public BlockHitResult(
            int blockX,
            int blockY,
            int blockZ,
            int adjacentX,
            int adjacentY,
            int adjacentZ,
            ResourceLocation block,
            int normalX,
            int normalY,
            int normalZ,
            float pointX,
            float pointY,
            float pointZ,
            float distance) {
        this(
                blockX, blockY, blockZ,
                adjacentX, adjacentY, adjacentZ,
                block,
                normalX, normalY, normalZ,
                pointX, pointY, pointZ, distance,
                pointX, pointY, pointZ,
                0L, FullRaycastTarget.INSTANCE);
    }
}
