package com.gaia.interaction;

import com.overlord.interaction.api.BlockHitResult;
import java.util.Objects;

public record BlockBreakSession(
        BlockHitResult target,
        long chunkRevision,
        double elapsedSeconds,
        double requiredSeconds) {
    public BlockBreakSession {
        target = Objects.requireNonNull(target, "target");
        if (chunkRevision <= 0) {
            throw new IllegalArgumentException("chunkRevision must be positive");
        }
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException(
                    "elapsedSeconds must be finite and non-negative");
        }
        if (!Double.isFinite(requiredSeconds) || requiredSeconds < 0) {
            throw new IllegalArgumentException(
                    "requiredSeconds must be finite and non-negative");
        }
        if (elapsedSeconds > requiredSeconds && requiredSeconds != 0) {
            throw new IllegalArgumentException(
                    "elapsedSeconds must not exceed requiredSeconds");
        }
    }

    public static BlockBreakSession start(
            BlockHitResult target, long chunkRevision, double requiredSeconds) {
        return new BlockBreakSession(target, chunkRevision, 0, requiredSeconds);
    }

    public BlockBreakSession advance(double fixedDeltaSeconds) {
        if (!Double.isFinite(fixedDeltaSeconds) || fixedDeltaSeconds <= 0) {
            throw new IllegalArgumentException(
                    "fixedDeltaSeconds must be finite and positive");
        }
        if (complete()) {
            return this;
        }
        return new BlockBreakSession(
                target,
                chunkRevision,
                Math.min(requiredSeconds, elapsedSeconds + fixedDeltaSeconds),
                requiredSeconds);
    }

    public boolean matches(BlockHitResult observedTarget, long observedRevision) {
        BlockHitResult observed = Objects.requireNonNull(
                observedTarget, "observedTarget");
        return target.blockX() == observed.blockX()
                && target.blockY() == observed.blockY()
                && target.blockZ() == observed.blockZ()
                && target.block().equals(observed.block())
                && target.normalX() == observed.normalX()
                && target.normalY() == observed.normalY()
                && target.normalZ() == observed.normalZ()
                && chunkRevision == observedRevision;
    }

    public boolean complete() {
        return requiredSeconds == 0 || elapsedSeconds >= requiredSeconds;
    }

    public double progress() {
        return requiredSeconds == 0
                ? 1
                : Math.min(1, elapsedSeconds / requiredSeconds);
    }

    public int crackStage() {
        return Math.min(9, (int) Math.floor(progress() * 10 + 1.0e-9));
    }
}
