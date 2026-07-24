package com.overlord.physics;

import com.overlord.voxel.World;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3fc;

public final class CollisionWorld {
    private static final int Y_AXIS_PRIORITY = 0;
    private static final int X_AXIS_PRIORITY = 1;
    private static final int Z_AXIS_PRIORITY = 2;

    private final World world;
    private final BlockCollisionShapeResolver shapeResolver;

    public CollisionWorld(
            World world,
            BlockCollisionShapeResolver shapeResolver) {
        this.world = Objects.requireNonNull(world, "world");
        this.shapeResolver =
                Objects.requireNonNull(shapeResolver, "shapeResolver");
    }

    public Optional<SweepResult> sweep(
            Aabb localCollider,
            Vector3fc position,
            Vector3fc displacement) {
        Objects.requireNonNull(localCollider, "localCollider");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(displacement, "displacement");
        validateFinite(position, "position");
        validateFinite(displacement, "displacement");

        if (displacement.x() == 0
                && displacement.y() == 0
                && displacement.z() == 0) {
            return Optional.empty();
        }

        Aabb start = localCollider.translated(position);
        Aabb broadPhase = start.sweptBounds(displacement);
        int minBlockX = floorBlockCoordinate(broadPhase.minX());
        int minBlockY = floorBlockCoordinate(broadPhase.minY());
        int minBlockZ = floorBlockCoordinate(broadPhase.minZ());
        int maxBlockX = floorBlockCoordinate(broadPhase.maxX());
        int maxBlockY = floorBlockCoordinate(broadPhase.maxY());
        int maxBlockZ = floorBlockCoordinate(broadPhase.maxZ());

        Candidate best = null;
        for (long blockX = minBlockX; blockX <= maxBlockX; blockX++) {
            for (long blockY = minBlockY; blockY <= maxBlockY; blockY++) {
                for (long blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
                    int x = (int) blockX;
                    int y = (int) blockY;
                    int z = (int) blockZ;
                    BlockCollisionShape shape =
                            Objects.requireNonNull(
                                    shapeResolver.shapeFor(
                                            world.getBlock(x, y, z)),
                                    "shapeResolver result");
                    List<Aabb> boxes = shape.boxes();
                    for (int subShapeIndex = 0;
                            subShapeIndex < boxes.size();
                            subShapeIndex++) {
                        Aabb blockShape =
                                translate(
                                        boxes.get(subShapeIndex), x, y, z);
                        Candidate candidate =
                                sweepAgainst(
                                        start,
                                        displacement,
                                        x,
                                        y,
                                        z,
                                        subShapeIndex,
                                        blockShape);
                        if (candidate != null
                                && (best == null
                                        || candidate.isBetterThan(best))) {
                            best = candidate;
                        }
                    }
                }
            }
        }

        if (best == null) {
            return Optional.empty();
        }
        float fraction = best.fraction() == 0 ? 0 : best.fraction();
        return Optional.of(
                new SweepResult(
                        fraction,
                        best.normalX(),
                        best.normalY(),
                        best.normalZ(),
                        position.x() + displacement.x() * fraction,
                        position.y() + displacement.y() * fraction,
                        position.z() + displacement.z() * fraction,
                        best.blockX(),
                        best.blockY(),
                        best.blockZ(),
                        best.blockShape()));
    }

