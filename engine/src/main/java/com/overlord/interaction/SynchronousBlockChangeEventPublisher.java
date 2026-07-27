package com.overlord.interaction;

import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Explicit synchronous delivery required by WorldMutationService ordering. */
public final class SynchronousBlockChangeEventPublisher
        implements BlockChangeEventPublisher {
    private final Function<BeforeBlockChangedEvent, BlockChangeDecision> before;
    private final Consumer<BlockChangedEvent> changed;
    private final Consumer<ChunkDirtyEvent> dirty;

    public SynchronousBlockChangeEventPublisher(
            Function<BeforeBlockChangedEvent, BlockChangeDecision> before,
            Consumer<BlockChangedEvent> changed,
            Consumer<ChunkDirtyEvent> dirty) {
        this.before = Objects.requireNonNull(before, "before");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
    }

    public static SynchronousBlockChangeEventPublisher noSubscribers() {
        return new SynchronousBlockChangeEventPublisher(
                ignored -> BlockChangeDecision.ALLOW,
                ignored -> {},
                ignored -> {});
    }

    @Override
    public BlockChangeDecision beforeChange(BeforeBlockChangedEvent event) {
        return before.apply(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void blockChanged(BlockChangedEvent event) {
        changed.accept(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void chunksDirty(ChunkDirtyEvent event) {
        dirty.accept(Objects.requireNonNull(event, "event"));
    }
}
