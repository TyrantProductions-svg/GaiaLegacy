package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Optional;

public interface InventoryService {
    Optional<InventoryView> snapshot(EntityRef owner);

    InventoryChangeResult replaceSlot(
            InventoryChangeRequest request);
}
