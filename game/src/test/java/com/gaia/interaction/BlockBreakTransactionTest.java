package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BlockBreakTransactionTest {
    private static final EntityRef OWNER = new EntityRef(11);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ItemStack DROP = new ItemStack(STONE, 1);

    @Test
    void reservesInventoryBeforeMutationThenCommitsExactlyOneDrop() {
        BodyInventoryService inventory = inventory();
        LogicalWorldItemService worldItems = worldItems(2);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    assertEquals(0, inventory.totalCount(OWNER, STONE));
                    assertTrue(worldItems.snapshots().isEmpty());
                    return applied(request);
                },
                inventory,
                OWNER,
                worldItems,
                AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED, result.status());
        assertEquals(1, mutations.get());
        assertEquals(1, result.produced());
        assertEquals(1, result.inventoryCommitted());
        assertEquals(0, result.worldItemCommitted());
        assertEquals(1, inventory.totalCount(OWNER, STONE));
        assertTrue(worldItems.snapshots().isEmpty());
    }

    @Test
    void fullInventoryUsesReservedLogicalWorldItemThatPersistsAfterMutation() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 128));
        LogicalWorldItemService worldItems = worldItems(1);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                BlockBreakTransactionTest::applied,
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED, result.status());
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(DROP, worldItems.snapshots().get(0).stack());
        assertEquals(128, inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void worldCapacityRejectionRollsBackInventoryAndDoesNotMutateBlock() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 128));
        LogicalWorldItemService worldItems = worldItems(1);
        worldItems.spawn(spawnRequest(new ItemStack(STONE, 1), 1));
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return applied(request);
                }, inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.RESERVATION_REJECTED, result.status());
        assertEquals(0, mutations.get());
        assertEquals(128, inventory.totalCount(OWNER, DIRT));
        assertEquals(1, worldItems.snapshots().size());
    }

    @Test
    void mutationRejectionRollsBackEveryReservationAndPreservesCounts() {
        BodyInventoryService inventory = inventory();
        LogicalWorldItemService worldItems = worldItems(1);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> new BlockChangeResult(
                        request, BlockChangeResult.Status.CANCELLED,
                        Optional.of(request.expectedBlock()), List.of()),
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertTrue(worldItems.snapshots().isEmpty());
    }

    @Test
    void appliedPostWriteDispatchFailureCommitsDropsAndMustNotRetryMutation() {
        BodyInventoryService inventory = inventory();
        LogicalWorldItemService worldItems = worldItems(1);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    throw new BlockChangeDispatchException(
                            "post-write", new IllegalStateException("subscriber"), true);
                }, inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.get());
        assertEquals(1, inventory.totalCount(OWNER, STONE));
        assertEquals(1, result.inventoryCommitted() + result.worldItemCommitted());
        assertTrue(result.failure().isPresent());
    }

    @Test
    void creativeNoDropMutationSkipsBothItemServices() {
        BodyInventoryService inventory = inventory();
        LogicalWorldItemService worldItems = worldItems(1);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                BlockBreakTransactionTest::applied,
                inventory, OWNER, worldItems, AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.empty(), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED, result.status());
        assertEquals(0, result.produced());
        assertFalse(result.failure().isPresent());
        assertTrue(worldItems.snapshots().isEmpty());
    }

    @Test
    void orderedWorldEventsFinishBeforeCommittedInventoryNotification() {
        List<String> order = new java.util.ArrayList<>();
        Map<ResourceLocation, ItemFormDefinition> forms = Map.of(
                STONE, new ItemFormDefinition(STONE, 64, false, false));
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(forms.get(id)),
                event -> order.add("inventory"));
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    order.add("before");
                    order.add("changed");
                    order.add("dirty");
                    return applied(request);
                },
                inventory,
                OWNER,
                worldItems(1),
                AIR);

        transaction.execute(hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 1, 1);

        assertEquals(List.of("before", "changed", "dirty", "inventory"), order);
    }

    private static BodyInventoryService inventory() {
        Map<ResourceLocation, ItemFormDefinition> forms = Map.of(
                DIRT, new ItemFormDefinition(DIRT, 64, false, false),
                STONE, new ItemFormDefinition(STONE, 64, false, false));
        return new BodyInventoryService(OWNER, id -> Optional.ofNullable(forms.get(id)), event -> {});
    }

    private static LogicalWorldItemService worldItems(int capacity) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, 10);
    }

    private static BlockChangeResult applied(
            com.overlord.interaction.api.BlockChangeRequest request) {
        return new BlockChangeResult(
                request,
                BlockChangeResult.Status.APPLIED,
                Optional.of(request.replacementBlock()),
                List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 3)));
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                STONE, 1, 0, 0,
                2, 2.5f, 3.5f, 2);
    }

    private static com.overlord.worlditem.api.WorldItemSpawnRequest spawnRequest(
            ItemStack stack, long tick) {
        return new com.overlord.worlditem.api.WorldItemSpawnRequest(
                stack, 0, 0, 0, 0, 0, 0, Optional.of(OWNER), tick);
    }
}
