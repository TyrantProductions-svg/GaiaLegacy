package com.gaia.inventory;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;

/** Stable text rendering for developer console inspection. */
public final class InventorySnapshotFormatter {
    public String format(BodyInventoryViewModel viewModel) {
        Objects.requireNonNull(viewModel, "viewModel");
        StringBuilder output = new StringBuilder()
                .append("owner=").append(viewModel.owner().id())
                .append(" revision=").append(viewModel.inventory().revision())
                .append(" active=").append(viewModel.activeSlot());
        for (BodySlot slot : BodySlot.values()) {
            output.append(' ').append(slot).append('=');
            ItemStackView stack = viewModel.inventory().stack(slot).orElse(null);
            if (stack == null) {
                output.append("empty");
            } else {
                output.append(stack.itemId()).append(" x").append(stack.count());
            }
        }
        return output.toString();
    }
}
