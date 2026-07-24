package com.overlord.interaction.api;

public interface BlockChangeEventPublisher {
    BlockChangeDecision beforeChange(
            BeforeBlockChangedEvent event);

    void blockChanged(BlockChangedEvent event);

    void chunksDirty(ChunkDirtyEvent event);
}
