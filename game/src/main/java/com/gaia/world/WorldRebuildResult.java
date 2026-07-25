package com.gaia.world;

import com.overlord.voxel.ChunkGenerationResult;
import com.overlord.voxel.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record WorldRebuildResult(
        Set<ChunkKey> committedChunks,
        Map<ChunkKey, ChunkGenerationResult> outcomes) {
    public WorldRebuildResult {
        committedChunks =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                Objects.requireNonNull(
                                        committedChunks,
                                        "committedChunks")));
        outcomes =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                Objects.requireNonNull(
                                        outcomes, "outcomes")));
    }
}
