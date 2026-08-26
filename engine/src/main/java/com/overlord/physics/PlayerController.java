package com.overlord.physics;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
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
    private SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));
    private boolean originAware;
    private Optional<BlockedSpaceObservation> lastBlockedSpace = Optional.empty();

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

    /** Last unavailable canonical collision encountered by this fixed update. */
    public Optional<BlockedSpaceObservation> lastBlockedSpace() {
        return lastBlockedSpace;
    }

    /** Precomputes player body endpoints and publishes the collision origin at commit. */
    public PreparedOriginRebase prepareOriginRebase(
            SimulationOrigin oldOrigin, SimulationOrigin nextOrigin) {
        Objects.requireNonNull(oldOrigin, "oldOrigin");
        Objects.requireNonNull(nextOrigin, "nextOrigin");
        if (!simulationOrigin.equals(oldOrigin)) {
            throw new IllegalStateException("old origin does not match PlayerController");
        }
        float offsetX = preciseOffset(oldOrigin.worldOriginX(), nextOrigin.worldOriginX(), "x");
        float offsetZ = preciseOffset(oldOrigin.worldOriginZ(), nextOrigin.worldOriginZ(), "z");
        PhysicsBody.PreparedRebase bodyRebase =
                body.prepareRebase(new Vector3f(offsetX, 0, offsetZ));
        return () -> {
            bodyRebase.commit();
            simulationOrigin = nextOrigin;
            originAware = true;
        };
    }

    public void teleport(Vector3fc feetPosition) {
        body.teleport(feetPosition);
        body.setLinearVelocity(new Vector3f());
        grounded = false;
    }

    /**
     * Restores detached motion inside a saved world's exact vertical bounds.
     * Collision recovery is planned completely before authoritative state is
     * mutated and never changes the saved velocity.
     */
    public void restoreCanonical(
            Vector3fc feetPosition,
            Vector3fc linearVelocity,
            boolean restoredNoclip,
            int worldHeight) {
        Vector3f validatedPosition =
                new Vector3f(
                        Objects.requireNonNull(
                                feetPosition, "feetPosition"));
        Vector3f validatedVelocity =
                new Vector3f(
                        Objects.requireNonNull(
                                linearVelocity, "linearVelocity"));
        requireFinite(validatedPosition, "feetPosition");
        requireFinite(validatedVelocity, "linearVelocity");
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be positive");
        }

        float minimumFeetY = -body.collider().minY();
        float maximumFeetY = worldHeight - body.collider().maxY();
        if (validatedPosition.y < minimumFeetY
                || validatedPosition.y > maximumFeetY) {
            throw new IllegalArgumentException(
                    "feetPosition is outside the saved world height");
        }

        Vector3f restoredPosition = validatedPosition;
        if (!restoredNoclip
                && overlapsSolid(body.collider().translated(validatedPosition))) {
            restoredPosition = recoveryPosition(
                            validatedPosition,
                            minimumFeetY,
                            maximumFeetY)
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "restored player penetration recovery failed"));
        }

        body.teleport(restoredPosition);
        body.setLinearVelocity(validatedVelocity);
        noclip = restoredNoclip;
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
        lastBlockedSpace = Optional.empty();

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
        try {
            MotionResult baseline = moveAndSlide(
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
        } catch (UnavailableCollision unavailable) {
            if (hasHorizontalIntent(moveX, moveZ)) {
                lastBlockedSpace = Optional.of(new BlockedSpaceObservation(
                        unavailable.status == SpatialQueryResult.Status.FAILED
                                ? ChunkAvailability.FAILED
                                : ChunkAvailability.UNKNOWN,
                        unavailable.key,
                        direction(moveX, moveZ)));
            }
            body.setPosition(position);
            body.setLinearVelocity(new Vector3f());
            grounded = false;
        }
    }

    public boolean overlapsSolid() {
        Vector3f position = body.position(new Vector3f());
        return overlapsSolid(body.collider().translated(position));
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
        float minimumFeetY = -body.collider().minY();
        float maximumFeetY =
                GameConfig.Chunk.MAX_HEIGHT
                        - body.collider().maxY();
        Optional<Vector3f> recovered;
        try {
            recovered = recoveryPosition(position, minimumFeetY, maximumFeetY);
        } catch (UnavailableCollision unavailable) {
            return false;
        }
        if (recovered.isPresent()) {
            applyRecoveredPosition(recovered.orElseThrow());
            return true;
        }
        return false;
    }

    private Optional<Vector3f> recoveryPosition(
            Vector3fc position,
            float minimumFeetY,
            float maximumFeetY) {
        Optional<Vector3f> local = depenetrate(
                        body.collider(),
                        position,
                        GameConfig.Physics
                                .MAX_DEPENETRATION_ITERATIONS);
        if (local.isPresent()) {
            Vector3f candidate = local.orElseThrow();
            if (candidate.y >= minimumFeetY
                    && candidate.y <= maximumFeetY
                    && !overlapsSolid(body.collider().translated(candidate))) {
                return Optional.of(candidate);
            }
        }

        for (int offset = 1;
                position.y() + offset <= maximumFeetY;
                offset++) {
            Vector3f candidate =
                    new Vector3f(position).add(0, offset, 0);
            if (!overlapsSolid(body.collider().translated(candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
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
        if (sweep(body.collider(), position, upward).isPresent()) {
            return Optional.empty();
        }

        Vector3f raisedPosition =
                new Vector3f(position).add(upward);
        Vector3f horizontal =
                new Vector3f(displacement.x(), 0, displacement.z());
        MotionResult horizontalMotion = moveAndSlide(
                        body.collider(),
                        raisedPosition,
                        horizontal,
                        GameConfig.Physics.MAX_SLIDE_ITERATIONS);
        Vector3f horizontalPosition =
                horizontalMotion.position(new Vector3f());
        MotionResult landing = moveAndSlide(
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
        if (overlapsSolid(body.collider().translated(finalPosition))) {
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
        MotionResult snap = moveAndSlide(
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

    private MotionResult moveAndSlide(
            Aabb collider,
            Vector3fc position,
            Vector3fc displacement,
            int iterations) {
        if (!originAware) {
            return collisionWorld.moveAndSlide(collider, position, displacement, iterations);
        }
        SpatialQueryResult<MotionResult> query = collisionWorld.moveAndSlide(
                simulationOrigin, collider, position, displacement, iterations);
        requireAvailable(query.status(), query.unavailableKey());
        return query.result().orElseThrow();
    }

    private Optional<SweepResult> sweep(
            Aabb collider, Vector3fc position, Vector3fc displacement) {
        if (!originAware) {
            return collisionWorld.sweep(collider, position, displacement);
        }
        SpatialQueryResult<SweepResult> query = collisionWorld.sweep(
                simulationOrigin, collider, position, displacement);
        requireAvailable(query.status(), query.unavailableKey());
        return query.result();
    }

    private boolean overlapsSolid(Aabb bounds) {
        if (!originAware) {
            return collisionWorld.overlapsSolid(bounds);
        }
        SpatialQueryResult<Boolean> query =
                collisionWorld.overlapsSolid(simulationOrigin, bounds);
        requireAvailable(query.status(), query.unavailableKey());
        return query.result().orElseThrow();
    }

    private Optional<Vector3f> depenetrate(
            Aabb collider, Vector3fc position, int iterations) {
        if (!originAware) {
            return collisionWorld.depenetrate(collider, position, iterations);
        }
        SpatialQueryResult<Vector3f> query = collisionWorld.depenetrate(
                simulationOrigin, collider, position, iterations);
        requireAvailable(query.status(), query.unavailableKey());
        return query.result();
    }

    private static void requireAvailable(
            SpatialQueryResult.Status status, Optional<ChunkKey> unavailableKey) {
        if (status != SpatialQueryResult.Status.AVAILABLE) {
            throw new UnavailableCollision(status, unavailableKey.orElseThrow());
        }
    }

    private static float preciseOffset(long oldOrigin, long nextOrigin, String axis) {
        long difference = Math.subtractExact(oldOrigin, nextOrigin);
        float converted = difference;
        if (!Float.isFinite(converted) || (long) converted != difference) {
            throw new IllegalArgumentException(
                    axis + " origin delta is not precisely representable");
        }
        return converted;
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

    @FunctionalInterface
    public interface PreparedOriginRebase {
        void commit();
    }

    private static Direction direction(float moveX, float moveZ) {
        if (Math.abs(moveX) >= Math.abs(moveZ)) {
            return moveX < 0.0f ? Direction.WEST : Direction.EAST;
        }
        return moveZ < 0.0f ? Direction.NORTH : Direction.SOUTH;
    }

    public enum Direction { NORTH, SOUTH, WEST, EAST }

    public record BlockedSpaceObservation(
            ChunkAvailability availability,
            ChunkKey key,
            Direction direction) {
        public BlockedSpaceObservation {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
        }
    }

    private static final class UnavailableCollision extends IllegalStateException {
        private final SpatialQueryResult.Status status;
        private final ChunkKey key;

        private UnavailableCollision(SpatialQueryResult.Status status, ChunkKey key) {
            super("collision space is " + status + " at " + key);
            this.status = Objects.requireNonNull(status, "status");
            this.key = Objects.requireNonNull(key, "key");
        }
    }
}
