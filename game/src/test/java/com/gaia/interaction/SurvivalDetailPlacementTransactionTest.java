package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.inventory.BodyInventoryReservationPlanner;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationEventDispatchException;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.DetailToFullRequest;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.testing.TestInventoryView;
import com.overlord.physics.Aabb;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class SurvivalDetailPlacementTransactionTest {
    private static final EntityRef OWNER = new EntityRef(19);
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE_UNIT = ResourceLocation.parse("gaia:stone_detail_unit");
    private static final LocalSubVoxelPosition LOCAL = new LocalSubVoxelPosition(0, 2, 1);

    @Test
    void placementExtractsOneMatchingUnitAfterOneAppliedCas() {
        ExtractingInventory inventory = new ExtractingInventory(Map.of(BodySlot.LEFT_HAND,
                new ItemStack(STONE_UNIT, 1)));
        RecordingMutations mutations = new RecordingMutations();
        SurvivalDetailEditTransaction transaction = transaction(inventory, mutations, outsideBody());

        SurvivalDetailEditResult result = transaction.place(
                fullAirCandidate(16, 7, 15, 8L), recoverDecision(),
                BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.APPLIED, result.status());
        assertEquals(1, result.occupiedDelta());
        assertEquals(-1, result.inventoryDelta());
        assertTrue(result.materialConserved());
        assertEquals(List.of(BodySlot.RIGHT_HAND, BodySlot.LEFT_HAND), inventory.reserveOrder);
        assertEquals(1, mutations.sculptCalls);
        assertTrue(mutations.inventoryWasReserved);
        assertEquals(InventoryReservationOperation.EXTRACT,
                inventory.lastRequest.orElseThrow().operation());
    }

    @Test
    void noMatchingUnitOrWrongUnitRejectsBeforeMutation() {
        ExtractingInventory inventory = new ExtractingInventory(Map.of(
                BodySlot.RIGHT_HAND, new ItemStack(ResourceLocation.parse("gaia:dirt_detail_unit"), 4)));
        RecordingMutations mutations = new RecordingMutations();

        SurvivalDetailEditResult result = transaction(inventory, mutations, outsideBody()).place(
                fullAirCandidate(1, 2, 3, 4L), recoverDecision(),
                BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.INVENTORY_ITEM_UNAVAILABLE, result.status());
        assertEquals(0, mutations.sculptCalls);
    }

    @Test
    void occupiedUnavailableAndOverlapRejectWithoutExtraction() {
        ExtractingInventory inventory = new ExtractingInventory(Map.of(
                BodySlot.RIGHT_HAND, new ItemStack(STONE_UNIT, 3)));
        RecordingMutations mutations = new RecordingMutations();
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, bodyAt(new Vector3f(0.1f, 0f, 0.1f)));
        DetailPrecisionTarget source = source();
        DetailPlacementCandidate occupied = new DetailPlacementCandidate(
                source, 0, 0, 0, LOCAL, STONE,
                available(0, 0, 0, 3L, detail(LOCAL, (byte) 1)),
                DetailPlacementCandidate.Status.OCCUPIED);
        DetailPlacementCandidate unavailable = new DetailPlacementCandidate(
                source, 16, 0, 0, LOCAL, STONE,
                ParentCellObservationResult.unavailable(
                        com.overlord.voxel.ChunkAvailability.UNKNOWN, new ChunkKey(1, 0)),
                DetailPlacementCandidate.Status.UNKNOWN);
        DetailPlacementCandidate overlap = fullAirCandidate(0, 0, 0, 3L);

        assertEquals(SurvivalDetailEditResult.Status.INVALID_CANDIDATE,
                transaction.place(occupied, recoverDecision(), BodySlot.RIGHT_HAND, context()).status());
        assertEquals(SurvivalDetailEditResult.Status.UNAVAILABLE,
                transaction.place(unavailable, recoverDecision(), BodySlot.RIGHT_HAND, context()).status());
        assertEquals(SurvivalDetailEditResult.Status.PLAYER_INTERSECTION,
                transaction.place(overlap, recoverDecision(), BodySlot.RIGHT_HAND, context()).status());
        assertTrue(inventory.reserveOrder.isEmpty());
        assertEquals(0, mutations.sculptCalls);
    }

    @Test
    void staleCrossChunkPlacementRollsBackAndDoesNotRetry() {
        ExtractingInventory inventory = new ExtractingInventory(Map.of(
                BodySlot.RIGHT_HAND, new ItemStack(STONE_UNIT, 2)));
        RecordingMutations mutations = new RecordingMutations();
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;

        SurvivalDetailEditResult result = transaction(inventory, mutations, outsideBody()).place(
                fullAirCandidate(-16, 7, -16, 12L), recoverDecision(),
                BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(1, mutations.sculptCalls);
        assertEquals(1, inventory.rollbackOrder.size());
        assertEquals(0, inventory.committedCount);
        assertFalse(result.feedbackEligible());
    }

    @Test
    void appliedMutationNotificationFailureCommitsExtractionWithoutRetryOrRollback() {
        ExtractingInventory inventory = new ExtractingInventory(Map.of(
                BodySlot.RIGHT_HAND, new ItemStack(STONE_UNIT, 1)));
        RecordingMutations mutations = new RecordingMutations();
        mutations.appliedNotificationFailure = true;

        SurvivalDetailEditResult result = transaction(inventory, mutations, outsideBody()).place(
                fullAirCandidate(16, 7, 15, 8L), recoverDecision(),
                BodySlot.RIGHT_HAND, context());

        assertEquals(
                SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.sculptCalls);
        assertEquals(1, inventory.committedCount);
        assertTrue(inventory.rollbackOrder.isEmpty());
        assertTrue(result.notificationFailure().isPresent());
        assertTrue(result.materialConserved());
    }

    private static SurvivalDetailEditTransaction transaction(
            ExtractingInventory inventory, RecordingMutations mutations, PhysicsBody body) {
        mutations.inventory = inventory;
        return new SurvivalDetailEditTransaction(
                mutations,
                (x, y, z) -> { throw new AssertionError("placement uses candidate observation"); },
                new BodyInventoryReservationPlanner(inventory),
                inventory,
                OWNER,
                new DetailPlacementCollisionValidator(),
                body);
    }

    private static DetailActionDecision recoverDecision() {
        return DetailActionDecision.allowed(DetailRecoveryKind.DETAIL_UNIT, STONE_UNIT);
    }

    private static GaiaInteractionContext context() {
        return new GaiaInteractionContext(
                OWNER, BodySlot.RIGHT_HAND, InteractionAction.SECONDARY, 20L, 30L);
    }

    private static DetailPrecisionTarget source() {
        return new DetailPrecisionTarget(
                15, 7, 15, new LocalSubVoxelPosition(3, 2, 1), BlockFace.EAST,
                DIRT, 5L,
                new DetailRaycastTarget(VoxelScale.DETAIL_4,
                        new LocalSubVoxelPosition(3, 2, 1)));
    }

    private static DetailPlacementCandidate fullAirCandidate(int x, int y, int z, long revision) {
        return new DetailPlacementCandidate(
                source(), x, y, z, LOCAL, STONE,
                available(x, y, z, revision, new FullCellState((byte) 0)),
                DetailPlacementCandidate.Status.VALID_FULL_AIR);
    }

    private static ParentCellObservationResult available(
            int x, int y, int z, long revision, ParentCellState state) {
        ChunkKey key = ChunkKey.fromWorld(x, z);
        return ParentCellObservationResult.available(new ParentCellObservation(
                key, ChunkKey.localCoordinate(x), y, ChunkKey.localCoordinate(z), revision, state));
    }

    private static DetailCellState detail(LocalSubVoxelPosition position, byte id) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[position.index()] = id;
        return new DetailCellState(1L << position.index(), ids);
    }

    private static PhysicsBody outsideBody() { return bodyAt(new Vector3f(100, 100, 100)); }

    private static PhysicsBody bodyAt(Vector3f position) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(position);
        return body;
    }

    private static final class RecordingMutations implements DetailMutationService {
        private int sculptCalls;
        private DetailMutationResult.Status nextStatus = DetailMutationResult.Status.APPLIED;
        private ExtractingInventory inventory;
        private boolean inventoryWasReserved;
        private boolean appliedNotificationFailure;

        @Override
        public DetailMutationResult sculptParentSubVoxel(SculptParentSubVoxelRequest request) {
            sculptCalls++;
            inventoryWasReserved = inventory.outstandingReservations > 0;
            boolean applied = nextStatus == DetailMutationResult.Status.APPLIED;
            DetailMutationResult result = new DetailMutationResult(
                    request.context(), nextStatus, Optional.of(request.expectedState()),
                    applied ? Optional.of(detail(request.position(), (byte) 1)) : Optional.empty(),
                    request.expectedChunkRevision(),
                    applied ? request.expectedChunkRevision() + 1 : request.expectedChunkRevision(),
                    applied ? List.of(new DirtyChunkRevision(
                            ChunkKey.fromWorld(request.x(), request.z()),
                            request.expectedChunkRevision() + 1)) : List.of());
            if (appliedNotificationFailure) {
                throw new DetailMutationEventDispatchException(
                        "detail notification failed", new RuntimeException("listener"), result);
            }
            return result;
        }

        @Override public DetailMutationResult convertFullToDetail(FullToDetailRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult setSubVoxel(DetailMutationRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult removeDetailParent(RemoveDetailParentRequest request) { throw new AssertionError(); }
        @Override public DetailMutationResult compactDetailToFull(DetailToFullRequest request) { throw new AssertionError(); }
    }

    private static final class ExtractingInventory implements InventoryService {
        private final EnumMap<BodySlot, ItemStack> slots = new EnumMap<>(BodySlot.class);
        private final List<BodySlot> reserveOrder = new ArrayList<>();
        private final List<InventoryReservationId> rollbackOrder = new ArrayList<>();
        private final Map<InventoryReservationId, InventoryReservation> held = new HashMap<>();
        private Optional<InventoryReservationRequest> lastRequest = Optional.empty();
        private long nextId;
        private int outstandingReservations;
        private int committedCount;

        private ExtractingInventory(Map<BodySlot, ItemStack> slots) { this.slots.putAll(slots); }
        @Override public Optional<InventoryView> snapshot(EntityRef owner) { return Optional.empty(); }
        @Override public InventoryChangeResult replaceSlot(InventoryChangeRequest request) { throw new UnsupportedOperationException(); }

        @Override
        public InventoryReserveResult reserve(InventoryReservationRequest request) {
            reserveOrder.add(request.slot());
            lastRequest = Optional.of(request);
            ItemStack available = slots.get(request.slot());
            int accepted = request.operation() == InventoryReservationOperation.EXTRACT
                    && available != null && available.itemId().equals(request.requested().itemId())
                    ? Math.min(available.count(), request.requested().count()) : 0;
            InventoryView view = new TestInventoryView(request.owner(), 0, Map.of());
            if (accepted == 0) {
                return new InventoryReserveResult(request, InventoryReserveResult.Status.REJECTED,
                        Optional.empty(), Optional.of(request.requested()), Optional.of(view));
            }
            InventoryReservation reservation = new InventoryReservation(
                    new InventoryReservationId(nextId++), request,
                    new ItemStack(request.requested().itemId(), accepted));
            held.put(reservation.id(), reservation);
            outstandingReservations++;
            Optional<ItemStack> remainder = accepted == request.requested().count()
                    ? Optional.empty()
                    : Optional.of(new ItemStack(request.requested().itemId(),
                            request.requested().count() - accepted));
            return new InventoryReserveResult(request,
                    remainder.isEmpty() ? InventoryReserveResult.Status.RESERVED
                            : InventoryReserveResult.Status.PARTIALLY_RESERVED,
                    Optional.of(reservation), remainder, Optional.of(view));
        }

        @Override
        public InventoryReservationResult commit(InventoryReservationId id) {
            InventoryReservation reservation = held.remove(id);
            outstandingReservations--;
            committedCount += reservation.reserved().count();
            return new InventoryReservationResult(id, InventoryReservationResult.Status.COMMITTED,
                    Optional.of(new TestInventoryView(OWNER, committedCount, Map.of())));
        }

        @Override
        public InventoryReservationResult rollback(InventoryReservationId id) {
            rollbackOrder.add(id);
            if (held.remove(id) != null) { outstandingReservations--; }
            return new InventoryReservationResult(id, InventoryReservationResult.Status.ROLLED_BACK,
                    Optional.of(new TestInventoryView(OWNER, 0, Map.of())));
        }
    }
}
