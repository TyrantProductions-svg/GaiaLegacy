package com.overlord.interaction.api;

import com.overlord.inventory.api.ItemStackView;
import java.util.Optional;

public interface InteractionViewModel {
    Optional<BlockHitResult> target();

    Optional<BlockFace> hitFace();

    double progress();

    InteractionMode mode();

    Optional<ItemStackView> activeItem();

    Optional<InteractionFailureReason> failureReason();
}
