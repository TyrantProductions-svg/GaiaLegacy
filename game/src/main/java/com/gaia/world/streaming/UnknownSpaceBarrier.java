package com.gaia.world.streaming;

import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Pure availability policy that prevents unavailable space from behaving like AIR. */
public final class UnknownSpaceBarrier {
    public static final int MAX_TELEPORT_RADIUS = 7;
    public static final int MAX_CROSSED_CHUNKS = 1024;
    private final Function<ChunkKey, ChunkAvailability> availability;

    public UnknownSpaceBarrier(Function<ChunkKey, ChunkAvailability> availability) {
        this.availability = Objects.requireNonNull(availability, "availability");
    }

    public Decision movement(GlobalPosition current, GlobalPosition target) {
        return motion(current, target, current);
    }

    public Decision movement(
            GlobalPosition current,
            GlobalPosition target,
            GlobalPosition priorSafePosition) {
        return motion(current, target, priorSafePosition);
    }

    public Decision worldItemMotion(GlobalPosition current, GlobalPosition target) {
        return motion(current, target, current);
    }

    public Decision worldItemMotion(
            GlobalPosition current,
            GlobalPosition target,
            GlobalPosition priorSafePosition) {
        return motion(current, target, priorSafePosition);
    }

    public Decision noclipOrTeleport(
            GlobalPosition destination,
            int radius,
            GlobalPosition priorSafePosition) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(priorSafePosition, "priorSafePosition");
        if (radius < 0 || radius > MAX_TELEPORT_RADIUS) {
            throw new IllegalArgumentException(
                    "radius must be between 0 and " + MAX_TELEPORT_RADIUS);
        }
        ChunkAvailability blocked = null;
        ChunkKey blockedKey = null;
        ChunkKey center = destination.chunkKey();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkKey key = ChunkCoordinatePolicy.neighbor(center, x, z);
                ChunkAvailability observed = Objects.requireNonNull(
                        availability.apply(key), "availability result");
                if (observed != ChunkAvailability.AVAILABLE
                        && isBetterBlocked(observed, key, blocked, blockedKey)) {
                    blocked = observed;
                    blockedKey = key;
                }
            }
        }
        return blocked == null
                ? new Decision(ChunkAvailability.AVAILABLE, destination, Optional.empty())
                : new Decision(blocked, priorSafePosition, Optional.of(blockedKey));
    }

    private Decision motion(
            GlobalPosition current,
            GlobalPosition target,
            GlobalPosition priorSafePosition) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(priorSafePosition, "priorSafePosition");
        ChunkKey cursor = current.chunkKey();
        ChunkKey destination = target.chunkKey();
        double startX = com.overlord.voxel.ChunkCoordinatePolicy.worldOriginX(cursor)
                + current.localX();
        double startZ = com.overlord.voxel.ChunkCoordinatePolicy.worldOriginZ(cursor)
                + current.localZ();
        double endX = com.overlord.voxel.ChunkCoordinatePolicy.worldOriginX(destination)
                + target.localX();
        double endZ = com.overlord.voxel.ChunkCoordinatePolicy.worldOriginZ(destination)
                + target.localZ();
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        int stepX = Integer.compare(destination.x(), cursor.x());
        int stepZ = Integer.compare(destination.z(), cursor.z());
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(deltaX);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(deltaZ);
        double boundaryX = (stepX > 0 ? (long) cursor.x() + 1 : cursor.x()) * 16.0;
        double boundaryZ = (stepZ > 0 ? (long) cursor.z() + 1 : cursor.z()) * 16.0;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (boundaryX - startX) / deltaX;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (boundaryZ - startZ) / deltaZ;
        if (crossingCountExceedsBound(
                cursor, destination, stepX, stepZ, tMaxX, tMaxZ, tDeltaX, tDeltaZ)) {
            throw new IllegalArgumentException(
                    "movement crosses more than " + MAX_CROSSED_CHUNKS + " Chunks");
        }
        ChunkAvailability blocked = null;
        ChunkKey blockedKey = null;
        if (cursor.equals(destination)) {
            ChunkAvailability observed = Objects.requireNonNull(
                    availability.apply(cursor), "availability result");
            return observed == ChunkAvailability.AVAILABLE
                    ? new Decision(observed, target, Optional.empty())
                    : new Decision(observed, priorSafePosition, Optional.of(cursor));
        }
        while (!cursor.equals(destination)) {
            if (tMaxX < tMaxZ) {
                cursor = ChunkCoordinatePolicy.neighbor(cursor, stepX, 0);
                tMaxX += tDeltaX;
            } else if (tMaxZ < tMaxX) {
                cursor = ChunkCoordinatePolicy.neighbor(cursor, 0, stepZ);
                tMaxZ += tDeltaZ;
            } else {
                cursor = ChunkCoordinatePolicy.neighbor(cursor, stepX, stepZ);
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            }
            ChunkAvailability observed = Objects.requireNonNull(
                    availability.apply(cursor), "availability result");
            if (observed != ChunkAvailability.AVAILABLE
                    && isBetterBlocked(observed, cursor, blocked, blockedKey)) {
                blocked = observed;
                blockedKey = cursor;
            }
        }
        return blocked == null
                ? new Decision(ChunkAvailability.AVAILABLE, target, Optional.empty())
                : new Decision(blocked, priorSafePosition, Optional.of(blockedKey));
    }

    private static boolean crossingCountExceedsBound(
            ChunkKey start,
            ChunkKey destination,
            int stepX,
            int stepZ,
            double initialTMaxX,
            double initialTMaxZ,
            double tDeltaX,
            double tDeltaZ) {
        int x = start.x();
        int z = start.z();
        double tMaxX = initialTMaxX;
        double tMaxZ = initialTMaxZ;
        int crossings = 0;
        while (x != destination.x() || z != destination.z()) {
            if (++crossings > MAX_CROSSED_CHUNKS) {
                return true;
            }
            if (tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxZ < tMaxX) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            } else {
                x += stepX;
                z += stepZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    private static boolean isBetterBlocked(
            ChunkAvailability candidateStatus,
            ChunkKey candidateKey,
            ChunkAvailability currentStatus,
            ChunkKey currentKey) {
        if (currentStatus == null) {
            return true;
        }
        if (candidateStatus == ChunkAvailability.FAILED
                && currentStatus != ChunkAvailability.FAILED) {
            return true;
        }
        if (candidateStatus != currentStatus) {
            return false;
        }
        return ChunkCoordinatePolicy.canonicalComparator().compare(candidateKey, currentKey) < 0;
    }

    public record Decision(
            ChunkAvailability availability,
            GlobalPosition lastAvailablePosition,
            Optional<ChunkKey> unavailableKey) {
        public Decision {
            availability = Objects.requireNonNull(availability, "availability");
            lastAvailablePosition = Objects.requireNonNull(
                    lastAvailablePosition, "lastAvailablePosition");
            unavailableKey = Objects.requireNonNull(unavailableKey, "unavailableKey");
            if ((availability == ChunkAvailability.AVAILABLE) == unavailableKey.isPresent()) {
                throw new IllegalArgumentException("availability and unavailableKey disagree");
            }
        }
    }
}
