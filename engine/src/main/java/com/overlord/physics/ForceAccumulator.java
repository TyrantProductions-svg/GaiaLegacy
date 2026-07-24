package com.overlord.physics;

import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class ForceAccumulator {
    private final Vector3f force = new Vector3f();
    private final Vector3f impulse = new Vector3f();
    private final Vector3f torque = new Vector3f();

    public void applyForce(Vector3fc value) {
        accumulate(force, value, "force");
    }

    public void applyImpulse(Vector3fc value) {
        accumulate(impulse, value, "impulse");
    }

    public void applyTorque(Vector3fc value) {
        accumulate(torque, value, "torque");
    }

    public Vector3f consumeForce(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        destination.set(force);
        force.zero();
        return destination;
    }

    public Vector3f consumeImpulse(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        destination.set(impulse);
        impulse.zero();
        return destination;
    }

    public Vector3f consumeTorque(Vector3f destination) {
        Objects.requireNonNull(destination, "destination");
        destination.set(torque);
        torque.zero();
        return destination;
    }

    public void clear() {
        force.zero();
        impulse.zero();
        torque.zero();
    }

    private static void accumulate(Vector3f accumulator, Vector3fc value, String label) {
        requireFinite(value, label);
        float candidateX = accumulator.x + value.x();
        float candidateY = accumulator.y + value.y();
        float candidateZ = accumulator.z + value.z();
        if (!Float.isFinite(candidateX)
                || !Float.isFinite(candidateY)
                || !Float.isFinite(candidateZ)) {
            throw new IllegalArgumentException(label + " accumulation must be finite");
        }
        accumulator.set(candidateX, candidateY, candidateZ);
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
