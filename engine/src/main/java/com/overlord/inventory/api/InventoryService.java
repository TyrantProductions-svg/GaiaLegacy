package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;

public interface InventoryService {
    InventoryView snapshot(EntityRef owner);

    InventoryChangeResult replaceSlot(
            InventoryChangeRequest request);
}
