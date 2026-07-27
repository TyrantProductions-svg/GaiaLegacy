package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Creative-only selection that never reads or writes the body inventory. */
public final class CreativeSelection {
    private final Predicate<ResourceLocation> selectable;
    private ResourceLocation selected;

    public CreativeSelection(
            BlockRegistry blocks, Optional<ResourceLocation> initialSelection) {
        this(registrySelection(blocks), initialSelection);
    }

    private static Predicate<ResourceLocation> registrySelection(BlockRegistry blocks) {
        BlockRegistry registry = Objects.requireNonNull(blocks, "blocks");
        return item -> registry.blockForItem(item)
                        .filter(definition -> definition.id() != 0)
                        .isPresent();
    }

    CreativeSelection(
            Predicate<ResourceLocation> selectable,
            Optional<ResourceLocation> initialSelection) {
        this.selectable = Objects.requireNonNull(selectable, "selectable");
        Objects.requireNonNull(initialSelection, "initialSelection")
                .ifPresent(item -> {
                    if (!select(item)) {
                        throw new IllegalArgumentException(
                                "initial creative selection is not a block item");
                    }
                });
    }

    public boolean select(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (!selectable.test(itemId)) {
            return false;
        }
        selected = itemId;
        return true;
    }

    public void clear() {
        selected = null;
    }

    public Optional<ItemStack> selected() {
        return selected == null
                ? Optional.empty()
                : Optional.of(new ItemStack(selected, 1));
    }
}
