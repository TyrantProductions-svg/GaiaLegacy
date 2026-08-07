package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.gaia.worlditem.WorldItemPickupResult;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CommittedPickupVisualAdapterTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final TextureRegion REGION =
            new TextureRegion(DIRT, 0, 0, 16, 16, 16, 16);

    @Test
    void everyCommittedPickupStatusEmitsOneEightParticleHighRequestFromReceipt() {
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedPickupVisualAdapter adapter = new CommittedPickupVisualAdapter(
                item -> REGION, emissions::add, (receipt, failure) -> {});

        adapter.onPickup(applied(WorldItemPickupResult.Status.PICKED_ALL, 0,
                Optional.empty()));
        adapter.onPickup(applied(WorldItemPickupResult.Status.PICKED_PARTIAL, 2,
                Optional.empty()));
        adapter.onPickup(applied(
                WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE,
                0,
                Optional.of(new RuntimeException("notification"))));

        assertEquals(3, emissions.size());
        for (ParticleEmission emission : emissions) {
            assertEquals(ParticleCategory.PICKUP_COMMITTED, emission.category());
            assertEquals(ParticlePriority.HIGH, emission.priority());
            assertEquals(8, emission.count());
            assertEquals(1.0f, emission.x());
            assertEquals(2.0f, emission.y());
            assertEquals(3.0f, emission.z());
            assertEquals(REGION, emission.region());
        }
    }

    @Test
    void everyNonCommittedStatusEmitsNothing() {
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedPickupVisualAdapter adapter = new CommittedPickupVisualAdapter(
                item -> REGION, emissions::add, (receipt, failure) -> {});

        for (WorldItemPickupResult.Status status : WorldItemPickupResult.Status.values()) {
            if (status == WorldItemPickupResult.Status.PICKED_ALL
                    || status == WorldItemPickupResult.Status.PICKED_PARTIAL
                    || status == WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE) {
                continue;
            }
            int original = status == WorldItemPickupResult.Status.UNKNOWN_ITEM ? 0 : 1;
            Optional<Throwable> failure = status == WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN
                            || status == WorldItemPickupResult.Status.INDETERMINATE
                    ? Optional.of(new IllegalStateException("fatal"))
                    : Optional.empty();
            adapter.onPickup(new WorldItemPickupResult(
                    status, new WorldItemId(4), original, 0, original,
                    Optional.empty(), failure));
        }

        assertTrue(emissions.isEmpty());
    }

    @Test
    void resolverFailureReportsAndEmitsNothing() {
        List<Throwable> failures = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        RuntimeException failure = new RuntimeException("missing region");
        CommittedPickupVisualAdapter adapter = new CommittedPickupVisualAdapter(
                item -> { throw failure; },
                emissions::add,
                (receipt, thrown) -> failures.add(thrown));

        adapter.onPickup(applied(WorldItemPickupResult.Status.PICKED_ALL, 0,
                Optional.empty()));

        assertTrue(emissions.isEmpty());
        assertEquals(List.of(failure), failures);
    }

    private static WorldItemPickupResult applied(
            WorldItemPickupResult.Status status,
            int remaining,
            Optional<Throwable> failure) {
        WorldItemPickupReceipt receipt = new WorldItemPickupReceipt(
                new WorldItemId(4), new ItemStack(DIRT, 2), 1, 2, 3, 10);
        return new WorldItemPickupResult(
                status, receipt.itemId(), 2 + remaining, 2, remaining,
                Optional.of(receipt), failure);
    }
}
