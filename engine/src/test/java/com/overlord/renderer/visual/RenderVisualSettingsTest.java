package com.overlord.renderer.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RenderVisualSettingsTest {
    @Test
    void milestoneDefaultsUseApprovedLightingFogAndGamma() {
        RenderVisualSettings settings = RenderVisualSettings.milestoneOneDefaults();

        assertEquals(0.38f, settings.ambientStrength());
        assertEquals(0.72f, settings.directionalStrength());
        assertEquals(64.0f, settings.fogStart());
        assertEquals(160.0f, settings.fogEnd());
        assertEquals(GammaPath.SHADER_SRGB_DECODE_ENCODE, settings.gammaPath());
        assertEquals(1.0f, settings.sunDirection().length(), 1.0e-6f);
        assertEquals(new LinearColor(0.035f, 0.160f, 0.470f), settings.skyTop());
        assertEquals(new LinearColor(0.350f, 0.570f, 0.780f), settings.skyHorizon());
        assertEquals(settings.skyHorizon(), settings.fogColor());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new LinearColor(Float.NaN, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new LinearColor(1.1f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> settings(new Vector3f(), 0.38f, 0.72f, 64.0f, 160.0f));
        assertThrows(IllegalArgumentException.class, () -> settings(new Vector3f(Float.POSITIVE_INFINITY, 1.0f, 1.0f), 0.38f, 0.72f, 64.0f, 160.0f));
        assertThrows(IllegalArgumentException.class, () -> settings(new Vector3f(1.0f, 0.0f, 0.0f), -0.01f, 0.72f, 64.0f, 160.0f));
        assertThrows(IllegalArgumentException.class, () -> settings(new Vector3f(1.0f, 0.0f, 0.0f), 0.38f, Float.NEGATIVE_INFINITY, 64.0f, 160.0f));
        assertThrows(IllegalArgumentException.class, () -> settings(new Vector3f(1.0f, 0.0f, 0.0f), 0.38f, 0.72f, 64.0f, 64.0f));
    }

    @Test
    void copiesAndNormalizesTheSunDirection() {
        RenderVisualSettings settings = settings(new Vector3f(3.0f, 0.0f, 0.0f), 0.38f, 0.72f, 64.0f, 160.0f);

        Vector3f returnedDirection = settings.sunDirection();
        returnedDirection.set(0.0f, 1.0f, 0.0f);

        assertEquals(new Vector3f(1.0f, 0.0f, 0.0f), settings.sunDirection());
    }

    private static RenderVisualSettings settings(
            Vector3f sunDirection,
            float ambientStrength,
            float directionalStrength,
            float fogStart,
            float fogEnd) {
        LinearColor horizon = new LinearColor(0.350f, 0.570f, 0.780f);
        return new RenderVisualSettings(
                sunDirection,
                ambientStrength,
                directionalStrength,
                new LinearColor(0.035f, 0.160f, 0.470f),
                horizon,
                horizon,
                fogStart,
                fogEnd,
                GammaPath.SHADER_SRGB_DECODE_ENCODE);
    }
}
