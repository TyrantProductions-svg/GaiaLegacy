package com.overlord.renderer.queue;

import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.material.RenderType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RenderQueue {
    private final List<RenderItem> opaqueItems = new ArrayList<>();
    private final List<RenderItem> transparentItems = new ArrayList<>();

    public void submit(ChunkRenderObject object, Material material) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(material, "material");
        RenderItem item = new RenderItem(object, material);
        if (material.definition().renderType() == RenderType.TRANSPARENT) {
            transparentItems.add(item);
        } else {
            opaqueItems.add(item);
        }
    }

    public List<RenderItem> opaqueItems() {
        return List.copyOf(opaqueItems);
    }

    public List<RenderItem> transparentItems() {
        return List.copyOf(transparentItems);
    }

    public void clear() {
        opaqueItems.clear();
        transparentItems.clear();
    }

    public boolean isEmpty() {
        return opaqueItems.isEmpty() && transparentItems.isEmpty();
    }
}
