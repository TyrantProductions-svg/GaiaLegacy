package com.overlord.physics;

import com.overlord.config.GameConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private boolean noclip;

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
        return noclip;
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

        if (noclip) {
            updateNoclip(
                    fixedDeltaSeconds,
                    moveX,
                    moveZ,
                    ascendHeld,
                    descendHeld);
            return;
        }

        boolean wasGrounded = grounded;
        Vector3f velocity = body.linearVelocity(new Vector3f());
        setNormalizedHorizontalVelocity(velocity, moveX, moveZ);

        if (jumpPressed && wasGrounded) {
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
        MotionResult baseline =
                collisionWorld.moveAndSlide(
                        body.collider(),
                        position,
                        displacement,
                        GameConfig.Physics.MAX_SLIDE_ITERATIONS);
        MotionResult motion = baseline;

        if (wasGrounded
                && hasHorizontalMovement(displacement)
                && isHorizontallyBlocked(displacement, baseline)) {
            Optional<MotionResult> step =
                    tryStep(position, displacement);
            if (step.isPresent()
                    && horizontalProgressSquared(step.orElseThrow())
                            > horizontalProgressSquared(baseline)) {
                motion = step.orElseThrow();
            }
        }

        if (wasGrounded
                && !jumpPressed
                && velocity.y <= 0
                && hasHorizontalIntent(moveX, moveZ)) {
            motion = snapToGround(position, motion);
        }

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

    public boolean setNoclip(boolean enabled) {
        if (enabled) {
            if (noclip) {
                return true;
            }
            noclip = true;
            grounded = false;
            Vector3f velocity =
                    body.linearVelocity(new Vector3f());
            velocity.y = 0;
            body.setLinearVelocity(velocity);
            return true;
        }
        if (!noclip) {
            return true;
        }
        if (!recoverFromPenetration()) {
            return false;
        }
        noclip = false;
        return true;
    }

    public boolean recoverFromPenetration() {
        Vector3f position = body.position(new Vector3f());
        Optional<Vector3f> local =
                collisionWorld.depenetrate(
                        body.collider(),
                        position,
                        GameConfig.Physics
                                .MAX_DEPENETRATION_ITERATIONS);
        if (local.isPresent()) {
            applyRecoveredPosition(local.orElseThrow());
            return true;
        }

        float maximumFeetY =
                GameConfig.Chunk.MAX_HEIGHT
                        - body.collider().maxY();
        for (int offset = 1;
                position.y + offset <= maximumFeetY;
                offset++) {
            Vector3f candidate =
                    new Vector3f(position).add(0, offset, 0);
            if (!collisionWorld.overlapsSolid(
                    body.collider().translated(candidate))) {
                applyRecoveredPosition(candidate);
                return true;
            }
        }
        return false;
    }

    private void updateNoclip(
            float fixedDeltaSeconds,
            float moveX,
            float moveZ,
            boolean ascendHeld,
            boolean descendHeld) {
        float moveY =
                (ascendHeld ? 1.0f : 0.0f)
                        - (descendHeld ? 1.0f : 0.0f);
        double intentLength =
                Math.sqrt(
                        (double) moveX * moveX
                                + (double) moveY * moveY
                                + (double) moveZ * moveZ);
        Vector3f velocity = new Vector3f();
        if (intentLength != 0) {
            velocity.set(
                    (float) (moveX / intentLength) * noclipSpeed,
                    (float) (moveY / intentLength) * noclipSpeed,
                    (float) (moveZ / intentLength) * noclipSpeed);
        }

        Vector3f position = body.position(new Vector3f());
        Vector3f displacement =
                new Vector3f(velocity).mul(fixedDeltaSeconds);
        requireFinite(displacement, "integrated displacement");
        Vector3f destination =
                new Vector3f(position).add(displacement);
        requireFinite(destination, "integrated position");

        body.beginStep();
        body.setPosition(destination);
        body.setLinearVelocity(velocity);
        grounded = false;
    }

    private Optional<MotionResult> tryStep(
            Vector3fc position, Vector3fc displacement) {
        Vector3f upward =
                new Vector3f(
                        0, GameConfig.Player.MAX_STEP_HEIGHT, 0);
        if (collisionWorld
                .sweep(body.collider(), position, upward)
                .isPresent()) {
            return Optional.empty();
        }

        Vector3f raisedPosition =
                new Vector3f(position).add(upward);
        Vector3f horizontal =
                new Vector3f(displacement.x(), 0, displacement.z());
        MotionResult horizontalMotion =
                collisionWorld.moveAndSlide(
                        body.collider(),
                        raisedPosition,
                        horizontal,
                        GameConfig.Physics.MAX_SLIDE_ITERATIONS);
        Vector3f horizontalPosition =
                horizontalMotion.position(new Vector3f());
        MotionResult landing =
                collisionWorld.moveAndSlide(
                        body.collider(),
                        horizontalPosition,
                        new Vector3f(
                                0,
                                -GameConfig.Player.MAX_STEP_HEIGHT,
                                0),
                        1);
        if (!hasGroundContact(landing)) {
            return Optional.empty();
        }

        Vector3f finalPosition =
                landing.position(new Vector3f());
        if (collisionWorld.overlapsSolid(
                body.collider().translated(finalPosition))) {
            return Optional.empty();
        }

        List<SweepResult> contacts =
                new ArrayList<>(horizontalMotion.contacts());
        contacts.addAll(landing.contacts());
        return Optional.of(
                motionBetween(position, finalPosition, contacts));
    }

    private MotionResult snapToGround(
            Vector3fc start, MotionResult motion) {
        Vector3f current = motion.position(new Vector3f());
        MotionResult snap =
                collisionWorld.moveAndSlide(
                        body.collider(),
                        current,
                        new Vector3f(
                                0,
                                -GameConfig.Player
                                        .GROUND_SNAP_DISTANCE,
                                0),
                        1);
        if (!hasGroundContact(snap)) {
            return motion;
        }

        List<SweepResult> contacts =
                new ArrayList<>(motion.contacts());
        contacts.addAll(snap.contacts());
        return motionBetween(
                start, snap.position(new Vector3f()), contacts);
    }

    private void applyRecoveredPosition(Vector3fc position) {
        body.teleport(position);
        body.setLinearVelocity(new Vector3f());
        grounded = false;
    }

    private static MotionResult motionBetween(
            Vector3fc start,
            Vector3fc end,
            List<SweepResult> contacts) {
        return new MotionResult(
                end.x(),
                end.y(),
                end.z(),
                end.x() - start.x(),
                end.y() - start.y(),
                end.z() - start.z(),
                contacts);
    }

    private static boolean hasGroundContact(MotionResult motion) {
        for (SweepResult contact : motion.contacts()) {
            if (contact.normalY() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHorizontalMovement(
            Vector3fc displacement) {
        return displacement.x() != 0 || displacement.z() != 0;
    }

    private static boolean hasHorizontalIntent(
            float moveX, float moveZ) {
        return moveX != 0 || moveZ != 0;
    }

    private static boolean isHorizontallyBlocked(
            Vector3fc intended, MotionResult actual) {
        float intendedLength =
                (float) Math.hypot(intended.x(), intended.z());
        float actualLength =
                (float)
                        Math.hypot(
                                actual.appliedX(),
                                actual.appliedZ());
        return actualLength
                + GameConfig.Physics.COLLISION_TOLERANCE
                < intendedLength;
    }

    private static float horizontalProgressSquared(
            MotionResult motion) {
        return motion.appliedX() * motion.appliedX()
                + motion.appliedZ() * motion.appliedZ();
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
