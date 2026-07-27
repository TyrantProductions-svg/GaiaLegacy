package com.gaia.interaction;

import com.overlord.interaction.api.BlockHitResult;
import java.util.Optional;

@FunctionalInterface
public interface BlockTargetProvider {
    Optional<BlockHitResult> target();
}
