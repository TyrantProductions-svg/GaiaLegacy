package com.overlord.worlditem.api;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete bounded durable paging snapshot published with one streamed index. */
public record WorldItemPagingCheckpoint(
        SaveIdentity saveIdentity,
        long checkpointRevision,
        long worldTick,
        long nextItemId,
        boolean itemIdsExhausted,
        int totalLiveItemCount,
        List<WorldItemPageDescriptor> pages) {
    public static final int MAX_PAGE_DESCRIPTORS =
            GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS;

    public WorldItemPagingCheckpoint {
        saveIdentity = Objects.requireNonNull(saveIdentity, "saveIdentity");
        if (checkpointRevision <= 0L) {
            throw new IllegalArgumentException("checkpointRevision must be positive");
        }
        if (worldTick < 0L || nextItemId < 0L) {
            throw new IllegalArgumentException("checkpoint ticks and allocator must be non-negative");
        }
        if (itemIdsExhausted && nextItemId != Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "an exhausted allocator must own the final item ID");
        }
        if (totalLiveItemCount < 0
                || totalLiveItemCount > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS) {
            throw new IllegalArgumentException("totalLiveItemCount exceeds its bound");
        }
        List<WorldItemPageDescriptor> checked = new ArrayList<>(
                Objects.requireNonNull(pages, "pages"));
        if (checked.size() > MAX_PAGE_DESCRIPTORS) {
            throw new IllegalArgumentException("checkpoint page descriptor count exceeds its bound");
        }
        checked.replaceAll(value -> Objects.requireNonNull(value, "page descriptor"));
        checked.sort((first, second) -> ChunkCoordinatePolicy.canonicalComparator()
                .compare(first.chunkKey(), second.chunkKey()));
        Set<ChunkKey> keys = new HashSet<>();
        int survivorCount = 0;
        for (WorldItemPageDescriptor descriptor : checked) {
            if (!keys.add(descriptor.chunkKey())) {
                throw new IllegalArgumentException("checkpoint repeats a page key");
            }
            survivorCount = Math.addExact(
                    survivorCount, descriptor.expectedLiveCountAtCheckpointTick());
        }
        if (survivorCount != totalLiveItemCount) {
            throw new IllegalArgumentException(
                    "checkpoint survivor count does not match totalLiveItemCount");
        }
        pages = List.copyOf(checked);
    }
}
