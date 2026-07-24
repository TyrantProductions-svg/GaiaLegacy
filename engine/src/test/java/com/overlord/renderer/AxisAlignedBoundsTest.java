package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AxisAlignedBoundsTest {
    @Test
    void translatesBoundsWithoutChangingExtent() {
        AxisAlignedBounds local =
                new AxisAlignedBounds(0, 2, 1, 16, 9, 16);

        assertEquals(
                new AxisAlignedBounds(32, 2, -15, 48, 9, 0),
                local.translate(32, 0, -16));
    }

    @Test
    void acceptsZeroExtentBounds() {
        assertEquals(
                new AxisAlignedBounds(1, 2, 3, 1, 2, 3),
                new AxisAlignedBounds(1, 2, 3, 1, 2, 3));
    }

    @Test
    void rejectsNonFiniteComponents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AxisAlignedBounds(Float.NaN, 0, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AxisAlignedBounds(0, 0, 0, Float.POSITIVE_INFINITY, 1, 1));
    }

    @Test
    void rejectsMaximumBelowMinimum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AxisAlignedBounds(2, 0, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AxisAlignedBounds(0, 2, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AxisAlignedBounds(0, 0, 2, 1, 1, 1));
    }
}
