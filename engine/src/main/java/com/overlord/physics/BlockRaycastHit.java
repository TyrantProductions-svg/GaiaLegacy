package com.overlord.physics;

import org.joml.Vector3f;

public record BlockRaycastHit(
        int blockX,
        int blockY,
        int blockZ,
        int adjacentX,
        int adjacentY,
        int adjacentZ,
        byte blockId,
        float normalX,
        float normalY,
        float normalZ,
        float pointX,
        float pointY,
        float pointZ,
        float distance) {
    public Vector3f normal(Vector3f destination) {
        return destination.set(normalX, normalY, normalZ);
    }

    public Vector3f point(Vector3f destination) {
        return destination.set(pointX, pointY, pointZ);
    }
}