    private static Candidate sweepAgainst(
            Aabb moving,
            Vector3fc displacement,
            int blockX,
            int blockY,
            int blockZ,
            int subShapeIndex,
            Aabb target) {
        AxisSweep x =
                sweepAxis(
                        moving.minX(),
                        moving.maxX(),
                        target.minX(),
                        target.maxX(),
                        displacement.x(),
                        X_AXIS_PRIORITY);
        AxisSweep y =
                sweepAxis(
                        moving.minY(),
                        moving.maxY(),
                        target.minY(),
                        target.maxY(),
                        displacement.y(),
                        Y_AXIS_PRIORITY);
        AxisSweep z =
                sweepAxis(
                        moving.minZ(),
                        moving.maxZ(),
                        target.minZ(),
                        target.maxZ(),
                        displacement.z(),
                        Z_AXIS_PRIORITY);
        if (x == null || y == null || z == null) {
            return null;
        }

        float entry = Math.max(x.entry(), Math.max(y.entry(), z.entry()));
        float exit = Math.min(x.exit(), Math.min(y.exit(), z.exit()));
        if (entry > exit || entry < 0 || entry > 1 || exit < 0) {
            return null;
        }

        AxisSweep contactAxis;
        if (y.entry() == entry) {
            contactAxis = y;
        } else if (x.entry() == entry) {
            contactAxis = x;
        } else {
            contactAxis = z;
        }

        float fraction = entry == 0 ? 0 : entry;
        return new Candidate(
                fraction,
                contactAxis.priority(),
                contactAxis.normalX(),
                contactAxis.normalY(),
                contactAxis.normalZ(),
                blockX,
                blockY,
                blockZ,
                subShapeIndex,
                target);
    }

    private static AxisSweep sweepAxis(
            float movingMin,
            float movingMax,
            float targetMin,
            float targetMax,
            float displacement,
            int priority) {
        if (displacement == 0) {
            if (movingMin >= targetMax || movingMax <= targetMin) {
                return null;
            }
            return new AxisSweep(
                    Float.NEGATIVE_INFINITY,
                    Float.POSITIVE_INFINITY,
                    priority,
                    0,
                    0,
                    0);
        }

        float entry;
        float exit;
        float normal;
        if (displacement > 0) {
            entry = (targetMin - movingMax) / displacement;
            exit = (targetMax - movingMin) / displacement;
            normal = -1;
        } else {
            entry = (targetMax - movingMin) / displacement;
            exit = (targetMin - movingMax) / displacement;
            normal = 1;
        }

        return switch (priority) {
            case X_AXIS_PRIORITY ->
                    new AxisSweep(entry, exit, priority, normal, 0, 0);
            case Y_AXIS_PRIORITY ->
                    new AxisSweep(entry, exit, priority, 0, normal, 0);
            case Z_AXIS_PRIORITY ->
                    new AxisSweep(entry, exit, priority, 0, 0, normal);
            default -> throw new IllegalArgumentException("Unknown axis");
        };
    }

    private static Aabb translate(
            Aabb localShape, int blockX, int blockY, int blockZ) {
        return new Aabb(
                localShape.minX() + blockX,
                localShape.minY() + blockY,
                localShape.minZ() + blockZ,
                localShape.maxX() + blockX,
                localShape.maxY() + blockY,
                localShape.maxZ() + blockZ);
    }

    private static int floorBlockCoordinate(float value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "swept bounds exceed integer block coordinates");
        }
        return (int) floor;
    }

    private static void validateFinite(Vector3fc vector, String name) {
        if (!Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private record AxisSweep(
            float entry,
            float exit,
            int priority,
            float normalX,
            float normalY,
            float normalZ) {}

    private record Candidate(
            float fraction,
            int axisPriority,
            float normalX,
            float normalY,
            float normalZ,
            int blockX,
            int blockY,
            int blockZ,
            int subShapeIndex,
            Aabb blockShape) {
        private boolean isBetterThan(Candidate other) {
            int fractionComparison =
                    Float.compare(fraction, other.fraction);
            if (fractionComparison != 0) {
                return fractionComparison < 0;
            }
            if (axisPriority != other.axisPriority) {
                return axisPriority < other.axisPriority;
            }
            if (blockX != other.blockX) {
                return blockX < other.blockX;
            }
            if (blockY != other.blockY) {
                return blockY < other.blockY;
            }
            if (blockZ != other.blockZ) {
                return blockZ < other.blockZ;
            }
            return subShapeIndex < other.subShapeIndex;
        }
    }
}
