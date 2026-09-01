package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.DetailToFullRequest;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.Aabb;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CreativeDetailEditTransactionTest {
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final LocalSubVoxelPosition LOCAL = new LocalSubVoxelPosition(3, 2, 1);

    @Test
    void fullAndDetailRemovalSubmitOneExactAtomicSculpt() {
        RecordingMutations mutations = new RecordingMutations();
        ParentCellState full = new FullCellState((byte) 1);
        CreativeDetailEditTransaction transaction = transaction(
                mutations, (x, y, z) -> available(x, y, z, 7L, full), outsideBody());
        DetailPrecisionTarget fullTarget = target(
                4, 7, 6, LOCAL, 7L, FullRaycastTarget.INSTANCE, STONE);

        DetailEditResult fullResult = transaction.executeRemove(
                fullTarget, BodySlot.RIGHT_HAND, 11L, 22L);

        assertEquals(DetailEditResult.Status.APPLIED, fullResult.status());
        SculptParentSubVoxelRequest fullRequest = mutations.sculpt.orElseThrow();
        assertEquals(full, fullRequest.expectedState());
        assertEquals(LOCAL, fullRequest.position());
        assertTrue(fullRequest.replacementBlock().isEmpty());
        assertTrue(fullResult.feedbackEligible());

        DetailCellState detail = detail(LOCAL, (byte) 1);
        mutations.reset();
        CreativeDetailEditTransaction detailTransaction = transaction(
                mutations, (x, y, z) -> available(x, y, z, 9L, detail), outsideBody());
        DetailPrecisionTarget detailTarget = target(
                4,
                7,
                6,
                LOCAL,
                9L,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, LOCAL),
                STONE);

        DetailEditResult detailResult = detailTransaction.executeRemove(
                detailTarget, BodySlot.LEFT_HAND, 12L, 23L);

        assertEquals(DetailEditResult.Status.APPLIED, detailResult.status());
        assertEquals(detail, mutations.sculpt.orElseThrow().expectedState());
        assertEquals(BodySlot.LEFT_HAND, mutations.sculpt.orElseThrow().context().activeBodySlot());
        assertEquals(1, mutations.sculptCalls);
    }

    @Test
    void placesIntoFullAirAndEmptyDetailUsingCandidateObservation() {
        RecordingMutations mutations = new RecordingMutations();
        CreativeDetailEditTransaction transaction = transaction(
                mutations, ignoredWorld(), outsideBody());
        DetailPrecisionTarget source = target(
                15, 7, 15, LOCAL, 5L,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, LOCAL), STONE);
        ParentCellObservationResult fullAir = available(16, 7, 15, 8L, new FullCellState((byte) 0));
        DetailPlacementCandidate firstCell = new DetailPlacementCandidate(
                source,
                16,
                7,
                15,
                new LocalSubVoxelPosition(0, 2, 1),
                DIRT,
                fullAir,
                DetailPlacementCandidate.Status.VALID_FULL_AIR);

        DetailEditResult first = transaction.executePlace(
                firstCell, BodySlot.RIGHT_HAND, 13L, 24L);

        assertEquals(DetailEditResult.Status.APPLIED, first.status());
        SculptParentSubVoxelRequest request = mutations.sculpt.orElseThrow();
        assertEquals(16, request.x());
        assertEquals(8L, request.expectedChunkRevision());
        assertEquals(new FullCellState((byte) 0), request.expectedState());
        assertEquals(Optional.of(DIRT), request.replacementBlock());

        mutations.reset();
        LocalSubVoxelPosition empty = new LocalSubVoxelPosition(1, 1, 1);
        DetailCellState occupiedElsewhere = detail(new LocalSubVoxelPosition(0, 0, 0), (byte) 1);
        ParentCellObservationResult detailObservation = available(-16, 7, -16, 12L, occupiedElsewhere);
        DetailPlacementCandidate crossChunk = new DetailPlacementCandidate(
                source,
                -16,
                7,
                -16,
                empty,
                STONE,
                detailObservation,
                DetailPlacementCandidate.Status.VALID_DETAIL_EMPTY);
        DetailEditResult placed = transaction.executePlace(
                crossChunk, BodySlot.RIGHT_HAND, 14L, 25L);
        assertEquals(DetailEditResult.Status.APPLIED, placed.status());
        assertEquals(-16, mutations.sculpt.orElseThrow().x());
        assertEquals(-16, mutations.sculpt.orElseThrow().z());
        assertEquals(occupiedElsewhere, mutations.sculpt.orElseThrow().expectedState());
    }

    @Test
    void rejectsInvalidUnavailableOccupiedAndPlayerOverlapBeforeMutation() {
        RecordingMutations mutations = new RecordingMutations();
        DetailPrecisionTarget source = target(
                0, 0, 0, new LocalSubVoxelPosition(0, 0, 0), 2L,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, new LocalSubVoxelPosition(0, 0, 0)),
                STONE);
        PhysicsBody overlapping = bodyAt(new Vector3f(0.1f, 0.0f, 0.1f));
        CreativeDetailEditTransaction transaction = transaction(
                mutations, ignoredWorld(), overlapping);
        DetailPlacementCandidate overlap = new DetailPlacementCandidate(
                source,
                0,
                0,
                0,
                new LocalSubVoxelPosition(0, 0, 0),
                STONE,
                available(0, 0, 0, 3L, new FullCellState((byte) 0)),
                DetailPlacementCandidate.Status.VALID_FULL_AIR);
        assertEquals(
                DetailEditResult.Status.PLAYER_INTERSECTION,
                transaction.executePlace(overlap, BodySlot.RIGHT_HAND, 1L, 1L).status());

        PhysicsBody touching = bodyAt(new Vector3f(0.55f, 0.0f, 0.55f));
        CreativeDetailEditTransaction touchingTransaction = transaction(
                mutations, ignoredWorld(), touching);
        assertEquals(
                DetailEditResult.Status.APPLIED,
                touchingTransaction.executePlace(overlap, BodySlot.RIGHT_HAND, 1L, 1L).status());

        mutations.reset();
        DetailPlacementCandidate occupied = new DetailPlacementCandidate(
                source, 0, 0, 0, LOCAL, STONE,
                available(0, 0, 0, 3L, detail(LOCAL, (byte) 1)),
                DetailPlacementCandidate.Status.OCCUPIED);
        assertEquals(
                DetailEditResult.Status.INVALID_CANDIDATE,
                transaction.executePlace(occupied, BodySlot.RIGHT_HAND, 1L, 1L).status());
        DetailPlacementCandidate unavailable = new DetailPlacementCandidate(
                source, 16, 0, 0, LOCAL, STONE,
                ParentCellObservationResult.unavailable(
                        ChunkAvailability.UNKNOWN, new ChunkKey(1, 0)),
                DetailPlacementCandidate.Status.UNKNOWN);
        assertEquals(
                DetailEditResult.Status.UNAVAILABLE,
                transaction.executePlace(unavailable, BodySlot.RIGHT_HAND, 1L, 1L).status());
        assertEquals(0, mutations.sculptCalls);
    }

    @Test
    void playerOverlapUsesResidentLocalCoordinatesAfterSimulationOriginRebase() {
        SimulationOrigin origin = new SimulationOrigin(new ChunkKey(100_000_000, -100_000_000));
        int parentX = Math.toIntExact(origin.worldOriginX());
        int parentZ = Math.toIntExact(origin.worldOriginZ());
        DetailPrecisionTarget source = target(
                parentX,
                0,
                parentZ,
                new LocalSubVoxelPosition(0, 0, 0),
                2L,
                new DetailRaycastTarget(
                        VoxelScale.DETAIL_4,
                        new LocalSubVoxelPosition(0, 0, 0)),
                STONE);
        DetailPlacementCandidate candidate = new DetailPlacementCandidate(
                source,
                parentX,
                0,
                parentZ,
                new LocalSubVoxelPosition(0, 0, 0),
                STONE,
                available(parentX, 0, parentZ, 3L, new FullCellState((byte) 0)),
                DetailPlacementCandidate.Status.VALID_FULL_AIR);
        DetailPlacementCollisionValidator validator =
                new DetailPlacementCollisionValidator(() -> origin);

        assertTrue(validator.overlapsPlayer(
                candidate,
                bodyAt(new Vector3f(0.1f, 0.0f, 0.1f))));
        assertFalse(validator.overlapsPlayer(
                candidate,
                bodyAt(new Vector3f(0.55f, 0.0f, 0.55f))));
    }

    @Test
    void staleOrRejectedMutationNeverEnablesCommittedFeedback() {
        RecordingMutations mutations = new RecordingMutations();
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;
        ParentCellState full = new FullCellState((byte) 1);
        CreativeDetailEditTransaction transaction = transaction(
                mutations, (x, y, z) -> available(x, y, z, 7L, full), outsideBody());

        DetailEditResult result = transaction.executeRemove(
                target(4, 7, 6, LOCAL, 6L, FullRaycastTarget.INSTANCE, STONE),
                BodySlot.RIGHT_HAND,
                1L,
                2L);

        assertEquals(DetailEditResult.Status.MUTATION_REJECTED, result.status());
        assertFalse(result.feedbackEligible());
        assertEquals(
                DetailMutationResult.Status.STALE_CHUNK_REVISION,
                result.mutation().orElseThrow().status());
    }

    private static CreativeDetailEditTransaction transaction(
            RecordingMutations mutations,
            DetailTargetWorldView world,
            PhysicsBody body) {
        return new CreativeDetailEditTransaction(
                mutations,
                world,
                new DetailPlacementCollisionValidator(),
                body,
                new EntityRef(42));
    }

    private static DetailPrecisionTarget target(
            int x,
            int y,
            int z,
            LocalSubVoxelPosition local,
            long revision,
            com.overlord.physics.RaycastCellTarget representation,
            ResourceLocation material) {
        return new DetailPrecisionTarget(
                x, y, z, local, BlockFace.EAST, material, revision, representation);
    }

    private static ParentCellObservationResult available(
            int x, int y, int z, long revision, ParentCellState state) {
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

    private static DetailTargetWorldView ignoredWorld() {
        return (x, y, z) -> {
            throw new AssertionError("placement must use the accepted candidate observation");
        };
    }

    private static PhysicsBody outsideBody() {
        return bodyAt(new Vector3f(100, 100, 100));
    }

    private static PhysicsBody bodyAt(Vector3f position) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(position);
        return body;
    }

    private static DetailCellState detail(LocalSubVoxelPosition position, byte id) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[position.index()] = id;
        return new DetailCellState(1L << position.index(), ids);
    }

    private static final class RecordingMutations implements DetailMutationService {
        private Optional<SculptParentSubVoxelRequest> sculpt = Optional.empty();
        private int sculptCalls;
        private DetailMutationResult.Status nextStatus = DetailMutationResult.Status.APPLIED;

        void reset() {
            sculpt = Optional.empty();
            sculptCalls = 0;
            nextStatus = DetailMutationResult.Status.APPLIED;
        }

        @Override
        public DetailMutationResult sculptParentSubVoxel(SculptParentSubVoxelRequest request) {
            sculpt = Optional.of(request);
            sculptCalls++;
            ParentCellState replacement = request.replacementBlock().isEmpty()
                    ? new FullCellState((byte) 0)
                    : detail(request.position(), (byte) 9);
            boolean applied = nextStatus == DetailMutationResult.Status.APPLIED;
            return new DetailMutationResult(
                    request.context(),
                    nextStatus,
                    Optional.of(request.expectedState()),
                    applied ? Optional.of(replacement) : Optional.empty(),
                    request.expectedChunkRevision(),
                    applied ? request.expectedChunkRevision() + 1 : request.expectedChunkRevision(),
                    applied ? List.of(new DirtyChunkRevision(
                            ChunkKey.fromWorld(request.x(), request.z()),
                            request.expectedChunkRevision() + 1)) : List.of());
        }

        @Override public DetailMutationResult convertFullToDetail(FullToDetailRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult setSubVoxel(DetailMutationRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult removeDetailParent(RemoveDetailParentRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult compactDetailToFull(DetailToFullRequest request) { throw new AssertionError(); }
    }
}
