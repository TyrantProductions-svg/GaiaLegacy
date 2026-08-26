package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

/** Stable deterministic identity and world-sampling radius for one stage. */
public record GenerationStageContract(
        ResourceLocation id, int version, int haloRadius) {
    public GenerationStageContract {
        Objects.requireNonNull(id, "id");
        if (version <= 0) {
            throw new IllegalArgumentException(
                    "version must be positive");
        }
        if (haloRadius < 0) {
            throw new IllegalArgumentException(
                    "haloRadius must be non-negative");
        }
    }

    public GenerationStageContract withVersion(int newVersion) {
        return new GenerationStageContract(
                id, newVersion, haloRadius);
    }

    public GenerationStageContract child(
            ResourceLocation childId, int childHaloRadius) {
        return new GenerationStageContract(
                childId, version, childHaloRadius);
    }

    public RegionRange regionsForChunk(
            long chunkOrigin,
            int chunkSize,
            int regionSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "chunkSize must be positive");
        }
        if (regionSize <= 0) {
            throw new IllegalArgumentException(
                    "regionSize must be positive");
        }
        long minimumWorld =
                Math.subtractExact(
                        chunkOrigin, (long) haloRadius);
        long maximumWorld =
                Math.addExact(
                        Math.addExact(
                                chunkOrigin,
                                (long) chunkSize - 1L),
                        (long) haloRadius);
        return new RegionRange(
                Math.floorDiv(minimumWorld, regionSize),
                Math.floorDiv(maximumWorld, regionSize));
    }

    public record RegionRange(long minimum, long maximum) {
        public RegionRange {
            if (maximum < minimum) {
                throw new IllegalArgumentException(
                        "maximum must be at least minimum");
            }
        }
    }
}
