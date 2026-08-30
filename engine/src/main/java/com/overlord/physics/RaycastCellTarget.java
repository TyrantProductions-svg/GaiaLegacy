package com.overlord.physics;

/** Immutable discriminator for the canonical parent representation hit by a ray. */
public sealed interface RaycastCellTarget
        permits FullRaycastTarget, DetailRaycastTarget {}
