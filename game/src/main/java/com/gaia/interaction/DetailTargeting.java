package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.VoxelScale;
import java.util.Objects;

public final class DetailTargeting {
    private DetailTargeting() {}

    public static DetailPrecisionTarget removalTarget(
            BlockHitResult hit) {
        Objects.requireNonNull(hit, "hit");
        BlockFace face = BlockFace.fromHit(hit);
        LocalSubVoxelPosition local;
        if (hit.target() instanceof DetailRaycastTarget detail) {
            local = detail.position();
        } else if (hit.target() instanceof FullRaycastTarget) {
            int x = quarter(hit.worldPointX(), hit.blockX());
            int y = quarter(hit.worldPointY(), hit.blockY());
            int z = quarter(hit.worldPointZ(), hit.blockZ());
            switch (face) {
                case EAST -> x = 3;
                case WEST -> x = 0;
                case UP -> y = 3;
                case DOWN -> y = 0;
                case SOUTH -> z = 3;
                case NORTH -> z = 0;
            }
            local = new LocalSubVoxelPosition(x, y, z);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported raycast target " + hit.target());
        }
        return new DetailPrecisionTarget(
                hit.blockX(),
                hit.blockY(),
                hit.blockZ(),
                local,
                face,
                hit.block(),
                hit.chunkRevision(),
                hit.target());
    }

    public static DetailPlacementCandidate placementCandidate(
            BlockHitResult hit,
            ResourceLocation material,
            DetailTargetWorldView worldView) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(worldView, "worldView");
        DetailPrecisionTarget source = removalTarget(hit);
        int parentX = source.parentX();
        int parentY = source.parentY();
        int parentZ = source.parentZ();
        int localX = source.localPosition().x() + source.face().normalX();
        int localY = source.localPosition().y() + source.face().normalY();
        int localZ = source.localPosition().z() + source.face().normalZ();
        int subdivisions = VoxelScale.DETAIL_4.subdivisionsPerAxis();
        if (localX < 0) {
            parentX = Math.subtractExact(parentX, 1);
            localX = subdivisions - 1;
        } else if (localX >= subdivisions) {
            parentX = Math.addExact(parentX, 1);
            localX = 0;
        }
        if (localY < 0) {
            parentY = Math.subtractExact(parentY, 1);
            localY = subdivisions - 1;
        } else if (localY >= subdivisions) {
            parentY = Math.addExact(parentY, 1);
            localY = 0;
        }
        if (localZ < 0) {
            parentZ = Math.subtractExact(parentZ, 1);
            localZ = subdivisions - 1;
        } else if (localZ >= subdivisions) {
            parentZ = Math.addExact(parentZ, 1);
            localZ = 0;
        }

        LocalSubVoxelPosition local =
                new LocalSubVoxelPosition(localX, localY, localZ);
        ParentCellObservationResult destination =
                Objects.requireNonNull(
                        worldView.observeCell(parentX, parentY, parentZ),
                        "destination observation");
        DetailPlacementCandidate.Status status =
                candidateStatus(destination, local);
        return new DetailPlacementCandidate(
                source,
                parentX,
                parentY,
                parentZ,
                local,
                material,
                destination,
                status);
    }

    private static DetailPlacementCandidate.Status candidateStatus(
            ParentCellObservationResult destination,
            LocalSubVoxelPosition local) {
        return switch (destination.status()) {
            case UNKNOWN -> DetailPlacementCandidate.Status.UNKNOWN;
            case FAILED -> DetailPlacementCandidate.Status.FAILED;
            case AVAILABLE -> {
                var observation = destination.observation().orElse(null);
                if (observation == null) {
                    yield DetailPlacementCandidate.Status.OUT_OF_BOUNDS;
                }
                if (observation.state() instanceof DetailCellState detail) {
                    yield detail.occupied(local)
                            ? DetailPlacementCandidate.Status.OCCUPIED
                            : DetailPlacementCandidate.Status.VALID_DETAIL_EMPTY;
                }
                FullCellState full = (FullCellState) observation.state();
                yield full.blockId() == 0
                        ? DetailPlacementCandidate.Status.VALID_FULL_AIR
                        : DetailPlacementCandidate.Status.OCCUPIED;
            }
        };
    }

    private static int quarter(
            double canonicalWorldCoordinate,
            int parentCoordinate) {
        double parentFraction =
                canonicalWorldCoordinate - (double) parentCoordinate;
        int subdivisions = VoxelScale.DETAIL_4.subdivisionsPerAxis();
        int index = (int) Math.floor(
                subdivisions * parentFraction);
        return Math.max(0, Math.min(subdivisions - 1, index));
    }
}
