package com.overlord.voxel;

import java.util.Arrays;
import java.util.Objects;

public final class DetailCellState implements ParentCellState {
    public static final int CELL_COUNT = VoxelScale.DETAIL_4.cellCount();

    private final long occupancyMask;
    private final byte[] blockIds;

    public DetailCellState(long occupancyMask, byte[] blockIds) {
        if (occupancyMask == 0L) {
            throw new IllegalArgumentException(
                    "occupancyMask must contain at least one occupied cell");
        }
        Objects.requireNonNull(blockIds, "blockIds");
        if (blockIds.length != CELL_COUNT) {
            throw new IllegalArgumentException(
                    "blockIds must contain exactly 64 entries");
        }
        for (int index = 0; index < CELL_COUNT; index++) {
            boolean occupied = (occupancyMask & (1L << index)) != 0L;
            boolean hasMaterial = blockIds[index] != 0;
            if (occupied != hasMaterial) {
                throw new IllegalArgumentException(
                        "occupancy and block ID disagree at index " + index);
            }
        }
        this.occupancyMask = occupancyMask;
        this.blockIds = Arrays.copyOf(blockIds, blockIds.length);
    }

    public static DetailCellState uniform(byte blockId) {
        if (blockId == 0) {
            throw new IllegalArgumentException(
                    "uniform detail block ID must be nonzero");
        }
        byte[] blockIds = new byte[CELL_COUNT];
        Arrays.fill(blockIds, blockId);
        return new DetailCellState(-1L, blockIds);
    }

    public long occupancyMask() {
        return occupancyMask;
    }

    public boolean occupied(LocalSubVoxelPosition position) {
        Objects.requireNonNull(position, "position");
        return (occupancyMask & (1L << position.index())) != 0L;
    }

    public byte blockId(LocalSubVoxelPosition position) {
        Objects.requireNonNull(position, "position");
        return blockIds[position.index()];
    }

    public byte blockIdAtIndex(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IllegalArgumentException(
                    "index must be between 0 and 63");
        }
        return blockIds[index];
    }

    public byte[] copyBlockIds() {
        return Arrays.copyOf(blockIds, blockIds.length);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DetailCellState other)) {
            return false;
        }
        return occupancyMask == other.occupancyMask
                && Arrays.equals(blockIds, other.blockIds);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(occupancyMask);
        return 31 * result + Arrays.hashCode(blockIds);
    }
}
