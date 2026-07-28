package com.overlord.renderer.feedback;

public final class CrackStageMapper {
    private static final int MINIMUM_STAGE_COUNT = 8;
    private static final int MAXIMUM_STAGE_COUNT = 10;

    private CrackStageMapper() {}

    public static int map(double progress, int stageCount) {
        if (stageCount < MINIMUM_STAGE_COUNT || stageCount > MAXIMUM_STAGE_COUNT) {
            throw new IllegalArgumentException("stageCount must be between 8 and 10");
        }
        if (!Double.isFinite(progress)) {
            throw new IllegalArgumentException("progress must be finite");
        }
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return Math.min(stageCount - 1, (int) Math.floor(clamped * stageCount));
    }
}
