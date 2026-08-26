package com.overlord.physics;

import com.overlord.voxel.ChunkKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class PhysicsWorld {
    private static final int MAX_COLLISION_ITERATIONS = 4;

    private final CollisionWorld collisionWorld;
    private final Vector3f gravity;
    private final Set<PhysicsBody> bodies = new LinkedHashSet<>();
    private SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));
    private boolean originAware;

    public PhysicsWorld(
            CollisionWorld collisionWorld,
            Vector3fc gravity) {
        this.collisionWorld =
                Objects.requireNonNull(collisionWorld, "collisionWorld");
        requireFinite(gravity, "gravity");
        this.gravity = new Vector3f(gravity);
    }

    public boolean addBody(PhysicsBody body) {
        return bodies.add(Objects.requireNonNull(body, "body"));
    }

    public boolean removeBody(PhysicsBody body) {
        return bodies.remove(Objects.requireNonNull(body, "body"));
    }

    /** Returns whether this exact body instance is currently registered. */
    public boolean containsBody(PhysicsBody body) {
        Objects.requireNonNull(body, "body");
        for (PhysicsBody registered : bodies) {
            if (registered == body) {
                return true;
            }
        }
        return false;
    }

    public List<PhysicsBody> bodies() {
        return List.copyOf(bodies);
    }

    /** Returns the static collision world used by this physics world. */
    public CollisionWorld collisionWorld() {
        return collisionWorld;
    }

    /** Prepares publication of the collision-query origin without moving bodies. */
    public PreparedOriginRebase prepareOriginRebase(
            SimulationOrigin oldOrigin, SimulationOrigin nextOrigin) {
        Objects.requireNonNull(oldOrigin, "oldOrigin");
        Objects.requireNonNull(nextOrigin, "nextOrigin");
        if (!simulationOrigin.equals(oldOrigin)) {
            throw new IllegalStateException("old origin does not match PhysicsWorld");
        }
        return () -> {
            simulationOrigin = nextOrigin;
            originAware = true;
        };
    }

    public void step(float fixedDeltaSeconds) {
        if (!Float.isFinite(fixedDeltaSeconds)
                || fixedDeltaSeconds <= 0) {
            throw new IllegalArgumentException(
                    "fixedDeltaSeconds must be finite and positive");
        }

        try {
            for (PhysicsBody body : bodies) {
                if (body.isActive()
                        && !body.isSleeping()
                        && !body.massProperties().isStatic()) {
                    integrate(body, fixedDeltaSeconds);
                }
            }
        } finally {
            for (PhysicsBody body : bodies) {
                body.forces().clear();
            }
        }
    }

    private void integrate(PhysicsBody body, float fixedDeltaSeconds) {
        body.beginStep();

        float inverseMass = body.massProperties().inverseMass();
        Vector3f velocity = body.linearVelocity(new Vector3f());
        Vector3f force = body.forces().consumeForce(new Vector3f());
        Vector3f impulse = body.forces().consumeImpulse(new Vector3f());
        velocity.fma(inverseMass * fixedDeltaSeconds, force);
        velocity.fma(body.gravityScale() * fixedDeltaSeconds, gravity);
        velocity.fma(inverseMass, impulse);
        if (velocity.y < body.maximumFallSpeed()) {
            velocity.y = body.maximumFallSpeed();
        }
        requireFinite(velocity, "integrated velocity");

        Vector3f position = body.position(new Vector3f());
        Vector3f displacement =
                new Vector3f(velocity).mul(fixedDeltaSeconds);
        requireFinite(displacement, "integrated displacement");
        requireFinite(
                new Vector3f(position).add(displacement),
                "integrated position");

        MotionResult motion;
        if (originAware) {
            SpatialQueryResult<MotionResult> query = collisionWorld.moveAndSlide(
                    simulationOrigin,
                    body.collider(),
                    position,
                    displacement,
                    MAX_COLLISION_ITERATIONS);
            if (query.status() != SpatialQueryResult.Status.AVAILABLE) {
                body.setPosition(position);
                body.setLinearVelocity(new Vector3f());
                return;
            }
            motion = query.result().orElseThrow();
        } else {
            motion = collisionWorld.moveAndSlide(
                    body.collider(),
                    position,
                    displacement,
                    MAX_COLLISION_ITERATIONS);
        }
        applyContactResponse(body, velocity, motion);
        requireFinite(velocity, "collision velocity");

        body.setPosition(motion.position(new Vector3f()));
        body.setLinearVelocity(velocity);
    }

    private static void applyContactResponse(
            PhysicsBody body,
            Vector3f velocity,
            MotionResult motion) {
        Vector3f normal = new Vector3f();
        Vector3f tangent = new Vector3f();
        for (SweepResult contact : motion.contacts()) {
            contact.normal(normal);
            float inwardSpeed = velocity.dot(normal);
            if (inwardSpeed >= 0) {
                continue;
            }

            velocity.fma(
                    -(1 + body.restitution()) * inwardSpeed,
                    normal);
            float outwardSpeed = velocity.dot(normal);
            tangent.set(velocity).fma(-outwardSpeed, normal);
            velocity.fma(-body.friction(), tangent);
        }
    }

    private static void requireFinite(Vector3fc value, String label) {
        if (value == null
                || !Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }

    @FunctionalInterface
    public interface PreparedOriginRebase {
        void commit();
    }
}
