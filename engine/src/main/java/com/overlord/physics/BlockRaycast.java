package com.overlord.physics;

import com.overlord.voxel.World;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3fc;

public final class BlockRaycast {
    public static final float MAX_DISTANCE = 4096.0f;

    private static final int Y_AXIS_PRIORITY = 0;
    private static final int X_AXIS_PRIORITY = 1;
    private static final int Z_AXIS_PRIORITY = 2;
    private static final double EVENT_RELATIVE_EPSILON = 1.0e-12;

    private final World world;
    private final BlockCollisionShapeResolver shapeResolver;

    public BlockRaycast(
            World world,
            BlockCollisionShapeResolver shapeResolver) {
        this.world = Objects.requireNonNull(world, "world");
        this.shapeResolver =
                Objects.requireNonNull(shapeResolver, "shapeResolver");
    }

    public Optional<BlockRaycastHit> cast(
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        validateFinite(origin, "origin");
        validateFinite(direction, "direction");
        if (!Float.isFinite(maxDistance)
                || maxDistance < 0
                || maxDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException(
                    "maxDistance must be finite and between 0 and "
                            + MAX_DISTANCE);
        }

        double directionLength =
                Math.sqrt(
                        (double) direction.x() * direction.x()
                                + (double) direction.y() * direction.y()
                                + (double) direction.z() * direction.z());
        if (directionLength == 0) {
            throw new IllegalArgumentException(
                    "direction must not have zero length");
        }
        double directionX = direction.x() / directionLength;
        double directionY = direction.y() / directionLength;
        double directionZ = direction.z() / directionLength;

        int blockX = floorBlockCoordinate(origin.x());
        int blockY = floorBlockCoordinate(origin.y());
        int blockZ = floorBlockCoordinate(origin.z());
        AxisTraversal xTraversal =
                traversal(origin.x(), directionX, blockX);
        AxisTraversal yTraversal =
                traversal(origin.y(), directionY, blockY);
        AxisTraversal zTraversal =
                traversal(origin.z(), directionZ, blockZ);

        while (true) {
            double nextDistance =
                    Math.min(
                            xTraversal.nextDistance(),
                            Math.min(
                                    yTraversal.nextDistance(),
                                    zTraversal.nextDistance()));
            Candidate best =
                    hitInBlock(
                            origin,
                            directionX,
                            directionY,
                            directionZ,
                            maxDistance,
                            blockX,
                            blockY,
                            blockZ);
            if (best != null
                    && compareEvents(
                                    best.distance(), nextDistance)
                            < 0) {
                return Optional.of(
                        best.toHit(
                                origin,
                                directionX,
                                directionY,
                                directionZ));
            }
            if (nextDistance > maxDistance
                    || nextDistance == Double.POSITIVE_INFINITY) {
                return Optional.empty();
            }

            boolean stepX =
                    sameEvent(
                            xTraversal.nextDistance(),
                            nextDistance);
            boolean stepY =
                    sameEvent(
                            yTraversal.nextDistance(),
                            nextDistance);
            boolean stepZ =
                    sameEvent(
                            zTraversal.nextDistance(),
                            nextDistance);
            boolean canStepX =
                    !stepX
                            || !cannotStep(
                                    blockX, xTraversal.step());
            boolean canStepY =
                    !stepY
                            || !cannotStep(
                                    blockY, yTraversal.step());
            boolean canStepZ =
                    !stepZ
                            || !cannotStep(
                                    blockZ, zTraversal.step());
            int xChoices = stepX && canStepX ? 1 : 0;
            int yChoices = stepY && canStepY ? 1 : 0;
            int zChoices = stepZ && canStepZ ? 1 : 0;
            for (int chooseX = 0; chooseX <= xChoices; chooseX++) {
                for (int chooseY = 0;
                        chooseY <= yChoices;
                        chooseY++) {
                    for (int chooseZ = 0;
                            chooseZ <= zChoices;
                            chooseZ++) {
                        if (chooseX == 0
                                && chooseY == 0
                                && chooseZ == 0) {
                            continue;
                        }
                        Candidate candidate =
                                hitInBlock(
                                        origin,
                                        directionX,
                                        directionY,
                                        directionZ,
                                        maxDistance,
                                        blockX
                                                + chooseX
                                                        * xTraversal.step(),
                                        blockY
                                                + chooseY
                                                        * yTraversal.step(),
                                        blockZ
                                                + chooseZ
                                                        * zTraversal.step());
                        if (candidate != null
                                && sameEvent(
                                        candidate.distance(),
                                        nextDistance)
                                && (best == null
                                        || candidate.isBetterThan(best))) {
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null
                    && sameEvent(
                            best.distance(), nextDistance)) {
                return Optional.of(
                        best.toHit(
                                origin,
                                directionX,
                                directionY,
                                directionZ));
            }
            if (!canStepX || !canStepY || !canStepZ) {
                return Optional.empty();
            }
            if (stepX) {
                blockX += xTraversal.step();
                xTraversal = xTraversal.advance();
            }
            if (stepY) {
                blockY += yTraversal.step();
                yTraversal = yTraversal.advance();
            }
            if (stepZ) {
                blockZ += zTraversal.step();
                zTraversal = zTraversal.advance();
            }
        }
    }

    private Candidate hitInBlock(
            Vector3fc origin,
            double directionX,
            double directionY,
            double directionZ,
            float maxDistance,
            int blockX,
            int blockY,
            int blockZ) {
        byte blockId = world.getBlock(blockX, blockY, blockZ);
        BlockCollisionShape shape =
                Objects.requireNonNull(
                        shapeResolver.shapeFor(blockId),
                        "shapeResolver result");
        return nearestHit(
                origin,
                directionX,
                directionY,
                directionZ,
                maxDistance,
                blockX,
                blockY,
                blockZ,
                blockId,
                shape.boxes());
    }

    private static Candidate nearestHit(
            Vector3fc origin,
            double directionX,
            double directionY,
            double directionZ,
            float maxDistance,
            int blockX,
            int blockY,
            int blockZ,
            byte blockId,
            List<Aabb> boxes) {
        Candidate best = null;
        for (int subShapeIndex = 0;
                subShapeIndex < boxes.size();
                subShapeIndex++) {
            Aabb localShape = boxes.get(subShapeIndex);
            TranslatedBounds shape =
                    translate(localShape, blockX, blockY, blockZ);
            Candidate candidate =
                    intersect(
                            origin,
                            directionX,
                            directionY,
                            directionZ,
                            maxDistance,
                            blockX,
                            blockY,
                            blockZ,
                            blockId,
                            subShapeIndex,
                            shape);
            if (candidate != null
                    && (best == null || candidate.isBetterThan(best))) {
                best = candidate;
            }
        }
        return best;
    }

    private static Candidate intersect(
            Vector3fc origin,
            double directionX,
            double directionY,
            double directionZ,
            float maxDistance,
            int blockX,
            int blockY,
            int blockZ,
            byte blockId,
            int subShapeIndex,
            TranslatedBounds shape) {
        if (strictlyContains(shape, origin)) {
            return insideCandidate(
                    directionX,
                    directionY,
                    directionZ,
                    blockX,
                    blockY,
                    blockZ,
                    blockId,
                    subShapeIndex);
        }

        AxisIntersection x =
                intersectAxis(
                        origin.x(),
                        directionX,
                        shape.minX(),
                        shape.maxX(),
                        X_AXIS_PRIORITY);
        AxisIntersection y =
                intersectAxis(
                        origin.y(),
                        directionY,
                        shape.minY(),
                        shape.maxY(),
                        Y_AXIS_PRIORITY);
        AxisIntersection z =
                intersectAxis(
                        origin.z(),
                        directionZ,
                        shape.minZ(),
                        shape.maxZ(),
                        Z_AXIS_PRIORITY);
        if (x == null || y == null || z == null) {
            return null;
        }

        double entry =
                Math.max(x.entry(), Math.max(y.entry(), z.entry()));
        double exit = Math.min(x.exit(), Math.min(y.exit(), z.exit()));
        if (compareEvents(entry, exit) > 0
                || exit < 0
                || entry < 0
                || entry > maxDistance) {
            return null;
        }

        AxisIntersection contactAxis;
        if (sameEvent(y.entry(), entry)) {
            contactAxis = y;
        } else if (sameEvent(x.entry(), entry)) {
            contactAxis = x;
        } else {
            contactAxis = z;
        }
        return new Candidate(
                entry == 0 ? 0 : entry,
                contactAxis.priority(),
                contactAxis.normalX(),
                contactAxis.normalY(),
                contactAxis.normalZ(),
                blockX,
                blockY,
                blockZ,
                blockId,
                subShapeIndex);
    }

    private static Candidate insideCandidate(
            double directionX,
            double directionY,
            double directionZ,
            int blockX,
            int blockY,
            int blockZ,
            byte blockId,
            int subShapeIndex) {
        double absoluteX = Math.abs(directionX);
        double absoluteY = Math.abs(directionY);
        double absoluteZ = Math.abs(directionZ);
        if (absoluteY >= absoluteX && absoluteY >= absoluteZ) {
            return new Candidate(
                    0,
                    Y_AXIS_PRIORITY,
                    0,
                    directionY > 0 ? -1 : 1,
                    0,
                    blockX,
                    blockY,
                    blockZ,
                    blockId,
                    subShapeIndex);
        }
        if (absoluteX >= absoluteZ) {
            return new Candidate(
                    0,
                    X_AXIS_PRIORITY,
                    directionX > 0 ? -1 : 1,
                    0,
                    0,
                    blockX,
                    blockY,
                    blockZ,
                    blockId,
                    subShapeIndex);
        }
        return new Candidate(
                0,
                Z_AXIS_PRIORITY,
                0,
                0,
                directionZ > 0 ? -1 : 1,
                blockX,
                blockY,
                blockZ,
                blockId,
                subShapeIndex);
    }

    private static AxisIntersection intersectAxis(
            double origin,
            double direction,
            double minimum,
            double maximum,
            int priority) {
        if (direction == 0) {
            if (origin < minimum || origin > maximum) {
                return null;
            }
            return new AxisIntersection(
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    priority,
                    0,
                    0,
                    0);
        }

        double first = (minimum - origin) / direction;
        double second = (maximum - origin) / direction;
        double entry = Math.min(first, second);
        double exit = Math.max(first, second);
        float normal = direction > 0 ? -1 : 1;
        return switch (priority) {
            case X_AXIS_PRIORITY ->
                    new AxisIntersection(
                            entry, exit, priority, normal, 0, 0);
            case Y_AXIS_PRIORITY ->
                    new AxisIntersection(
                            entry, exit, priority, 0, normal, 0);
            case Z_AXIS_PRIORITY ->
                    new AxisIntersection(
                            entry, exit, priority, 0, 0, normal);
            default -> throw new IllegalArgumentException("Unknown axis");
        };
    }

    private static boolean strictlyContains(
            TranslatedBounds shape, Vector3fc point) {
        return point.x() > shape.minX()
                && point.x() < shape.maxX()
                && point.y() > shape.minY()
                && point.y() < shape.maxY()
                && point.z() > shape.minZ()
                && point.z() < shape.maxZ();
    }

    private static TranslatedBounds translate(
            Aabb localShape, int blockX, int blockY, int blockZ) {
        return new TranslatedBounds(
                (double) blockX + localShape.minX(),
                (double) blockY + localShape.minY(),
                (double) blockZ + localShape.minZ(),
                (double) blockX + localShape.maxX(),
                (double) blockY + localShape.maxY(),
                (double) blockZ + localShape.maxZ());
    }

    private static AxisTraversal traversal(
            double origin, double direction, int blockCoordinate) {
        if (direction == 0) {
            return new AxisTraversal(
                    0,
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY);
        }
        int step = direction > 0 ? 1 : -1;
        double boundary =
                step > 0
                        ? (double) blockCoordinate + 1
                        : blockCoordinate;
        return new AxisTraversal(
                step,
                (boundary - origin) / direction,
                Math.abs(1 / direction));
    }

    private static boolean cannotStep(int coordinate, int step) {
        return step > 0
                ? coordinate == Integer.MAX_VALUE
                : coordinate == Integer.MIN_VALUE;
    }

    private static int compareEvents(double first, double second) {
        return sameEvent(first, second)
                ? 0
                : Double.compare(first, second);
    }

    private static boolean sameEvent(double first, double second) {
        if (first == second) {
            return true;
        }
        if (!Double.isFinite(first) || !Double.isFinite(second)) {
            return false;
        }
        double scale =
                Math.max(
                        1,
                        Math.max(
                                Math.abs(first),
                                Math.abs(second)));
        return Math.abs(first - second)
                <= EVENT_RELATIVE_EPSILON * scale;
    }

    private static int floorBlockCoordinate(float value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "origin exceeds integer block coordinates");
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

    private record AxisTraversal(
            int step, double nextDistance, double distancePerCell) {
        private AxisTraversal advance() {
            return new AxisTraversal(
                    step,
                    nextDistance + distancePerCell,
                    distancePerCell);
        }
    }

    private record TranslatedBounds(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {}

    private record AxisIntersection(
            double entry,
            double exit,
            int priority,
            float normalX,
            float normalY,
            float normalZ) {}

    private record Candidate(
            double distance,
            int axisPriority,
            float normalX,
            float normalY,
            float normalZ,
            int blockX,
            int blockY,
            int blockZ,
            byte blockId,
            int subShapeIndex) {
        private boolean isBetterThan(Candidate other) {
            int distanceComparison =
                    compareEvents(distance, other.distance);
            if (distanceComparison != 0) {
                return distanceComparison < 0;
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

        private BlockRaycastHit toHit(
                Vector3fc origin,
                double directionX,
                double directionY,
                double directionZ) {
            float hitDistance = (float) distance;
            return new BlockRaycastHit(
                    blockX,
                    blockY,
                    blockZ,
                    Math.addExact(blockX, (int) normalX),
                    Math.addExact(blockY, (int) normalY),
                    Math.addExact(blockZ, (int) normalZ),
                    blockId,
                    normalX,
                    normalY,
                    normalZ,
                    (float) (origin.x() + directionX * distance),
                    (float) (origin.y() + directionY * distance),
                    (float) (origin.z() + directionZ * distance),
                    hitDistance == 0 ? 0 : hitDistance);
        }
    }
}
