package com.overlord.renderer.state;

public record Viewport(int x, int y, int width, int height) {
    public Viewport {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(
                    "viewport dimensions must be non-negative");
        }
    }
}
