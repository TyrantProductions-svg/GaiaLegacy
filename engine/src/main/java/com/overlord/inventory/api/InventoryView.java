package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Optional;

public interface InventoryView {
    EntityRef owner();

    long revision();

    Optional<ItemStackView> stack(BodySlot slot);
}
