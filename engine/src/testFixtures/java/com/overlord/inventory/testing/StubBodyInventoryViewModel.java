package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import java.util.Objects;

public record StubBodyInventoryViewModel(
        EntityRef owner,
        BodySlot activeSlot,
        InventoryView inventory)
        implements BodyInventoryViewModel {
    public StubBodyInventoryViewModel {
        owner = Objects.requireNonNull(owner, "owner");
        activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        inventory = Objects.requireNonNull(inventory, "inventory");
    }
}
