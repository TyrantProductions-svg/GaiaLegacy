package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.List;
import java.util.Objects;

/** Checked upper bounds for detached non-indexed Chunk mesh output. */
final class ChunkMeshGeometryBounds {
    private static final long FACES_PER_CELL = 6L;
    private static final long QUARTER_FACELETS_PER_FULL_FACE = 16L;
    private static final long VERTICES_PER_FACELET = 6L;
    private static final long FLOATS_PER_VERTEX =
            VoxelVertexFormat.FLOATS_PER_VERTEX;

    private ChunkMeshGeometryBounds() {}

    static OutputBound forInput(ChunkMeshInput input) {
        ChunkMeshInput required = Objects.requireNonNull(input, "input");
        boolean detailParticipates = snapshots(required).stream()
                .anyMatch(snapshot -> !snapshot.details().isEmpty());
        long faceletLimit = 0L;
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int y = 0; y < required.center().worldHeight(); y++) {
                for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                    ParentCellState state = required.center().cellState(x, y, z);
                    if (state instanceof DetailCellState detail) {
                        faceletLimit = Math.addExact(
                                faceletLimit,
                                Math.multiplyExact(
                                        (long) Long.bitCount(detail.occupancyMask()),
                                        FACES_PER_CELL));
                    } else if (((FullCellState) state).blockId() != 0) {
                        long perFace = detailParticipates
                                ? QUARTER_FACELETS_PER_FULL_FACE
                                : 1L;
                        faceletLimit = Math.addExact(
                                faceletLimit,
                                Math.multiplyExact(FACES_PER_CELL, perFace));
                    }
                }
            }
        }
        return fromFaceletLimit(faceletLimit);
    }

    static OutputBound fullOnly(int worldHeight) {
        if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException("invalid worldHeight");
        }
        long parentCount = Math.multiplyExact(
                Math.multiplyExact(
                        (long) GameConfig.Chunk.SIZE, worldHeight),
                GameConfig.Chunk.SIZE);
        return fromFaceletLimit(
                Math.multiplyExact(parentCount, FACES_PER_CELL));
    }

    static OutputBound fromFaceletLimit(long faceletLimit) {
        if (faceletLimit < 0L) {
            throw new IllegalArgumentException(
                    "faceletLimit must not be negative");
        }
        long vertexLimit = Math.multiplyExact(
                faceletLimit, VERTICES_PER_FACELET);
        long floatLimit = Math.multiplyExact(
                vertexLimit, FLOATS_PER_VERTEX);
        long byteLimit = Math.multiplyExact(floatLimit, Float.BYTES);
        return new OutputBound(
                faceletLimit, vertexLimit, floatLimit, byteLimit);
    }

    private static List<ChunkSnapshot> snapshots(ChunkMeshInput input) {
        return List.of(
                input.center(),
                input.north(),
                input.northEast(),
                input.east(),
                input.southEast(),
                input.south(),
                input.southWest(),
                input.west(),
                input.northWest());
    }

    record OutputBound(
            long faceletLimit,
            long vertexLimit,
            long floatLimit,
            long byteLimit) {
        int floatArrayLimit() {
            return Math.toIntExact(floatLimit);
        }
    }
}
