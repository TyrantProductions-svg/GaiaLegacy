package com.overlord.physics;

import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class PhysicsBody {
    private final Aabb collider;
    private final MassProperties massProperties;
    private final ForceAccumulator forces = new ForceAccumulator();
    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f position = new Vector3f();
    private final Vector3f linearVelocity = new Vector3f();
    private final Vector3f angularVelocity = new Vector3f();

    private float gravityScale = 1.0f;
    private float restitution;
    private float friction;
    private boolean active = true;
    private boolean sleeping;

    public PhysicsBody(Aabb collider, MassProperties massProperties) {
        this.collider = Objects.requireNonNull(collider, "collider");
        this.massProperties = Objects.requireNonNull(massProperties, "massProperties");
    }

    public void beginStep() {
        previousPosition.set(position);
    }

    public void teleport(Vector3fc position) {
        requireFinite(position, "position");
        previousPosition.set(position);
        this.position.set(position);
    }

    public Vector3f position(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        return destination.set(position);
    }

    public Vector3f previousPosition(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        return destination.set(previousPosition);
    }

    public void setPosition(Vector3fc position) {
        requireFinite(position, "position");
        this.position.set(position);
    }

    public Vector3f interpolatedPosition(float alpha, Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        return destination.set(previousPosition).lerp(position, alpha);
    }

    public Vector3f linearVelocity(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        return destination.set(linearVelocity);
    }

    public void setLinearVelocity(Vector3fc velocity) {
        requireFinite(velocity, "linear velocity");
        linearVelocity.set(velocity);
    }

    public Vector3f angularVelocity(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        return destination.set(angularVelocity);
    }

    public void setAngularVelocity(Vector3fc velocity) {
        requireFinite(velocity, "angular velocity");
        angularVelocity.set(velocity);
    }

    public ForceAccumulator forces() {
        return forces;
    }

    public Aabb collider() {
        return collider;
    }

    public MassProperties massProperties() {
        return massProperties;
    }

    public float gravityScale() {
        return gravityScale;
    }

    public void setGravityScale(float gravityScale) {
        requireFinite(gravityScale, "gravity scale");
        this.gravityScale = gravityScale;
    }

    public float restitution() {
        return restitution;
    }

    public void setRestitution(float restitution) {
        requireUnitInterval(restitution, "restitution");
        this.restitution = restitution;
    }

    public float friction() {
        return friction;
    }

    public void setFriction(float friction) {
        requireUnitInterval(friction, "friction");
        this.friction = friction;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public void setSleeping(boolean sleeping) {
        this.sleeping = sleeping;
    }

    private static void requireFinite(Vector3fc value, String label) {
        if (value == null
                || !Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }

    private static void requireUnitInterval(float value, String label) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be finite and in [0, 1]");
        }
    }
}
