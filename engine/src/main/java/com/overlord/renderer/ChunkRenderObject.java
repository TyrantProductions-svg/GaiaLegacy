package com.overlord.renderer;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import org.joml.Matrix4f;

public final class ChunkRenderObject {
    private final ChunkKey key;
    private final long revision;
    private final ChunkGpuMesh mesh;
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
        Objects.requireNonNull(localBounds, "localBounds");
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
}
