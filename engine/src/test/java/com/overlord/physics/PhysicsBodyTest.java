package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PhysicsBodyTest {
    private static final Aabb COLLIDER = new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f);
    private static final MassProperties MASS = MassProperties.dynamic(2);

    @Test
    void interpolationDoesNotMutatePhysicsPositions() {
        PhysicsBody body = bodyAt(0, 0, 0);
        body.beginStep();
        body.setPosition(new Vector3f(2, 0, 0));
        Vector3f destination = new Vector3f();

        Vector3f rendered = body.interpolatedPosition(0.25f, destination);

        assertSame(destination, rendered);
        assertEquals(new Vector3f(0.5f, 0, 0), rendered);
        assertEquals(new Vector3f(2, 0, 0), body.position(new Vector3f()));
        assertEquals(new Vector3f(0, 0, 0), body.previousPosition(new Vector3f()));
        rendered.set(99, 99, 99);
        assertEquals(new Vector3f(2, 0, 0), body.position(new Vector3f()));
        assertEquals(new Vector3f(0, 0, 0), body.previousPosition(new Vector3f()));
    }

    @Test
    void beginStepSnapshotsCurrentPositionWithoutAliasingIt() {
        PhysicsBody body = bodyAt(1, 2, 3);

        body.beginStep();
        body.setPosition(new Vector3f(4, 5, 6));

        assertEquals(new Vector3f(1, 2, 3), body.previousPosition(new Vector3f()));
        assertEquals(new Vector3f(4, 5, 6), body.position(new Vector3f()));
    }

    @Test
    void teleportSetsPreviousAndCurrentPositionsTogether() {
        PhysicsBody body = bodyAt(1, 2, 3);
        body.beginStep();
        body.setPosition(new Vector3f(4, 5, 6));
        Vector3f destination = new Vector3f(7, 8, 9);

        body.teleport(destination);
        destination.zero();

        assertEquals(new Vector3f(7, 8, 9), body.position(new Vector3f()));
        assertEquals(new Vector3f(7, 8, 9), body.previousPosition(new Vector3f()));
        assertEquals(new Vector3f(7, 8, 9), body.interpolatedPosition(0.5f, new Vector3f()));
    }

    @Test
    void vectorSettersAndGettersDoNotExposeMutableAliases() {
        PhysicsBody body = bodyAt(0, 0, 0);
        Vector3f position = new Vector3f(1, 2, 3);
        Vector3f linearVelocity = new Vector3f(4, 5, 6);
        Vector3f angularVelocity = new Vector3f(7, 8, 9);

        body.setPosition(position);
        body.setLinearVelocity(linearVelocity);
        body.setAngularVelocity(angularVelocity);
        position.zero();
        linearVelocity.zero();
        angularVelocity.zero();

        Vector3f positionCopy = body.position(new Vector3f());
        Vector3f linearCopy = body.linearVelocity(new Vector3f());
        Vector3f angularCopy = body.angularVelocity(new Vector3f());
        assertEquals(new Vector3f(1, 2, 3), positionCopy);
        assertEquals(new Vector3f(4, 5, 6), linearCopy);
        assertEquals(new Vector3f(7, 8, 9), angularCopy);

        positionCopy.zero();
        linearCopy.zero();
        angularCopy.zero();
        assertEquals(new Vector3f(1, 2, 3), body.position(new Vector3f()));
        assertEquals(new Vector3f(4, 5, 6), body.linearVelocity(new Vector3f()));
        assertEquals(new Vector3f(7, 8, 9), body.angularVelocity(new Vector3f()));
    }

    @Test
    void bodyRetainsValidatedConstructionDependenciesAndOwnsForces() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertSame(COLLIDER, body.collider());
        assertSame(MASS, body.massProperties());
        assertSame(body.forces(), body.forces());
        body.forces().applyForce(new Vector3f(1, 2, 3));
        assertEquals(new Vector3f(1, 2, 3), body.forces().consumeForce(new Vector3f()));
        assertThrows(NullPointerException.class, () -> new PhysicsBody(null, MASS));
        assertThrows(NullPointerException.class, () -> new PhysicsBody(COLLIDER, null));
    }

    @Test
    void coefficientsHaveSafeDefaultsAndAcceptValidValues() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertEquals(1.0f, body.gravityScale());
        assertEquals(0.0f, body.restitution());
        assertEquals(0.0f, body.friction());

        body.setGravityScale(-2.5f);
        body.setRestitution(1.0f);
        body.setFriction(0.75f);

        assertEquals(-2.5f, body.gravityScale());
        assertEquals(1.0f, body.restitution());
        assertEquals(0.75f, body.friction());
        body.setRestitution(0.0f);
        body.setFriction(1.0f);
        assertEquals(0.0f, body.restitution());
        assertEquals(1.0f, body.friction());
    }

    @Test
    void activeAndSleepingFlagsAreIndependentValidatedBodyState() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertTrue(body.isActive());
        assertFalse(body.isSleeping());

        body.setActive(false);
        body.setSleeping(true);

        assertFalse(body.isActive());
        assertTrue(body.isSleeping());
        body.setActive(true);
        body.setSleeping(false);
        assertTrue(body.isActive());
        assertFalse(body.isSleeping());
    }

    @Test
    void vectorInputsMustBeNonNullAndFinite() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertThrows(IllegalArgumentException.class, () -> body.teleport(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.teleport(new Vector3f(Float.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> body.setPosition(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.setPosition(new Vector3f(0, Float.POSITIVE_INFINITY, 0)));
        assertThrows(IllegalArgumentException.class, () -> body.setLinearVelocity(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.setLinearVelocity(new Vector3f(0, 0, Float.NEGATIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> body.setAngularVelocity(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.setAngularVelocity(new Vector3f(Float.POSITIVE_INFINITY, 0, 0)));
    }

    @Test
    void interpolationAlphaMustBeFiniteAndWithinUnitInterval() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertThrows(
                IllegalArgumentException.class,
                () -> body.interpolatedPosition(Float.NaN, new Vector3f()));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.interpolatedPosition(Float.POSITIVE_INFINITY, new Vector3f()));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.interpolatedPosition(-0.001f, new Vector3f()));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.interpolatedPosition(1.001f, new Vector3f()));
        assertEquals(new Vector3f(), body.interpolatedPosition(0.0f, new Vector3f()));
        assertEquals(new Vector3f(), body.interpolatedPosition(1.0f, new Vector3f()));
    }

    @Test
    void coefficientInputsMustBeFiniteAndUnitCoefficientsMustBeInRange() {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);

        assertThrows(IllegalArgumentException.class, () -> body.setGravityScale(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> body.setGravityScale(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> body.setRestitution(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> body.setRestitution(-0.001f));
        assertThrows(IllegalArgumentException.class, () -> body.setRestitution(1.001f));
        assertThrows(IllegalArgumentException.class, () -> body.setFriction(Float.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> body.setFriction(-0.001f));
        assertThrows(IllegalArgumentException.class, () -> body.setFriction(1.001f));
    }

    private static PhysicsBody bodyAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(COLLIDER, MASS);
        body.teleport(new Vector3f(x, y, z));
        return body;
    }
}
