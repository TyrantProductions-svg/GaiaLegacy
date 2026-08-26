package com.overlord.renderer;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import org.joml.Matrix4f;

public final class ChunkRenderObject {
    private final ChunkKey key;
    private final long revision;
    private final ChunkGpuMesh mesh;
    private final AxisAlignedBounds localBounds;
    private final Matrix4f modelMatrix;
    private final AxisAlignedBounds worldBounds;

    public ChunkRenderObject(
            ChunkKey key,
            long revision,
            ChunkGpuMesh mesh,
            AxisAlignedBounds localBounds) {
        this.key = Objects.requireNonNull(key, "key");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        if (mesh.vertexCount() <= 0) {
            throw new IllegalArgumentException("mesh must contain vertices");
        }
        this.localBounds = Objects.requireNonNull(localBounds, "localBounds");
        this.modelMatrix = new Matrix4f().translation(
                key.worldOriginX(), 0, key.worldOriginZ());
        this.worldBounds = localBounds.translate(
                key.worldOriginX(), 0, key.worldOriginZ());
    }

    public ChunkKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public ChunkGpuMesh mesh() {
        return mesh;
    }

    public Matrix4f modelMatrix() {
        return new Matrix4f(modelMatrix);
    }

    public AxisAlignedBounds worldBounds() {
        return worldBounds;
    }

    /** Returns an immutable replacement relative to {@code origin}, reusing the same GPU mesh. */
    public ChunkRenderObject forOrigin(RenderOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        long offsetX = Math.subtractExact(
                com.overlord.voxel.ChunkCoordinatePolicy.worldOriginX(key), origin.worldOriginX());
        long offsetZ = Math.subtractExact(
                com.overlord.voxel.ChunkCoordinatePolicy.worldOriginZ(key), origin.worldOriginZ());
        float x = checkedFloat(offsetX, "x");
        float z = checkedFloat(offsetZ, "z");
        return new ChunkRenderObject(key, revision, mesh, localBounds, x, z);
    }

    private ChunkRenderObject(
            ChunkKey key,
            long revision,
            ChunkGpuMesh mesh,
            AxisAlignedBounds localBounds,
            float originRelativeX,
            float originRelativeZ) {
        this.key = key;
        this.revision = revision;
        this.mesh = mesh;
        this.localBounds = localBounds;
        this.modelMatrix = new Matrix4f().translation(originRelativeX, 0, originRelativeZ);
        this.worldBounds = localBounds.translate(originRelativeX, 0, originRelativeZ);
    }

    private static float checkedFloat(long value, String axis) {
        float converted = value;
        if (!Float.isFinite(converted) || (long) converted != value) {
            throw new IllegalArgumentException(axis + " origin offset is not precisely representable");
        }
        return converted;
    }
}
