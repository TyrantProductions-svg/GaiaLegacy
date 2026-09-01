package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.physics.RaycastCellTarget;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.Objects;

public record DetailPrecisionTarget(
        int parentX,
        int parentY,
        int parentZ,
        LocalSubVoxelPosition localPosition,
        BlockFace face,
        ResourceLocation material,
        long observedChunkRevision,
        RaycastCellTarget representation) {
    public DetailPrecisionTarget {
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(representation, "representation");
        if (observedChunkRevision < 0L) {
            throw new IllegalArgumentException(
                    "observedChunkRevision must be nonnegative");
        }
    }
}
