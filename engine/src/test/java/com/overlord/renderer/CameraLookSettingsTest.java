package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CameraLookSettingsTest {
    @Test
    void sensitivityAcceptsInclusiveBoundsAndRejectsOutOfRangeOrNonFiniteValues() {
        Camera camera = new Camera();

        assertDoesNotThrow(() -> camera.setLookSettings(0.02f, false));
        assertDoesNotThrow(() -> camera.setLookSettings(0.50f, true));

        for (float invalid : new float[] {
            0.019f,
            0.501f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> camera.setLookSettings(invalid, false),
                    () -> "Expected invalid sensitivity to be rejected: " + invalid);
        }
    }

    @Test
    void configuredSensitivityScalesBothLookAxes() {
        Camera lowSensitivity = new Camera();
        lowSensitivity.setLookSettings(0.02f, false);
        lowSensitivity.processMouseMovement(10.0f, 10.0f);

        Camera highSensitivity = new Camera();
        highSensitivity.setLookSettings(0.50f, false);
        highSensitivity.processMouseMovement(10.0f, 10.0f);

        assertEquals(-89.8f, lowSensitivity.getYaw(), 1.0e-5f);
        assertEquals(0.2f, lowSensitivity.getPitch(), 1.0e-5f);
        assertEquals(-85.0f, highSensitivity.getYaw(), 1.0e-5f);
        assertEquals(5.0f, highSensitivity.getPitch(), 1.0e-5f);
    }

    @Test
    void invertYChangesOnlyThePitchDirection() {
        Camera normal = new Camera();
        normal.setLookSettings(0.10f, false);
        normal.processMouseMovement(10.0f, 10.0f);

        Camera inverted = new Camera();
        inverted.setLookSettings(0.10f, true);
        inverted.processMouseMovement(10.0f, 10.0f);

        assertEquals(normal.getYaw(), inverted.getYaw(), 0.0f);
        assertEquals(normal.getPitch(), -inverted.getPitch(), 0.0f);
        assertEquals(1.0f, normal.getPitch(), 1.0e-6f);
    }
}
