package com.gaia.session;

import com.gaia.world.streaming.ChunkStreamingMetrics;
import com.overlord.renderer.RenderFrameInput;
import java.util.Objects;

/** One immutable gameplay presentation captured by the session owner. */
public record GameSessionFrame(
        RenderFrameInput renderInput,
        ChunkStreamingMetrics streamingMetrics) {
    public GameSessionFrame(RenderFrameInput renderInput) {
        this(renderInput, ChunkStreamingMetrics.empty());
    }

    public GameSessionFrame {
        renderInput = Objects.requireNonNull(renderInput, "renderInput");
        streamingMetrics = Objects.requireNonNull(streamingMetrics, "streamingMetrics");
    }

    public GameSessionFrame copy() {
        return new GameSessionFrame(renderInput, streamingMetrics);
    }
}
