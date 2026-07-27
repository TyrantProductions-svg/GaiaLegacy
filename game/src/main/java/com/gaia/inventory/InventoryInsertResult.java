package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Closed result for an insert attempt. */
public record InventoryInsertResult(
        Status status,
        Optional<ItemStack> remainder,
        Optional<BodyInventoryViewModel> viewModel) {
    public InventoryInsertResult {
        status = Objects.requireNonNull(status, "status");
        remainder = Objects.requireNonNull(remainder, "remainder");
        viewModel = Objects.requireNonNull(viewModel, "viewModel");
        switch (status) {
            case INSERTED -> {
                if (remainder.isPresent() || viewModel.isEmpty()) {
                    throw new IllegalArgumentException(
                            "INSERTED requires a view and no remainder");
                }
            }
            case PARTIALLY_INSERTED, REJECTED -> {
                if (remainder.isEmpty() || viewModel.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires a remainder and inventory view");
                }
            }
            case UNKNOWN_OWNER -> {
                if (remainder.isEmpty() || viewModel.isPresent()) {
                    throw new IllegalArgumentException(
                            "UNKNOWN_OWNER requires only the full remainder");
                }
            }
        }
    }

    public enum Status {
        INSERTED,
        PARTIALLY_INSERTED,
        REJECTED,
        UNKNOWN_OWNER
    }
}
