package com.gaia.world;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.joml.Vector3f;

public final class WorldLoader {
    private static final int CHUNK_RADIUS = 2;

    private final GaiaWorldGenerator worldGenerator;
    private final byte fallbackGroundId;

    public WorldLoader(
            GaiaWorldGenerator worldGenerator,
            byte fallbackGroundId) {
        this.worldGenerator =
                Objects.requireNonNull(
                        worldGenerator, "worldGenerator");
        this.fallbackGroundId = fallbackGroundId;
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
                worldGenerator.generateChunk(world, key);
                generated.add(key);
            }
        }

        checkCancelled();
        int spawnX = 0;
        int spawnZ = 0;
        int highestBlockY = findHighestBlock(world, spawnX, spawnZ);
        if (highestBlockY <= 0) {
            for (int y = 0; y < 30; y++) {
                world.setBlock(
                        spawnX, y, spawnZ, fallbackGroundId);
            }
            highestBlockY = 29;
        }

        int playerFeetY = highestBlockY + 1;
        Vector3f playerFeetPosition =
                new Vector3f(
                        spawnX + 0.5f,
                        playerFeetY,
                        spawnZ + 0.5f);
        return new WorldLoadResult(generated, playerFeetPosition);
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
