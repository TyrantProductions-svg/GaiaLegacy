package com.gaia.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.testing.TestInventoryView;
import com.overlord.inventory.testing.TestItemStackView;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineContractFixtureSmokeTest {
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
    }
}
