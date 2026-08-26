package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable deterministic simulation, render, and preload identities. */
public record ChunkDesiredSets(
        Set<ChunkKey> simulation,
        Set<ChunkKey> render,
        Set<ChunkKey> preload) {
    public ChunkDesiredSets {
        simulation = canonicalSet(simulation, "simulation");
        render = canonicalSet(render, "render");
        preload = canonicalSet(preload, "preload");
        if (!render.containsAll(simulation) || !preload.containsAll(render)) {
            throw new IllegalArgumentException(
                    "desired sets must satisfy simulation subset render subset preload");
        }
    }

    static Set<ChunkKey> canonicalSet(Set<ChunkKey> source, String name) {
        Objects.requireNonNull(source, name);
        List<ChunkKey> sorted = source.stream()
                .map(ChunkCoordinatePolicy::requireSafe)
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
