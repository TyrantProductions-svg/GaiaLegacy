package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record InteractionFailureReason(ResourceLocation code) {
    public InteractionFailureReason {
        code = Objects.requireNonNull(code, "code");
    }
}
