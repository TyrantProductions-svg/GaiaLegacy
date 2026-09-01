package com.gaia.blocks;

import java.util.Objects;
import java.util.Set;

public record StandaloneItemDefinition(
        ItemFormDefinition form,
        Set<ItemCapability> capabilities,
        ItemVisualReference visual) {
    public StandaloneItemDefinition {
        Objects.requireNonNull(form, "form");
        capabilities = Set.copyOf(
                Objects.requireNonNull(capabilities, "capabilities"));
        Objects.requireNonNull(visual, "visual");
    }
}
