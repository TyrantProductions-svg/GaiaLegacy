package com.overlord.renderer.feedback;

import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import java.util.Objects;

public record WorldItemVisual(
        WorldItemId id,
        long sourceRevision,
        double x,
        double y,
        double z,
        TextureRegion region) {
    public WorldItemVisual {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(region, "region");
    }
}
