package com.overlord.worlditem.api;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;

/** Last verified durable identity for one clean dormant or evicted page. */
public record WorldItemDurablePageProof(
        ChunkKey chunkKey, long pageRevision, String pageHash) {
    public WorldItemDurablePageProof {
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                chunkKey, pageRevision, pageHash, 1, 0);
        chunkKey = descriptor.chunkKey();
        pageHash = Objects.requireNonNull(descriptor.pageHash(), "pageHash");
    }
}
