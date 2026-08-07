package com.overlord.renderer.feedback;

import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.worlditem.api.WorldItemId;
import java.util.Objects;

public record WorldItemVisual(
        WorldItemId id,
        long sourceRevision,
        double x,
        double y,
        double z,
        WorldItemFaceRegions faces) {
    public WorldItemVisual {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(faces, "faces");
    }

    /** Compatibility constructor for callers that intentionally use one region. */
    public WorldItemVisual(
            WorldItemId id,
            long sourceRevision,
            double x,
            double y,
            double z,
            TextureRegion region) {
        this(id, sourceRevision, x, y, z, WorldItemFaceRegions.uniform(region));
    }

    /** Compatibility accessor for single-region effects. */
    public TextureRegion region() {
        return faces.region(BlockFace.UP);
    }
}
