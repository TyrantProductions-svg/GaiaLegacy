package com.overlord.voxel;

import java.util.Objects;

/** Opaque authority for one owner-thread-bound streaming unload. */
public final class ChunkUnloadTicket {
    private final Object issuer;
    private final Thread ownerThread;
    private final ChunkKey key;

    ChunkUnloadTicket(Object issuer, Thread ownerThread, ChunkKey key) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        this.key = Objects.requireNonNull(key, "key");
    }

    boolean belongsTo(Object expectedIssuer) {
        return issuer == expectedIssuer;
    }

    ChunkKey key() {
        return key;
    }

    void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Chunk unload ticket must be used by its owner thread");
        }
    }
}
