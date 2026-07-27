package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.InteractionFailureReason;

final class BlockInteractionFailures {
    private BlockInteractionFailures() {}

    static InteractionFailureReason of(String path) {
        return new InteractionFailureReason(
                new ResourceLocation("gaia", "interaction/" + path));
    }
}
