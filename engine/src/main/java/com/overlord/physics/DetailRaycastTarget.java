package com.overlord.physics;

import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import java.util.Objects;

/** Canonical DETAIL_4 parent and exact local subvoxel hit provenance. */
public record DetailRaycastTarget(
        VoxelScale scale, LocalSubVoxelPosition position)
        implements RaycastCellTarget {
    public DetailRaycastTarget {
        scale = Objects.requireNonNull(scale, "scale");
        position = Objects.requireNonNull(position, "position");
        if (scale != VoxelScale.DETAIL_4) {
            throw new IllegalArgumentException(
                    "Phase 16 supports only DETAIL_4 raycast targets");
        }
    }
}
