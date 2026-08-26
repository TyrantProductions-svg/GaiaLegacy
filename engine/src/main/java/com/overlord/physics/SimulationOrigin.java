package com.overlord.physics;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Immutable origin for checked canonical-global to resident-local conversion. */
public record SimulationOrigin(ChunkKey chunkKey) {
    private static final double MAX_LOCAL_HORIZONTAL_DISTANCE = 8192.0;

    public SimulationOrigin {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
    }

    public Vector3f toLocal(GlobalPosition global) {
        Objects.requireNonNull(global, "global");
        double x = Math.addExact(
                        ChunkCoordinatePolicy.worldOriginX(global.chunkKey()),
                        0L)
                - ChunkCoordinatePolicy.worldOriginX(chunkKey)
                + global.localX();
        double z = Math.addExact(
                        ChunkCoordinatePolicy.worldOriginZ(global.chunkKey()),
                        0L)
                - ChunkCoordinatePolicy.worldOriginZ(chunkKey)
                + global.localZ();
        requireRepresentableLocal(x, "x");
        requireRepresentableLocal(global.y(), "y");
        requireRepresentableLocal(z, "z");
        if (Math.abs(x) > MAX_LOCAL_HORIZONTAL_DISTANCE
                || Math.abs(z) > MAX_LOCAL_HORIZONTAL_DISTANCE) {
            throw new IllegalArgumentException("global position is too distant from the simulation origin");
        }
        return new Vector3f((float) x, (float) global.y(), (float) z);
    }

    public GlobalPosition toGlobal(Vector3fc local) {
        Objects.requireNonNull(local, "local");
        requireFinite(local.x(), "x");
        requireFinite(local.y(), "y");
        requireFinite(local.z(), "z");
        if (Math.abs(local.x()) > MAX_LOCAL_HORIZONTAL_DISTANCE
                || Math.abs(local.z()) > MAX_LOCAL_HORIZONTAL_DISTANCE) {
            throw new IllegalArgumentException("local position is outside the precise origin envelope");
        }
        double chunkXOffset = Math.floor(local.x() / GameConfig.Chunk.SIZE);
        double chunkZOffset = Math.floor(local.z() / GameConfig.Chunk.SIZE);
        if (chunkXOffset < Integer.MIN_VALUE || chunkXOffset > Integer.MAX_VALUE
                || chunkZOffset < Integer.MIN_VALUE || chunkZOffset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("local position exceeds Chunk coordinates");
        }
        ChunkKey key = ChunkCoordinatePolicy.neighbor(
                chunkKey, (int) chunkXOffset, (int) chunkZOffset);
        double localX = local.x() - chunkXOffset * GameConfig.Chunk.SIZE;
        double localZ = local.z() - chunkZOffset * GameConfig.Chunk.SIZE;
        return new GlobalPosition(key, localX, local.y(), localZ);
    }

    public long worldOriginX() {
        return ChunkCoordinatePolicy.worldOriginX(chunkKey);
    }

    public long worldOriginZ() {
        return ChunkCoordinatePolicy.worldOriginZ(chunkKey);
    }

    private static void requireRepresentableLocal(double value, String label) {
        if (!Double.isFinite(value) || !Float.isFinite((float) value)
                || (double) (float) value != value) {
            throw new IllegalArgumentException(label + " cannot be represented exactly as a local float");
        }
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
