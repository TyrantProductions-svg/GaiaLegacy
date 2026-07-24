package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;

public interface BodyInventoryViewModel {
    EntityRef owner();

    BodySlot activeSlot();

    InventoryView inventory();
}
