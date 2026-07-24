package com.overlord.physics;

import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class ForceAccumulator {
    private final Vector3f force = new Vector3f();
    private final Vector3f impulse = new Vector3f();
    private final Vector3f torque = new Vector3f();

    public void applyForce(Vector3fc value) {
        requireFinite(value, "force");
        force.add(value);
    }

    public void applyImpulse(Vector3fc value) {
        requireFinite(value, "impulse");
        impulse.add(value);
    }

    public void applyTorque(Vector3fc value) {
        requireFinite(value, "torque");
        torque.add(value);
    }

    public Vector3f consumeForce(Vector3f destination) {
        destination.set(force);
        force.zero();
        return destination;
    }

    public Vector3f consumeImpulse(Vector3f destination) {
        destination.set(impulse);
        impulse.zero();
        return destination;
    }

    public Vector3f consumeTorque(Vector3f destination) {
        destination.set(torque);
        torque.zero();
        return destination;
    }

    public void clear() {
        force.zero();
        impulse.zero();
        torque.zero();
    }

    private static void requireFinite(Vector3fc value, String label) {
        if (value == null
                || !Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
