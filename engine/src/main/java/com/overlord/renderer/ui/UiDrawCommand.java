package com.overlord.renderer.ui;

import java.util.Objects;
import java.util.Optional;

public record UiDrawCommand(
        UiTextureId texture,
        UiRect framebufferBounds,
        UiUvRect uv,
        UiColor tint,
        Optional<UiRect> clip) {
    public UiDrawCommand {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(framebufferBounds, "framebufferBounds");
        Objects.requireNonNull(uv, "uv");
        Objects.requireNonNull(tint, "tint");
        Objects.requireNonNull(clip, "clip");
    }
}
