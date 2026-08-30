package com.overlord.voxel;

import java.util.Objects;

/** Typed, bounded rejection for a canonical Chunk whose hybrid mesh is too large. */
public final class ChunkMeshOutputLimitExceededException
        extends RuntimeException {
    public enum Code {
        MESH_OUTPUT_LIMIT_EXCEEDED
    }

    private final ChunkKey chunkKey;
    private final long revision;
    private final long configuredByteLimit;
    private final long acceptedByteCount;
    private final long requiredByteCount;
    private final long requiredFaceletCount;
    private final long requiredVertexCount;
    private final long allocatedCapacityByteCount;

    ChunkMeshOutputLimitExceededException(
            ChunkKey chunkKey,
            long revision,
            long configuredByteLimit,
            long acceptedByteCount,
            long requiredByteCount,
            long requiredFaceletCount,
            long requiredVertexCount,
            long allocatedCapacityByteCount) {
        super(message(
                chunkKey,
                revision,
                configuredByteLimit,
                requiredByteCount));
        this.chunkKey = Objects.requireNonNull(chunkKey, "chunkKey");
        this.revision = revision;
        this.configuredByteLimit = configuredByteLimit;
        this.acceptedByteCount = acceptedByteCount;
        this.requiredByteCount = requiredByteCount;
        this.requiredFaceletCount = requiredFaceletCount;
        this.requiredVertexCount = requiredVertexCount;
        this.allocatedCapacityByteCount = allocatedCapacityByteCount;
    }

    public Code code() {
        return Code.MESH_OUTPUT_LIMIT_EXCEEDED;
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

    public long acceptedByteCount() {
        return acceptedByteCount;
    }

    public long requiredByteCount() {
        return requiredByteCount;
    }

    public long requiredFaceletCount() {
        return requiredFaceletCount;
    }

    public long requiredVertexCount() {
        return requiredVertexCount;
    }

    public long allocatedCapacityByteCount() {
        return allocatedCapacityByteCount;
    }

    private static String message(
            ChunkKey key,
            long revision,
            long configuredByteLimit,
            long requiredByteCount) {
        return "Hybrid Chunk mesh output limit exceeded for "
                + Objects.requireNonNull(key, "key")
                + " revision "
                + revision
                + ": required "
                + requiredByteCount
                + " bytes, limit "
                + configuredByteLimit;
    }
}
