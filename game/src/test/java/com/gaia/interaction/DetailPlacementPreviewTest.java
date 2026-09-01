package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.physics.Aabb;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.VoxelScale;
import org.junit.jupiter.api.Test;

class DetailPlacementPreviewTest {
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    @Test
    void validCandidateProducesImmutableQuarterPreview() {
        DetailPlacementPreview preview = DetailPlacementPreview.forPlacement(
                CHISEL, candidate(DetailPlacementCandidate.Status.VALID_FULL_AIR));

        assertEquals(BlockInteractionRoute.DETAIL_PRECISION_PLACE, preview.action());
        assertEquals(CHISEL, preview.tool());
        assertEquals(17L, preview.observedRevision());
        assertEquals(new LocalSubVoxelPosition(2, 1, 3), preview.localPosition());
        assertEquals(DetailPreviewValidity.VALID, preview.validity());
        assertTrue(preview.reason().isEmpty());
        assertEquals(new Aabb(0.5f, 0.25f, 0.75f, 0.75f, 0.5f, 1.0f),
                preview.quarterBounds());
        assertThrows(NullPointerException.class, () -> new DetailPlacementPreview(
                null, CHISEL, candidate(DetailPlacementCandidate.Status.VALID_FULL_AIR).source(),
                1, 2, 3, new LocalSubVoxelPosition(0, 0, 0), BlockFace.EAST,
                STONE, 1, DetailPreviewValidity.VALID, java.util.Optional.empty(),
                new Aabb(0, 0, 0, .25f, .25f, .25f)));
    }

    @Test
    void typedCandidateFailuresRemainBoundedAndDistinct() {
        DetailPlacementPreview occupied = DetailPlacementPreview.forPlacement(
                CHISEL, candidate(DetailPlacementCandidate.Status.OCCUPIED));
        DetailPlacementPreview unknown = DetailPlacementPreview.forPlacement(
                CHISEL, candidate(DetailPlacementCandidate.Status.UNKNOWN));
        DetailPlacementPreview failed = DetailPlacementPreview.forPlacement(
                CHISEL, candidate(DetailPlacementCandidate.Status.FAILED));

        assertEquals(DetailPreviewValidity.OCCUPIED, occupied.validity());
        assertEquals(DetailPreviewValidity.UNKNOWN, unknown.validity());
        assertEquals(DetailPreviewValidity.FAILED, failed.validity());
        assertFalse(occupied.valid());
        assertTrue(occupied.reason().orElseThrow().length() <= 64);
    }

    private static DetailPlacementCandidate candidate(DetailPlacementCandidate.Status status) {
        DetailPrecisionTarget source = new DetailPrecisionTarget(
                1, 2, 3, new LocalSubVoxelPosition(1, 1, 3), BlockFace.EAST,
                STONE, 17L, FullRaycastTarget.INSTANCE);
        ParentCellObservation observation = new ParentCellObservation(
                new com.overlord.voxel.ChunkKey(0, 0), 2, 3, 4, 23L,
                new FullCellState((byte) 0));
        return new DetailPlacementCandidate(
                source, 2, 2, 3, new LocalSubVoxelPosition(2, 1, 3), STONE,
                ParentCellObservationResult.available(observation), status);
    }
}
