package com.gaia.interaction.feedback;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.gaia.interaction.DetailPlacementCandidate;
import com.gaia.interaction.DetailPrecisionTarget;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;

/** Presentation-only consumer of immutable facts from already committed gameplay. */
public interface CommittedGameplayFeedback {
    CommittedGameplayFeedback NONE = new CommittedGameplayFeedback() {};

    default void onPlacementCommitted(
            BlockHitResult target,
            ResourceLocation placedItem,
            long eventIdentity) {}

    default void onBreakCommitted(
            BlockHitResult target,
            ResourceLocation brokenItem,
            long eventIdentity) {}

    default void onDetailRemovalCommitted(
            DetailPrecisionTarget target,
            ResourceLocation material,
            long eventIdentity) {}

    default void onDetailPlacementCommitted(
            DetailPlacementCandidate candidate,
            long eventIdentity) {}

    default void onDropCommitted(ResourceLocation item, long eventIdentity) {}

    default void onPickupCommitted(WorldItemPickupReceipt receipt) {}
}
