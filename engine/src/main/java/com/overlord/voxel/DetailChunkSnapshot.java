package com.overlord.voxel;

import java.util.Arrays;
import java.util.Optional;

public final class DetailChunkSnapshot {
    private static final DetailChunkSnapshot EMPTY_VIEW =
            new DetailChunkSnapshot(
                    new short[0], new long[0], new byte[0]);

    private final short[] parentIndices;
    private final long[] occupancyMasks;
    private final byte[] blockIds;

    private DetailChunkSnapshot(
            short[] parentIndices,
            long[] occupancyMasks,
            byte[] blockIds) {
        this.parentIndices = Arrays.copyOf(parentIndices, parentIndices.length);
        this.occupancyMasks =
                Arrays.copyOf(occupancyMasks, occupancyMasks.length);
        this.blockIds = Arrays.copyOf(blockIds, blockIds.length);
    }

    public static DetailChunkSnapshot of(
            int[] parentIndices,
            long[] occupancyMasks,
            byte[] blockIds) {
        if (parentIndices == null
                || occupancyMasks == null
                || blockIds == null) {
            throw new NullPointerException(
                    "DETAIL snapshot arrays must not be null");
        }
        if (parentIndices.length == 0
                && occupancyMasks.length == 0
                && blockIds.length == 0) {
            return EMPTY_VIEW;
        }
        validate(parentIndices, occupancyMasks, blockIds);
        short[] compactIndices = new short[parentIndices.length];
        for (int entry = 0; entry < parentIndices.length; entry++) {
            compactIndices[entry] = (short) parentIndices[entry];
        }
        return new DetailChunkSnapshot(
                compactIndices, occupancyMasks, blockIds);
    }

    public static DetailChunkSnapshot emptyView() {
        return EMPTY_VIEW;
    }

    public int entryCount() {
        return parentIndices.length;
    }

    public boolean isEmpty() {
        return parentIndices.length == 0;
    }

    public Optional<DetailCellState> stateAtParentIndex(int parentIndex) {
        if (parentIndex < 0 || parentIndex > 0xffff) {
            throw new IllegalArgumentException(
                    "parentIndex must be an unsigned 16-bit value");
        }
        int entry = binarySearchUnsigned(parentIndex);
        if (entry < 0) {
            return Optional.empty();
        }
        return Optional.of(
                new DetailCellState(
                        occupancyMasks[entry],
                        Arrays.copyOfRange(
                                blockIds,
                                entry * DetailCellState.CELL_COUNT,
                                (entry + 1) * DetailCellState.CELL_COUNT)));
    }

    int blockIdAt(int parentIndex, int subIndex) {
        if (parentIndex < 0 || parentIndex > 0xffff) {
            throw new IllegalArgumentException(
                    "parentIndex must be an unsigned 16-bit value");
        }
        if (subIndex < 0 || subIndex >= DetailCellState.CELL_COUNT) {
            throw new IllegalArgumentException(
                    "subIndex must be between 0 and 63");
        }
        int entry = binarySearchUnsigned(parentIndex);
        if (entry < 0) {
            return -1;
        }
        return Byte.toUnsignedInt(
                blockIds[entry * DetailCellState.CELL_COUNT + subIndex]);
    }

    public int[] copyParentIndices() {
        int[] copy = new int[parentIndices.length];
        for (int entry = 0; entry < parentIndices.length; entry++) {
            copy[entry] = Short.toUnsignedInt(parentIndices[entry]);
        }
        return copy;
    }

    public long[] copyOccupancyMasks() {
        return Arrays.copyOf(occupancyMasks, occupancyMasks.length);
    }

    public byte[] copyBlockIds() {
        return Arrays.copyOf(blockIds, blockIds.length);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DetailChunkSnapshot other)) {
            return false;
        }
        return Arrays.equals(parentIndices, other.parentIndices)
                && Arrays.equals(occupancyMasks, other.occupancyMasks)
                && Arrays.equals(blockIds, other.blockIds);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(parentIndices);
        result = 31 * result + Arrays.hashCode(occupancyMasks);
        return 31 * result + Arrays.hashCode(blockIds);
    }

    private static void validate(
            int[] parentIndices,
            long[] occupancyMasks,
            byte[] blockIds) {
        if (parentIndices.length == 0) {
            throw new IllegalArgumentException(
                    "stored DETAIL snapshot must not be empty");
        }
        if (parentIndices.length > Chunk.MAX_DETAIL_PARENTS_PER_CHUNK) {
            throw new IllegalArgumentException(
                    "DETAIL snapshot exceeds Chunk parent capacity");
        }
        if (parentIndices.length != occupancyMasks.length
                || blockIds.length
                        != parentIndices.length * DetailCellState.CELL_COUNT) {
            throw new IllegalArgumentException(
                    "DETAIL snapshot arrays must have matching lengths");
        }
        int previous = -1;
        for (int entry = 0; entry < parentIndices.length; entry++) {
            int parentIndex = parentIndices[entry];
            if (parentIndex < 0 || parentIndex > 0xffff) {
                throw new IllegalArgumentException(
                        "DETAIL parent index must be unsigned 16-bit");
            }
            if (parentIndex <= previous) {
                throw new IllegalArgumentException(
                        "DETAIL parent indices must be strictly ascending");
            }
            previous = parentIndex;
            new DetailCellState(
                    occupancyMasks[entry],
                    Arrays.copyOfRange(
                            blockIds,
                            entry * DetailCellState.CELL_COUNT,
                            (entry + 1) * DetailCellState.CELL_COUNT));
        }
    }

    private int binarySearchUnsigned(int target) {
        int low = 0;
        int high = parentIndices.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int candidate = Short.toUnsignedInt(parentIndices[middle]);
            if (candidate < target) {
                low = middle + 1;
            } else if (candidate > target) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }
}
