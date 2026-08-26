package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LogicalWorldItemPageCacheTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void policyRejectsEveryValueAboveTheApprovedProductionHardMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPageCachePolicy(
                1_024, 33, 16L * 1_024L * 1_024L,
                64, 1_024, 16L * 1_024L * 1_024L,
                64, 64L * 1_024L));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPageCachePolicy(
                1_024, 32, 16L * 1_024L * 1_024L + 1L,
                65, 1_024, 16L * 1_024L * 1_024L,
                65, 64L * 1_024L + 1L));
    }

    @Test
    void deterministicLruUsesChunkKeyTieBreakAndNeverExceedsPageOrByteBounds() {
        WorldItemPageCache cache = new WorldItemPageCache(policy());
        ChunkKey first = new ChunkKey(-1, 0);
        ChunkKey second = new ChunkKey(0, 0);
        ChunkKey third = new ChunkKey(1, 0);

        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitClean(descriptor(first, 1), page(first, 1), 200, 10L));
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitClean(descriptor(second, 2), page(second, 2), 200, 10L));
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitClean(descriptor(third, 3), page(third, 3), 200, 11L));

        assertEquals(List.of(second, third), cache.cachedChunkKeys());
        assertEquals(2, cache.metrics().decodedPageCount());
        assertEquals(400L, cache.metrics().decodedPageBytes());
    }

    @Test
    void allPinnedAdmissionFailsWithoutEvictingDirtyOrUnprovedState() {
        WorldItemPageCache cache = new WorldItemPageCache(policy());
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(1, 0);
        ChunkKey rejected = new ChunkKey(2, 0);
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitDirty(descriptor(first, 1), page(first, 1), 200, 1L));
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitUnproved(descriptor(second, 2), page(second, 2), 200, 2L));

        assertEquals(WorldItemPageCache.Admission.ALL_PINNED,
                cache.admitClean(descriptor(rejected, 3), page(rejected, 3), 200, 3L));
        assertEquals(List.of(first, second), cache.cachedChunkKeys());
        assertEquals(2, cache.metrics().pinnedPageCount());
        assertFalse(cache.contains(rejected));
        assertTrue(cache.isPinned(first));
        assertTrue(cache.isPinned(second));
    }

    @Test
    void cleanDormantOrEvictedMetadataRequiresExactDurableProof() {
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemId id = new WorldItemId(7L);
        assertThrows(IllegalArgumentException.class, () -> new WorldItemLiveMetadata(
                id, key, 1L, 18_000L,
                WorldItemLiveState.EVICTED_UNEXPIRED, Optional.empty()));

        WorldItemDurablePageProof proof = new WorldItemDurablePageProof(
                key, 1L, "11".repeat(32));
        assertEquals(proof, new WorldItemLiveMetadata(
                id, key, 1L, 18_000L,
                WorldItemLiveState.EVICTED_UNEXPIRED, Optional.of(proof))
                .durableProof().orElseThrow());
        assertTrue(new WorldItemLiveMetadata(
                id, key, 2L, 18_000L,
                WorldItemLiveState.ACTIVE, Optional.empty()).durableProof().isEmpty());
        assertTrue(new WorldItemLiveMetadata(
                id, key, 2L, 18_000L,
                WorldItemLiveState.DECODED_DORMANT, Optional.empty())
                .durableProof().isEmpty());
    }

    @Test
    void oneHundredHistoricalPagesLeaveOnlyBoundedCurrentCacheAndMetadataIsDtoFree() {
        WorldItemPageCache cache = new WorldItemPageCache(policy());
        for (int index = 0; index < 100; index++) {
            ChunkKey key = new ChunkKey(index, -index);
            cache.admitClean(
                    descriptor(key, index + 1L),
                    page(key, index + 1L),
                    200,
                    index);
            assertTrue(cache.metrics().decodedPageCount() <= 2);
            assertTrue(cache.metrics().decodedPageBytes() <= 512L);
        }
        assertEquals(2, cache.cachedChunkKeys().size());

        assertEquals(
                List.of(
                        WorldItemId.class,
                        ChunkKey.class,
                        long.class,
                        long.class,
                        WorldItemLiveState.class,
                        Optional.class),
                java.util.Arrays.stream(WorldItemLiveMetadata.class.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toList());
        assertEquals(
                "java.util.Optional<com.overlord.worlditem.api.WorldItemDurablePageProof>",
                WorldItemLiveMetadata.class.getRecordComponents()[5]
                        .getGenericType().getTypeName());
        assertEquals(List.of(
                        WorldItemLiveState.ACTIVE,
                        WorldItemLiveState.DECODED_DORMANT,
                        WorldItemLiveState.EVICTED_UNEXPIRED,
                        WorldItemLiveState.PENDING),
                List.of(WorldItemLiveState.values()));
    }

    private static WorldItemPageCachePolicy policy() {
        return new WorldItemPageCachePolicy(
                3, 2, 512L, 64, 3, 512L, 64, 64L * 1_024L);
    }

    private static WorldItemPageDescriptor descriptor(ChunkKey key, long revision) {
        return new WorldItemPageDescriptor(
                key, revision, String.format("%064x", revision), 1, 1);
    }

    private static WorldItemPageSnapshot page(ChunkKey key, long revision) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(revision),
                new ItemStack(DIRT, 1),
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                revision);
        WorldItemRuntimeSnapshot runtime = new WorldItemRuntimeSnapshot(
                item, Optional.empty(), 0L, 0L, 18_000L);
        return new WorldItemPageSnapshot(
                key,
                revision,
                List.of(new WorldItemRestoreEntry(
                        runtime, WorldItemPhysicalState.FROZEN_UNLOADED)));
    }

    @Test
    void dirtyAdmissionUsesNetReplacementAndAppliesToUnprovedPages() {
        WorldItemPageCache cache = new WorldItemPageCache(
                new WorldItemPageCachePolicy(
                        2, 2, 512L, 64, 1, 512L, 64, 64L * 1_024L));
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(1, 0);
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitDirty(descriptor(first, 1L), page(first, 1L), 200L, 1L));
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitDirty(descriptor(first, 2L), page(first, 2L), 200L, 2L));
        assertEquals(1, cache.metrics().dirtyEntryCount());
        assertEquals(200L, cache.metrics().dirtyBytes());

        assertEquals(WorldItemPageCache.Admission.CAPACITY_EXCEEDED,
                cache.admitUnproved(
                        descriptor(second, 3L), page(second, 3L), 200L, 3L));
        assertEquals(List.of(first), cache.cachedChunkKeys());
    }

    @Test
    void failedDirtyBatchRestoresEveryReplacedAndEvictedCacheEntry() {
        WorldItemPageCache cache = new WorldItemPageCache(policy());
        ChunkKey source = new ChunkKey(0, 0);
        ChunkKey pinned = new ChunkKey(1, 0);
        ChunkKey destination = new ChunkKey(2, 0);
        WorldItemPageSnapshot originalSource = page(source, 1L);
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitClean(
                        descriptor(source, 1L), originalSource, 200L, 1L));
        assertEquals(WorldItemPageCache.Admission.ADMITTED,
                cache.admitDirty(
                        descriptor(pinned, 2L), page(pinned, 2L), 200L, 2L));

        assertEquals(WorldItemPageCache.Admission.ALL_PINNED,
                cache.admitDirtyBatch(List.of(
                        new WorldItemPageCache.DirtyCandidate(
                                descriptor(source, 3L), page(source, 3L), 100L, 3L),
                        new WorldItemPageCache.DirtyCandidate(
                                descriptor(destination, 4L),
                                page(destination, 4L), 100L, 4L))));
        assertEquals(List.of(source, pinned), cache.cachedChunkKeys());
        assertEquals(originalSource, cache.page(source));
        assertFalse(cache.isPinned(source));
        assertTrue(cache.isPinned(pinned));
        assertFalse(cache.contains(destination));
    }
}
