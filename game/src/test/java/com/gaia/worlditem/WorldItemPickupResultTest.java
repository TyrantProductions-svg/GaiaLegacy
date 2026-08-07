package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorldItemPickupResultTest {
    private static final WorldItemId ID = new WorldItemId(4);
    private static final ItemStack PICKED =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 2);
    private static final WorldItemPickupReceipt RECEIPT =
            new WorldItemPickupReceipt(ID, PICKED, 1, 2, 3, 10);

    @Test
    void validAppliedAndFailureShapesAreConstructible() {
        assertDoesNotThrow(() -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_ALL,
                ID, 2, 2, 0, Optional.of(RECEIPT), Optional.empty()));
        assertDoesNotThrow(() -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_PARTIAL,
                ID, 3, 2, 1, Optional.of(RECEIPT), Optional.empty()));
        assertDoesNotThrow(() -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE,
                ID, 2, 2, 0, Optional.of(RECEIPT),
                Optional.of(new RuntimeException("notification"))));
        assertDoesNotThrow(() -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN,
                ID, 2, 2, 2, Optional.empty(),
                Optional.of(new IllegalStateException("fatal"))));
    }

    @Test
    void impossibleStatusPayloadAndCountCombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_ALL,
                ID, 2, 2, 0, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_ALL,
                ID, 3, 2, 1, Optional.of(RECEIPT), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_PARTIAL,
                ID, 2, 2, 0, Optional.of(RECEIPT), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE,
                ID, 2, 2, 0, Optional.of(RECEIPT), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKUP_DELAYED,
                ID, 2, 0, 2, Optional.of(RECEIPT), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.PICKUP_DELAYED,
                ID, 2, 1, 1, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.UNKNOWN_ITEM,
                ID, 2, 0, 2, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new WorldItemPickupResult(
                WorldItemPickupResult.Status.INDETERMINATE,
                ID, 2, 0, 2, Optional.empty(), Optional.empty()));
    }
}
