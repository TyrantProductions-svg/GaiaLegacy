package com.overlord.physics;

import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.VoxelScale;
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
        try {
            return castInternal(QueryContext.legacy(), origin, direction, maxDistance);
        } catch (UnavailableSpace unavailable) {
            throw new IllegalStateException(
                    "Raycast entered " + unavailable.status + " canonical space at " + unavailable.key,
                    unavailable);
        }
    }

    public SpatialQueryResult<BlockRaycastHit> cast(
            SimulationOrigin simulationOrigin,
            Vector3fc origin,
            Vector3fc direction,
            float maxDistance) {
        Objects.requireNonNull(simulationOrigin, "simulationOrigin");
        try {
            return SpatialQueryResult.available(
                    castInternal(new QueryContext(
                            simulationOrigin.worldOriginX(),
                            simulationOrigin.worldOriginZ()), origin, direction, maxDistance));
        } catch (UnavailableSpace unavailable) {
            return SpatialQueryResult.unavailable(unavailable.status, unavailable.key);
        }
    }

    private Optional<BlockRaycastHit> castInternal(
            QueryContext queryContext,
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
                            queryContext,
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
                                directionZ,
                                queryContext.originX(),
                                queryContext.originZ()));
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
                                        queryContext,
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
                                directionZ,
                                queryContext.originX(),
                                queryContext.originZ()));
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
            QueryContext queryContext,
            Vector3fc origin,
            double directionX,
            double directionY,
            double directionZ,
            float maxDistance,
            int blockX,
            int blockY,
            int blockZ) {
        int globalX = queryContext.globalX(blockX);
        int globalZ = queryContext.globalZ(blockZ);
        ParentCellObservationResult observationResult =
                world.observeCell(globalX, blockY, globalZ);
        if (observationResult.status() != ChunkAvailability.AVAILABLE) {
            ChunkKey key = observationResult.unavailableKey().orElseThrow();
            throw new UnavailableSpace(
                    observationResult.status() == ChunkAvailability.FAILED
                            ? SpatialQueryResult.Status.FAILED
                            : SpatialQueryResult.Status.UNKNOWN,
                    key);
        }
        if (observationResult.observation().isEmpty()) {
            return null;
        }
        ParentCellObservation observation =
                observationResult.observation().orElseThrow();
        if (observation.state() instanceof FullCellState full) {
            byte blockId = full.blockId();
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
                    globalX,
                    globalZ,
                    blockId,
                    shape.boxes(),
                    observation.chunkRevision());
        }
        return nearestDetailHit(
                origin,
                directionX,
                directionY,
                directionZ,
                maxDistance,
                blockX,
                blockY,
                blockZ,
                globalX,
                globalZ,
                (DetailCellState) observation.state(),
                observation.chunkRevision());
    }

    private static Candidate nearestDetailHit(
            Vector3fc origin,
            double directionX,
            double directionY,
            double directionZ,
            float maxDistance,
            int blockX,
            int blockY,
            int blockZ,
            int globalBlockX,
            int globalBlockZ,
            DetailCellState detail,
            long chunkRevision) {
        Candidate best = null;
        long remaining = detail.occupancyMask();
        while (remaining != 0L) {
            int subIndex = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int subX = subIndex & 3;
            int subY = (subIndex >>> 2) & 3;
            int subZ = subIndex >>> 4;
            Candidate candidate = intersectBounds(
                    origin,
                    directionX,
                    directionY,
                    directionZ,
                    maxDistance,
                    globalBlockX,
                    blockY,
                    globalBlockZ,
                    detail.blockIdAtIndex(subIndex),
                    subIndex,
                    chunkRevision,
                    subIndex,
                    blockX + subX * 0.25,
                    blockY + subY * 0.25,
                    blockZ + subZ * 0.25,
                    blockX + (subX + 1) * 0.25,
                    blockY + (subY + 1) * 0.25,
                    blockZ + (subZ + 1) * 0.25);
            if (candidate != null
                    && (best == null || candidate.isBetterThan(best))) {
                best = candidate;
            }
        }
        return best;
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
            int globalBlockX,
            int globalBlockZ,
            byte blockId,
            List<Aabb> boxes,
            long chunkRevision) {
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
                            globalBlockX,
                            blockY,
                            globalBlockZ,
                            blockId,
                            subShapeIndex,
                            chunkRevision,
                            -1,
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
            long chunkRevision,
            int detailSubIndex,
            TranslatedBounds shape) {
        return intersectBounds(
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
                chunkRevision,
                detailSubIndex,
                shape.minX(),
                shape.minY(),
                shape.minZ(),
                shape.maxX(),
                shape.maxY(),
                shape.maxZ());
    }

    private static Candidate intersectBounds(
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
            long chunkRevision,
            int detailSubIndex,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        if (strictlyContains(
                minX, minY, minZ, maxX, maxY, maxZ, origin)) {
            return insideCandidate(
                    directionX,
                    directionY,
                    directionZ,
                    blockX,
                    blockY,
                    blockZ,
                    blockId,
                    subShapeIndex,
                    chunkRevision,
                    detailSubIndex);
        }

        AxisIntersection x =
                intersectAxis(
                        origin.x(),
                        directionX,
                        minX,
                        maxX,
                        X_AXIS_PRIORITY);
        AxisIntersection y =
                intersectAxis(
                        origin.y(),
                        directionY,
                        minY,
                        maxY,
                        Y_AXIS_PRIORITY);
        AxisIntersection z =
                intersectAxis(
                        origin.z(),
                        directionZ,
                        minZ,
                        maxZ,
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
                subShapeIndex,
                chunkRevision,
                detailSubIndex);
    }

    private static Candidate insideCandidate(
            double directionX,
            double directionY,
            double directionZ,
            int blockX,
            int blockY,
            int blockZ,
            byte blockId,
            int subShapeIndex,
            long chunkRevision,
            int detailSubIndex) {
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
                    subShapeIndex,
                    chunkRevision,
                    detailSubIndex);
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
                    subShapeIndex,
                    chunkRevision,
                    detailSubIndex);
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
                subShapeIndex,
                chunkRevision,
                detailSubIndex);
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
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            Vector3fc point) {
        return point.x() > minX
                && point.x() < maxX
                && point.y() > minY
                && point.y() < maxY
                && point.z() > minZ
                && point.z() < maxZ;
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

    private record QueryContext(long originX, long originZ) {
        private static QueryContext legacy() {
            return new QueryContext(0, 0);
        }

        private int globalX(int localX) {
            return Math.toIntExact(Math.addExact(originX, localX));
        }

        private int globalZ(int localZ) {
            return Math.toIntExact(Math.addExact(originZ, localZ));
        }
    }

    private static final class UnavailableSpace extends RuntimeException {
        private final SpatialQueryResult.Status status;
        private final ChunkKey key;

        private UnavailableSpace(SpatialQueryResult.Status status, ChunkKey key) {
            super(null, null, false, false);
            this.status = status;
            this.key = key;
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
            int subShapeIndex,
            long chunkRevision,
            int detailSubIndex) {
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
                double directionZ,
                long worldOriginX,
                long worldOriginZ) {
            float hitDistance = (float) distance;
            double localPointX = origin.x() + directionX * distance;
            double localPointY = origin.y() + directionY * distance;
            double localPointZ = origin.z() + directionZ * distance;
            RaycastCellTarget target = detailSubIndex >= 0
                    ? new DetailRaycastTarget(
                            VoxelScale.DETAIL_4,
                            com.overlord.voxel.LocalSubVoxelPosition.fromIndex(
                                    detailSubIndex))
                    : FullRaycastTarget.INSTANCE;
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
                    (float) localPointX,
                    (float) localPointY,
                    (float) localPointZ,
                    hitDistance == 0 ? 0 : hitDistance,
                    (double) worldOriginX + localPointX,
                    localPointY,
                    (double) worldOriginZ + localPointZ,
                    chunkRevision,
                    target);
        }
    }
}
