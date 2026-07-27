package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.BlockRaycastHit;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class GaiaBlockRaycastServiceTest {
    @Test
    void delegatesToPhaseSixRaycastAndMapsStoredIdentityExactly() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.of(new BlockRaycastHit(
                        1, 2, 3,
                        0, 2, 3,
                        (byte) 200,
                        -1, 0, 0,
                        1, 2.5f, 3.5f,
                        4.5f)),
                id -> {
                    assertEquals(200, id);
                    return ResourceLocation.parse("gaia:high_id");
                });

        BlockHitResult hit = service.raycast(
                new Vector3f(), new Vector3f(1, 0, 0), 6).orElseThrow();

        assertEquals(ResourceLocation.parse("gaia:high_id"), hit.block());
        assertEquals(BlockFace.WEST, BlockFace.fromHit(hit));
        assertEquals(4.5f, hit.distance());
    }

    @Test
    void preservesPhaseSixMiss() {
        GaiaBlockRaycastService service = new GaiaBlockRaycastService(
                (origin, direction, distance) -> Optional.empty(),
                ignored -> ResourceLocation.parse("gaia:air"));

        assertTrue(service.raycast(
                new Vector3f(), new Vector3f(0, 0, -1), 6).isEmpty());
    }
}
