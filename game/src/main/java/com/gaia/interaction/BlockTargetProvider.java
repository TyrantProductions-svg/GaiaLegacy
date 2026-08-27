package com.gaia.interaction;

import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.SpatialQueryResult;

@FunctionalInterface
public interface BlockTargetProvider {
    SpatialQueryResult<BlockHitResult> target();
}
