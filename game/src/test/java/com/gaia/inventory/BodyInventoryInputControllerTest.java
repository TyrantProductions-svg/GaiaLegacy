package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.event.Event;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ActiveBodySlotChanged;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
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
}
