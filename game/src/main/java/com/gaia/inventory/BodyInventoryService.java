package com.gaia.inventory;

import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.event.Event;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ActiveBodySlotChanged;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryChanged;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The sole mutable entry point for a player's three physical body slots.
 * Item rules are resolved from the data-driven {@link ItemFormDefinition}
 * source supplied at construction time.
 */
public final class BodyInventoryService implements InventoryService {
    private final BodyInventory inventory;
    private final ItemFormLookup itemForms;
    private final MainThreadGuard mainThreadGuard;
    private final Consumer<Event> eventSink;
    private final Map<InventoryReservationId, ReservationState> reservations =
            new HashMap<>();
    private final EnumMap<BodySlot, InventoryReservationId> locks =
            new EnumMap<>(BodySlot.class);

    private long nextReservationId;
    private boolean reservationIdsExhausted;

    public BodyInventoryService(
            EntityRef owner,
            BlockRegistry blockRegistry,
            MainThreadGuard mainThreadGuard,
            Consumer<Event> eventSink) {
        this(owner,
                Objects.requireNonNull(blockRegistry, "blockRegistry")::itemForm,
                mainThreadGuard,
                eventSink);
    }

    public BodyInventoryService(
            EntityRef owner, ItemFormLookup itemForms, Consumer<Event> eventSink) {
        this(owner, itemForms, MainThreadGuard.captureCurrentThread(), eventSink);
    }

    public BodyInventoryService(
            EntityRef owner,
            ItemFormLookup itemForms,
            MainThreadGuard mainThreadGuard,
            Consumer<Event> eventSink) {
        inventory = new BodyInventory(Objects.requireNonNull(owner, "owner"));
        this.itemForms = Objects.requireNonNull(itemForms, "itemForms");
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    @Override
    public Optional<InventoryView> snapshot(EntityRef owner) {
        assertMainThread("body inventory snapshot");
        Objects.requireNonNull(owner, "owner");
        if (!owns(owner)) {
            return Optional.empty();
        }
        return Optional.of(inventory.snapshot());
    }

    public Optional<BodyInventoryViewModel> viewModel(EntityRef owner) {
        assertMainThread("body inventory view model");
        Objects.requireNonNull(owner, "owner");
        if (!owns(owner)) {
            return Optional.empty();
        }
        return Optional.of(inventory.viewModel());
    }

    public int totalCount(EntityRef owner, com.overlord.assets.ResourceLocation itemId) {
        assertMainThread("body inventory total count");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(itemId, "itemId");
        if (!owns(owner)) {
            return 0;
        }
        int total = 0;
        ItemStack left = inventory.directStack(BodySlot.LEFT_HAND);
        if (left != null && left.itemId().equals(itemId)) {
            total += left.count();
        }
        if (!inventory.hasTwoHandedHandsOccupied()) {
            ItemStack right = inventory.directStack(BodySlot.RIGHT_HAND);
            if (right != null && right.itemId().equals(itemId)) {
                total += right.count();
            }
        }
        ItemStack mouth = inventory.directStack(BodySlot.MOUTH);
        if (mouth != null && mouth.itemId().equals(itemId)) {
            total += mouth.count();
        }
        return total;
    }

    public InventoryInsertResult insert(EntityRef owner, ItemStack requested) {
        assertMainThread("body inventory insert");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requested, "requested");
        if (!owns(owner)) {
            return new InventoryInsertResult(
                    InventoryInsertResult.Status.UNKNOWN_OWNER,
                    Optional.of(requested), Optional.empty());
        }
        ItemFormDefinition form = itemForm(requested).orElse(null);
        if (form == null) {
            return insertRejected(requested);
        }
        int remaining = form.twoHanded()
                ? insertTwoHanded(form, requested)
                : insertSingleHandedOrMouth(form, requested);
        int inserted = requested.count() - remaining;
        if (inserted == 0) {
            return insertRejected(requested);
        }
        contentsChanged();
        Optional<ItemStack> remainder = remaining == 0
                ? Optional.empty()
                : Optional.of(new ItemStack(requested.itemId(), remaining));
        return new InventoryInsertResult(
                remaining == 0
                        ? InventoryInsertResult.Status.INSERTED
                        : InventoryInsertResult.Status.PARTIALLY_INSERTED,
                remainder,
                viewModel(owner));
    }

