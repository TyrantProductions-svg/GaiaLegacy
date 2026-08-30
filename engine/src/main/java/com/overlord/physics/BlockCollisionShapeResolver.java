package com.overlord.physics;

import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import java.util.Objects;

@FunctionalInterface
public interface BlockCollisionShapeResolver {
    BlockCollisionShape shapeFor(byte blockId);

    default BlockCollisionShape shapeFor(
            ParentCellState state,
            DetailCollisionBoxMerger detailMerger) {
        ParentCellState requiredState = Objects.requireNonNull(state, "state");
        DetailCollisionBoxMerger requiredMerger =
                Objects.requireNonNull(detailMerger, "detailMerger");
        if (requiredState instanceof FullCellState full) {
            return shapeFor(full.blockId());
        }
        return requiredMerger.merge((DetailCellState) requiredState);
    }

    static BlockCollisionShapeResolver fullCubesForNonAir() {
        return blockId ->
                blockId == 0
                        ? BlockCollisionShape.empty()
                        : BlockCollisionShape.fullCube();
    }
}
