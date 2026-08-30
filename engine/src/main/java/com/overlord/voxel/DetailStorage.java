package com.overlord.voxel;

import java.util.Arrays;
import java.util.Objects;

final class DetailStorage {
    private final short[] parentIndices;
    private final long[] occupancyMasks;
    private final byte[] blockIds;

    private DetailStorage(
            short[] parentIndices,
            long[] occupancyMasks,
            byte[] blockIds) {
        if (parentIndices.length == 0) {
            throw new IllegalArgumentException(
                    "stored DETAIL table must not be empty");
        }
        if (parentIndices.length != occupancyMasks.length
                || blockIds.length
                        != parentIndices.length * DetailCellState.CELL_COUNT) {
            throw new IllegalArgumentException(
                    "DETAIL table arrays must have matching lengths");
        }
        int previous = -1;
        for (int entry = 0; entry < parentIndices.length; entry++) {
            int parentIndex = Short.toUnsignedInt(parentIndices[entry]);
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
        this.parentIndices = parentIndices;
        this.occupancyMasks = occupancyMasks;
        this.blockIds = blockIds;
    }

    static DetailStorage single(
            int parentIndex, DetailCellState state) {
        requireParentIndex(parentIndex);
        Objects.requireNonNull(state, "state");
        return new DetailStorage(
                new short[] {(short) parentIndex},
                new long[] {state.occupancyMask()},
                state.copyBlockIds());
    }

    int size() {
        return parentIndices.length;
    }

    boolean contains(int parentIndex) {
        return search(parentIndex) >= 0;
    }

    DetailCellState stateAt(int parentIndex) {
        int entry = search(parentIndex);
        if (entry < 0) {
            return null;
        }
        return new DetailCellState(
                occupancyMasks[entry],
                Arrays.copyOfRange(
                        blockIds,
                        entry * DetailCellState.CELL_COUNT,
                        (entry + 1) * DetailCellState.CELL_COUNT));
    }

    DetailStorage put(
            int parentIndex,
            DetailCellState state,
            int maximumEntries) {
        requireParentIndex(parentIndex);
        Objects.requireNonNull(state, "state");
        int located = search(parentIndex);
        if (located >= 0) {
            long[] nextMasks = Arrays.copyOf(occupancyMasks, occupancyMasks.length);
            byte[] nextIds = Arrays.copyOf(blockIds, blockIds.length);
            nextMasks[located] = state.occupancyMask();
            System.arraycopy(
                    state.copyBlockIds(),
                    0,
                    nextIds,
                    located * DetailCellState.CELL_COUNT,
                    DetailCellState.CELL_COUNT);
            return new DetailStorage(
                    Arrays.copyOf(parentIndices, parentIndices.length),
                    nextMasks,
                    nextIds);
        }
        if (parentIndices.length >= maximumEntries) {
            throw new IllegalStateException(
                    "Chunk DETAIL parent capacity exceeded");
        }

        int insertion = -located - 1;
        short[] nextIndices = new short[parentIndices.length + 1];
        long[] nextMasks = new long[occupancyMasks.length + 1];
        byte[] nextIds =
                new byte[
                        blockIds.length
                                + DetailCellState.CELL_COUNT];
        System.arraycopy(parentIndices, 0, nextIndices, 0, insertion);
        System.arraycopy(
                parentIndices,
                insertion,
                nextIndices,
                insertion + 1,
                parentIndices.length - insertion);
        System.arraycopy(occupancyMasks, 0, nextMasks, 0, insertion);
        System.arraycopy(
                occupancyMasks,
                insertion,
                nextMasks,
                insertion + 1,
                occupancyMasks.length - insertion);
        int blockInsertion = insertion * DetailCellState.CELL_COUNT;
        System.arraycopy(blockIds, 0, nextIds, 0, blockInsertion);
        System.arraycopy(
                blockIds,
                blockInsertion,
                nextIds,
                blockInsertion + DetailCellState.CELL_COUNT,
                blockIds.length - blockInsertion);
        nextIndices[insertion] = (short) parentIndex;
        nextMasks[insertion] = state.occupancyMask();
        System.arraycopy(
                state.copyBlockIds(),
                0,
                nextIds,
                blockInsertion,
                DetailCellState.CELL_COUNT);
        return new DetailStorage(nextIndices, nextMasks, nextIds);
    }

    DetailStorage remove(int parentIndex) {
        int located = search(parentIndex);
        if (located < 0) {
            return this;
        }
        if (parentIndices.length == 1) {
            return null;
        }

        short[] nextIndices = new short[parentIndices.length - 1];
        long[] nextMasks = new long[occupancyMasks.length - 1];
        byte[] nextIds =
                new byte[
                        blockIds.length
                                - DetailCellState.CELL_COUNT];
        System.arraycopy(parentIndices, 0, nextIndices, 0, located);
        System.arraycopy(
                parentIndices,
                located + 1,
                nextIndices,
                located,
                nextIndices.length - located);
        System.arraycopy(occupancyMasks, 0, nextMasks, 0, located);
        System.arraycopy(
                occupancyMasks,
                located + 1,
                nextMasks,
                located,
                nextMasks.length - located);
        int blockOffset = located * DetailCellState.CELL_COUNT;
        System.arraycopy(blockIds, 0, nextIds, 0, blockOffset);
        System.arraycopy(
                blockIds,
                blockOffset + DetailCellState.CELL_COUNT,
                nextIds,
                blockOffset,
                nextIds.length - blockOffset);
        return new DetailStorage(nextIndices, nextMasks, nextIds);
    }

    int[] copyParentIndices() {
        int[] copied = new int[parentIndices.length];
        for (int index = 0; index < parentIndices.length; index++) {
            copied[index] = Short.toUnsignedInt(parentIndices[index]);
        }
        return copied;
    }

    long[] copyOccupancyMasks() {
        return Arrays.copyOf(occupancyMasks, occupancyMasks.length);
    }

    byte[] copyBlockIds() {
        return Arrays.copyOf(blockIds, blockIds.length);
    }

    DetailChunkSnapshot snapshot() {
        return DetailChunkSnapshot.of(
                copyParentIndices(),
                copyOccupancyMasks(),
                copyBlockIds());
    }

    private int search(int parentIndex) {
        requireParentIndex(parentIndex);
        int low = 0;
        int high = parentIndices.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int candidate = Short.toUnsignedInt(parentIndices[middle]);
            if (candidate < parentIndex) {
                low = middle + 1;
            } else if (candidate > parentIndex) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -(low + 1);
    }

    private static void requireParentIndex(int parentIndex) {
        if (parentIndex < 0 || parentIndex > 0xffff) {
            throw new IllegalArgumentException(
                    "parentIndex must be an unsigned 16-bit value");
        }
    }
}
