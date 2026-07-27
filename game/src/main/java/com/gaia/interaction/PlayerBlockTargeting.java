package com.gaia.interaction;

import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.physics.PhysicsBody;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3f;

public final class PlayerBlockTargeting implements BlockTargetProvider {
    private final BlockRaycastService raycast;
    private final PhysicsBody body;
    private final Camera camera;
    private final ChunkRepository chunks;
    private final float eyeHeight;
    private final float maxDistance;
    private final Vector3f origin = new Vector3f();
    private final Vector3f direction = new Vector3f();

    public PlayerBlockTargeting(
            BlockRaycastService raycast,
            PhysicsBody body,
            Camera camera,
            ChunkRepository chunks,
            float eyeHeight,
            float maxDistance) {
        this.raycast = Objects.requireNonNull(raycast, "raycast");
        this.body = Objects.requireNonNull(body, "body");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        if (!Float.isFinite(eyeHeight) || eyeHeight < 0) {
            throw new IllegalArgumentException("eyeHeight must be finite and non-negative");
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException("maxDistance must be finite and positive");
        }
        this.eyeHeight = eyeHeight;
        this.maxDistance = maxDistance;
    }

    @Override
    public Optional<BlockHitResult> target() {
        body.position(origin);
        origin.y += eyeHeight;
        camera.getForward(direction);
        Optional<BlockHitResult> hit = raycast.raycast(origin, direction, maxDistance);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        BlockHitResult result = hit.orElseThrow();
        ChunkKey key = ChunkKey.fromWorld(result.blockX(), result.blockZ());
        return chunks.snapshot(key).isPresent() ? hit : Optional.empty();
    }
}
