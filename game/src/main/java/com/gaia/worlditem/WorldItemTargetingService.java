package com.gaia.worlditem;

import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Deterministic renderer-independent targeting over immutable physical snapshots. */
public final class WorldItemTargetingService {
    private static final float HALF_EDGE = 0.25f;

    private final BlockRaycastService blocks;

    public WorldItemTargetingService(BlockRaycastService blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    public Optional<WorldItemTarget> target(
            Vector3fc eye,
            Vector3fc direction,
            float maximumDistance,
            long tick,
            List<WorldItemPhysicalSnapshot> candidates) {
        requireFinite(eye, "eye");
        requireFinite(direction, "direction");
        if (!Float.isFinite(maximumDistance) || maximumDistance < 0.0f) {
            throw new IllegalArgumentException(
                    "maximumDistance must be finite and non-negative");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        Objects.requireNonNull(candidates, "candidates");

        Vector3f rayDirection = new Vector3f(direction);
        float lengthSquared = rayDirection.lengthSquared();
        if (!Float.isFinite(lengthSquared) || lengthSquared == 0.0f) {
            throw new IllegalArgumentException("direction must be non-zero and finite");
        }
        rayDirection.normalize();

        Optional<Float> opaqueBlockDistance = blocks
                .raycast(eye, rayDirection, maximumDistance)
                .map(hit -> hit.distance());
        WorldItemTarget best = null;
        for (WorldItemPhysicalSnapshot candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!eligible(candidate, tick)) {
                continue;
            }
            Optional<Float> intersection = intersect(eye, rayDirection, candidate);
            if (intersection.isEmpty()) {
                continue;
            }
            float distance = intersection.orElseThrow();
            if (distance > maximumDistance
                    || opaqueBlockDistance
                            .map(blockDistance -> distance >= blockDistance)
                            .orElse(false)) {
                continue;
            }
            if (best == null
                    || distance < best.distance()
                    || (Float.compare(distance, best.distance()) == 0
                            && candidate.id().value() < best.itemId().value())) {
                best = new WorldItemTarget(candidate.id(), candidate, distance);
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean eligible(WorldItemPhysicalSnapshot candidate, long tick) {
        return candidate.state() != WorldItemPhysicalState.FROZEN_UNLOADED
                && !candidate.extractionReserved()
                && tick >= candidate.runtime().pickupAvailableTick();
    }

    private static Optional<Float> intersect(
            Vector3fc eye,
            Vector3fc direction,
            WorldItemPhysicalSnapshot candidate) {
        double entry = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        double[] origins = {eye.x(), eye.y(), eye.z()};
        double[] directions = {direction.x(), direction.y(), direction.z()};
        double[] centers = {
                candidate.runtime().item().positionX(),
                candidate.runtime().item().positionY(),
                candidate.runtime().item().positionZ()
        };
        for (int axis = 0; axis < 3; axis++) {
            double minimum = centers[axis] - HALF_EDGE;
            double maximum = centers[axis] + HALF_EDGE;
            if (directions[axis] == 0.0) {
                if (origins[axis] < minimum || origins[axis] > maximum) {
                    return Optional.empty();
                }
                continue;
            }
            double first = (minimum - origins[axis]) / directions[axis];
            double second = (maximum - origins[axis]) / directions[axis];
            double near = Math.min(first, second);
            double far = Math.max(first, second);
            entry = Math.max(entry, near);
            exit = Math.min(exit, far);
            if (exit < Math.max(entry, 0.0)) {
                return Optional.empty();
            }
        }
        return Optional.of((float) Math.max(entry, 0.0));
    }

    private static void requireFinite(Vector3fc vector, String label) {
        if (vector == null
                || !Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
