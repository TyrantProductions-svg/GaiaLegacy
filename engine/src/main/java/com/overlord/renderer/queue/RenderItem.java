package com.overlord.renderer.queue;

import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.Material;
import java.util.Objects;

public record RenderItem(
        ChunkRenderObject object,
        Material material) {
    public RenderItem {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(material, "material");
    }
}
