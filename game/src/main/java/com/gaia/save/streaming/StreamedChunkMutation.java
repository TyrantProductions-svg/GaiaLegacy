package com.gaia.save.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;

/** Explicit streamed Chunk replacement or compare-and-remove. */
public sealed interface StreamedChunkMutation {
    record Upsert(StreamedChunkStore.ExactChunkCapture capture)
            implements StreamedChunkMutation {
        public Upsert {
            Objects.requireNonNull(capture, "capture");
        }
    }

    record Remove(ChunkKey key, long expectedRevision, String expectedHash)
            implements StreamedChunkMutation {
        public Remove {
            key = ChunkCoordinatePolicy.requireSafe(key);
            if (expectedRevision <= 0L) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            expectedHash = StreamedChunkPayload.requireHash(
                    expectedHash, "expectedHash");
        }
    }
}
