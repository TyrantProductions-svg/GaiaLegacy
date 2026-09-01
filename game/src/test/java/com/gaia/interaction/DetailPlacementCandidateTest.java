package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DetailPlacementCandidateTest {
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");

    @Test
    void sameParentEmptyDetailQuarterIsValid() {
        LocalSubVoxelPosition occupied = new LocalSubVoxelPosition(0, 0, 0);
        DetailPlacementCandidate candidate = DetailTargeting.placementCandidate(
                detailHit(
                        4, 5, 6,
                        new LocalSubVoxelPosition(1, 2, 3),
                        BlockFace.EAST,
                        11L),
                STONE,
                (x, y, z) -> available(x, y, z, 23L, detail(occupied)));

        assertEquals(DetailPlacementCandidate.Status.VALID_DETAIL_EMPTY,
                candidate.status());
        assertEquals(4, candidate.parentX());
        assertEquals(new LocalSubVoxelPosition(2, 2, 3),
                candidate.localPosition());
        assertEquals(11L, candidate.source().observedChunkRevision());
        assertEquals(23L, candidate.destinationObservation()
                .observation().orElseThrow().chunkRevision());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappedFaces")
    void wrapsAllSixFacesAcrossCanonicalParents(
            BlockFace face,
            LocalSubVoxelPosition sourceLocal,
            int expectedX,
            int expectedY,
            int expectedZ,
            LocalSubVoxelPosition expectedLocal) {
        AtomicReference<String> observed = new AtomicReference<>();

        DetailPlacementCandidate candidate = DetailTargeting.placementCandidate(
                detailHit(4, 5, 6, sourceLocal, face, 12L),
                STONE,
                (x, y, z) -> {
                    observed.set(x + "," + y + "," + z);
                    return available(x, y, z, 31L, new FullCellState((byte) 0));
                });

        assertEquals(expectedX + "," + expectedY + "," + expectedZ,
                observed.get());
        assertEquals(expectedLocal, candidate.localPosition());
        assertEquals(DetailPlacementCandidate.Status.VALID_FULL_AIR,
                candidate.status());
    }

    @Test
    void wrapsAcrossPositiveAndNegativeChunkBoundaries() {
        DetailPlacementCandidate positive = DetailTargeting.placementCandidate(
                detailHit(
                        15, 2, 15,
                        new LocalSubVoxelPosition(3, 1, 1),
                        BlockFace.EAST,
                        4L),
                STONE,
                (x, y, z) -> available(x, y, z, 9L, new FullCellState((byte) 0)));
        DetailPlacementCandidate negative = DetailTargeting.placementCandidate(
                detailHit(
                        -16, 2, -16,
                        new LocalSubVoxelPosition(0, 1, 1),
                        BlockFace.WEST,
                        5L),
                STONE,
                (x, y, z) -> available(x, y, z, 10L, new FullCellState((byte) 0)));

        assertEquals(16, positive.parentX());
        assertEquals(new ChunkKey(1, 0), positive.destinationObservation()
                .observation().orElseThrow().chunkKey());
        assertEquals(-17, negative.parentX());
        assertEquals(new ChunkKey(-2, -1), negative.destinationObservation()
                .observation().orElseThrow().chunkKey());
    }

    @ParameterizedTest
    @MethodSource("destinationStates")
    void preservesTypedDestinationSemantics(
            ParentCellObservationResult result,
            DetailPlacementCandidate.Status expected) {
        DetailPlacementCandidate candidate = DetailTargeting.placementCandidate(
                detailHit(
                        0, 1, 0,
                        new LocalSubVoxelPosition(1, 1, 1),
                        BlockFace.EAST,
                        7L),
                STONE,
                (x, y, z) -> result);

        assertEquals(expected, candidate.status());
        assertEquals(result, candidate.destinationObservation());
    }

    private static Stream<Arguments> wrappedFaces() {
        return Stream.of(
                Arguments.of(
                        BlockFace.EAST,
                        new LocalSubVoxelPosition(3, 1, 2),
                        5, 5, 6,
                        new LocalSubVoxelPosition(0, 1, 2)),
                Arguments.of(
                        BlockFace.WEST,
                        new LocalSubVoxelPosition(0, 1, 2),
                        3, 5, 6,
                        new LocalSubVoxelPosition(3, 1, 2)),
                Arguments.of(
                        BlockFace.UP,
                        new LocalSubVoxelPosition(1, 3, 2),
                        4, 6, 6,
                        new LocalSubVoxelPosition(1, 0, 2)),
                Arguments.of(
                        BlockFace.DOWN,
                        new LocalSubVoxelPosition(1, 0, 2),
                        4, 4, 6,
                        new LocalSubVoxelPosition(1, 3, 2)),
                Arguments.of(
                        BlockFace.SOUTH,
                        new LocalSubVoxelPosition(1, 2, 3),
                        4, 5, 7,
                        new LocalSubVoxelPosition(1, 2, 0)),
                Arguments.of(
                        BlockFace.NORTH,
                        new LocalSubVoxelPosition(1, 2, 0),
                        4, 5, 5,
                        new LocalSubVoxelPosition(1, 2, 3)));
    }

    private static Stream<Arguments> destinationStates() {
        LocalSubVoxelPosition candidate = new LocalSubVoxelPosition(2, 1, 1);
        return Stream.of(
                Arguments.of(
                        available(0, 1, 0, 20L, new FullCellState((byte) 0)),
                        DetailPlacementCandidate.Status.VALID_FULL_AIR),
                Arguments.of(
                        available(0, 1, 0, 21L, new FullCellState((byte) 4)),
                        DetailPlacementCandidate.Status.OCCUPIED),
                Arguments.of(
                        available(0, 1, 0, 22L, detail(candidate)),
                        DetailPlacementCandidate.Status.OCCUPIED),
                Arguments.of(
                        ParentCellObservationResult.unavailable(
                                ChunkAvailability.UNKNOWN,
                                new ChunkKey(0, 0)),
                        DetailPlacementCandidate.Status.UNKNOWN),
                Arguments.of(
                        ParentCellObservationResult.unavailable(
                                ChunkAvailability.FAILED,
                                new ChunkKey(0, 0)),
                        DetailPlacementCandidate.Status.FAILED),
                Arguments.of(
                        ParentCellObservationResult.availableEmpty(),
                        DetailPlacementCandidate.Status.OUT_OF_BOUNDS));
    }

    private static ParentCellObservationResult available(
            int x,
            int y,
            int z,
            long revision,
            ParentCellState state) {
        ChunkKey key = ChunkKey.fromWorld(x, z);
        return ParentCellObservationResult.available(
                new ParentCellObservation(
                        key,
                        ChunkKey.localCoordinate(x),
                        y,
                        ChunkKey.localCoordinate(z),
                        revision,
                        state));
    }

    private static DetailCellState detail(LocalSubVoxelPosition occupied) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[occupied.index()] = 1;
        return new DetailCellState(1L << occupied.index(), ids);
    }

    private static BlockHitResult detailHit(
            int x,
            int y,
            int z,
            LocalSubVoxelPosition local,
            BlockFace face,
            long revision) {
        return new BlockHitResult(
                x, y, z,
                x + face.normalX(),
                y + face.normalY(),
                z + face.normalZ(),
                STONE,
                face.normalX(), face.normalY(), face.normalZ(),
                x + 0.5f, y + 0.5f, z + 0.5f,
                1.0f,
                x + 0.5, y + 0.5, z + 0.5,
                revision,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, local));
    }
}
