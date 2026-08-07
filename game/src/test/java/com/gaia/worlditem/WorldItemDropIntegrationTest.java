package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.BlockBreakResult;
import com.gaia.interaction.BlockBreakTransaction;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.InventoryDropController;
import com.gaia.inventory.InventoryDropResult;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WorldItemDropIntegrationTest {
    private static final EntityRef OWNER = new EntityRef(4);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");

    @Test
    void qAndBlockBreakShareCanonicalIdsThenReconcileThroughPhysicsAndVisuals() {
        Map<ResourceLocation, ItemFormDefinition> forms = Map.of(
                DIRT, new ItemFormDefinition(DIRT, 64, false, false),
                STONE, new ItemFormDefinition(STONE, 64, false, false));
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER, id -> Optional.ofNullable(forms.get(id)), ignored -> {});
        inventory.insert(OWNER, new ItemStack(DIRT, 128));
        LogicalWorldItemService logical = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 0);

        BlockBreakTransaction blockBreak = new BlockBreakTransaction(
                request -> new BlockChangeResult(
                        request,
                        BlockChangeResult.Status.APPLIED,
                        Optional.of(AIR),
                        List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 1))),
                inventory,
                OWNER,
                logical,
                AIR);
        BlockBreakResult broken = blockBreak.execute(
                hit(),
                Optional.of(new ItemStack(STONE, 1)),
                BodySlot.LEFT_HAND,
                2,
                2);
        InventoryDropResult qDrop = new InventoryDropController(inventory, logical).drop(
                OWNER,
                BodySlot.LEFT_HAND,
                2.5,
                3.5,
                4.5,
                0,
                0,
                0,
                3);

        assertEquals(BlockBreakResult.Status.APPLIED, broken.status());
        assertEquals(InventoryDropResult.Status.DROPPED, qDrop.status());
        assertEquals(List.of(0L, 1L), logical.snapshots().stream()
                .map(snapshot -> snapshot.id().value()).toList());
        assertEquals(STONE, logical.snapshots().get(0).stack().itemId());
        assertEquals(DIRT, logical.snapshots().get(1).stack().itemId());

        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                physics,
                MainThreadGuard.captureCurrentThread(),
                new WorldItemPhysicsConfig(0.50f, 8));
        physical.prepareStep(3);
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("gaia:missing"), 0, 0, 16, 16, 16, 16);
        WorldItemVisualTracker visuals = new WorldItemVisualTracker(
                ignored -> WorldItemFaceRegions.uniform(region));

        assertEquals(2, physics.bodies().size());
        assertEquals(List.of(0L, 1L), physical.presentationSnapshots().stream()
                .map(snapshot -> snapshot.id().value()).toList());
        assertEquals(List.of(0L, 1L), visuals.reconcilePhysical(
                        physical.presentationSnapshots(), 0.0f).stream()
                .map(visual -> visual.id().value()).toList());
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3,
                1, 3, 3,
                STONE,
                0, 1, 0,
                1.5f, 3.0f, 3.5f,
                2.0f);
    }
}
