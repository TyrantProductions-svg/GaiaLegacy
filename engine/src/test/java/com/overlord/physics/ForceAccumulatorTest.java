package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ForceAccumulatorTest {
    @Test
    void appliedValuesAreCopiedAndAccumulated() {
        ForceAccumulator accumulator = new ForceAccumulator();
        Vector3f force = new Vector3f(1, 2, 3);
        Vector3f impulse = new Vector3f(4, 5, 6);
        Vector3f torque = new Vector3f(7, 8, 9);

        accumulator.applyForce(force);
        accumulator.applyForce(new Vector3f(0.5f, 1, 1.5f));
        accumulator.applyImpulse(impulse);
        accumulator.applyTorque(torque);
        force.set(100, 100, 100);
        impulse.zero();
        torque.negate();

        assertEquals(new Vector3f(1.5f, 3, 4.5f), accumulator.consumeForce(new Vector3f()));
        assertEquals(new Vector3f(4, 5, 6), accumulator.consumeImpulse(new Vector3f()));
        assertEquals(new Vector3f(7, 8, 9), accumulator.consumeTorque(new Vector3f()));
    }

    @Test
    void consumingWritesToDestinationAndClearsOnlyThatAccumulator() {
        ForceAccumulator accumulator = new ForceAccumulator();
        accumulator.applyForce(new Vector3f(1, 2, 3));
        accumulator.applyImpulse(new Vector3f(4, 5, 6));
        accumulator.applyTorque(new Vector3f(7, 8, 9));
        Vector3f destination = new Vector3f();

        assertSame(destination, accumulator.consumeForce(destination));
        assertEquals(new Vector3f(1, 2, 3), destination);
        assertEquals(new Vector3f(), accumulator.consumeForce(new Vector3f()));
        assertEquals(new Vector3f(4, 5, 6), accumulator.consumeImpulse(new Vector3f()));
        assertEquals(new Vector3f(7, 8, 9), accumulator.consumeTorque(new Vector3f()));
    }

    @Test
    void clearDiscardsForceImpulseAndReservedTorque() {
        ForceAccumulator accumulator = new ForceAccumulator();
        accumulator.applyForce(new Vector3f(1, 2, 3));
        accumulator.applyImpulse(new Vector3f(4, 5, 6));
        accumulator.applyTorque(new Vector3f(7, 8, 9));

        accumulator.clear();

        assertEquals(new Vector3f(), accumulator.consumeForce(new Vector3f()));
        assertEquals(new Vector3f(), accumulator.consumeImpulse(new Vector3f()));
        assertEquals(new Vector3f(), accumulator.consumeTorque(new Vector3f()));
    }

    @Test
    void appliedVectorsMustBeNonNullAndFinite() {
        ForceAccumulator accumulator = new ForceAccumulator();

        assertThrows(IllegalArgumentException.class, () -> accumulator.applyForce(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.applyForce(new Vector3f(Float.NaN, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.applyImpulse(new Vector3f(0, Float.POSITIVE_INFINITY, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.applyTorque(new Vector3f(0, 0, Float.NEGATIVE_INFINITY)));
    }
}
