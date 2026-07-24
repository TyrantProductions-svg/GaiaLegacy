package com.overlord.interaction.testing;

import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecordingBlockChangeEventPublisher
        implements BlockChangeEventPublisher {
    private final List<Object> events = new ArrayList<>();
    private BlockChangeDecision decision =
            BlockChangeDecision.ALLOW;

    @Override
    public BlockChangeDecision beforeChange(
            BeforeBlockChangedEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
        return decision;
    }

    @Override
    public void blockChanged(BlockChangedEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void chunksDirty(ChunkDirtyEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    public void setDecision(BlockChangeDecision decision) {
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    public List<Object> events() {
        return List.copyOf(events);
    }
}