    public InventoryExtractResult extract(
            EntityRef owner, BodySlot slot, int requestedCount) {
        assertMainThread("body inventory extract");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(slot, "slot");
        if (!owns(owner)) {
            return new InventoryExtractResult(
                    InventoryExtractResult.Status.UNKNOWN_OWNER,
                    Optional.empty(), Optional.empty());
        }
        if (requestedCount <= 0) {
            return new InventoryExtractResult(
                    InventoryExtractResult.Status.INVALID_COUNT,
                    Optional.empty(), viewModel(owner));
        }
        BodySlot anchor = inventory.anchor(slot);
        if (locked(anchor)) {
            return new InventoryExtractResult(
                    InventoryExtractResult.Status.RESERVED,
                    Optional.empty(), viewModel(owner));
        }
        ItemStack current = inventory.stack(slot);
        if (current == null) {
            return new InventoryExtractResult(
                    InventoryExtractResult.Status.EMPTY_SLOT,
                    Optional.empty(), viewModel(owner));
        }
        int extractedCount = Math.min(requestedCount, current.count());
        ItemStack extracted = new ItemStack(current.itemId(), extractedCount);
        setAnchoredStack(anchor, current.count() == extractedCount
                ? null
                : new ItemStack(current.itemId(), current.count() - extractedCount));
        contentsChanged();
        return new InventoryExtractResult(
                extractedCount == requestedCount
                        ? InventoryExtractResult.Status.EXTRACTED
                        : InventoryExtractResult.Status.PARTIALLY_EXTRACTED,
                Optional.of(extracted), viewModel(owner));
    }

