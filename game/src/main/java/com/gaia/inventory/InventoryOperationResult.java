package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import java.util.Objects;
import java.util.Optional;

/** Closed result for a non-stack-returning inventory operation. */
public record InventoryOperationResult(
        Status status, Optional<BodyInventoryViewModel> viewModel) {
    public InventoryOperationResult {
        status = Objects.requireNonNull(status, "status");
        viewModel = Objects.requireNonNull(viewModel, "viewModel");
        if (status == Status.UNKNOWN_OWNER) {
            if (viewModel.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OWNER must not include an inventory view");
            }
        } else if (viewModel.isEmpty()) {
            throw new IllegalArgumentException(status + " requires an inventory view");
        }
    }

    public enum Status {
        APPLIED,
        NO_CHANGE,
        REJECTED,
        RESERVED,
        UNKNOWN_OWNER
    }
}
