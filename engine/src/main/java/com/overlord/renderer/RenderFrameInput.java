package com.overlord.renderer;

import java.util.List;
import java.util.Objects;

public record RenderFrameInput(
        List<ChunkRenderObject> chunks, double frameDeltaSeconds, int meshQueueDepth) {
    public RenderFrameInput {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        for (ChunkRenderObject chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
        }
        if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0d) {
            throw new IllegalArgumentException("frameDeltaSeconds must be finite and non-negative");
        }
        if (meshQueueDepth < 0) {
            throw new IllegalArgumentException("meshQueueDepth must be non-negative");
        }
    }
}
