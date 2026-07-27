package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Closed result for an atomic source-to-destination stack split. */
public record InventorySplitResult(
        Status status,
        Optional<ItemStack> moved,
        Optional<BodyInventoryViewModel> viewModel) {
    public InventorySplitResult {
        status = Objects.requireNonNull(status, "status");
        moved = Objects.requireNonNull(moved, "moved");
        viewModel = Objects.requireNonNull(viewModel, "viewModel");
        if (status == Status.UNKNOWN_OWNER) {
            if (moved.isPresent() || viewModel.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OWNER must not include split payloads");
            }
        } else if (status == Status.SPLIT) {
            if (moved.isEmpty() || viewModel.isEmpty()) {
                throw new IllegalArgumentException(
                        "SPLIT requires the moved stack and resulting view");
            }
        } else if (moved.isPresent() || viewModel.isEmpty()) {
            throw new IllegalArgumentException(
                    "failed split requires only the unchanged inventory view");
        }
    }

    public enum Status {
        SPLIT,
        UNKNOWN_OWNER,
        INVALID_COUNT,
        SAME_SLOT,
        EMPTY_SOURCE,
        SOURCE_TOO_SMALL,
        RESERVED,
        REJECTED,
        DESTINATION_FULL
    }
}
