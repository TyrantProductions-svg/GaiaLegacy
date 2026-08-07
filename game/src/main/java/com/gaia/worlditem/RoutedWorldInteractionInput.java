package com.gaia.worlditem;

import com.overlord.core.input.InputSnapshot;
import java.util.Objects;

/** One fixed step's disjoint pickup and block-interaction input. */
public record RoutedWorldInteractionInput(
        InputSnapshot blockInput,
        boolean pickupPressed) {
    public RoutedWorldInteractionInput {
        blockInput = Objects.requireNonNull(blockInput, "blockInput");
    }
}
