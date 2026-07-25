package com.gaia.world.generation;

import java.util.Objects;

public record GenerationContext(
        WorldGenerationConfig config,
        GenerationBlockPalette palette,
        DeterministicCoordinateSampler sampler) {
    public GenerationContext {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(sampler, "sampler");
    }
}
