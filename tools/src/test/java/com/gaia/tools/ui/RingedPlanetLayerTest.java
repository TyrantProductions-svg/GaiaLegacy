package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RingedPlanetLayerTest {
    @Test
    void sphereIsFilledLargeAndAtmosphericRatherThanAnOutline() {
        assertTrue((RingedPlanetLayer.sample(1040, 132) >>> 24) >= 30);
        assertTrue((RingedPlanetLayer.sample(1040, 132) >>> 24) <= 65);
        assertTrue((RingedPlanetLayer.sample(1040, 38) >>> 24) > 0);
        assertEquals(0, RingedPlanetLayer.sample(1040, 25) >>> 24);
        assertEquals(0, RingedPlanetLayer.sample(1040, 239) >>> 24);
        assertNotEquals(RingedPlanetLayer.sphere(1010, 100),
                RingedPlanetLayer.sphere(1070, 160), "sphere has directional shading");
    }

    @Test
    void obliqueRingsExtendBeyondSphereAndHaveCorrectFrontBackOcclusion() {
        double angle = StrictMath.toRadians(-15);
        double cos = StrictMath.cos(angle), sin = StrictMath.sin(angle);
        double backX = 1040 + 48 * sin, backY = 132 - 48 * cos;
        double frontX = 1040 - 48 * sin, frontY = 132 + 48 * cos;
        assertTrue((RingedPlanetLayer.ring(backX, backY) >>> 24) > 0);
        assertEquals(RingedPlanetLayer.sphere(backX, backY),
                RingedPlanetLayer.sample(backX, backY), "sphere hides back ring completely");
        assertNotEquals(RingedPlanetLayer.sphere(frontX, frontY),
                RingedPlanetLayer.sample(frontX, frontY), "near ring crosses sphere face");
        assertTrue((RingedPlanetLayer.sample(1040 + 195 * cos, 132 + 195 * sin)
                >>> 24) > 0, "wide tilted ring extends to the right");
        assertTrue((RingedPlanetLayer.sample(1040 - 195 * cos, 132 - 195 * sin)
                >>> 24) > 0, "wide tilted ring extends to the left");
        assertEquals(0, RingedPlanetLayer.sample(1040, 255) >>> 24,
                "no giant circular HUD ring");
    }
}
