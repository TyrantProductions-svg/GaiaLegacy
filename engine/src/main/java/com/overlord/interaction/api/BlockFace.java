package com.overlord.interaction.api;

import java.util.Objects;

public enum BlockFace {
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    SOUTH(0, 0, 1),
    NORTH(0, 0, -1);

    private final int normalX;
    private final int normalY;
    private final int normalZ;

    BlockFace(int normalX, int normalY, int normalZ) {
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
    }

    public int normalX() {
        return normalX;
    }

    public int normalY() {
        return normalY;
    }

    public int normalZ() {
        return normalZ;
    }

    public static BlockFace fromNormal(int normalX, int normalY, int normalZ) {
        for (BlockFace face : values()) {
            if (face.normalX == normalX
                    && face.normalY == normalY
                    && face.normalZ == normalZ) {
                return face;
            }
        }
        throw new IllegalArgumentException("normal must identify a block face");
    }

    public static BlockFace fromHit(BlockHitResult hit) {
        BlockHitResult result = Objects.requireNonNull(hit, "hit");
        return fromNormal(result.normalX(), result.normalY(), result.normalZ());
    }
}
