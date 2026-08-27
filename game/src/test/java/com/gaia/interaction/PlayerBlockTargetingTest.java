package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.SpatialBlockRaycastService;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import java.util.Optional;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class PlayerBlockTargetingTest {
    @Test
    void usesAuthoritativeBodyPlusEyeHeightAndCameraDirection() {
        ChunkRepository chunks = new ChunkRepository();
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        PhysicsBody body = bodyAt(2, 10, 3);
        Camera camera = new Camera();
        camera.setYaw(0);
        camera.setPitch(-15);
        CapturingRaycast raycast = new CapturingRaycast(hitAt(4, 9, 3));
        PlayerBlockTargeting targeting = new PlayerBlockTargeting(
                raycast, body, camera, chunks, 1.6f, 6.0f);

        Optional<BlockHitResult> target = targeting.target().result();

        assertTrue(target.isPresent());
        assertEquals(new Vector3f(2, 11.6f, 3), raycast.origin);
        assertEquals(camera.getForward(new Vector3f()), raycast.direction);
        assertEquals(6.0f, raycast.maxDistance);
    }

    @Test
    void preservesUnknownWhenHitChunkLosesResidentAuthority() {
        PlayerBlockTargeting targeting = new PlayerBlockTargeting(
                new CapturingRaycast(hitAt(64, 5, 64)),
                bodyAt(0, 0, 0),
                new Camera(),
                new ChunkRepository(),
                1.6f,
                6.0f);

        SpatialQueryResult<BlockHitResult> result = targeting.target();

        assertEquals(SpatialQueryResult.Status.UNKNOWN, result.status());
        assertEquals(new ChunkKey(4, 4), result.unavailableKey().orElseThrow());
        assertTrue(result.result().isEmpty());
    }

    @Test
    void targetingPublishesTypedAvailableResultToItsOwner() {
        ChunkRepository chunks = new ChunkRepository();
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        PlayerBlockTargeting targeting = new PlayerBlockTargeting(
                new CapturingRaycast(hitAt(4, 5, 4)),
                bodyAt(0, 0, 0),
                new Camera(),
                chunks,
                1.6f,
                6.0f);

        Object raw = targeting.target();

        SpatialQueryResult<?> query = assertInstanceOf(
                SpatialQueryResult.class, raw,
                "PlayerBlockTargeting must not erase spatial-query status");
        assertEquals(SpatialQueryResult.Status.AVAILABLE, query.status());
        assertEquals(hitAt(4, 5, 4), query.result().orElseThrow());
    }

    private static PhysicsBody bodyAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(x, y, z));
        return body;
    }

    private static BlockHitResult hitAt(int x, int y, int z) {
        return new BlockHitResult(
                x, y, z,
                x + 1, y, z,
                ResourceLocation.parse("gaia:stone"),
                1, 0, 0,
                x + 1, y + 0.5f, z + 0.5f,
                2);
    }

    private static final class CapturingRaycast implements SpatialBlockRaycastService {
        private final BlockHitResult hit;
        private Vector3f origin;
        private Vector3f direction;
        private float maxDistance;

        private CapturingRaycast(BlockHitResult hit) {
            this.hit = hit;
        }

        @Override
        public SpatialQueryResult<BlockHitResult> query(
                Vector3fc origin, Vector3fc direction, float maxDistance) {
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction);
            this.maxDistance = maxDistance;
            return SpatialQueryResult.available(Optional.of(hit));
        }
    }
}
