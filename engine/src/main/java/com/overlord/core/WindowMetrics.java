package com.overlord.core;

import java.util.Optional;

public final class WindowMetrics {
    private int logicalWidth;
    private int logicalHeight;
    private int framebufferWidth;
    private int framebufferHeight;
    private FramebufferSize pendingFramebufferResize;

    public WindowMetrics(
            int logicalWidth, int logicalHeight, int framebufferWidth, int framebufferHeight) {
        validateDimensions(logicalWidth, logicalHeight);
        validateDimensions(framebufferWidth, framebufferHeight);
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
    }

    public void updateLogicalSize(int width, int height) {
        validateDimensions(width, height);
        logicalWidth = width;
        logicalHeight = height;
    }

    public void updateFramebufferSize(int width, int height) {
        validateDimensions(width, height);
        framebufferWidth = width;
        framebufferHeight = height;
        pendingFramebufferResize = new FramebufferSize(width, height);
    }

    public Optional<FramebufferSize> consumeFramebufferResize() {
        FramebufferSize resize = pendingFramebufferResize;
        pendingFramebufferResize = null;
        return Optional.ofNullable(resize);
    }

    public int logicalWidth() {
        return logicalWidth;
    }

    public int logicalHeight() {
        return logicalHeight;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    private static void validateDimensions(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
    }

    public record FramebufferSize(int width, int height) {
        public FramebufferSize {
            validateDimensions(width, height);
        }
    }
}
