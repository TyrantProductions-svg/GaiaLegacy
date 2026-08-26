package com.overlord.worlditem.api;

import com.overlord.config.GameConfig;

/** Reviewed hard bounds for all resident WorldItem paging state. */
public record WorldItemPageCachePolicy(
        int maxLiveMetadata,
        int maxDecodedPages,
        long maxDecodedPageBytes,
        int maxPagingTickets,
        int maxDirtyEntries,
        long maxDirtyCandidateBytes,
        int maxCleanupIntents,
        long maxCleanupIntentBytes) {
    public static final int MAX_DECODED_PAGES = 32;
    public static final long MAX_DECODED_PAGE_BYTES = 16L * 1_024L * 1_024L;
    public static final int MAX_PAGING_TICKETS = 64;
    public static final int MAX_DIRTY_ENTRIES = 1_024;
    public static final long MAX_DIRTY_CANDIDATE_BYTES = 16L * 1_024L * 1_024L;
    public static final int MAX_CLEANUP_INTENTS = 64;
    public static final long MAX_CLEANUP_INTENT_BYTES = 64L * 1_024L;

    public WorldItemPageCachePolicy {
        if (maxLiveMetadata <= 0
                || maxLiveMetadata > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS
                || maxDecodedPages <= 0
                || maxDecodedPageBytes <= 0L
                || maxPagingTickets <= 0
                || maxDirtyEntries <= 0
                || maxDirtyEntries > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS
                || maxDirtyCandidateBytes <= 0L
                || maxCleanupIntents <= 0
                || maxCleanupIntentBytes <= 0L) {
            throw new IllegalArgumentException("WorldItem page cache policy is invalid");
        }
        if (maxDecodedPages > MAX_DECODED_PAGES
                || maxDecodedPageBytes > MAX_DECODED_PAGE_BYTES
                || maxPagingTickets > MAX_PAGING_TICKETS
                || maxDirtyEntries > MAX_DIRTY_ENTRIES
                || maxDirtyCandidateBytes > MAX_DIRTY_CANDIDATE_BYTES
                || maxCleanupIntents > MAX_CLEANUP_INTENTS
                || maxCleanupIntentBytes > MAX_CLEANUP_INTENT_BYTES) {
            throw new IllegalArgumentException(
                    "WorldItem page cache policy exceeds approved hard maxima");
        }
    }
}
