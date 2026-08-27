package com.overlord.interaction.api;

import com.overlord.physics.SpatialQueryResult;
import org.joml.Vector3fc;

/** Typed spatial query boundary that preserves unavailable terrain semantics. */
@FunctionalInterface
public interface SpatialBlockRaycastService {
    SpatialQueryResult<BlockHitResult> query(
            Vector3fc origin,
            Vector3fc direction,
            float maximumDistance);
}
