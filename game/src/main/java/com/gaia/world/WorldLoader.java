package com.gaia.world;

import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationStageResult;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkGenerationResult;
import com.overlord.voxel.ChunkGenerationTicket;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.World;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.joml.Vector3f;

public final class WorldLoader {
    private static final int CHUNK_RADIUS = 2;

    private final WorldGenerator worldGenerator;
    private final GenerationContext generationContext;

    public WorldLoader(
            WorldGenerator worldGenerator,
            GenerationContext generationContext) {
        this.worldGenerator =
                Objects.requireNonNull(
                        worldGenerator, "worldGenerator");
        this.generationContext =
                Objects.requireNonNull(
                        generationContext,
                        "generationContext");
    }

    public WorldLoadResult load(World world) {
        Objects.requireNonNull(world, "world");

        Set<ChunkKey> generated = new LinkedHashSet<>();
        for (int chunkX = -CHUNK_RADIUS;
                chunkX < CHUNK_RADIUS;
                chunkX++) {
            for (int chunkZ = -CHUNK_RADIUS; chunkZ < CHUNK_RADIUS; chunkZ++) {
                checkCancelled();
                ChunkKey key = new ChunkKey(chunkX, chunkZ);
                generateChunk(world.chunks(), key);
                generated.add(key);
            }
        }

        checkCancelled();
        int spawnX = 0;
        int spawnZ = 0;
        int highestBlockY = findHighestBlock(world, spawnX, spawnZ);

        int playerFeetY = highestBlockY + 1;
        Vector3f playerFeetPosition =
                new Vector3f(
                        spawnX + 0.5f,
                        playerFeetY,
                        spawnZ + 0.5f);
        return new WorldLoadResult(generated, playerFeetPosition);
    }

    private void generateChunk(
            ChunkRepository chunks, ChunkKey key) {
        ChunkGenerationTicket ticket =
                chunks.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        try {
            WorldGenerationResult generated =
                    worldGenerator.generate(
                            generationContext, key);
            if (!generated.succeeded()) {
                throw stageFailure(key, generated);
            }
            ChunkGenerationResult committed =
                    chunks.commitGeneration(
                            ticket,
                            generated.chunkData()
                                    .orElseThrow());
            if (committed.status()
                    != ChunkGenerationResult.Status.COMMITTED) {
                throw new IllegalStateException(
                        "Initial generation commit failed for "
                                + key
                                + ": "
                                + committed.status());
            }
        } catch (RuntimeException | Error failure) {
            chunks.failGeneration(ticket, failure);
            throw failure;
        }
    }

    private static IllegalStateException stageFailure(
            ChunkKey key,
            WorldGenerationResult generated) {
        GenerationStageResult failedStage =
                generated.failedStage().orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "World generator failed "
                                                + "without a failed stage"));
        Throwable cause =
                failedStage.failure().orElseThrow();
        return new IllegalStateException(
                "World generation failed for "
                        + key
                        + " at stage "
                        + failedStage.stageId(),
                cause);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("World loading was cancelled");
        }
    }

    private static int findHighestBlock(World world, int x, int z) {
        for (int y = GameConfig.Chunk.MAX_HEIGHT - 1; y >= 0; y--) {
            if (world.getBlock(x, y, z) != 0) {
                return y;
            }
        }
        return 0;
    }

}
