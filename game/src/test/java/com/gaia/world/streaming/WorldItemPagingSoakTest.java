package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.assertStructuralBounds;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.voxel.ChunkKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldItemPagingSoakTest {
    @Test
    void fiveHundredActualPageTransitionsAcrossTravelAndLifecycleChurnStayBounded() {
        var backend = com.gaia.save.streaming.WorldItemPagingAcceptanceFixture
                .boundedSoakBackend();
        LogicalWorldItemService service = backend.service();
        long tick = 0L;
        int pageTransitions = 0;

        for (int cycle = 0; cycle < 250; cycle++) {
            ChunkKey key = new ChunkKey(cycle - 125, (cycle % 17) - 8);
            service.deliverWorldTick(tick);
            var spawned = service.spawn(new WorldItemSpawnRequest(
                    new ItemStack(ResourceLocation.of("gaia", "test/soak"), 2),
                    key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                    0.0, 0.0, 0.0, Optional.empty(), tick)).item().orElseThrow();
            service.commit(service.reserve(spawned.id(), 1)
                    .reservation().orElseThrow().id());
            assertEquals(1, service.snapshot(spawned.id()).orElseThrow().stack().count());
            var hibernate = service.prepareHibernate(
                    key,
                    Map.of(
                            spawned.id(),
                            service.snapshot(spawned.id()).orElseThrow().revision()));
            assertEquals(
                    com.overlord.worlditem.api.WorldItemHibernateResult.Status
                            .PREPARED,
                    hibernate.status());
            assertEquals(1, service.pagingMetrics().persistenceTicketCount());
            assertTrue(service.pagingMetrics().dirtyCandidateBytes() > 0L);
            assertEquals(1, service.pagingMetrics().unprovedPinnedPageCount());
            var proof = backend.persist(hibernate.persistencePlan().orElseThrow());
            assertEquals(
                    com.overlord.worlditem.api.WorldItemHibernateResult.Status.COMMITTED,
                    service.commitPersistence(
                            hibernate.persistenceTicket().orElseThrow(), proof).status());
            pageTransitions++;
            assertEquals(1, service.pagingMetrics().evictedUnexpiredCount());
            assertStructuralBounds(service);

            try (var view = backend.openReadView()) {
                var descriptor = view.checkpoint().pages().stream()
                        .filter(value -> value.chunkKey().equals(key))
                        .findFirst().orElseThrow();
                var activation = service.prepareActivate(view, descriptor);
                assertEquals(
                        com.overlord.worlditem.api.WorldItemActivationResult.Status.PREPARED,
                        activation.status());
                assertEquals(1, service.pagingMetrics().activationTicketCount());
                assertEquals(
                        com.overlord.worlditem.api.WorldItemActivationResult.Status.COMMITTED,
                        service.commitActivate(activation.ticket().orElseThrow()).status());
            }
            pageTransitions++;

            var atBoundary = service.snapshot(spawned.id()).orElseThrow();
            double crossedX = key.worldOriginX()
                    + com.overlord.config.GameConfig.Chunk.SIZE + 0.25;
            assertEquals(
                    com.overlord.worlditem.api.WorldItemMotionUpdateResult.Status.APPLIED,
                    service.updateMotion(new WorldItemMotionUpdate(
                            spawned.id(), atBoundary.revision(),
                            crossedX, atBoundary.positionY(), atBoundary.positionZ(),
                            1.0, 0.0, 0.0, WorldItemPhysicalState.ACTIVE)).status());
            var crossed = service.snapshot(spawned.id()).orElseThrow();
            assertEquals(
                    com.overlord.worlditem.api.WorldItemMotionUpdateResult.Status.APPLIED,
                    service.updateMotion(new WorldItemMotionUpdate(
                            spawned.id(), crossed.revision(),
                            key.worldOriginX() + 0.25,
                            crossed.positionY(), crossed.positionZ(),
                            -1.0, 0.0, 0.0, WorldItemPhysicalState.ACTIVE)).status());

            service.deliverWorldTick(tick); // pause: no authoritative time passes
            if ((cycle & 1) == 0) {
                service.commit(service.reserve(spawned.id(), 1)
                        .reservation().orElseThrow().id());
            } else {
                tick = Math.addExact(tick,
                        com.overlord.worlditem.api.WorldItemRuntimeSnapshot
                                .WORLD_ITEM_TTL_TICKS);
                assertEquals(List.of(spawned.id()), service.deliverWorldTick(tick));
            }
            assertStructuralBounds(service);
            assertTrue(service.snapshots().isEmpty());

            var cleanup = service.prepareSavePersistence();
            assertEquals(
                    com.overlord.worlditem.api.WorldItemHibernateResult.Status
                            .PERSISTENCE_PREPARED,
                    cleanup.status());
            var cleanupProof = backend.persist(cleanup.persistencePlan().orElseThrow());
            assertEquals(
                    com.overlord.worlditem.api.WorldItemHibernateResult.Status.COMMITTED,
                    service.commitPersistence(
                            cleanup.persistenceTicket().orElseThrow(), cleanupProof).status());
            assertEquals(0, service.pagingMetrics().physicalDescriptorCount());
            assertStructuralBounds(service);
        }

        assertEquals(500, pageTransitions);
        assertEquals(250L, service.canonicalSnapshot().nextItemId());
        assertTrue(service.snapshots().isEmpty());
    }
}
