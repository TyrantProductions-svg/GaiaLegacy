package com.overlord.physics;

@FunctionalInterface
public interface BlockCollisionShapeResolver {
    BlockCollisionShape shapeFor(byte blockId);

    static BlockCollisionShapeResolver fullCubesForNonAir() {
        return blockId ->
                blockId == 0
                        ? BlockCollisionShape.empty()
                        : BlockCollisionShape.fullCube();
    }
}
