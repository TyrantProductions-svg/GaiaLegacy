package com.gaia.inventory;

import java.util.Objects;
import java.util.Optional;

/** Immutable outcome for one fixed-step inventory input sample. */
public record InventoryInputResult(
        Optional<ActiveSlotChangeResult> selection,
        Optional<InventoryDropResult> drop) {
    public InventoryInputResult {
        selection = Objects.requireNonNull(selection, "selection");
        drop = Objects.requireNonNull(drop, "drop");
    }
}
