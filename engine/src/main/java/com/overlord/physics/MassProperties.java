package com.overlord.physics;

public final class MassProperties {
    private final float mass;
    private final float inverseMass;

    private MassProperties(float mass, float inverseMass) {
        this.mass = mass;
        this.inverseMass = inverseMass;
    }

    public static MassProperties dynamic(float mass) {
        if (!Float.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException("dynamic mass must be finite and positive");
        }
        float inverseMass = 1.0f / mass;
        if (!Float.isFinite(inverseMass) || inverseMass <= 0) {
            throw new IllegalArgumentException(
                    "dynamic mass must produce a finite positive inverse mass");
        }
        return new MassProperties(mass, inverseMass);
    }

    public static MassProperties staticBody() {
        return new MassProperties(0, 0);
    }

    public float mass() {
        return mass;
    }

    public float inverseMass() {
        return inverseMass;
    }

    public boolean isStatic() {
        return inverseMass == 0;
    }
}
