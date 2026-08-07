package com.overlord.renderer.particle;

/** Immutable lifetime allocation counters plus current priority occupancy. */
public record ParticleAllocationMetrics(
        long receivedRequests,
        long admittedRequests,
        long rejectedRequests,
        long particleStatesCreated,
        long particleStatesAdvanced,
        long evictions,
        int lowActive,
        int highActive) {
    public ParticleAllocationMetrics {
        if (receivedRequests < 0
                || admittedRequests < 0
                || rejectedRequests < 0
                || particleStatesCreated < 0
                || particleStatesAdvanced < 0
                || evictions < 0
                || lowActive < 0
                || highActive < 0) {
            throw new IllegalArgumentException("particle metrics must be non-negative");
        }
        if (admittedRequests + rejectedRequests != receivedRequests) {
            throw new IllegalArgumentException("particle request metrics must balance");
        }
    }
}
