package com.overlord.voxel;

import com.overlord.renderer.AxisAlignedBounds;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.nio.FloatBuffer;

public final class ChunkMeshData {
    private final ChunkKey key;
    private final long revision;
    private final float[] vertices;
    private final Optional<AxisAlignedBounds> localBounds;
    private final byte[] canonicalHash;

    public ChunkMeshData(
            ChunkKey key, long revision, float[] vertices) {
        this.key = Objects.requireNonNull(key, "key");
        this.revision = revision;
        Objects.requireNonNull(vertices, "vertices");
        if (vertices.length
                % VoxelVertexFormat.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(
                    "vertices must use a ten-float layout");
        }
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.localBounds = calculateLocalBounds(this.vertices);
        this.canonicalHash = calculateCanonicalHash(this.vertices);
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
        return vertices.length
                / VoxelVertexFormat.FLOATS_PER_VERTEX;
    }

    public Optional<AxisAlignedBounds> localBounds() {
        return localBounds;
    }

    public boolean isEmpty() {
        return vertices.length == 0;
    }

    public long outputByteSize() {
        return Math.multiplyExact((long) vertices.length, Float.BYTES);
    }

    /** Copies directly into caller-owned upload storage without a heap clone. */
    public void copyVerticesTo(FloatBuffer destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.remaining() < vertices.length) {
            throw new IllegalArgumentException(
                    "destination does not have enough remaining floats");
        }
        destination.put(vertices);
    }

    public byte[] canonicalHash() {
        return Arrays.copyOf(canonicalHash, canonicalHash.length);
    }

    private static byte[] calculateCanonicalHash(float[] vertices) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, vertices.length);
            for (float vertexValue : vertices) {
                updateInt(digest, Float.floatToIntBits(vertexValue));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
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
                offset += VoxelVertexFormat.FLOATS_PER_VERTEX) {
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
