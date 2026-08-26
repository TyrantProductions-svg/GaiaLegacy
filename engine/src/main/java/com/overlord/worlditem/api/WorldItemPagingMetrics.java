package com.overlord.worlditem.api;

/** Read-only bounded-state counters used by acceptance tests and soak telemetry. */
public record WorldItemPagingMetrics(
        int liveMetadataCount,
        int expiryIndexCount,
        int activeDtoCount,
        int decodedDormantDtoCount,
        int evictedUnexpiredCount,
        int pendingCount,
        int decodedPageCount,
        long decodedPageBytes,
        int pinnedPageCount,
        int dirtyEntryCount,
        long dirtyCandidateBytes,
        int zeroLiveDescriptorCount,
        int unprovedPinnedPageCount,
        int cleanupIntentCount,
        long cleanupIntentBytes,
        int tombstoneCount,
        long cleanupWrittenBytes,
        long droppedCleanupIntentCount,
        long droppedCleanupIntentBytes,
        int persistenceTicketCount,
        int activationTicketCount,
        int physicalDescriptorCount,
        int projectionCallbackDepth) {
}
