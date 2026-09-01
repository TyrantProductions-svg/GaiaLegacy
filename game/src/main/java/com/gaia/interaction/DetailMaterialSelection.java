package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import java.util.List;
import java.util.Objects;

/** Session-local bounded selection for the two Phase 17 v1 DETAIL materials. */
public final class DetailMaterialSelection {
    private final List<ResourceLocation> materials;
    private int selectedIndex;

    public DetailMaterialSelection(ResourceLocation first, ResourceLocation second) {
        materials = List.of(
                Objects.requireNonNull(first, "first"),
                Objects.requireNonNull(second, "second"));
        if (first.equals(second)) {
            throw new IllegalArgumentException("detail materials must be distinct");
        }
    }

    public ResourceLocation selected() {
        return materials.get(selectedIndex);
    }

    public boolean handleCycle(boolean precisionActive, boolean pressedEdge) {
        if (!precisionActive || !pressedEdge) {
            return false;
        }
        selectedIndex = (selectedIndex + 1) % materials.size();
        return true;
    }
}
