package com.gaia.ui;

import java.util.Objects;

/** Read-only lifecycle and visibility decision captured for one render frame. */
public record HudVisibility(
        boolean hudVisible,
        boolean debugVisible,
        boolean interactionEligible,
        Lifecycle lifecycle,
        Reason reason) {
    public HudVisibility {
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        reason = Objects.requireNonNull(reason, "reason");
        if (interactionEligible && !hudVisible) {
            throw new IllegalArgumentException("interaction cannot be eligible while the HUD is hidden");
        }
        if (lifecycle == Lifecycle.LOADING) {
            requireFullyHidden(hudVisible, debugVisible, interactionEligible, "loading");
            if (reason != Reason.LOADING) {
                throw new IllegalArgumentException("LOADING lifecycle requires LOADING reason");
            }
        } else if (lifecycle == Lifecycle.SHUTDOWN) {
            requireFullyHidden(hudVisible, debugVisible, interactionEligible, "shutdown");
            if (reason != Reason.SHUTDOWN) {
                throw new IllegalArgumentException("SHUTDOWN lifecycle requires SHUTDOWN reason");
            }
        } else if (reason == Reason.LOADING || reason == Reason.SHUTDOWN) {
            throw new IllegalArgumentException("RUNNING lifecycle cannot use a non-running reason");
        } else if (reason == Reason.VISIBLE) {
            if (!hudVisible || !interactionEligible) {
                throw new IllegalArgumentException(
                        "VISIBLE reason requires HUD visibility and interaction eligibility");
            }
        } else if (reason == Reason.HUD_DISABLED) {
            if (hudVisible || interactionEligible) {
                throw new IllegalArgumentException(
                        "HUD_DISABLED reason requires gameplay presentation to be hidden");
            }
        } else {
            requireFullyHidden(
                    hudVisible, debugVisible, interactionEligible, "unsafe running boundary");
        }
    }

    private static void requireFullyHidden(
            boolean hudVisible,
            boolean debugVisible,
            boolean interactionEligible,
            String boundary) {
        if (hudVisible || debugVisible || interactionEligible) {
            throw new IllegalArgumentException(boundary + " presentation must be fully hidden");
        }
    }

    public enum Lifecycle {
        RUNNING,
        LOADING,
        SHUTDOWN
    }

    public enum Reason {
        VISIBLE,
        HUD_DISABLED,
        CURSOR_RELEASED,
        FOCUS_LOST,
        LOADING,
        SHUTDOWN,
        BLOCKING_UI
    }
}
