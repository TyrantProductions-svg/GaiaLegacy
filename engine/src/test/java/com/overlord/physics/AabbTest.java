package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class AabbTest {
    @Test
    void touchingFacesDoNotIntersectButPositiveVolumeDoes() {
        Aabb left = new Aabb(0, 0, 0, 1, 1, 1);

        assertFalse(left.intersects(new Aabb(1, 0, 0, 2, 1, 1)));
        assertTrue(left.intersects(new Aabb(0.999f, 0, 0, 2, 1, 1)));
    }

    @Test
    void sweptBoundsCoverStartAndEnd() {
        Aabb start = new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);

        assertEquals(
                new Aabb(-0.3f, -4, -0.3f, 2.3f, 1.8f, 0.3f),
                start.sweptBounds(new Vector3f(2, -4, 0)));
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Aabb(Float.NaN, 0, 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Aabb(2, 0, 0, 1, 1, 1));
    }

    @Test
    void translatedCreatesOffsetBoundsWithoutMutatingOriginal() {
        Aabb bounds = new Aabb(0, 1, 2, 3, 4, 5);

        assertEquals(new Aabb(-2, 4, 2.5f, 1, 7, 5.5f), bounds.translated(new Vector3f(-2, 3, 0.5f)));
        assertEquals(new Aabb(0, 1, 2, 3, 4, 5), bounds);
    }

    @Test
    void overlapDepthIsClampedPerAxis() {
        Aabb bounds = new Aabb(0, 0, 0, 2, 2, 2);
        Aabb overlap = new Aabb(1.5f, -1, 0.5f, 3, 1, 3);
        Aabb separate = new Aabb(2, 0, 0, 3, 1, 1);

        assertEquals(0.5f, bounds.overlapX(overlap));
        assertEquals(1.0f, bounds.overlapY(overlap));
        assertEquals(1.5f, bounds.overlapZ(overlap));
        assertEquals(0.0f, bounds.overlapX(separate));
    }
}
