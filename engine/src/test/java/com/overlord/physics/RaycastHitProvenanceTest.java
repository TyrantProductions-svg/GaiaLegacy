package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.VoxelScale;
import org.junit.jupiter.api.Test;

class RaycastHitProvenanceTest {
    private static final DetailRaycastTarget DETAIL =
            new DetailRaycastTarget(
                    VoxelScale.DETAIL_4,
                    new LocalSubVoxelPosition(0, 0, 0));

    @Test
    void detailEngineHitRequiresObservedPositiveChunkRevision() {
        assertThrows(IllegalArgumentException.class, () ->
                new BlockRaycastHit(
                        1, 2, 3, 0, 2, 3, (byte) 7,
                        -1, 0, 0,
                        1, 2.125f, 3.125f, 1,
                        1, 2.125, 3.125,
                        0L, DETAIL));
    }

    @Test
    void detailGameHitRequiresObservedPositiveChunkRevision() {
        assertThrows(IllegalArgumentException.class, () ->
                new BlockHitResult(
                        1, 2, 3, 0, 2, 3,
                        ResourceLocation.parse("gaia:stone"),
                        -1, 0, 0,
                        1, 2.125f, 3.125f, 1,
                        1, 2.125, 3.125,
                        0L, DETAIL));
    }
}
