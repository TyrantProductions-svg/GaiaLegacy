package com.overlord.physics;

import com.overlord.voxel.BlockPlacement;
import com.overlord.voxel.World;
import java.util.List;

@FunctionalInterface
public interface BlockCollisionShapeResolver {
    BlockCollisionShape shapeFor(byte blockId);

    default BlockCollisionShape shapeFor(byte blockId, int x, int y, int z, World world) {
        return shapeFor(blockId);
    }

    static BlockCollisionShapeResolver fullCubesForNonAir() {
        return blockId ->
                blockId == 0
                        ? BlockCollisionShape.empty()
                        : BlockCollisionShape.fullCube();
    }

    static BlockCollisionShapeResolver sizeAwareForNonAir() {
        return new BlockCollisionShapeResolver() {
            @Override
            public BlockCollisionShape shapeFor(byte blockId) {
                return blockId == 0
                        ? BlockCollisionShape.empty()
                        : BlockCollisionShape.fullCube();
            }

            @Override
            public BlockCollisionShape shapeFor(byte blockId, int x, int y, int z, World world) {
                if (blockId == 0) {
                    return BlockCollisionShape.empty();
                }

                BlockPlacement placement = world.getBlockPlacement(x, y, z);
                if (placement.isFullBlock()) {
                    return BlockCollisionShape.fullCube();
                }

                return BlockCollisionShape.of(
                    List.of(new Aabb(
                        placement.offsetX(),
                        placement.offsetY(),
                        placement.offsetZ(),
                        placement.offsetX() + placement.size().units(),
                        placement.offsetY() + placement.size().units(),
                        placement.offsetZ() + placement.size().units()
                    ))
                );
            }
        };
    }
}