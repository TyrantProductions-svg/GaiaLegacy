package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.overlord.assets.ResourceLocation;
import java.util.Optional;

public interface DetailActionPolicy {
    DetailActionDecision decide(
            GameMode mode,
            DetailAction action,
            Optional<ResourceLocation> activeItem,
            BlockDefinition material,
            boolean uniformFullCompatible);
}
