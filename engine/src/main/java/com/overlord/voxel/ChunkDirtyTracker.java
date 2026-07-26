package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ChunkDirtyTracker {
    public Set<ChunkKey> affectedByBlock(
            ChunkKey target, int localX, int localZ) {
        validateLocalCoordinate(localX, "localX");
        validateLocalCoordinate(localZ, "localZ");

        Set<ChunkKey> affected = new LinkedHashSet<>();
        affected.add(target);
        if (localX == 0) {
            affected.add(target.west());
        }
        if (localX == GameConfig.Chunk.SIZE - 1) {
            affected.add(target.east());
        }
        if (localZ == 0) {
            affected.add(target.north());
        }
        if (localZ == GameConfig.Chunk.SIZE - 1) {
            affected.add(target.south());
        }
        if (localX == 0 && localZ == 0) {
            affected.add(target.northWest());
        } else if (localX == GameConfig.Chunk.SIZE - 1
                && localZ == 0) {
            affected.add(target.northEast());
        } else if (localX == 0
                && localZ == GameConfig.Chunk.SIZE - 1) {
            affected.add(target.southWest());
        } else if (localX == GameConfig.Chunk.SIZE - 1
                && localZ == GameConfig.Chunk.SIZE - 1) {
            affected.add(target.southEast());
        }
        return affected;
    }

    public Set<ChunkKey> horizontalNeighbors(ChunkKey target) {
        Set<ChunkKey> neighbors = new LinkedHashSet<>();
        neighbors.add(target.north());
        neighbors.add(target.south());
        neighbors.add(target.west());
        neighbors.add(target.east());
        return neighbors;
    }

    public Set<ChunkKey> meshingNeighbors(ChunkKey target) {
        Set<ChunkKey> neighbors = new LinkedHashSet<>();
        neighbors.add(target.north());
        neighbors.add(target.northEast());
        neighbors.add(target.east());
        neighbors.add(target.southEast());
        neighbors.add(target.south());
        neighbors.add(target.southWest());
        neighbors.add(target.west());
        neighbors.add(target.northWest());
        return neighbors;
    }

    private static void validateLocalCoordinate(
            int localCoordinate, String name) {
        if (localCoordinate < 0 || localCoordinate >= GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and "
                            + (GameConfig.Chunk.SIZE - 1));
        }
    }
}
