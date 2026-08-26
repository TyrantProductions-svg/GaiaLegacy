package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChunkStreamingPolicyTest {
    @Test
    void productionDefaultsOwnEveryApprovedRadiusAndBudget() {
        ChunkStreamingPolicy policy = ChunkStreamingPolicy.productionDefaults();

        assertEquals(2, policy.simulationRadius());
        assertEquals(4, policy.renderRadius());
        assertEquals(5, policy.preloadRadius());
        assertEquals(7, policy.unloadRadius());
        assertEquals(32, policy.loadGenerationQueueCapacity());
        assertEquals(4, policy.loadGenerationActiveLimit());
        assertEquals(32, policy.meshQueueCapacity());
        assertEquals(2, policy.meshActiveLimit());
        assertEquals(8, policy.saveQueueCapacity());
        assertEquals(1, policy.saveActiveLimit());
        assertEquals(2, policy.publicationBudget());
        assertEquals(2, policy.uploadBudget());
        assertEquals(4, policy.destroyBudget());
    }

    @Test
    void rejectsInvalidRadiusOrderingAndNonPositiveBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> policy(3, 2, 5, 7, 32));
        assertThrows(IllegalArgumentException.class,
                () -> policy(2, 4, 5, 5, 32));
        assertThrows(IllegalArgumentException.class,
                () -> policy(2, 4, 5, 7, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkStreamingPolicy(
                        2, 4, 5, 7,
                        32, 4, 32, 2, 8, 1,
                        2, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> policy(2, 4, 8, 9, 32),
                "eager desired-set materialization must have a hard footprint bound");
    }

    static ChunkStreamingPolicy policy(
            int simulation, int render, int preload, int unload, int loadQueue) {
        return new ChunkStreamingPolicy(
                simulation, render, preload, unload,
                loadQueue, Math.min(4, loadQueue),
                32, 2, 8, 1, 2, 2, 4);
    }
}
