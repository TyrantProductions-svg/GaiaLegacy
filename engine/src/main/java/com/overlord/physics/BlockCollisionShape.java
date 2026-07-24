package com.overlord.physics;

import java.util.List;
import java.util.Objects;

public final class BlockCollisionShape {
    private static final BlockCollisionShape EMPTY =
            new BlockCollisionShape(List.of());
    private static final BlockCollisionShape FULL_CUBE =
            new BlockCollisionShape(
                    List.of(new Aabb(0, 0, 0, 1, 1, 1)));

    private final List<Aabb> boxes;

    private BlockCollisionShape(List<Aabb> boxes) {
        this.boxes = boxes;
    }

    public static BlockCollisionShape empty() {
        return EMPTY;
    }

    public static BlockCollisionShape fullCube() {
        return FULL_CUBE;
    }

    public static BlockCollisionShape of(List<Aabb> boxes) {
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.isEmpty()) {
            return EMPTY;
        }
        List<Aabb> copy = List.copyOf(boxes);
        for (Aabb box : copy) {
            if (box.minX() < 0
                    || box.minY() < 0
                    || box.minZ() < 0
                    || box.maxX() > 1
                    || box.maxY() > 1
                    || box.maxZ() > 1) {
                throw new IllegalArgumentException(
                        "Block collision boxes must stay within the unit block");
            }
        }
        return new BlockCollisionShape(copy);
    }

    public List<Aabb> boxes() {
        return boxes;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }
}
