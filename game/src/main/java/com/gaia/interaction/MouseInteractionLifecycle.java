package com.gaia.interaction;

import java.util.Objects;
import java.util.function.Consumer;

/** Frame-boundary cancellation; physical release suppression remains InputManager-owned. */
public final class MouseInteractionLifecycle {
    private final Runnable cancelAndSuppressInteraction;

    public MouseInteractionLifecycle(Runnable cancelAndSuppressInteraction) {
        this.cancelAndSuppressInteraction = Objects.requireNonNull(
                cancelAndSuppressInteraction, "cancelAndSuppressInteraction");
    }

    public boolean toggleCursorCapture(
            boolean cursorCaptured, Consumer<Boolean> cursorCaptureSink) {
        Objects.requireNonNull(cursorCaptureSink, "cursorCaptureSink");
        boolean nextCaptured = !cursorCaptured;
        cursorCaptureSink.accept(nextCaptured);
        cancelAndSuppressInteraction.run();
        return nextCaptured;
    }

    public void onFocusLost() {
        cancelAndSuppressInteraction.run();
    }
}
