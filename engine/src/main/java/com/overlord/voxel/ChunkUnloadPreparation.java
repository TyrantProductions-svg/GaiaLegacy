package com.overlord.voxel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public record ChunkUnloadPreparation(
        Status status,
        Optional<ChunkUnloadTicket> ticket,
        Optional<ChunkSnapshot> capture,
        long persistedRevision,
        boolean voxelModified,
        BooleanSupplier stillCurrent) {
    public enum Status {
        PREPARED,
        NOT_RESIDENT,
        ALREADY_PREPARED
    }

    public ChunkUnloadPreparation {
        Objects.requireNonNull(status, "status");
        ticket = Objects.requireNonNull(ticket, "ticket");
        capture = Objects.requireNonNull(capture, "capture");
        stillCurrent = Objects.requireNonNull(stillCurrent, "stillCurrent");
        if (persistedRevision < 0L) {
            throw new IllegalArgumentException(
                    "persistedRevision must not be negative");
        }
        boolean prepared = status == Status.PREPARED;
        if (prepared != (ticket.isPresent() && capture.isPresent())) {
            throw new IllegalArgumentException(
                    "Only PREPARED may carry a ticket and capture");
        }
    }

    static ChunkUnloadPreparation prepared(
            ChunkUnloadTicket ticket,
            ChunkSnapshot capture,
            long persistedRevision,
            boolean voxelModified,
            BooleanSupplier stillCurrent) {
        return new ChunkUnloadPreparation(
                Status.PREPARED,
                Optional.of(ticket),
                Optional.of(capture),
                persistedRevision,
                voxelModified,
                stillCurrent);
    }

    static ChunkUnloadPreparation empty(Status status) {
        return new ChunkUnloadPreparation(
                status, Optional.empty(), Optional.empty(), 0L, false, () -> false);
    }
}
