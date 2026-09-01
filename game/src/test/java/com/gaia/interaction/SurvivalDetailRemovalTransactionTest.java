package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.inventory.BodyInventoryReservationPlanner;
import com.overlord.assets.ResourceLocation;
import com.overlord.event.Event;
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
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.testing.TestInventoryView;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SurvivalDetailRemovalTransactionTest {
    private static final EntityRef OWNER = new EntityRef(17);
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");
    private static final LocalSubVoxelPosition LOCAL =
            new LocalSubVoxelPosition(2, 1, 3);

    @Test
    void recoverableRemovalReservesBeforeOneCasAndConservesOneUnit() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.LEFT_HAND, 1));
        RecordingMutations mutations = new RecordingMutations();
        DetailCellState state = detail(LOCAL, (byte) 1);
        SurvivalDetailEditTransaction transaction = transaction(inventory, mutations, state, 8L);

        SurvivalDetailEditResult result = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.APPLIED, result.status());
        assertEquals(-1, result.occupiedDelta());
        assertEquals(1, result.inventoryDelta());
        assertTrue(result.materialConserved());
        assertEquals(List.of(BodySlot.RIGHT_HAND, BodySlot.LEFT_HAND), inventory.reserveOrder);
        assertEquals(1, mutations.sculptCalls);
        assertEquals(1, inventory.commitOrder.size());
        assertTrue(mutations.inventoryWasReserved);
    }

    @Test
    void fullInventoryRejectsBeforeMutationAndRollsBackPartialReservation() {
        RecordingInventory inventory = new RecordingInventory(Map.of());
        RecordingMutations mutations = new RecordingMutations();
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 8L);

        SurvivalDetailEditResult result = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.INVENTORY_FULL, result.status());
        assertEquals(0, mutations.sculptCalls);
        assertEquals(0, result.occupiedDelta());
        assertEquals(0, result.inventoryDelta());
    }

    @Test
    void staleMutationRollsBackReservationWithoutFeedbackOrConservationDelta() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 1));
        RecordingMutations mutations = new RecordingMutations();
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 9L);

        SurvivalDetailEditResult result = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(1, inventory.rollbackOrder.size());
        assertEquals(0, inventory.commitOrder.size());
        assertFalse(result.feedbackEligible());
        assertTrue(result.materialConserved());
    }

    @Test
    void mutationExceptionRollsBackAndPreservesTheOriginalFailure() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 1));
        RecordingMutations mutations = new RecordingMutations();
        mutations.failure = new IllegalStateException("mutation failed");
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 8L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> transaction.removeRecoverable(
                        target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context()));

        assertEquals("mutation failed", failure.getMessage());
        assertEquals(1, inventory.rollbackOrder.size());
    }

    @Test
    void appliedInventoryNotificationFailureIsReportedWithoutRetry() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 1));
        inventory.commitNotificationFailure = true;
        RecordingMutations mutations = new RecordingMutations();
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 8L);

        SurvivalDetailEditResult result = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(
                SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, inventory.commitOrder.size());
        assertEquals(1, mutations.sculptCalls);
        assertTrue(result.notificationFailure().isPresent());
        assertTrue(result.materialConserved());
    }

    @Test
    void appliedMutationNotificationFailureCommitsRecoveryWithoutRetryOrRollback() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 1));
        RecordingMutations mutations = new RecordingMutations();
        mutations.appliedNotificationFailure = true;
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 8L);

        SurvivalDetailEditResult result = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(
                SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.sculptCalls);
        assertEquals(1, inventory.commitOrder.size());
        assertEquals(0, inventory.rollbackOrder.size());
        assertTrue(result.notificationFailure().isPresent());
        assertTrue(result.materialConserved());
    }

    @Test
    void unsupportedAndDestructiveDecisionsNeverReserveOrMutate() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 1));
        RecordingMutations mutations = new RecordingMutations();
        SurvivalDetailEditTransaction transaction = transaction(
                inventory, mutations, detail(LOCAL, (byte) 1), 8L);

        SurvivalDetailEditResult rejected = transaction.removeRecoverable(
                target(8L), DetailActionDecision.rejected("unsupported"),
                BodySlot.RIGHT_HAND, context());
        SurvivalDetailEditResult destructive = transaction.removeRecoverable(
                target(8L), DetailActionDecision.allowedNone(),
                BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.ACTION_REJECTED, rejected.status());
        assertEquals(SurvivalDetailEditResult.Status.ACTION_REJECTED, destructive.status());
        assertTrue(inventory.reserveOrder.isEmpty());
        assertEquals(0, mutations.sculptCalls);
    }

    @Test
    void repeatedStaleInputCannotDuplicateRecovery() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.RIGHT_HAND, 2));
        RecordingMutations mutations = new RecordingMutations();
        DetailCellState state = detail(LOCAL, (byte) 1);
        SurvivalDetailEditTransaction transaction = transaction(inventory, mutations, state, 8L);

        SurvivalDetailEditResult first = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());
        mutations.nextStatus = DetailMutationResult.Status.STALE_CHUNK_REVISION;
        SurvivalDetailEditResult second = transaction.removeRecoverable(
                target(8L), recoverDecision(), BodySlot.RIGHT_HAND, context());

        assertEquals(SurvivalDetailEditResult.Status.APPLIED, first.status());
        assertEquals(SurvivalDetailEditResult.Status.MUTATION_REJECTED, second.status());
        assertEquals(1, inventory.committedCount);
    }

    private static SurvivalDetailEditTransaction transaction(
            RecordingInventory inventory,
            RecordingMutations mutations,
            ParentCellState state,
            long observedRevision) {
        mutations.inventory = inventory;
        return new SurvivalDetailEditTransaction(
                mutations,
                (x, y, z) -> available(x, y, z, observedRevision, state),
                new BodyInventoryReservationPlanner(inventory),
                inventory,
                OWNER);
    }

    private static DetailActionDecision recoverDecision() {
        return DetailActionDecision.allowed(DetailRecoveryKind.DETAIL_UNIT, STONE_UNIT);
    }

    private static GaiaInteractionContext context() {
        return new GaiaInteractionContext(
                OWNER, BodySlot.RIGHT_HAND, InteractionAction.PRIMARY, 12L, 34L);
    }

    private static DetailPrecisionTarget target(long revision) {
        return new DetailPrecisionTarget(
                4, 5, 6, LOCAL, BlockFace.UP, STONE, revision,
                new DetailRaycastTarget(VoxelScale.DETAIL_4, LOCAL));
    }

    private static ParentCellObservationResult available(
            int x, int y, int z, long revision, ParentCellState state) {
        ChunkKey key = ChunkKey.fromWorld(x, z);
        return ParentCellObservationResult.available(new ParentCellObservation(
                key,
                ChunkKey.localCoordinate(x),
                y,
                ChunkKey.localCoordinate(z),
                revision,
                state));
    }

    private static DetailCellState detail(LocalSubVoxelPosition position, byte id) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[position.index()] = id;
        return new DetailCellState(1L << position.index(), ids);
    }

    private static final class RecordingMutations implements DetailMutationService {
        private int sculptCalls;
        private DetailMutationResult.Status nextStatus = DetailMutationResult.Status.APPLIED;
        private RuntimeException failure;
        private boolean appliedNotificationFailure;
        private RecordingInventory inventory;
        private boolean inventoryWasReserved;

        @Override
        public DetailMutationResult sculptParentSubVoxel(SculptParentSubVoxelRequest request) {
            sculptCalls++;
            inventoryWasReserved = inventory.outstandingReservations > 0;
            if (failure != null) {
                throw failure;
            }
            boolean applied = nextStatus == DetailMutationResult.Status.APPLIED;
            DetailMutationResult result = new DetailMutationResult(
                    request.context(), nextStatus, Optional.of(request.expectedState()),
                    applied ? Optional.of(new com.overlord.voxel.FullCellState((byte) 0))
                            : Optional.empty(),
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

    private static final class RecordingInventory implements InventoryService {
        private final EnumMap<BodySlot, Integer> capacity = new EnumMap<>(BodySlot.class);
        private final List<BodySlot> reserveOrder = new ArrayList<>();
        private final List<InventoryReservationId> commitOrder = new ArrayList<>();
        private final List<InventoryReservationId> rollbackOrder = new ArrayList<>();
        private final Map<InventoryReservationId, InventoryReservation> held = new java.util.HashMap<>();
        private long nextId;
        private int outstandingReservations;
        private int committedCount;
        private boolean commitNotificationFailure;

        private RecordingInventory(Map<BodySlot, Integer> capacity) {
            this.capacity.putAll(capacity);
        }

        @Override public Optional<InventoryView> snapshot(EntityRef owner) { return Optional.empty(); }
        @Override public InventoryChangeResult replaceSlot(InventoryChangeRequest request) { throw new UnsupportedOperationException(); }

        @Override
        public InventoryReserveResult reserve(InventoryReservationRequest request) {
            reserveOrder.add(request.slot());
            int accepted = Math.min(capacity.getOrDefault(request.slot(), 0), request.requested().count());
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
            capacity.put(request.slot(), capacity.get(request.slot()) - accepted);
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
            commitOrder.add(id);
            InventoryReservation reservation = held.remove(id);
            outstandingReservations--;
            committedCount += reservation.reserved().count();
            if (commitNotificationFailure) {
                throw new InventoryEventDispatchException(
                        "notification failed", new RuntimeException("listener"),
                        new TestEvent(), true);
            }
            return new InventoryReservationResult(id, InventoryReservationResult.Status.COMMITTED,
                    Optional.of(new TestInventoryView(OWNER, committedCount, Map.of())));
        }

        @Override
        public InventoryReservationResult rollback(InventoryReservationId id) {
            rollbackOrder.add(id);
            InventoryReservation reservation = held.remove(id);
            if (reservation != null) {
                outstandingReservations--;
            }
            return new InventoryReservationResult(id, InventoryReservationResult.Status.ROLLED_BACK,
                    Optional.of(new TestInventoryView(OWNER, 0, Map.of())));
        }
    }

    private static final class TestEvent extends Event {
        @Override
        public String getEventType() {
            return "test";
        }
    }
}
