package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import java.util.Objects;
import java.util.Optional;

/** Closed result for active-slot selection. */
public record ActiveSlotChangeResult(
        Status status, Optional<BodyInventoryViewModel> viewModel) {
    public ActiveSlotChangeResult {
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
        SELECTED,
        UNCHANGED,
        UNKNOWN_OWNER
    }
}
