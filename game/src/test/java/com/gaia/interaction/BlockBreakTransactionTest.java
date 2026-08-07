package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.WorldItemSpawnIndeterminateException;
import com.gaia.testing.FaultInjectingWorldItemService;
import com.gaia.testing.FaultInjectingWorldItemService.CommitFailureKind;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlockBreakTransactionTest {
    private static final EntityRef OWNER = new EntityRef(11);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ItemStack DROP = new ItemStack(STONE, 1);

    @Test
    void reservesWorldDropBeforeMutationThenCommitsExactlyOneDrop() {
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
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertEquals(List.of(DROP), worldItems.snapshots().stream()
                .map(WorldItemSnapshot::stack)
                .toList());
    }

    @Test
    void normalInventoryCapacityStillCommitsCanonicalWorldDropWithApprovedMotion() {
        BodyInventoryService inventory = inventory();
        LogicalWorldItemService worldItems = worldItems(2);
        PhysicsBody player = playerAt(4.5f, 2.0f, 3.5f);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                BlockBreakTransactionTest::applied,
                inventory,
                OWNER,
                worldItems,
                player,
                AIR);

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED, result.status());
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        WorldItemSnapshot spawned = worldItems.snapshots().get(0);
        assertEquals(DROP, spawned.stack());
        assertEquals(1.5, spawned.positionX(), 1.0e-6);
        assertEquals(2.5, spawned.positionY(), 1.0e-6);
        assertEquals(3.5, spawned.positionZ(), 1.0e-6);
        assertEquals(1.40, spawned.velocityY(), 1.0e-6);
        assertTrue(spawned.velocityX() < 0.0);
        double outwardSpeed = -spawned.velocityX();
        double lateralSpeed = -spawned.velocityZ();
        assertTrue(outwardSpeed >= 1.25 - 1.0e-6);
        assertTrue(outwardSpeed <= 1.75 + 1.0e-6);
        assertTrue(Math.abs(lateralSpeed) <= 0.20 + 1.0e-6);
        assertTrue(Math.hypot(spawned.velocityX(), spawned.velocityZ())
                <= Math.hypot(1.75, 0.20) + 1.0e-6);
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
        assertEquals(0, inventory.totalCount(OWNER, STONE));
        assertEquals(0, result.inventoryCommitted());
        assertEquals(1, result.worldItemCommitted());
        assertEquals(1, worldItems.snapshots().size());
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
    void blockMutationDoesNotPublishAnInventoryNotification() {
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

        assertEquals(List.of("before", "changed", "dirty"), order);
    }

    @Test
    void mutationRejectedCannotReturnNormallyWhenSpawnRollbackIsNotProven() {
        assertMutationRollbackUnresolved(
                WorldItemSpawnCommitResult.Status.COMMITTED, null, false);
    }

    @Test
    void mutationRejectedWithCommittedSpawnRetainsFatalBarrier() {
        assertMutationRollbackUnresolved(
                WorldItemSpawnCommitResult.Status.COMMITTED, null, false);
    }

    @Test
    void mutationRejectedWithTerminalConflictRetainsFatalBarrier() {
        assertMutationRollbackUnresolved(
                WorldItemSpawnCommitResult.Status.TERMINAL_CONFLICT, null, false);
    }

    @Test
    void mutationRejectedWithUnknownSpawnAndAuditFailureRetainsBarrier() {
        assertMutationRollbackUnresolved(
                WorldItemSpawnCommitResult.Status.UNKNOWN_RESERVATION,
                new IllegalStateException("audit unavailable"),
                false);
    }

    @Test
    void mutationRejectedWithRollbackExceptionRetainsBarrier() {
        assertMutationRollbackUnresolved(
                null, new IllegalStateException("rollback failed"), false);
    }

    @Test
    void mutationRejectedWithRollbackErrorRetainsBarrierAndRethrowsExactError() {
        assertMutationRollbackUnresolved(
                null, new AssertionError("rollback fatal"), true);
    }

    @Test
    void mutationRejectedWithAlreadyRolledBackSpawnReturnsOrdinaryRejection() {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        worldItems.returnNextRollbackAs(
                WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return cancelled(request);
                }, inventory(), OWNER, worldItems, AIR, failure -> {});

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.MUTATION_REJECTED, result.status());
        assertEquals(1, mutations.get());
        assertEquals(1, worldItems.reserveCalls());
        assertEquals(1, worldItems.rollbackCalls());
        assertTrue(worldItems.snapshots().isEmpty());
        assertFalse(transaction.hasUnresolvedTransaction());
    }

    @Test
    void appliedMutationCompletesSamePendingSpawnReservationAfterTypedFailure() {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        IllegalStateException primary = new IllegalStateException("before apply");
        worldItems.failFirstCommit(CommitFailureKind.TYPED_BEFORE_APPLY, primary);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return applied(request);
                }, inventory(), OWNER, worldItems, AIR, failure -> {});

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, mutations.get());
        assertEquals(2, worldItems.commitCalls());
        assertEquals(0, worldItems.rollbackCalls());
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(worldItems.lastReservation().itemId(),
                worldItems.snapshots().get(0).id());
        assertEquals(1, result.worldItemCommitted());
    }

    @Test
    void appliedMutationAcceptsAuditedCommittedSpawnWithoutDuplicateCommit() {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        IllegalStateException primary = new IllegalStateException("after apply");
        worldItems.failFirstCommit(CommitFailureKind.UNTYPED_AFTER_APPLY, primary);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                BlockBreakTransactionTest::applied,
                inventory(), OWNER, worldItems, AIR, failure -> {});

        BlockBreakResult result = transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100);

        assertEquals(BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertSame(primary, result.failure().orElseThrow());
        assertEquals(1, worldItems.commitCalls());
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(0L, worldItems.snapshots().get(0).id().value());
    }

    @Test
    void appliedMutationFinishesSpawnThenRethrowsOriginalError() {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        AssertionError primary = new AssertionError("after apply fatal");
        worldItems.failFirstCommit(CommitFailureKind.TYPED_AFTER_APPLY, primary);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return applied(request);
                }, inventory(), OWNER, worldItems, AIR, failure -> {});

        AssertionError escaped = assertThrows(AssertionError.class, () ->
                transaction.execute(
                        hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100));

        assertSame(primary, escaped);
        assertEquals(1, mutations.get());
        assertEquals(1, worldItems.commitCalls());
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(DROP, worldItems.snapshots().get(0).stack());
    }

    @Test
    void auditFailureAfterMutationBlocksDuplicateUntilShutdownResolvesSameStableId() {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        IllegalStateException primary = new IllegalStateException("commit escaped");
        IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
        worldItems.failFirstCommit(CommitFailureKind.UNTYPED_BEFORE_APPLY, primary);
        worldItems.failAuditWith(auditFailure);
        AtomicInteger mutations = new AtomicInteger();
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return applied(request);
                }, inventory(), OWNER, worldItems, AIR, failure -> {});

        WorldItemSpawnIndeterminateException escaped = assertThrows(
                WorldItemSpawnIndeterminateException.class,
                () -> transaction.execute(
                        hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100));

        assertSame(primary, escaped.getCause());
        assertEquals(List.of(auditFailure), List.of(primary.getSuppressed()));
        assertEquals(worldItems.lastReservation(), escaped.worldReservation());
        assertTrue(transaction.hasUnresolvedTransaction());
        assertEquals(1, mutations.get());
        assertTrue(worldItems.snapshots().isEmpty());
        assertThrows(WorldItemSpawnIndeterminateException.class, () ->
                transaction.execute(
                        hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 21, 101));
        assertEquals(1, mutations.get());
        assertEquals(1, worldItems.reserveCalls());

        worldItems.clearAuditFailure();
        transaction.close();
        transaction.close();

        assertTrue(!transaction.hasUnresolvedTransaction());
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(0L, worldItems.snapshots().get(0).id().value());
        assertEquals(2, worldItems.commitCalls());
    }

    private static void assertMutationRollbackUnresolved(
            WorldItemSpawnCommitResult.Status rollbackStatus,
            Throwable failure,
            boolean expectError) {
        FaultInjectingWorldItemService worldItems = new FaultInjectingWorldItemService();
        if (rollbackStatus != null) {
            worldItems.returnNextRollbackAs(rollbackStatus);
        }
        if (failure != null) {
            if (rollbackStatus == WorldItemSpawnCommitResult.Status.UNKNOWN_RESERVATION) {
                worldItems.failAuditWith(failure);
            } else {
                worldItems.failNextRollbackWith(failure);
            }
        }
        AtomicInteger mutations = new AtomicInteger();
        AtomicReference<ResourceLocation> canonicalBlock = new AtomicReference<>(STONE);
        BlockBreakTransaction transaction = new BlockBreakTransaction(
                request -> {
                    mutations.incrementAndGet();
                    return cancelled(request);
                }, inventory(), OWNER, worldItems, AIR, ignored -> {});

        Throwable escaped = expectError
                ? assertThrows(AssertionError.class, () -> transaction.execute(
                        hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100))
                : assertThrows(WorldItemSpawnIndeterminateException.class,
                        () -> transaction.execute(
                                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 20, 100));

        assertTrue(transaction.hasUnresolvedTransaction());
        assertEquals(STONE, canonicalBlock.get());
        assertEquals(1, mutations.get());
        assertEquals(1, worldItems.reserveCalls());
        assertEquals(1, worldItems.rollbackCalls());
        WorldItemSpawnIndeterminateException barrier = expectError
                ? (WorldItemSpawnIndeterminateException) escaped.getSuppressed()[0]
                : (WorldItemSpawnIndeterminateException) escaped;
        assertEquals(worldItems.lastReservation(), barrier.worldReservation());
        assertEquals(worldItems.lastReservation().itemId(), barrier.worldReservation().itemId());

        Throwable repeated = assertThrows(Throwable.class, () -> transaction.execute(
                hit(), Optional.of(DROP), BodySlot.LEFT_HAND, 21, 101));
        assertSame(barrier, repeated);
        assertEquals(1, worldItems.reserveCalls());
        assertEquals(1, mutations.get());
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

    private static PhysicsBody playerAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0.0f, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1.0f));
        body.teleport(new Vector3f(x, y, z));
        return body;
    }

    private static BlockChangeResult applied(
            com.overlord.interaction.api.BlockChangeRequest request) {
        return new BlockChangeResult(
                request,
                BlockChangeResult.Status.APPLIED,
                Optional.of(request.replacementBlock()),
                List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 3)));
    }

    private static BlockChangeResult cancelled(
            com.overlord.interaction.api.BlockChangeRequest request) {
        return new BlockChangeResult(
                request,
                BlockChangeResult.Status.CANCELLED,
                Optional.of(request.expectedBlock()),
                List.of());
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
