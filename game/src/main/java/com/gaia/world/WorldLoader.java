package com.gaia.world;

import com.overlord.config.GameConfig;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.joml.Vector3f;

public final class WorldLoader {
    private static final int CHUNK_RADIUS = 2;

    private final GaiaWorldGenerator worldGenerator;
    private final ChunkMeshBuilder meshBuilder;
    private final byte fallbackGroundId;

    public WorldLoader(
            GaiaWorldGenerator worldGenerator,
            ChunkMeshBuilder meshBuilder,
            byte fallbackGroundId) {
        this.worldGenerator =
                Objects.requireNonNull(
                        worldGenerator, "worldGenerator");
        this.meshBuilder =
                Objects.requireNonNull(meshBuilder, "meshBuilder");
        this.fallbackGroundId = fallbackGroundId;
    }

    public WorldLoadResult load(World world) {
        Objects.requireNonNull(world, "world");

        for (int chunkX = -CHUNK_RADIUS; chunkX < CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = -CHUNK_RADIUS; chunkZ < CHUNK_RADIUS; chunkZ++) {
                checkCancelled();
                worldGenerator.generateChunk(world, chunkX, chunkZ);
            }
        }

        List<float[]> allMeshData = new ArrayList<>();
        for (int chunkX = -CHUNK_RADIUS; chunkX < CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = -CHUNK_RADIUS; chunkZ < CHUNK_RADIUS; chunkZ++) {
                checkCancelled();
                Chunk chunk = world.getChunk(chunkX, chunkZ);
                float[] meshData =
                        meshBuilder.buildChunkMeshData(
                                chunk, chunkX, chunkZ, world);
                if (meshData.length > 0) {
                    allMeshData.add(meshData);
                }
            }
        }

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

        int spawnY = highestBlockY + 1;
        Vector3f spawnPosition =
                new Vector3f(
                        spawnX + 0.5f,
                        spawnY + GameConfig.Player.HEIGHT,
                        spawnZ + 0.5f);
        return new WorldLoadResult(combineMeshData(allMeshData), spawnPosition);
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

    private static float[] combineMeshData(List<float[]> meshDataList) {
        int totalLength = 0;
        for (float[] data : meshDataList) {
            totalLength += data.length;
        }

        float[] combined = new float[totalLength];
        int offset = 0;
        for (float[] data : meshDataList) {
            System.arraycopy(data, 0, combined, offset, data.length);
            offset += data.length;
        }
        return combined;
    }
}
