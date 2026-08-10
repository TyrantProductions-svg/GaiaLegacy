package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import org.junit.jupiter.api.Test;

class GaiaMusicCatalogTest {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");
    private static final ResourceLocation LEGACY =
            ResourceLocation.parse("gaia:audio/music/legacy.ogg");

    @Test
    void exposesTheTwoApprovedRuntimeMusicResourcesAtTheirExactLocations() {
        GaiaMusicCatalog catalog = new GaiaMusicCatalog();

        assertEquals(GAIA, catalog.gaia());
        assertEquals(LEGACY, catalog.legacy());
    }
}
