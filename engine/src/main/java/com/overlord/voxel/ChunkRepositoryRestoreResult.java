package com.overlord.voxel;

import java.util.Objects;

public record ChunkRepositoryRestoreResult(
        Status status, int restoredChunkCount) {
    public ChunkRepositoryRestoreResult {
        Objects.requireNonNull(status, "status");
        if (restoredChunkCount < 0) {
            throw new IllegalArgumentException(
                    "restoredChunkCount must not be negative");
        }
        if (status != Status.RESTORED && restoredChunkCount != 0) {
            throw new IllegalArgumentException(
                    "A rejected restore cannot report restored Chunks");
        }
    }

    public static ChunkRepositoryRestoreResult restored(int chunkCount) {
        return new ChunkRepositoryRestoreResult(
                Status.RESTORED, chunkCount);
    }

    public static ChunkRepositoryRestoreResult rejected(Status status) {
        if (status == Status.RESTORED) {
            throw new IllegalArgumentException(
                    "RESTORED requires a restored Chunk count");
        }
        return new ChunkRepositoryRestoreResult(status, 0);
    }

    public enum Status {
        RESTORED,
        INVALID_SNAPSHOT,
        TARGET_NOT_EMPTY,
        TARGET_NOT_FRESH,
        GENERATION_ACTIVE
    }
}
