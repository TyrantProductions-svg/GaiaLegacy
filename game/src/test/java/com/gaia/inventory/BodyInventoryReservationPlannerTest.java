package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.testing.TestInventoryView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BodyInventoryReservationPlannerTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void preferredSlotIsFirstThenRemainingBodySlotsWithExactRemainder() {
        RecordingInventory inventory = new RecordingInventory(Map.of(
                BodySlot.RIGHT_HAND, 2,
                BodySlot.LEFT_HAND, 1,
                BodySlot.MOUTH, 0));

        InventoryReservationBatch batch = new BodyInventoryReservationPlanner(inventory)
                .reserveInsertion(OWNER, BodySlot.RIGHT_HAND, new ItemStack(DIRT, 5));

        assertEquals(List.of(BodySlot.RIGHT_HAND, BodySlot.LEFT_HAND, BodySlot.MOUTH),
                inventory.reserveOrder);
        assertEquals(2, batch.reservations().size());
        assertEquals(3, batch.acceptedCount());
        assertEquals(new ItemStack(DIRT, 2), batch.remainder().orElseThrow());
    }

    @Test
    void completeReservationStopsWithoutCallingLaterSlots() {
        RecordingInventory inventory = new RecordingInventory(Map.of(BodySlot.MOUTH, 4));

        InventoryReservationBatch batch = new BodyInventoryReservationPlanner(inventory)
                .reserveInsertion(OWNER, BodySlot.MOUTH, new ItemStack(DIRT, 4));

        assertEquals(List.of(BodySlot.MOUTH), inventory.reserveOrder);
        assertEquals(4, batch.acceptedCount());
        assertEquals(Optional.empty(), batch.remainder());
    }

    @Test
    void exceptionalAcquisitionRollsBackInReverseOrderAndPreservesPrimaryFailure() {
        RecordingInventory inventory = new RecordingInventory(Map.of(
                BodySlot.LEFT_HAND, 1,
                BodySlot.RIGHT_HAND, 1));
        inventory.failureSlot = BodySlot.MOUTH;
        RuntimeException primary = assertThrows(RuntimeException.class,
                () -> new BodyInventoryReservationPlanner(inventory).reserveInsertion(
                        OWNER, BodySlot.LEFT_HAND, new ItemStack(DIRT, 3)));

        assertEquals("reserve failed", primary.getMessage());
        assertEquals(List.of(new InventoryReservationId(1), new InventoryReservationId(0)),
                inventory.rollbackOrder);
    }

    private static final class RecordingInventory implements InventoryService {
        private final EnumMap<BodySlot, Integer> limits = new EnumMap<>(BodySlot.class);
        private final List<BodySlot> reserveOrder = new ArrayList<>();
        private final List<InventoryReservationId> rollbackOrder = new ArrayList<>();
        private long nextId;
        private BodySlot failureSlot;

        private RecordingInventory(Map<BodySlot, Integer> limits) {
            this.limits.putAll(limits);
        }

        @Override
        public Optional<InventoryView> snapshot(EntityRef owner) {
            return Optional.empty();
        }

        @Override
        public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InventoryReserveResult reserve(InventoryReservationRequest request) {
            reserveOrder.add(request.slot());
            if (request.slot() == failureSlot) {
                throw new RuntimeException("reserve failed");
            }
            int accepted = Math.min(limits.getOrDefault(request.slot(), 0),
                    request.requested().count());
            InventoryView view = new TestInventoryView(request.owner(), 0, Map.of());
            if (accepted == 0) {
                return new InventoryReserveResult(request, InventoryReserveResult.Status.REJECTED,
                        Optional.empty(), Optional.of(request.requested()), Optional.of(view));
            }
            InventoryReservation reservation = new InventoryReservation(
                    new InventoryReservationId(nextId++), request,
                    new ItemStack(request.requested().itemId(), accepted));
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
        public InventoryReservationResult commit(InventoryReservationId reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InventoryReservationResult rollback(InventoryReservationId reservationId) {
            rollbackOrder.add(reservationId);
            return new InventoryReservationResult(reservationId,
                    InventoryReservationResult.Status.ROLLED_BACK,
                    Optional.of(new TestInventoryView(OWNER, 0, Map.of())));
        }
    }
}
