package com.overlord.renderer.ui;

import java.util.Objects;

public record UiAssetBundle(
        UiTextureData icons,
        UiTextureData font,
        BitmapFont glyphs) {
    public UiAssetBundle {
        Objects.requireNonNull(icons, "icons");
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(glyphs, "glyphs");
    }
}
