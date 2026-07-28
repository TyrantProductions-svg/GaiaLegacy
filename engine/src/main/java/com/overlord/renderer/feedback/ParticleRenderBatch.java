package com.overlord.renderer.feedback;

import java.util.List;
import java.util.Objects;

public record ParticleRenderBatch(List<ParticleVisual> particles) {
    public ParticleRenderBatch {
        particles = List.copyOf(Objects.requireNonNull(particles, "particles"));
        for (ParticleVisual particle : particles) {
            Objects.requireNonNull(particle, "particle");
        }
    }
}
