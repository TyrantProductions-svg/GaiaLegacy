package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MassPropertiesTest {
    @Test
    void dynamicMassDerivesInverseAndStaticUsesZeroSentinel() {
        MassProperties dynamic = MassProperties.dynamic(4);
        MassProperties staticBody = MassProperties.staticBody();

        assertEquals(4.0f, dynamic.mass());
        assertEquals(0.25f, dynamic.inverseMass());
        assertFalse(dynamic.isStatic());
        assertEquals(0.0f, staticBody.mass());
        assertEquals(0.0f, staticBody.inverseMass());
        assertTrue(staticBody.isStatic());
    }

    @Test
    void dynamicMassMustBeFiniteAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> MassProperties.dynamic(0));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.dynamic(-1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.dynamic(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> MassProperties.dynamic(Float.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> MassProperties.dynamic(Float.NEGATIVE_INFINITY));
    }
}
