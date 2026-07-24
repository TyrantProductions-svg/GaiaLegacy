package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CollisionValueTest {
    private static final Aabb BLOCK_SHAPE = new Aabb(0, 0, 0, 1, 1, 1);

    @Test
    void sweepResultCopiesNormalAndPointIntoProvidedDestinations() {
        SweepResult result = new SweepResult(0.25f, 0, 1, 0, 1.5f, 2.5f, 3.5f, 4, 5, 6, BLOCK_SHAPE);
        Vector3f normal = new Vector3f();
        Vector3f point = new Vector3f();

        assertSame(normal, result.normal(normal));
        assertEquals(new Vector3f(0, 1, 0), normal);
        assertSame(point, result.point(point));
        assertEquals(new Vector3f(1.5f, 2.5f, 3.5f), point);
    }

    @Test
    void sweepResultRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepResult(Float.NaN, 1, 0, 0, 0, 0, 0, 0, 0, 0, BLOCK_SHAPE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepResult(1.1f, 1, 0, 0, 0, 0, 0, 0, 0, 0, BLOCK_SHAPE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepResult(0, 0.5f, 0.5f, 0, 0, 0, 0, 0, 0, 0, BLOCK_SHAPE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepResult(0, 1, 0, 0, Float.POSITIVE_INFINITY, 0, 0, 0, 0, 0, BLOCK_SHAPE));
        assertThrows(
                NullPointerException.class,
                () -> new SweepResult(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, null));
    }

    @Test
    void motionResultCopiesContactsAndWritesVectorsIntoProvidedDestinations() {
        SweepResult contact = new SweepResult(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, BLOCK_SHAPE);
        List<SweepResult> inputContacts = new ArrayList<>(List.of(contact));
        MotionResult result = new MotionResult(1, 2, 3, 4, 5, 6, inputContacts);
        Vector3f position = new Vector3f();
        Vector3f displacement = new Vector3f();

        inputContacts.clear();
        assertEquals(List.of(contact), result.contacts());
        assertThrows(UnsupportedOperationException.class, () -> result.contacts().add(contact));
        assertSame(position, result.position(position));
        assertEquals(new Vector3f(1, 2, 3), position);
        assertSame(displacement, result.appliedDisplacement(displacement));
        assertEquals(new Vector3f(4, 5, 6), displacement);
    }

    @Test
    void motionResultRejectsNonFiniteComponentsAndNullContacts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MotionResult(Float.NEGATIVE_INFINITY, 0, 0, 0, 0, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MotionResult(0, 0, 0, 0, Float.NaN, 0, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new MotionResult(0, 0, 0, 0, 0, 0, null));
    }
}
