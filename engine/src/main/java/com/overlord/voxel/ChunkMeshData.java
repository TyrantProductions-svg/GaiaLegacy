package com.overlord.voxel;

import com.overlord.renderer.AxisAlignedBounds;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class ChunkMeshData {
    private static final int FLOATS_PER_VERTEX = 5;

    private final ChunkKey key;
    private final long revision;
    private final float[] vertices;
    private final Optional<AxisAlignedBounds> localBounds;

    public ChunkMeshData(
            ChunkKey key, long revision, float[] vertices) {
        this.key = Objects.requireNonNull(key, "key");
        this.revision = revision;
        Objects.requireNonNull(vertices, "vertices");
        if (vertices.length % FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(
                    "vertices must use a five-float layout");
        }
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.localBounds = calculateLocalBounds(this.vertices);
    }

    public ChunkKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public float[] vertices() {
        return Arrays.copyOf(vertices, vertices.length);
    }

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public Optional<AxisAlignedBounds> localBounds() {
        return localBounds;
    }

    public boolean isEmpty() {
        return vertices.length == 0;
    }

    private static Optional<AxisAlignedBounds> calculateLocalBounds(
            float[] vertices) {
        if (vertices.length == 0) {
            return Optional.empty();
        }

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int offset = 0;
                offset < vertices.length;
                offset += FLOATS_PER_VERTEX) {
            float x = vertices[offset];
            float y = vertices[offset + 1];
            float z = vertices[offset + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return Optional.of(
                new AxisAlignedBounds(
                        minX, minY, minZ, maxX, maxY, maxZ));
    }
}
