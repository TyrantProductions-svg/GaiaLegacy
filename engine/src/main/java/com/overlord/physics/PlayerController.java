package com.overlord.physics;

import com.overlord.config.GameConfig;
import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class PlayerController {
    private final PhysicsBody body;
    private final CollisionWorld collisionWorld;
    private final float movementSpeed;
    private final float noclipSpeed;
    private final float jumpVelocity;
    private final float gravity;
    private final float terminalVelocity;

    private boolean grounded;

    public PlayerController(
            PhysicsBody body,
            CollisionWorld collisionWorld,
            float movementSpeed,
            float noclipSpeed,
            float jumpVelocity,
            float gravity,
            float terminalVelocity) {
        this.body = Objects.requireNonNull(body, "body");
        this.collisionWorld =
                Objects.requireNonNull(collisionWorld, "collisionWorld");
        this.movementSpeed =
                requireNonNegative(movementSpeed, "movementSpeed");
        this.noclipSpeed =
                requireNonNegative(noclipSpeed, "noclipSpeed");
        this.jumpVelocity =
                requireNonNegative(jumpVelocity, "jumpVelocity");
        this.gravity = requireFinite(gravity, "gravity");
        this.terminalVelocity =
                requireNonPositive(
                        terminalVelocity, "terminalVelocity");
    }

    public PhysicsBody body() {
        return body;
    }

    public boolean isGrounded() {
        return grounded;
    }

    public boolean isNoclip() {
        return false;
    }

    public void teleport(Vector3fc feetPosition) {
        body.teleport(feetPosition);
        body.setLinearVelocity(new Vector3f());
        grounded = false;
    }

    public void fixedUpdate(
            float fixedDeltaSeconds,
            float moveX,
            float moveZ,
            boolean jumpPressed,
            boolean ascendHeld,
            boolean descendHeld) {
        requirePositive(fixedDeltaSeconds, "fixedDeltaSeconds");
        requireFinite(moveX, "moveX");
        requireFinite(moveZ, "moveZ");

        Vector3f velocity = body.linearVelocity(new Vector3f());
        setNormalizedHorizontalVelocity(velocity, moveX, moveZ);

        if (jumpPressed && grounded) {
            velocity.y = jumpVelocity;
        }
        velocity.y =
                Math.max(
                        terminalVelocity,
                        velocity.y + gravity * fixedDeltaSeconds);
        requireFinite(velocity, "integrated velocity");

        Vector3f displacement =
                new Vector3f(velocity).mul(fixedDeltaSeconds);
        requireFinite(displacement, "integrated displacement");
        Vector3f position = body.position(new Vector3f());
        requireFinite(
                new Vector3f(position).add(displacement),
                "integrated position");

        body.beginStep();
        MotionResult motion =
                collisionWorld.moveAndSlide(
                        body.collider(),
                        position,
                        displacement,
                        GameConfig.Physics.MAX_SLIDE_ITERATIONS);

        grounded = false;
        applyOrderedContactResponse(velocity, motion);
        body.setPosition(motion.position(new Vector3f()));
        body.setLinearVelocity(velocity);
    }

    public boolean overlapsSolid() {
        Vector3f position = body.position(new Vector3f());
        return collisionWorld.overlapsSolid(
                body.collider().translated(position));
    }

    private void setNormalizedHorizontalVelocity(
            Vector3f velocity, float moveX, float moveZ) {
        double intentLength = Math.hypot(moveX, moveZ);
        if (intentLength == 0) {
            velocity.x = 0;
            velocity.z = 0;
            return;
        }
        velocity.x =
                (float) (moveX / intentLength) * movementSpeed;
        velocity.z =
                (float) (moveZ / intentLength) * movementSpeed;
    }

    private void applyOrderedContactResponse(
            Vector3f velocity, MotionResult motion) {
        for (SweepResult contact : motion.contacts()) {
            if (contact.normalY() > 0) {
                grounded = true;
            }

            float inwardSpeed =
                    velocity.x * contact.normalX()
                            + velocity.y * contact.normalY()
                            + velocity.z * contact.normalZ();
            if (inwardSpeed < 0) {
                velocity.sub(
                        contact.normalX() * inwardSpeed,
                        contact.normalY() * inwardSpeed,
                        contact.normalZ() * inwardSpeed);
            }
        }
    }

    private static float requireNonNegative(
            float value, String label) {
        requireFinite(value, label);
        if (value < 0) {
            throw new IllegalArgumentException(
                    label + " must not be negative");
        }
        return value;
    }

    private static float requireNonPositive(
            float value, String label) {
        requireFinite(value, label);
        if (value > 0) {
            throw new IllegalArgumentException(
                    label + " must not be positive");
        }
        return value;
    }

    private static float requirePositive(
            float value, String label) {
        requireFinite(value, label);
        if (value <= 0) {
            throw new IllegalArgumentException(
                    label + " must be positive");
        }
        return value;
    }

    private static float requireFinite(
            float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(
                    label + " must be finite");
        }
        return value;
    }

    private static void requireFinite(
            Vector3fc value, String label) {
        if (value == null
                || !Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(
                    label + " must be finite");
        }
    }
}
