package com.gaia.worlditem;

import com.gaia.inventory.InventoryDropLocation;
import com.overlord.interaction.api.BlockHitResult;
import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Pure deterministic calculators for canonical world-item spawn motion. */
public final class WorldItemDropKinematics {
    public static final float Q_FORWARD_OFFSET = 0.40f;
    public static final float Q_FORWARD_SPEED = 4.5f;
    public static final float Q_UPWARD_SPEED = 1.25f;
    public static final float Q_MAX_LATERAL_SPEED = 0.15f;
    public static final float BLOCK_MIN_OUTWARD_SPEED = 1.25f;
    public static final float BLOCK_MAX_OUTWARD_SPEED = 1.75f;
    public static final float BLOCK_UPWARD_SPEED = 1.40f;
    public static final float BLOCK_MAX_LATERAL_SPEED = 0.20f;

    private static final long LATERAL_SALT = 0xD1B54A32D192ED03L;
    private static final long SPEED_SALT = 0x94D049BB133111EBL;

    private WorldItemDropKinematics() {}

    public static InventoryDropLocation qDrop(
            Vector3fc eye,
            Vector3fc forward,
            Vector3fc right,
            long eventIdentity) {
        Vector3f eyeCopy = finiteCopy(eye, "eye");
        Vector3f forwardCopy = normalizedOrDeterministic(
                finiteCopy(forward, "forward"), eventIdentity);
        Vector3f rightCopy = finiteCopy(right, "right");
        rightCopy.sub(new Vector3f(forwardCopy).mul(rightCopy.dot(forwardCopy)));
        if (rightCopy.lengthSquared() < 1.0e-12f) {
            rightCopy.set(-forwardCopy.z, 0.0f, forwardCopy.x);
        }
        if (rightCopy.lengthSquared() < 1.0e-12f) {
            rightCopy.set(1.0f, 0.0f, 0.0f);
        }
        rightCopy.normalize();

        float lateral = signedUnit(mix64(eventIdentity ^ LATERAL_SALT))
                * Q_MAX_LATERAL_SPEED;
        Vector3f position = new Vector3f(forwardCopy)
                .mul(Q_FORWARD_OFFSET)
                .add(eyeCopy);
        Vector3f velocity = new Vector3f(forwardCopy)
                .mul(Q_FORWARD_SPEED)
                .add(0.0f, Q_UPWARD_SPEED, 0.0f)
                .fma(lateral, rightCopy);
        return new InventoryDropLocation(
                position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z);
    }

    public static InventoryDropLocation blockDrop(
            BlockHitResult hit,
            Vector3fc playerPosition,
            long eventIdentity) {
        Objects.requireNonNull(hit, "hit");
        Vector3f player = finiteCopy(playerPosition, "playerPosition");
        Vector3f center = new Vector3f(
                hit.blockX() + 0.5f,
                hit.blockY() + 0.5f,
                hit.blockZ() + 0.5f);
        Vector3f away = new Vector3f(
                center.x - player.x, 0.0f, center.z - player.z);
        away = normalizedOrDeterministic(away, eventIdentity);
        away.y = 0.0f;
        if (away.lengthSquared() < 1.0e-12f) {
            away.set(1.0f, 0.0f, 0.0f);
        } else {
            away.normalize();
        }
        Vector3f lateralAxis = new Vector3f(-away.z, 0.0f, away.x);
        float speedUnit = unit(mix64(eventIdentity ^ SPEED_SALT));
        float outwardSpeed = BLOCK_MIN_OUTWARD_SPEED
                + speedUnit * (BLOCK_MAX_OUTWARD_SPEED - BLOCK_MIN_OUTWARD_SPEED);
        float lateralSpeed = signedUnit(mix64(eventIdentity ^ LATERAL_SALT))
                * BLOCK_MAX_LATERAL_SPEED;
        Vector3f velocity = new Vector3f(away)
                .mul(outwardSpeed)
                .fma(lateralSpeed, lateralAxis)
                .add(0.0f, BLOCK_UPWARD_SPEED, 0.0f);
        return new InventoryDropLocation(
                center.x, center.y, center.z,
                velocity.x, velocity.y, velocity.z);
    }

    private static Vector3f finiteCopy(Vector3fc value, String name) {
        Vector3fc vector = Objects.requireNonNull(value, name);
        if (!Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Vector3f(vector);
    }

    private static Vector3f normalizedOrDeterministic(Vector3f value, long seed) {
        if (value.lengthSquared() >= 1.0e-12f) {
            return value.normalize();
        }
        double angle = unit(mix64(seed)) * Math.PI * 2.0;
        return value.set((float) Math.cos(angle), 0.0f, (float) Math.sin(angle));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static float unit(long mixed) {
        return (float) ((mixed >>> 40) * 0x1.0p-24);
    }

    private static float signedUnit(long mixed) {
        return unit(mixed) * 2.0f - 1.0f;
    }
}
