package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.testing.FaultInjectingWorldItemService;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservationId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldItemSpawnCommitResolverTest {
    @Test
    void resolverRejectsDifferentReservationIdentity() {
        ItemStack stack = new ItemStack(ResourceLocation.parse("gaia:stone"), 2);
        WorldItemSpawnRequest request = new WorldItemSpawnRequest(
                stack, 1, 2, 3, 4, 5, 6, Optional.of(new EntityRef(2)), 7);
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        WorldItemSpawnReservation reservationA = worldItems.reserveSpawn(request)
                .reservation().orElseThrow();
        WorldItemSpawnReservation reservationB = new WorldItemSpawnReservation(
                new WorldItemSpawnReservationId(reservationA.id().value() + 1),
                reservationA.itemId(),
                request,
                reservationA.pickupAvailableTick());
        WorldItemSnapshot itemB = new WorldItemSnapshot(
                reservationB.itemId(), stack,
                1, 2, 3, 4, 5, 6, 0);
        WorldItemRuntimeSnapshot runtimeB = new WorldItemRuntimeSnapshot(
                itemB, request.source(), request.tick(), reservationB.pickupAvailableTick());
        worldItems.returnNextCommitAs(new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservationB),
                Optional.of(runtimeB)));

        WorldItemSpawnCommitResolver.Resolution resolution =
                new WorldItemSpawnCommitResolver(worldItems).commit(reservationA);

        assertEquals(WorldItemSpawnCommitResolver.Status.UNRESOLVED, resolution.status());
    }

    @Test
    void appliedResolutionRejectsMismatchedItem() {
        ItemStack stack = new ItemStack(ResourceLocation.parse("gaia:stone"), 1);
        WorldItemSpawnRequest request = new WorldItemSpawnRequest(
                stack, 1, 2, 3, 4, 5, 6, Optional.of(new EntityRef(2)), 7);
        WorldItemSpawnReservation reservation = new WorldItemSpawnReservation(
                new WorldItemSpawnReservationId(3), new WorldItemId(5), request);
        WorldItemSnapshot unrelated = new WorldItemSnapshot(
                reservation.itemId(), stack, 10, 2, 3, 4, 5, 6, 0);

        assertThrows(IllegalArgumentException.class, () ->
                new WorldItemSpawnCommitResolver.Resolution(
                        WorldItemSpawnCommitResolver.Status.APPLIED,
                        reservation,
                        Optional.of(new WorldItemRuntimeSnapshot(
                                unrelated,
                                request.source(),
                                request.tick(),
                                reservation.pickupAvailableTick())),
                        Optional.empty(),
                        Optional.empty()));
    }
}
