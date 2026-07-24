package com.gaia.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.testing.FakeInventoryReservationService;
import com.overlord.inventory.testing.StubInventoryService;
import com.overlord.inventory.testing.TestInventoryView;
import com.overlord.inventory.testing.TestItemStackView;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineContractFixtureSmokeTest {
    @Test
    void gameTestsCanUseCanonicalItemStacks() {
        ItemStack stack =
                new ItemStack(ResourceLocation.parse("gaia:stone"), 2);

        assertEquals(ResourceLocation.parse("gaia:stone"), stack.itemId());
        assertEquals(2, stack.count());
    }

    @Test
    void gameTestsConsumeSharedInventoryFixtures() {
        TestItemStackView stack =
                new TestItemStackView(
                        ResourceLocation.parse("gaia:stone"), 2);
        TestInventoryView inventory =
                new TestInventoryView(
                        new EntityRef(3),
                        7,
                        Map.of(BodySlot.LEFT_HAND, stack));

        assertEquals(
                stack,
                inventory.stack(BodySlot.LEFT_HAND).orElseThrow());

        StubInventoryService inventoryService =
                new StubInventoryService(
                        Optional.of(inventory),
                        new InventoryChangeResult(
                                InventoryChangeResult.Status.APPLIED,
                                Optional.of(inventory)));
        assertEquals(
                Optional.of(inventory),
                inventoryService.snapshot(new EntityRef(3)));
    }

    @Test
    void gameTestsCanUseTheSharedReservationFake() {
        TestInventoryView inventory =
                new TestInventoryView(new EntityRef(3), 7, Map.of());
        FakeInventoryReservationService inventoryService =
                new FakeInventoryReservationService(Optional.of(inventory));

        inventoryService.setNextReservationLimit(1);

        assertEquals(
                InventoryReserveResult.Status.PARTIALLY_RESERVED,
                inventoryService.reserve(new InventoryReservationRequest(
                        new EntityRef(3),
                        BodySlot.LEFT_HAND,
                        InventoryReservationOperation.INSERT,
                        new ItemStack(ResourceLocation.parse("gaia:stone"), 2)))
                        .status());
    }
}
