package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.World;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PhysicsWorldTest {
    private static final float EPSILON = 1.0e-4f;
    private static final Aabb BODY_COLLIDER =
            new Aabb(-0.25f, 0, -0.25f, 0.25f, 1, 0.25f);

    @Test
    void forceAndImpulseUseSemiImplicitEuler() {
        PhysicsBody body = dynamicBodyAt(0, 10, 0, 2.0f);
        body.forces().applyForce(new Vector3f(4, 0, 0));
        body.forces().applyImpulse(new Vector3f(0, 2, 0));
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f(0, -10, 0));
        physics.addBody(body);

        physics.step(0.5f);

        assertVector(body.linearVelocity(new Vector3f()), 1, -4, 0);
        assertVector(body.position(new Vector3f()), 0.5f, 8, 0);
        assertVector(body.previousPosition(new Vector3f()), 0, 10, 0);
    }

    @Test
    void fallingBodyStopsOnVoxelGround() {
        PhysicsBody body = dynamicBodyAt(0.5f, 5, 0.5f, 1);
        PhysicsWorld physics =
                new PhysicsWorld(
                        collisionsWithBlocks(new int[][] {{0, 0, 0}}),
                        new Vector3f(0, -10, 0));
        physics.addBody(body);

        for (int step = 0; step < 180; step++) {
            physics.step(1.0f / 60.0f);
        }

        assertEquals(
                1.0f,
                body.position(new Vector3f()).y,
                GameConfig.Physics.COLLISION_TOLERANCE + EPSILON);
        assertEquals(0.0f, body.linearVelocity(new Vector3f()).y, EPSILON);
    }

    @Test
    void highSpeedBodySweepsWithoutTunnelingThroughGround() {
        PhysicsBody body = dynamicBodyAt(0.5f, 20, 0.5f, 1);
        body.setLinearVelocity(new Vector3f(0, -200, 0));
        PhysicsWorld physics =
                new PhysicsWorld(
                        collisionsWithBlocks(new int[][] {{0, 0, 0}}),
                        new Vector3f());
        physics.addBody(body);

        physics.step(0.25f);

        assertEquals(1.0f, body.position(new Vector3f()).y, 1.0e-3f);
        assertEquals(0.0f, body.linearVelocity(new Vector3f()).y, EPSILON);
    }

    @Test
    void orderedContactsApplyRestitutionAndFrictionToNormalAndTangentVelocity() {
        PhysicsBody body = dynamicBodyAt(1.5f, 2, 0.5f, 1);
        body.setLinearVelocity(new Vector3f(4, -4, 0));
        body.setRestitution(0.5f);
        body.setFriction(0.25f);
        PhysicsWorld physics =
                new PhysicsWorld(
                        collisionsWithBlocks(
                                new int[][] {
                                    {2, 1, 0},
                                    {1, 0, 0}
                                }),
                        new Vector3f());
        physics.addBody(body);

        physics.step(0.5f);

        assertVector(body.linearVelocity(new Vector3f()), -1.5f, 1.5f, 0);
    }

    @Test
    void staticInactiveAndSleepingBodiesDoNotAdvanceOrSnapshot() {
        PhysicsBody staticBody = staticBodyWithSeparatedState(1);
        PhysicsBody inactiveBody = dynamicBodyWithSeparatedState(2);
        inactiveBody.setActive(false);
        PhysicsBody sleepingBody = dynamicBodyWithSeparatedState(3);
        sleepingBody.setSleeping(true);
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f(0, -10, 0));
        physics.addBody(staticBody);
        physics.addBody(inactiveBody);
        physics.addBody(sleepingBody);

        physics.step(0.5f);

        assertSkipped(staticBody, 1, 2);
        assertSkipped(inactiveBody, 2, 3);
        assertSkipped(sleepingBody, 3, 4);
    }

    @Test
    void everyWorldStepClearsAllRegisteredBodyAccumulators() {
        PhysicsBody dynamicBody = dynamicBodyAt(0, 0, 0, 1);
        PhysicsBody staticBody =
                new PhysicsBody(BODY_COLLIDER, MassProperties.staticBody());
        PhysicsBody inactiveBody = dynamicBodyAt(0, 0, 0, 1);
        inactiveBody.setActive(false);
        PhysicsBody sleepingBody = dynamicBodyAt(0, 0, 0, 1);
        sleepingBody.setSleeping(true);
        List<PhysicsBody> bodies =
                List.of(dynamicBody, staticBody, inactiveBody, sleepingBody);
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());
        for (PhysicsBody body : bodies) {
            body.forces().applyForce(new Vector3f(1, 2, 3));
            body.forces().applyImpulse(new Vector3f(4, 5, 6));
            body.forces().applyTorque(new Vector3f(7, 8, 9));
            physics.addBody(body);
        }

        physics.step(0.25f);

        for (PhysicsBody body : bodies) {
            assertEquals(
                    new Vector3f(),
                    body.forces().consumeForce(new Vector3f()));
            assertEquals(
                    new Vector3f(),
                    body.forces().consumeImpulse(new Vector3f()));
            assertEquals(
                    new Vector3f(),
                    body.forces().consumeTorque(new Vector3f()));
        }
    }

    @Test
    void duplicateRegistrationIntegratesBodyOnlyOnce() {
        PhysicsBody body = dynamicBodyAt(0, 0, 0, 1);
        body.setLinearVelocity(new Vector3f(2, 0, 0));
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());

        assertTrue(physics.addBody(body));
        assertFalse(physics.addBody(body));
        physics.step(0.5f);

        assertVector(body.position(new Vector3f()), 1, 0, 0);
        assertEquals(List.of(body), physics.bodies());
    }

    @Test
    void bodiesAreAnImmutableInsertionOrderedSnapshot() {
        PhysicsBody first = dynamicBodyAt(0, 0, 0, 1);
        PhysicsBody second = dynamicBodyAt(0, 0, 0, 1);
        PhysicsBody third = dynamicBodyAt(0, 0, 0, 1);
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());
        physics.addBody(first);
        physics.addBody(second);
        List<PhysicsBody> snapshot = physics.bodies();

        assertEquals(List.of(first, second), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(third));
        assertTrue(physics.removeBody(first));
        assertTrue(physics.addBody(first));
        assertTrue(physics.addBody(third));

        assertEquals(List.of(second, first, third), physics.bodies());
        assertEquals(List.of(first, second), snapshot);
    }

    @Test
    void removedAndNeverRegisteredBodiesAreNotIntegratedOrCleared() {
        PhysicsBody removed = dynamicBodyAt(0, 0, 0, 1);
        PhysicsBody neverRegistered = dynamicBodyAt(0, 0, 0, 1);
        removed.setLinearVelocity(new Vector3f(2, 0, 0));
        neverRegistered.setLinearVelocity(new Vector3f(2, 0, 0));
        removed.forces().applyForce(new Vector3f(1, 0, 0));
        neverRegistered.forces().applyForce(new Vector3f(1, 0, 0));
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());
        physics.addBody(removed);

        assertTrue(physics.removeBody(removed));
        assertFalse(physics.removeBody(removed));
        physics.step(0.5f);

        assertVector(removed.position(new Vector3f()), 0, 0, 0);
        assertVector(neverRegistered.position(new Vector3f()), 0, 0, 0);
        assertVector(
                removed.forces().consumeForce(new Vector3f()), 1, 0, 0);
        assertVector(
                neverRegistered.forces().consumeForce(new Vector3f()), 1, 0, 0);
    }

    @Test
    void bodiesDoNotCollideWithEachOther() {
        PhysicsBody fromLeft = dynamicBodyAt(-1, 0, 0, 1);
        PhysicsBody fromRight = dynamicBodyAt(1, 0, 0, 1);
        fromLeft.setLinearVelocity(new Vector3f(2, 0, 0));
        fromRight.setLinearVelocity(new Vector3f(-2, 0, 0));
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());
        physics.addBody(fromLeft);
        physics.addBody(fromRight);

        physics.step(0.5f);

        assertVector(fromLeft.position(new Vector3f()), 0, 0, 0);
        assertVector(fromRight.position(new Vector3f()), 0, 0, 0);
        assertVector(fromLeft.linearVelocity(new Vector3f()), 2, 0, 0);
        assertVector(fromRight.linearVelocity(new Vector3f()), -2, 0, 0);
    }

    @Test
    void constructorRegistrationAndStepRejectInvalidInputs() {
        CollisionWorld collisions = emptyCollisions();

        assertThrows(
                NullPointerException.class,
                () -> new PhysicsWorld(null, new Vector3f()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PhysicsWorld(collisions, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PhysicsWorld(
                                collisions,
                                new Vector3f(Float.NaN, 0, 0)));

        PhysicsWorld physics =
                new PhysicsWorld(collisions, new Vector3f());
        assertThrows(NullPointerException.class, () -> physics.addBody(null));
        assertThrows(NullPointerException.class, () -> physics.removeBody(null));
        assertThrows(IllegalArgumentException.class, () -> physics.step(0));
        assertThrows(IllegalArgumentException.class, () -> physics.step(-0.1f));
        assertThrows(
                IllegalArgumentException.class,
                () -> physics.step(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> physics.step(Float.POSITIVE_INFINITY));
    }

    @Test
    void nonFiniteIntegrationCandidateIsRejectedWithoutCorruptingBodyState() {
        PhysicsBody body = dynamicBodyAt(1, 2, 3, 1);
        body.setLinearVelocity(new Vector3f(Float.MAX_VALUE, 0, 0));
        body.forces().applyImpulse(new Vector3f(1, 0, 0));
        PhysicsWorld physics =
                new PhysicsWorld(emptyCollisions(), new Vector3f());
        physics.addBody(body);

        assertThrows(IllegalArgumentException.class, () -> physics.step(2));

        assertVector(body.position(new Vector3f()), 1, 2, 3);
        assertTrue(body.linearVelocity(new Vector3f()).isFinite());
        assertEquals(
                new Vector3f(),
                body.forces().consumeImpulse(new Vector3f()));
    }

    private static PhysicsBody dynamicBodyAt(
            float x, float y, float z, float mass) {
        PhysicsBody body =
                new PhysicsBody(
                        BODY_COLLIDER, MassProperties.dynamic(mass));
        body.teleport(new Vector3f(x, y, z));
        return body;
    }

    private static PhysicsBody staticBodyWithSeparatedState(float previousX) {
        PhysicsBody body =
                new PhysicsBody(
                        BODY_COLLIDER, MassProperties.staticBody());
        separatePreviousAndCurrentState(body, previousX);
        return body;
    }

    private static PhysicsBody dynamicBodyWithSeparatedState(float previousX) {
        PhysicsBody body = dynamicBodyAt(previousX, 0, 0, 1);
        separatePreviousAndCurrentState(body, previousX);
        return body;
    }

    private static void separatePreviousAndCurrentState(
            PhysicsBody body, float previousX) {
        body.teleport(new Vector3f(previousX, 0, 0));
        body.beginStep();
        body.setPosition(new Vector3f(previousX + 1, 0, 0));
        body.setLinearVelocity(new Vector3f(10, 0, 0));
        body.forces().applyForce(new Vector3f(10, 0, 0));
        body.forces().applyImpulse(new Vector3f(10, 0, 0));
    }

    private static void assertSkipped(
            PhysicsBody body, float previousX, float currentX) {
        assertVector(body.previousPosition(new Vector3f()), previousX, 0, 0);
        assertVector(body.position(new Vector3f()), currentX, 0, 0);
        assertVector(body.linearVelocity(new Vector3f()), 10, 0, 0);
    }

    private static CollisionWorld emptyCollisions() {
        return new CollisionWorld(
                new World(),
                BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static CollisionWorld collisionsWithBlocks(int[][] blocks) {
        World world = new World();
        for (int[] block : blocks) {
            assertTrue(
                    world.setBlock(
                            block[0], block[1], block[2], (byte) 1));
        }
        return new CollisionWorld(
                world,
                BlockCollisionShapeResolver.fullCubesForNonAir());
    }

    private static void assertVector(
            Vector3f actual, float x, float y, float z) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}
