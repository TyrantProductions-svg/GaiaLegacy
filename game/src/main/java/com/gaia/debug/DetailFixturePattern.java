package com.gaia.debug;

import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic development-only DETAIL_4 acceptance fixtures. */
public enum DetailFixturePattern {
    SINGLE_QUARTER,
    QUARTER_SLAB,
    THIN_WALL,
    STAIRCASE,
    HOLLOW_OPENING,
    ASYMMETRIC,
    CHECKERBOARD,
    UNIFORM_FULL,
    MIXED_MATERIAL;

    public DetailCellState state(byte primaryBlockId, byte secondaryBlockId) {
        requireMaterial(primaryBlockId, "primaryBlockId");
        requireMaterial(secondaryBlockId, "secondaryBlockId");
        long occupancy = 0L;
        byte[] blockIds = new byte[DetailCellState.CELL_COUNT];
        for (int index = 0; index < DetailCellState.CELL_COUNT; index++) {
            LocalSubVoxelPosition position = LocalSubVoxelPosition.fromIndex(index);
            if (!occupied(position.x(), position.y(), position.z())) {
                continue;
            }
            occupancy |= 1L << index;
            blockIds[index] = usesSecondary(position.x(), position.y(), position.z())
                    ? secondaryBlockId
                    : primaryBlockId;
        }
        return new DetailCellState(occupancy, blockIds);
    }

    public static String canonicalHash(DetailCellState state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(state.occupancyMask())
                    .array());
            digest.update(state.copyBlockIds());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean occupied(int x, int y, int z) {
        return switch (this) {
            case SINGLE_QUARTER -> x == 0 && y == 0 && z == 0;
            case QUARTER_SLAB, MIXED_MATERIAL -> y == 0;
            case THIN_WALL -> x == 0;
            case STAIRCASE -> y <= x;
            case HOLLOW_OPENING -> x == 0
                    && !((y == 1 || y == 2) && (z == 1 || z == 2));
            case ASYMMETRIC ->
                    (x == 0 && y == 0 && z == 0)
                            || (x == 3 && y == 0 && z == 0)
                            || (x == 1 && y == 1 && z == 0)
                            || (x == 2 && y == 3 && z == 1)
                            || (x == 0 && y == 2 && z == 2)
                            || (x == 3 && y == 1 && z == 3)
                            || (x == 2 && y == 2 && z == 3);
            case CHECKERBOARD -> ((x + y + z) & 1) == 0;
            case UNIFORM_FULL -> true;
        };
    }

    private boolean usesSecondary(int x, int y, int z) {
        return this == MIXED_MATERIAL && ((x + z) & 1) != 0;
    }

    private static void requireMaterial(byte blockId, String name) {
        if (blockId == 0) {
            throw new IllegalArgumentException(name + " must be non-AIR");
        }
    }
}
