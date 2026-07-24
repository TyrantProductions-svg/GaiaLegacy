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
        int axisMagnitude = Math.abs(normalX) + Math.abs(normalY) + Math.abs(normalZ);
        if (axisMagnitude != 1
                || Math.abs(normalX) > 1
                || Math.abs(normalY) > 1
                || Math.abs(normalZ) > 1) {
            throw new IllegalArgumentException("normal must identify one axis-aligned face");
        }
        if (adjacentX != blockX + normalX
                || adjacentY != blockY + normalY
                || adjacentZ != blockZ + normalZ) {
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
