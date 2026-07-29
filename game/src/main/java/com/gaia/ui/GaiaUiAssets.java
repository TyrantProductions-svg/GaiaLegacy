package com.gaia.ui;

import com.overlord.renderer.ui.UiAssetBundle;
import java.util.Objects;

public record GaiaUiAssets(UiAssetBundle renderAssets, UiIconAtlas icons) {
    public GaiaUiAssets {
        Objects.requireNonNull(renderAssets, "renderAssets");
        Objects.requireNonNull(icons, "icons");
    }
}
