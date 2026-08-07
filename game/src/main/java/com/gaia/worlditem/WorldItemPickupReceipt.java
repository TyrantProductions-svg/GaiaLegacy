package com.gaia.worlditem;

import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import java.util.Objects;

/** Immutable committed pickup fact retained after full logical removal. */
public record WorldItemPickupReceipt(
        WorldItemId itemId,
        ItemStack picked,
        double positionX,
        double positionY,
        double positionZ,
        long tick) {
    public WorldItemPickupReceipt {
        itemId = Objects.requireNonNull(itemId, "itemId");
        picked = Objects.requireNonNull(picked, "picked");
        if (!Double.isFinite(positionX)
                || !Double.isFinite(positionY)
                || !Double.isFinite(positionZ)) {
            throw new IllegalArgumentException("pickup receipt position must be finite");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
    }
}
