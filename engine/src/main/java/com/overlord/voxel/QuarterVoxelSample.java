package com.overlord.voxel;

import java.util.Objects;

/** One immutable quarter-grid observation with parent representation provenance. */
public record QuarterVoxelSample(
        byte blockId,
        ParentRepresentation parentRepresentation) {
    private static final QuarterVoxelSample[] FULL_SAMPLES =
            samplesFor(ParentRepresentation.FULL);
    private static final QuarterVoxelSample[] DETAIL_SAMPLES =
            samplesFor(ParentRepresentation.DETAIL);

    public QuarterVoxelSample {
        parentRepresentation = Objects.requireNonNull(
                parentRepresentation, "parentRepresentation");
    }

    public boolean occupied() {
        return blockId != 0;
    }

    static QuarterVoxelSample full(byte blockId) {
        return FULL_SAMPLES[Byte.toUnsignedInt(blockId)];
    }

    static QuarterVoxelSample detail(byte blockId) {
        return DETAIL_SAMPLES[Byte.toUnsignedInt(blockId)];
    }

    private static QuarterVoxelSample[] samplesFor(
            ParentRepresentation representation) {
        QuarterVoxelSample[] samples = new QuarterVoxelSample[256];
        for (int blockId = 0; blockId < samples.length; blockId++) {
            samples[blockId] = new QuarterVoxelSample(
                    (byte) blockId, representation);
        }
        return samples;
    }

    public enum ParentRepresentation {
        FULL,
        DETAIL
    }
}
