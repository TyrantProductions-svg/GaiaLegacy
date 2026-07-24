package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
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
        float distance) {
    public BlockHitResult {
        block = Objects.requireNonNull(block, "block");
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
    }
}
