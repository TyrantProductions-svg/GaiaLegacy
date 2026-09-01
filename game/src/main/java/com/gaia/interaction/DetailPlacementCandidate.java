package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservationResult;
import java.util.Objects;

public record DetailPlacementCandidate(
        DetailPrecisionTarget source,
        int parentX,
        int parentY,
        int parentZ,
        LocalSubVoxelPosition localPosition,
        ResourceLocation material,
        ParentCellObservationResult destinationObservation,
        Status status) {
    public DetailPlacementCandidate {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(
                destinationObservation, "destinationObservation");
        Objects.requireNonNull(status, "status");
    }

    public boolean valid() {
        return status == Status.VALID_DETAIL_EMPTY
                || status == Status.VALID_FULL_AIR;
    }

    public enum Status {
        VALID_DETAIL_EMPTY,
        VALID_FULL_AIR,
        OCCUPIED,
        UNKNOWN,
        FAILED,
        OUT_OF_BOUNDS
    }
}
