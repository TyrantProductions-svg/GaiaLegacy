package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Closed result for an extraction attempt. */
public record InventoryExtractResult(
        Status status,
        Optional<ItemStack> extracted,
        Optional<BodyInventoryViewModel> viewModel) {
    public InventoryExtractResult {
        status = Objects.requireNonNull(status, "status");
        extracted = Objects.requireNonNull(extracted, "extracted");
        viewModel = Objects.requireNonNull(viewModel, "viewModel");
        switch (status) {
            case EXTRACTED, PARTIALLY_EXTRACTED -> {
                if (extracted.isEmpty() || viewModel.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires an extracted stack and inventory view");
                }
            }
            case EMPTY_SLOT, INVALID_COUNT, RESERVED -> {
                if (extracted.isPresent() || viewModel.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires only the unchanged inventory view");
                }
            }
            case UNKNOWN_OWNER -> {
                if (extracted.isPresent() || viewModel.isPresent()) {
                    throw new IllegalArgumentException(
                            "UNKNOWN_OWNER must not include extraction payloads");
                }
            }
        }
    }

    public enum Status {
        EXTRACTED,
        PARTIALLY_EXTRACTED,
        EMPTY_SLOT,
        INVALID_COUNT,
        RESERVED,
        UNKNOWN_OWNER
    }
}
