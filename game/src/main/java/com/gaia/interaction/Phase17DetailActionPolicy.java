package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemCapability;
import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.Optional;

public final class Phase17DetailActionPolicy implements DetailActionPolicy {
    private final BlockRegistry registry;

    public Phase17DetailActionPolicy(BlockRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public DetailActionDecision decide(
            GameMode mode,
            DetailAction action,
            Optional<ResourceLocation> activeItem,
            BlockDefinition material,
            boolean uniformFullCompatible) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(action, "action");
        activeItem = Objects.requireNonNull(activeItem, "activeItem");
        Objects.requireNonNull(material, "material");

        Optional<ResourceLocation> detailUnit =
                registry.detailUnitForBlock(material.name());
        if (detailUnit.isEmpty()) {
            return DetailActionDecision.rejected("unsupported_material");
        }
        if (action != DetailAction.COARSE_REMOVE) {
            boolean precision = activeItem
                    .map(registry::itemCapabilities)
                    .orElseGet(java.util.Set::of)
                    .contains(ItemCapability.DETAIL_PRECISION);
            if (!precision) {
                return DetailActionDecision.rejected("precision_tool_required");
            }
            if (mode == GameMode.CREATIVE) {
                return DetailActionDecision.allowedNone();
            }
            return DetailActionDecision.allowed(
                    DetailRecoveryKind.DETAIL_UNIT,
                    detailUnit.orElseThrow());
        }

        if (mode == GameMode.CREATIVE || !uniformFullCompatible) {
            return DetailActionDecision.allowedNone();
        }
        if (material.item() == null) {
            return DetailActionDecision.rejected("full_block_output_unavailable");
        }
        return DetailActionDecision.allowed(
                DetailRecoveryKind.FULL_BLOCK,
                material.item().id());
    }
}
