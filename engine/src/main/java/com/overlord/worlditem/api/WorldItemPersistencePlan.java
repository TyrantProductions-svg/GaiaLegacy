package com.overlord.worlditem.api;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Detached bounded semantic plan issued by the sole WorldItem authority. */
public record WorldItemPersistencePlan(
        long expectedCheckpointRevision,
        WorldItemPagingCheckpoint intendedCheckpoint,
        List<WorldItemPageMutation> pageMutations,
        String transactionDigest,
        BooleanSupplier stillCurrent) {
    public static final int MAX_PAGES = 1_024;
    public static final int MAX_AGGREGATE_PAGE_ENTRIES = 1_024;

    public WorldItemPersistencePlan {
        if (expectedCheckpointRevision < 0L) {
            throw new IllegalArgumentException(
                    "expectedCheckpointRevision must be non-negative");
        }
        intendedCheckpoint = Objects.requireNonNull(
                intendedCheckpoint, "intendedCheckpoint");
        if (intendedCheckpoint.checkpointRevision()
                != Math.addExact(expectedCheckpointRevision, 1L)) {
            throw new IllegalArgumentException("intended checkpoint must advance exactly once");
        }
        Objects.requireNonNull(pageMutations, "pageMutations");
        if (pageMutations.size() > MAX_PAGES) {
            throw new IllegalArgumentException("WorldItem persistence plan exceeds its bound");
        }
        pageMutations = List.copyOf(pageMutations);
        int aggregateEntries = 0;
        Set<com.overlord.voxel.ChunkKey> keys = new HashSet<>();
        for (WorldItemPageMutation mutation : pageMutations) {
            Objects.requireNonNull(mutation, "mutation");
            com.overlord.voxel.ChunkKey key;
            if (mutation instanceof WorldItemPageMutation.Upsert upsert) {
                key = upsert.page().chunkKey();
                aggregateEntries = Math.addExact(
                        aggregateEntries, upsert.page().entries().size());
            } else {
                key = ((WorldItemPageMutation.Remove) mutation).expected().chunkKey();
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate WorldItem page mutation");
            }
        }
        if (aggregateEntries > MAX_AGGREGATE_PAGE_ENTRIES) {
            throw new IllegalArgumentException(
                    "WorldItem persistence plan exceeds its aggregate entry bound");
        }
        transactionDigest = Objects.requireNonNull(
                transactionDigest, "transactionDigest");
        if (!transactionDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "transactionDigest must be canonical SHA-256");
        }
        stillCurrent = Objects.requireNonNull(stillCurrent, "stillCurrent");
    }
}
