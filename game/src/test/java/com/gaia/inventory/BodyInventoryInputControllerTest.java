package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.event.Event;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ActiveBodySlotChanged;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BodyInventoryInputControllerTest {
    private static final EntityRef OWNER = new EntityRef(9);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void numericSelectionAndScrollCycleAreEdgeDrivenAcrossFixedStepCatchUp() {
        BodyInventoryService service = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(Map.of(
                        DIRT, new ItemFormDefinition(DIRT, 64, false, false)).get(id)),
                event -> {});
        BodyInventoryInputController controller = new BodyInventoryInputController(service, OWNER);
        InputSnapshot firstStep = new InputSnapshot(
                Set.of(), Set.of(GameConfig.Input.KEY_SELECT_RIGHT), 0);

        controller.handle(firstStep);
        controller.handle(firstStep.heldOnly());
        controller.handle(new InputSnapshot(Set.of(), Set.of(), 1));
        controller.handle(new InputSnapshot(Set.of(), Set.of(), 1).heldOnly());

        assertEquals(BodySlot.MOUTH, service.viewModel(OWNER).orElseThrow().activeSlot());
    }

    @Test
    void qWithoutThePhaseElevenWorldItemAdapterFailsClosed() {
        BodyInventoryService service = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(Map.of(
                        DIRT, new ItemFormDefinition(DIRT, 64, false, false)).get(id)),
                event -> {});
        service.insert(OWNER, new ItemStack(DIRT, 3));
        BodyInventoryInputController controller = new BodyInventoryInputController(service, OWNER);

        InventoryInputResult result = controller.handle(new InputSnapshot(
                Set.of(), Set.of(GameConfig.Input.KEY_DROP), 0));

        assertEquals(InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE,
                result.drop().orElseThrow().status());
        assertEquals(3, service.totalCount(OWNER, DIRT));
    }

    @Test
    void qPressUsesOneItemAndHeldCatchUpCannotRepeat() {
        DropFixture fixture = dropFixture(5);
        InputSnapshot press = keyPress(Set.of(), GameConfig.Input.KEY_DROP);

        InventoryInputResult first = fixture.controller().handleDrop(
                press, 7, Optional.of(dropLocation()), true);
        InventoryInputResult held = fixture.controller().handleDrop(
                press.heldOnly(), 8, Optional.of(dropLocation()), true);

        assertEquals(InventoryDropResult.Status.DROPPED,
                first.drop().orElseThrow().status());
        assertTrue(held.drop().isEmpty());
        assertEquals(4, fixture.inventory().totalCount(OWNER, DIRT));
        assertEquals(1, fixture.worldItems().snapshots().size());
        assertEquals(1, fixture.worldItems().snapshots().get(0).stack().count());
    }

    @Test
    void leftControlQDropsTheCompleteActiveStackAsOneStableId() {
        assertControlDropsCompleteStack(GameConfig.Input.KEY_DROP_ALL_LEFT);
    }

    @Test
    void rightControlQDropsTheCompleteActiveStackAsOneStableId() {
        assertControlDropsCompleteStack(GameConfig.Input.KEY_DROP_ALL_RIGHT);
    }

    @Test
    void releaseAndRepressAllowsAnotherSingleDrop() {
        DropFixture fixture = dropFixture(3);
        InputSnapshot press = keyPress(Set.of(), GameConfig.Input.KEY_DROP);

        fixture.controller().handleDrop(press, 1, Optional.of(dropLocation()), true);
        fixture.controller().handleDrop(
                new InputSnapshot(Set.of(), Set.of()),
                2,
                Optional.of(dropLocation()),
                true);
        fixture.controller().handleDrop(press, 3, Optional.of(dropLocation()), true);

        assertEquals(1, fixture.inventory().totalCount(OWNER, DIRT));
        assertEquals(List.of(1, 1), fixture.worldItems().snapshots().stream()
                .map(snapshot -> snapshot.stack().count()).toList());
    }

    @Test
    void blockingLifecycleConsumesNoPendingQDrop() {
        DropFixture fixture = dropFixture(3);
        InputSnapshot press = keyPress(Set.of(), GameConfig.Input.KEY_DROP);

        InventoryInputResult blocked = fixture.controller().handleDrop(
                press, 1, Optional.of(dropLocation()), false);
        InventoryInputResult laterHeld = fixture.controller().handleDrop(
                press.heldOnly(), 2, Optional.of(dropLocation()), true);

        assertTrue(blocked.drop().isEmpty());
        assertTrue(laterHeld.drop().isEmpty());
        assertEquals(3, fixture.inventory().totalCount(OWNER, DIRT));
        assertTrue(fixture.worldItems().snapshots().isEmpty());
    }

    @Test
    void simultaneousNumericKeysUseStableLeftToRightPriorityAndOverrideWheel() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(events);
        BodyInventoryInputController controller = new BodyInventoryInputController(service, OWNER);

        InventoryInputResult result = controller.handle(new InputSnapshot(
                Set.of(),
                Set.of(GameConfig.Input.KEY_SELECT_RIGHT, GameConfig.Input.KEY_SELECT_MOUTH),
                2));

        assertEquals(ActiveSlotChangeResult.Status.SELECTED,
                result.selection().orElseThrow().status());
        assertEquals(BodySlot.RIGHT_HAND,
                service.viewModel(OWNER).orElseThrow().activeSlot());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof ActiveBodySlotChanged);
        ActiveBodySlotChanged event = (ActiveBodySlotChanged) events.get(0);
        assertEquals(BodySlot.LEFT_HAND, event.previousSlot());
        assertEquals(BodySlot.RIGHT_HAND, event.activeSlot());
    }

    @Test
    void multipleWheelStepsPublishTheCompleteOrderedSequenceOnlyOnce() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(events);
        BodyInventoryInputController controller = new BodyInventoryInputController(service, OWNER);
        InputSnapshot wheel = new InputSnapshot(Set.of(), Set.of(), 2);

        controller.handle(wheel);
        controller.handle(wheel.heldOnly());

        assertEquals(BodySlot.MOUTH,
                service.viewModel(OWNER).orElseThrow().activeSlot());
        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof ActiveBodySlotChanged);
        assertTrue(events.get(1) instanceof ActiveBodySlotChanged);
        ActiveBodySlotChanged first = (ActiveBodySlotChanged) events.get(0);
        ActiveBodySlotChanged second = (ActiveBodySlotChanged) events.get(1);
        assertEquals(BodySlot.LEFT_HAND, first.previousSlot());
        assertEquals(BodySlot.RIGHT_HAND, first.activeSlot());
        assertEquals(BodySlot.RIGHT_HAND, second.previousSlot());
        assertEquals(BodySlot.MOUTH, second.activeSlot());
    }

    @Test
    void opposingWheelCallbacksPublishEveryTransitionInCallbackOrder() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(events);
        BodyInventoryInputController controller = new BodyInventoryInputController(service, OWNER);

        controller.handle(new InputSnapshot(
                Set.of(), Set.of(), List.of(1, -1, 1)));

        assertEquals(BodySlot.RIGHT_HAND,
                service.viewModel(OWNER).orElseThrow().activeSlot());
        assertEquals(3, events.size());
        ActiveBodySlotChanged first = (ActiveBodySlotChanged) events.get(0);
        ActiveBodySlotChanged second = (ActiveBodySlotChanged) events.get(1);
        ActiveBodySlotChanged third = (ActiveBodySlotChanged) events.get(2);
        assertEquals(BodySlot.LEFT_HAND, first.previousSlot());
        assertEquals(BodySlot.RIGHT_HAND, first.activeSlot());
        assertEquals(BodySlot.RIGHT_HAND, second.previousSlot());
        assertEquals(BodySlot.LEFT_HAND, second.activeSlot());
        assertEquals(BodySlot.LEFT_HAND, third.previousSlot());
        assertEquals(BodySlot.RIGHT_HAND, third.activeSlot());
    }

    private static BodyInventoryService service(List<Event> events) {
        return new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(Map.of(
                        DIRT, new ItemFormDefinition(DIRT, 64, false, false)).get(id)),
                events::add);
    }

    private static void assertControlDropsCompleteStack(int controlKey) {
        DropFixture fixture = dropFixture(5);
        InputSnapshot press = keyPress(Set.of(controlKey), GameConfig.Input.KEY_DROP);

        fixture.controller().handleDrop(press, 7, Optional.of(dropLocation()), true);

        assertEquals(0, fixture.inventory().totalCount(OWNER, DIRT));
        assertEquals(1, fixture.worldItems().snapshots().size());
        assertEquals(5, fixture.worldItems().snapshots().get(0).stack().count());
        assertEquals(0L, fixture.worldItems().snapshots().get(0).id().value());
    }

    private static DropFixture dropFixture(int count) {
        BodyInventoryService inventory = service(new ArrayList<>());
        inventory.insert(OWNER, new ItemStack(DIRT, count));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 16, 20);
        BodyInventoryInputController controller = new BodyInventoryInputController(
                inventory,
                OWNER,
                Optional.of(new InventoryDropController(inventory, worldItems)));
        return new DropFixture(inventory, worldItems, controller);
    }

    private static InputSnapshot keyPress(Set<Integer> modifiers, int key) {
        Set<Integer> down = new java.util.HashSet<>(modifiers);
        down.add(key);
        return new InputSnapshot(down, Set.of(key));
    }

    private static InventoryDropLocation dropLocation() {
        return new InventoryDropLocation(1.0, 2.0, 3.0, 4.5, 1.25, 0.0);
    }

    private record DropFixture(
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems,
            BodyInventoryInputController controller) {}
}
