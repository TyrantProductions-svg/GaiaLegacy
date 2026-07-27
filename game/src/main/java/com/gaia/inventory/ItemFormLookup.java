package com.gaia.inventory;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import java.util.Optional;

/** Read-only adapter over the existing data-driven block item forms. */
@FunctionalInterface
public interface ItemFormLookup {
    Optional<ItemFormDefinition> find(ResourceLocation itemId);
}
