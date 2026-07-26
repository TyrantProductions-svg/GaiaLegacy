package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderFrameInputTest {
    @Test
    void rejectsInvalidTimingDepthAndChunksAndDefensivelyCopies() {
        assertThrows(IllegalArgumentException.class, () -> new RenderFrameInput(List.of(), -0.1d, 0));
        assertThrows(IllegalArgumentException.class, () -> new RenderFrameInput(List.of(), Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new RenderFrameInput(List.of(), 0.0d, -1));
        assertThrows(NullPointerException.class, () -> new RenderFrameInput(java.util.Arrays.asList((ChunkRenderObject) null), 0.0d, 0));

        List<ChunkRenderObject> chunks = new ArrayList<>();
        RenderFrameInput input = new RenderFrameInput(chunks, 0.0d, 0);
        chunks.add(null);
        assertDoesNotThrow(input.chunks()::size);
        assertThrows(UnsupportedOperationException.class, () -> input.chunks().add(null));
    }
}
