package com.overlord.voxel;

import java.util.Objects;

/** Typed rejection when one mesh cannot fit the process-local CPU budget. */
public final class ChunkMeshMemoryBudgetExceededException
        extends RuntimeException {
    public enum Code {
        MESH_MEMORY_BUDGET_EXCEEDED
    }

    private final ChunkKey chunkKey;
    private final long revision;
    private final long configuredByteLimit;
    private final long requiredReservationBytes;
    private final long plannedOutputBytes;

    ChunkMeshMemoryBudgetExceededException(
            ChunkKey chunkKey,
            long revision,
            long configuredByteLimit,
            long requiredReservationBytes,
            long plannedOutputBytes) {
        super("Chunk mesh memory budget exceeded for "
                + Objects.requireNonNull(chunkKey, "chunkKey")
                + " revision " + revision
                + ": reservation " + requiredReservationBytes
                + " bytes, budget " + configuredByteLimit);
        this.chunkKey = chunkKey;
        this.revision = revision;
        this.configuredByteLimit = configuredByteLimit;
        this.requiredReservationBytes = requiredReservationBytes;
        this.plannedOutputBytes = plannedOutputBytes;
    }

    public Code code() {
        return Code.MESH_MEMORY_BUDGET_EXCEEDED;
    }

    public ChunkKey chunkKey() {
        return chunkKey;
    }

    public long revision() {
        return revision;
    }

    public long configuredByteLimit() {
        return configuredByteLimit;
    }

    public long requiredReservationBytes() {
        return requiredReservationBytes;
    }

    public long plannedOutputBytes() {
        return plannedOutputBytes;
    }
}
