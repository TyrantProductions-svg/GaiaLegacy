package com.gaia.world.streaming;

/** One validated owner of all Chunk streaming radii and work budgets. */
public record ChunkStreamingPolicy(
        int simulationRadius,
        int renderRadius,
        int preloadRadius,
        int unloadRadius,
        int loadGenerationQueueCapacity,
        int loadGenerationActiveLimit,
        int meshQueueCapacity,
        int meshActiveLimit,
        int saveQueueCapacity,
        int saveActiveLimit,
        int publicationBudget,
        int uploadBudget,
        int destroyBudget) {
    static final int MAX_DESIRED_RADIUS = 7;

    public ChunkStreamingPolicy {
        if (simulationRadius < 0
                || simulationRadius > renderRadius
                || renderRadius > preloadRadius
                || preloadRadius >= unloadRadius) {
            throw new IllegalArgumentException(
                    "streaming radii must satisfy simulation <= render <= preload < unload");
        }
        if (preloadRadius > MAX_DESIRED_RADIUS) {
            throw new IllegalArgumentException(
                    "preload radius exceeds the bounded desired-set footprint");
        }
        requireQueueAndActive(
                loadGenerationQueueCapacity,
                loadGenerationActiveLimit,
                "load/generation");
        requireQueueAndActive(meshQueueCapacity, meshActiveLimit, "mesh");
        requireQueueAndActive(saveQueueCapacity, saveActiveLimit, "save");
        requirePositive(publicationBudget, "publicationBudget");
        requirePositive(uploadBudget, "uploadBudget");
        requirePositive(destroyBudget, "destroyBudget");
    }

    public static ChunkStreamingPolicy productionDefaults() {
        return new ChunkStreamingPolicy(
                2, 4, 5, 7,
                32, 4,
                32, 2,
                8, 1,
                2, 2, 4);
    }

    private static void requireQueueAndActive(
            int queueCapacity, int activeLimit, String name) {
        requirePositive(queueCapacity, name + "QueueCapacity");
        requirePositive(activeLimit, name + "ActiveLimit");
        if (activeLimit > queueCapacity) {
            throw new IllegalArgumentException(
                    name + " active limit must not exceed its queue capacity");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
