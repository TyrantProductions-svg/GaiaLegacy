package com.gaia.interaction;

import com.overlord.interaction.api.InteractionViewModel;
import com.overlord.assets.ResourceLocation;
import java.util.Optional;
import java.util.OptionalInt;

/** Phase 9A read-only extension of the protected Phase 7 presentation contract. */
public interface BlockInteractionViewModel extends InteractionViewModel {
    int crackStage();

    GameMode gameMode();

    default BlockInteractionRouteDecision route() {
        return BlockInteractionRouteDecision.rejected("not_evaluated");
    }

    default Optional<DetailPlacementPreview> detailPreview() {
        return Optional.empty();
    }

    default Optional<ResourceLocation> selectedDetailMaterial() {
        return Optional.empty();
    }

    default OptionalInt availableDetailUnitCount() {
        return OptionalInt.empty();
    }
}