    public InventoryOperationResult swap(
            EntityRef owner, BodySlot first, BodySlot second) {
        assertMainThread("body inventory swap");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!owns(owner)) {
            return operation(InventoryOperationResult.Status.UNKNOWN_OWNER);
        }
        if (first == second) {
            return operation(InventoryOperationResult.Status.NO_CHANGE);
        }
        if (inventory.hasTwoHandedHandsOccupied()
                && (BodyInventory.isHand(first) || BodyInventory.isHand(second))) {
            return operation(InventoryOperationResult.Status.REJECTED);
        }
        if (locked(first) || locked(second)) {
            return operation(InventoryOperationResult.Status.RESERVED);
        }
        ItemStack firstStack = inventory.directStack(first);
        ItemStack secondStack = inventory.directStack(second);
        if (firstStack == null && secondStack == null) {
            return operation(InventoryOperationResult.Status.NO_CHANGE);
        }
        if (!accepts(second, firstStack) || !accepts(first, secondStack)) {
            return operation(InventoryOperationResult.Status.REJECTED);
        }
        inventory.setSingle(first, secondStack);
        inventory.setSingle(second, firstStack);
        contentsChanged();
        return operation(InventoryOperationResult.Status.APPLIED);
    }

    public InventorySplitResult split(
            EntityRef owner,
            BodySlot source,
            BodySlot destination,
            int count) {
        assertMainThread("body inventory split");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (!owns(owner)) {
            return new InventorySplitResult(
                    InventorySplitResult.Status.UNKNOWN_OWNER,
                    Optional.empty(),
                    Optional.empty());
        }
        if (count <= 0) {
            return splitRejected(InventorySplitResult.Status.INVALID_COUNT);
        }
        if (source == destination) {
            return splitRejected(InventorySplitResult.Status.SAME_SLOT);
        }
        if (locked(source) || locked(destination)) {
            return splitRejected(InventorySplitResult.Status.RESERVED);
        }
        if (inventory.hasTwoHandedHandsOccupied()
                && (BodyInventory.isHand(source) || BodyInventory.isHand(destination))) {
            return splitRejected(InventorySplitResult.Status.REJECTED);
        }

        ItemStack sourceStack = inventory.directStack(source);
        if (sourceStack == null) {
            return splitRejected(InventorySplitResult.Status.EMPTY_SOURCE);
        }
        if (count > sourceStack.count()) {
            return splitRejected(InventorySplitResult.Status.SOURCE_TOO_SMALL);
        }
        if (!accepts(destination, sourceStack)) {
            return splitRejected(InventorySplitResult.Status.REJECTED);
        }
        ItemStack destinationStack = inventory.directStack(destination);
        if (destinationStack != null
                && !destinationStack.itemId().equals(sourceStack.itemId())) {
            return splitRejected(InventorySplitResult.Status.REJECTED);
        }
        ItemFormDefinition form = itemForm(sourceStack).orElse(null);
        if (form == null) {
            return splitRejected(InventorySplitResult.Status.REJECTED);
        }
        int destinationCount = destinationStack == null ? 0 : destinationStack.count();
        if (count > form.maxStackSize() - destinationCount) {
            return splitRejected(InventorySplitResult.Status.DESTINATION_FULL);
        }

        ItemStack moved = new ItemStack(sourceStack.itemId(), count);
        int sourceRemainder = sourceStack.count() - count;
        inventory.setSingle(source, sourceRemainder == 0
                ? null
                : new ItemStack(sourceStack.itemId(), sourceRemainder));
        inventory.setSingle(destination, new ItemStack(
                sourceStack.itemId(), destinationCount + count));
        contentsChanged();
        return new InventorySplitResult(
                InventorySplitResult.Status.SPLIT,
                Optional.of(moved),
                viewModel(owner));
    }

    public ActiveSlotChangeResult selectActiveSlot(EntityRef owner, BodySlot slot) {
        assertMainThread("body inventory active slot selection");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(slot, "slot");
        if (!owns(owner)) {
            return new ActiveSlotChangeResult(
                    ActiveSlotChangeResult.Status.UNKNOWN_OWNER, Optional.empty());
        }
        BodySlot previous = inventory.activeSlot();
        if (previous == slot) {
            return new ActiveSlotChangeResult(
                    ActiveSlotChangeResult.Status.UNCHANGED, viewModel(owner));
        }
        inventory.setActiveSlot(slot);
        publishCommitted(new ActiveBodySlotChanged(owner, previous, slot));
        return new ActiveSlotChangeResult(
                ActiveSlotChangeResult.Status.SELECTED, viewModel(owner));
    }

    public ActiveSlotChangeResult cycleActiveSlot(EntityRef owner, int direction) {
        assertMainThread("body inventory active slot cycle");
        Objects.requireNonNull(owner, "owner");
        if (!owns(owner)) {
            return new ActiveSlotChangeResult(
                    ActiveSlotChangeResult.Status.UNKNOWN_OWNER, Optional.empty());
        }
        if (direction == 0) {
            return new ActiveSlotChangeResult(
                    ActiveSlotChangeResult.Status.UNCHANGED, viewModel(owner));
        }
        BodySlot[] slots = BodySlot.values();
        int previousIndex = inventory.activeSlot().ordinal();
        int nextIndex = Math.floorMod(previousIndex + Integer.signum(direction), slots.length);
        return selectActiveSlot(owner, slots[nextIndex]);
    }

    @Override
    public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
        assertMainThread("body inventory replacement");
        Objects.requireNonNull(request, "request");
        if (!owns(request.owner())) {
            return new InventoryChangeResult(
                    InventoryChangeResult.Status.UNKNOWN_OWNER, Optional.empty());
        }
        if (request.expectedRevision() != inventory.revision()) {
            return new InventoryChangeResult(
                    InventoryChangeResult.Status.CONFLICT, Optional.of(inventory.snapshot()));
        }
        if (locked(request.slot())) {
            return new InventoryChangeResult(
                    InventoryChangeResult.Status.CONFLICT, Optional.of(inventory.snapshot()));
        }

        ItemStack replacement = request.replacement().orElse(null);
        if (replacement == null) {
            boolean changed = inventory.clearAt(request.slot());
            if (changed) {
                contentsChanged();
            }
            return new InventoryChangeResult(
                    InventoryChangeResult.Status.APPLIED, Optional.of(inventory.snapshot()));
        }
        ItemFormDefinition form = itemForm(replacement).orElse(null);
        if (form == null || replacement.count() > form.maxStackSize()
                || !accepts(request.slot(), replacement)) {
            return new InventoryChangeResult(
                    InventoryChangeResult.Status.INVALID_STACK, Optional.of(inventory.snapshot()));
        }
        if (form.twoHanded()) {
            if (!BodyInventory.isHand(request.slot())
                    || locked(BodySlot.LEFT_HAND)
                    || locked(BodySlot.RIGHT_HAND)
                    || (!inventory.hasTwoHandedHandsOccupied()
                            && (inventory.directStack(BodySlot.LEFT_HAND) != null
                                    || inventory.directStack(BodySlot.RIGHT_HAND) != null))) {
                return new InventoryChangeResult(
                        InventoryChangeResult.Status.CONFLICT, Optional.of(inventory.snapshot()));
            }
            inventory.clearHands();
            inventory.setTwoHanded(replacement);
        } else {
            if (inventory.hasTwoHandedHandsOccupied() && BodyInventory.isHand(request.slot())) {
                inventory.clearHands();
            }
            inventory.setSingle(request.slot(), replacement);
        }
        contentsChanged();
        return new InventoryChangeResult(
                InventoryChangeResult.Status.APPLIED, Optional.of(inventory.snapshot()));
    }

    @Override
    public InventoryReserveResult reserve(InventoryReservationRequest request) {
        assertMainThread("body inventory reservation");
        Objects.requireNonNull(request, "request");
        if (!owns(request.owner())) {
            return failedReservation(request, InventoryReserveResult.Status.UNKNOWN_OWNER, Optional.empty());
        }
        ItemFormDefinition form = itemForm(request.requested()).orElse(null);
        if (form == null) {
            return failedReservation(
                    request, InventoryReserveResult.Status.INVALID_STACK, Optional.of(inventory.snapshot()));
        }
        return request.operation() == InventoryReservationOperation.EXTRACT
                ? reserveExtract(request)
                : reserveInsert(request, form);
    }

    @Override
    public InventoryReservationResult commit(InventoryReservationId reservationId) {
        assertMainThread("body inventory reservation commit");
        Objects.requireNonNull(reservationId, "reservationId");
        ReservationState state = reservations.get(reservationId);
        if (state == null) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty());
        }
        if (state.terminal == Terminal.COMMITTED) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.ALREADY_COMMITTED,
                    Optional.of(inventory.snapshot()));
        }
        if (state.terminal == Terminal.ROLLED_BACK) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.TERMINAL_CONFLICT,
                    Optional.of(inventory.snapshot()));
        }

        applyReservedMutation(state);
        state.terminal = Terminal.COMMITTED;
        unlock(state);
        contentsChanged();
        return reservationResult(
                reservationId,
                InventoryReservationResult.Status.COMMITTED,
                Optional.of(inventory.snapshot()));
    }

    @Override
    public InventoryReservationResult rollback(InventoryReservationId reservationId) {
        assertMainThread("body inventory reservation rollback");
        Objects.requireNonNull(reservationId, "reservationId");
        ReservationState state = reservations.get(reservationId);
        if (state == null) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty());
        }
        if (state.terminal == Terminal.ROLLED_BACK) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.ALREADY_ROLLED_BACK,
                    Optional.of(inventory.snapshot()));
        }
        if (state.terminal == Terminal.COMMITTED) {
            return reservationResult(
                    reservationId,
                    InventoryReservationResult.Status.TERMINAL_CONFLICT,
                    Optional.of(inventory.snapshot()));
        }
        state.terminal = Terminal.ROLLED_BACK;
        unlock(state);
        return reservationResult(
                reservationId,
                InventoryReservationResult.Status.ROLLED_BACK,
                Optional.of(inventory.snapshot()));
    }

    private InventoryReserveResult reserveExtract(InventoryReservationRequest request) {
        BodySlot anchor = inventory.anchor(request.slot());
        ItemStack current = inventory.stack(request.slot());
        if (current == null || !current.itemId().equals(request.requested().itemId()) || locked(anchor)) {
            return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                    Optional.of(inventory.snapshot()));
        }
        int protectedCount = Math.min(request.requested().count(), current.count());
        boolean affectsBothHands = inventory.hasTwoHandedHandsOccupied()
                && BodyInventory.isHand(anchor);
        return createReservation(request, anchor, affectsBothHands, protectedCount);
    }

    private InventoryReserveResult reserveInsert(
            InventoryReservationRequest request, ItemFormDefinition form) {
        if (!accepts(request.slot(), request.requested())) {
            return failedReservation(request, InventoryReserveResult.Status.INVALID_STACK,
                    Optional.of(inventory.snapshot()));
        }
        if (form.twoHanded()) {
            if (!BodyInventory.isHand(request.slot())
                    || locked(BodySlot.LEFT_HAND)
                    || locked(BodySlot.RIGHT_HAND)) {
                return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                        Optional.of(inventory.snapshot()));
            }
            ItemStack current = inventory.directStack(BodySlot.LEFT_HAND);
            if (inventory.hasTwoHandedHandsOccupied()) {
                if (!current.itemId().equals(request.requested().itemId())) {
                    return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                            Optional.of(inventory.snapshot()));
                }
            } else if (current != null || inventory.directStack(BodySlot.RIGHT_HAND) != null) {
                return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                        Optional.of(inventory.snapshot()));
            }
            int currentCount = current == null ? 0 : current.count();
            return createReservation(request, BodySlot.LEFT_HAND, true,
                    Math.min(request.requested().count(), form.maxStackSize() - currentCount));
        }
        BodySlot anchor = inventory.anchor(request.slot());
        if (locked(anchor) || (inventory.hasTwoHandedHandsOccupied() && BodyInventory.isHand(anchor))) {
            return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                    Optional.of(inventory.snapshot()));
        }
        ItemStack current = inventory.stack(anchor);
        if (current != null && !current.itemId().equals(request.requested().itemId())) {
            return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                    Optional.of(inventory.snapshot()));
        }
        int currentCount = current == null ? 0 : current.count();
        return createReservation(request, anchor, false,
                Math.min(request.requested().count(), form.maxStackSize() - currentCount));
    }

    private InventoryReserveResult createReservation(
            InventoryReservationRequest request,
            BodySlot anchor,
            boolean affectsBothHands,
            int protectedCount) {
        if (protectedCount <= 0) {
            return failedReservation(request, InventoryReserveResult.Status.REJECTED,
                    Optional.of(inventory.snapshot()));
        }
        InventoryReservation reservation = new InventoryReservation(
                nextReservationId(), request,
                new ItemStack(request.requested().itemId(), protectedCount));
        ReservationState state = new ReservationState(
                reservation,
                anchor,
                affectsBothHands,
                affectedSlots(anchor, affectsBothHands));
        reservations.put(reservation.id(), state);
        lock(state);
        Optional<ItemStack> remainder = protectedCount == request.requested().count()
                ? Optional.empty()
                : Optional.of(new ItemStack(
                        request.requested().itemId(),
                        request.requested().count() - protectedCount));
        return new InventoryReserveResult(
                request,
                remainder.isEmpty()
                        ? InventoryReserveResult.Status.RESERVED
                        : InventoryReserveResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation), remainder, Optional.of(inventory.snapshot()));
    }

    private void applyReservedMutation(ReservationState state) {
        InventoryReservation reservation = state.reservation;
        ItemStack reserved = reservation.reserved();
        ItemStack current = inventory.stack(state.anchor);
        if (reservation.request().operation() == InventoryReservationOperation.EXTRACT) {
            if (current == null || !current.itemId().equals(reserved.itemId())
                    || current.count() < reserved.count()) {
                throw new IllegalStateException("inventory extraction reservation guarantee broken");
            }
            int remaining = current.count() - reserved.count();
            setAnchoredStack(state.anchor, remaining == 0
                    ? null : new ItemStack(current.itemId(), remaining));
            return;
        }

        ItemFormDefinition form = itemForm(reserved).orElseThrow(
                () -> new IllegalStateException("reserved item form disappeared"));
        int currentCount = current == null ? 0 : current.count();
        if (current != null && !current.itemId().equals(reserved.itemId())
                || currentCount + reserved.count() > form.maxStackSize()) {
            throw new IllegalStateException("inventory insertion reservation guarantee broken");
        }
        ItemStack committed = new ItemStack(
                reserved.itemId(), currentCount + reserved.count());
        if (state.affectsBothHands) {
            inventory.setTwoHanded(committed);
        } else {
            setAnchoredStack(state.anchor, committed);
        }
    }

    private int insertTwoHanded(ItemFormDefinition form, ItemStack requested) {
        if (locked(BodySlot.LEFT_HAND) || locked(BodySlot.RIGHT_HAND)) {
            return requested.count();
        }
        ItemStack current = inventory.directStack(BodySlot.LEFT_HAND);
        if (inventory.hasTwoHandedHandsOccupied()) {
            if (!current.itemId().equals(requested.itemId())) {
                return requested.count();
            }
        } else if (current != null || inventory.directStack(BodySlot.RIGHT_HAND) != null) {
            return requested.count();
        }
        int currentCount = current == null ? 0 : current.count();
        int inserted = Math.min(requested.count(), form.maxStackSize() - currentCount);
        if (inserted > 0) {
            inventory.setTwoHanded(new ItemStack(requested.itemId(), currentCount + inserted));
        }
        return requested.count() - inserted;
    }

    private int insertSingleHandedOrMouth(ItemFormDefinition form, ItemStack requested) {
        int remaining = requested.count();
        for (BodySlot slot : BodySlot.values()) {
            if (remaining == 0 || locked(slot)) {
                continue;
            }
            if (inventory.hasTwoHandedHandsOccupied() && BodyInventory.isHand(slot)) {
                continue;
            }
            ItemStack current = inventory.stack(slot);
            if (current != null && current.itemId().equals(requested.itemId())) {
                int inserted = Math.min(remaining, form.maxStackSize() - current.count());
                if (inserted > 0) {
                    inventory.setSingle(slot, new ItemStack(
                            requested.itemId(), current.count() + inserted));
                    remaining -= inserted;
                }
            }
        }
        for (BodySlot slot : BodySlot.values()) {
            if (remaining == 0 || locked(slot)
                    || !accepts(slot, requested)
                    || (inventory.hasTwoHandedHandsOccupied() && BodyInventory.isHand(slot))
                    || inventory.stack(slot) != null) {
                continue;
            }
            int inserted = Math.min(remaining, form.maxStackSize());
            inventory.setSingle(slot, new ItemStack(requested.itemId(), inserted));
            remaining -= inserted;
        }
        return remaining;
    }

    private void setAnchoredStack(BodySlot anchor, ItemStack stack) {
        if (inventory.hasTwoHandedHandsOccupied() && BodyInventory.isHand(anchor)) {
            if (stack == null) {
                inventory.clearHands();
            } else {
                inventory.setTwoHanded(stack);
            }
            return;
        }
        inventory.setSingle(anchor, stack);
    }

    private boolean accepts(BodySlot slot, ItemStack stack) {
        if (stack == null) {
            return true;
        }
        ItemFormDefinition form = itemForm(stack).orElse(null);
        if (form == null) {
            return false;
        }
        if (slot == BodySlot.MOUTH) {
            return !form.twoHanded() && form.mouthHoldable();
        }
        return !form.twoHanded() || BodyInventory.isHand(slot);
    }

    private Optional<ItemFormDefinition> itemForm(ItemStack stack) {
        Optional<ItemFormDefinition> form = itemForms.find(stack.itemId());
        return Objects.requireNonNull(form, "item form lookup result");
    }

    private boolean owns(EntityRef owner) {
        return inventory.owner().equals(owner);
    }

    private boolean locked(BodySlot slot) {
        if (locks.containsKey(slot)) {
            return true;
        }
        return inventory.hasTwoHandedHandsOccupied()
                && BodyInventory.isHand(slot)
                && (locks.containsKey(BodySlot.LEFT_HAND)
                        || locks.containsKey(BodySlot.RIGHT_HAND));
    }

    private List<BodySlot> affectedSlots(BodySlot anchor, boolean bothHands) {
        if (bothHands) {
            return List.of(BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND);
        }
        return List.of(anchor);
    }

    private void lock(ReservationState state) {
        for (BodySlot slot : state.affectedSlots) {
            locks.put(slot, state.reservation.id());
        }
    }

    private void unlock(ReservationState state) {
        for (BodySlot slot : state.affectedSlots) {
            locks.remove(slot, state.reservation.id());
        }
    }

    private InventoryInsertResult insertRejected(ItemStack requested) {
        return new InventoryInsertResult(
                InventoryInsertResult.Status.REJECTED,
                Optional.of(requested), viewModel(inventory.owner()));
    }

    private InventoryOperationResult operation(InventoryOperationResult.Status status) {
        return new InventoryOperationResult(
                status,
                status == InventoryOperationResult.Status.UNKNOWN_OWNER
                        ? Optional.empty()
                        : viewModel(inventory.owner()));
    }

    private InventorySplitResult splitRejected(InventorySplitResult.Status status) {
        return new InventorySplitResult(
                status, Optional.empty(), viewModel(inventory.owner()));
    }

    private InventoryReserveResult failedReservation(
            InventoryReservationRequest request,
            InventoryReserveResult.Status status,
            Optional<InventoryView> snapshot) {
        return new InventoryReserveResult(
                request, status, Optional.empty(), Optional.of(request.requested()), snapshot);
    }

    private InventoryReservationResult reservationResult(
            InventoryReservationId id,
            InventoryReservationResult.Status status,
            Optional<InventoryView> snapshot) {
        return new InventoryReservationResult(id, status, snapshot);
    }

    private InventoryReservationId nextReservationId() {
        if (reservationIdsExhausted) {
            throw new IllegalStateException("inventory reservation ID sequence exhausted");
        }
        InventoryReservationId id = new InventoryReservationId(nextReservationId);
        if (nextReservationId == Long.MAX_VALUE) {
            reservationIdsExhausted = true;
        } else {
            nextReservationId++;
        }
        return id;
    }

    private void contentsChanged() {
        inventory.incrementRevision();
        publishCommitted(new InventoryChanged(inventory.owner(), inventory.revision()));
    }

    private void publishCommitted(Event notification) {
        try {
            eventSink.accept(notification);
        } catch (RuntimeException | Error failure) {
            throw new InventoryEventDispatchException(
                    "post-commit inventory notification publication failed",
                    failure,
                    notification,
                    true);
        }
    }

    private void assertMainThread(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private static final class ReservationState {
        private final InventoryReservation reservation;
        private final BodySlot anchor;
        private final boolean affectsBothHands;
        private final List<BodySlot> affectedSlots;
        private Terminal terminal;

        private ReservationState(
                InventoryReservation reservation,
                BodySlot anchor,
                boolean affectsBothHands,
                List<BodySlot> affectedSlots) {
            this.reservation = Objects.requireNonNull(reservation, "reservation");
            this.anchor = Objects.requireNonNull(anchor, "anchor");
            this.affectsBothHands = affectsBothHands;
            this.affectedSlots = List.copyOf(affectedSlots);
        }
    }

    private enum Terminal {
        COMMITTED,
        ROLLED_BACK
    }
}
