package com.overlord.voxel;

public record ChunkGenerationTicket(
        ChunkKey key,
        ChunkGenerationMode mode,
        long attemptId,
        long baseRevision) {}
