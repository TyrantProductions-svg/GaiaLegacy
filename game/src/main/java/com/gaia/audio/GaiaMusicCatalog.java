package com.gaia.audio;

import com.overlord.assets.ResourceLocation;

/** Product-owned music resources approved for GaiaLegacy playback. */
public final class GaiaMusicCatalog {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");
    private static final ResourceLocation LEGACY =
            ResourceLocation.parse("gaia:audio/music/legacy.ogg");

    public ResourceLocation gaia() {
        return GAIA;
    }

    public ResourceLocation legacy() {
        return LEGACY;
    }

    boolean contains(ResourceLocation track) {
        return GAIA.equals(track) || LEGACY.equals(track);
    }
}
