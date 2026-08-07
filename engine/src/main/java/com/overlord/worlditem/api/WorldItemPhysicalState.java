package com.overlord.worlditem.api;

/**
 * Canonical physical projection state for a live logical world item.
 *
 * <p>Gate 11.2 applies gravity, static collision response, support, and
 * deterministic sleeping through this immutable state boundary.</p>
 */
public enum WorldItemPhysicalState {
    ACTIVE,
    GROUNDED,
    SLEEPING,
    FROZEN_UNLOADED
}
