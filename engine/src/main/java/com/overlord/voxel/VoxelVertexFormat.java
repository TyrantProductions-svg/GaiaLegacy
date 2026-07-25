package com.overlord.voxel;

import java.util.List;
import java.util.Objects;

public final class VoxelVertexFormat {
    public static final int FLOATS_PER_VERTEX = 10;
    public static final int STRIDE_BYTES = 40;
    public static final int DEFAULT_LIGHT_LEVEL = 15;
    public static final float DEFAULT_AMBIENT_OCCLUSION = 1.0f;

    private static final List<VoxelVertexAttribute> ATTRIBUTES =
            List.of(
                    new VoxelVertexAttribute(0, 3, 0),
                    new VoxelVertexAttribute(1, 2, 3),
                    new VoxelVertexAttribute(2, 3, 5),
                    new VoxelVertexAttribute(3, 1, 8),
                    new VoxelVertexAttribute(4, 1, 9));

    private VoxelVertexFormat() {}

    public static List<VoxelVertexAttribute> attributes() {
        return ATTRIBUTES;
    }

    public static int faceId(BlockFace face) {
        Objects.requireNonNull(face, "face");
        return switch (face) {
            case NORTH -> 0;
            case SOUTH -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case WEST -> 4;
            case EAST -> 5;
        };
    }

    public static float encodeFaceLight(
            BlockFace face, int lightLevel) {
        if (lightLevel < 0 || lightLevel > 15) {
            throw new IllegalArgumentException(
                    "lightLevel must be between 0 and 15");
        }
        return (float) (faceId(face) * 16 + lightLevel);
    }
}
