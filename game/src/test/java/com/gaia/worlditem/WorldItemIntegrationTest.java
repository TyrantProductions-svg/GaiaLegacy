package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class WorldItemIntegrationTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final TextureRegion REGION =
            new TextureRegion(DIRT, 0, 0, 16, 16, 16, 16);

    @Test
    void fullPickupRemovesLogicalProjectionAndStableIdVisualExactlyOnce() {
        Fixture fixture = fixture(64);
        WorldItemSnapshot item = fixture.spawn(2);
        fixture.physical.step(0);
        assertEquals(1, fixture.visuals().size());

        WorldItemPickupResult result = fixture.pickup.execute(
                item.id(), BodySlot.LEFT_HAND, 0);
        fixture.physical.prepareStep(1);

        assertEquals(WorldItemPickupResult.Status.PICKED_ALL, result.status());
        assertTrue(fixture.logical.snapshot(item.id()).isEmpty());
        assertTrue(fixture.physics.bodies().isEmpty());
        assertTrue(fixture.visuals().isEmpty());
        assertEquals(2, fixture.inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void partialPickupKeepsSameStableIdBodyAndVisual() {
        Fixture fixture = fixture(1);
        WorldItemSnapshot item = fixture.spawn(5);
        fixture.physical.step(0);
        PhysicsBody body = fixture.physics.bodies().get(0);

        WorldItemPickupResult result = fixture.pickup.execute(
                item.id(), BodySlot.RIGHT_HAND, 0);
        fixture.physical.prepareStep(1);

        assertEquals(WorldItemPickupResult.Status.PICKED_PARTIAL, result.status());
        assertEquals(3, result.inventoryCommittedCount());
        assertEquals(2, result.remainingWorldCount());
        assertEquals(item.id(), fixture.logical.snapshot(item.id()).orElseThrow().id());
        assertSame(body, fixture.physics.bodies().get(0));
        assertEquals(item.id(), fixture.visuals().get(0).id());
        assertEquals(1, fixture.visuals().size());
    }

    private static Fixture fixture(int maxStack) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 8, 0);
        World world = new World();
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(world, BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f(0, -25, 0));
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical, physics, guard, new WorldItemPhysicsConfig(0.50f, 8));
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER,
                id -> Optional.of(new ItemFormDefinition(id, maxStack, true, false)),
                guard,
                event -> {});
        WorldItemPickupTransaction pickup = new WorldItemPickupTransaction(
                inventory, logical, OWNER, failure -> {
                    throw new AssertionError(failure);
                });
        return new Fixture(logical, physical, physics, inventory, pickup,
                new WorldItemVisualTracker(item -> WorldItemFaceRegions.uniform(REGION)));
    }

    private record Fixture(
            LogicalWorldItemService logical,
            PhysicalWorldItemSystem physical,
            PhysicsWorld physics,
            BodyInventoryService inventory,
            WorldItemPickupTransaction pickup,
            WorldItemVisualTracker tracker) {
        private WorldItemSnapshot spawn(int count) {
            return logical.spawn(new WorldItemSpawnRequest(
                    new ItemStack(DIRT, count), 1.5, 3.0, 1.5,
                    0, 0, 0, Optional.empty(), 0)).item().orElseThrow();
        }

        private java.util.List<com.overlord.renderer.feedback.WorldItemVisual> visuals() {
            return tracker.reconcilePhysical(physical.presentationSnapshots(), 1.0f);
        }
    }
}
