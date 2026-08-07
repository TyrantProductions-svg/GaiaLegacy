package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.testing.FaultInjectingWorldItemService;
import com.gaia.testing.FaultInjectingWorldItemService.CommitFailureKind;
import com.gaia.testing.FaultInjectingInventoryService;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.InventoryReservationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryDropCommitBarrierTest {
    private static final EntityRef OWNER = new EntityRef(51);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void reserveSpawnRuntimeExceptionRollsBackInventoryAndPreservesPrimaryFailure() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        fixture.world.failReserveWith(primary);

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class, fixture::dropOne);

        assertSame(primary, escaped);
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(0, fixture.world.snapshots().size());
        assertEquals(0, fixture.world.rollbackCalls());
        assertEquals(
                com.overlord.core.transaction.ReservationTerminalState.ROLLED_BACK,
                fixture.inventory.reservationAudit(
                        new com.overlord.inventory.api.InventoryReservationId(0L))
                        .orElseThrow().state());
        assertEquals(InventoryDropResult.Status.DROPPED, fixture.dropOne().status());
        assertConserved(fixture);
    }

    @Test
    void reserveSpawnErrorCleansInventoryThenRethrowsSameError() {
        Fixture fixture = fixture();
        AssertionError primary = new AssertionError("reserve fatal");
        fixture.world.failReserveWith(primary);

        AssertionError escaped = assertThrows(AssertionError.class, fixture::dropOne);

        assertSame(primary, escaped);
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertTrue(fixture.world.snapshots().isEmpty());
        assertEquals(InventoryDropResult.Status.DROPPED, fixture.dropOne().status());
        assertConserved(fixture);
    }

    @Test
    void rolledBackSpawnDoesNotClearBarrierWhenInventoryRollbackIsUnproven() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("before apply");
        fixture.world.failFirstCommit(CommitFailureKind.TYPED_BEFORE_APPLY, primary);
        fixture.world.overrideAudit(ReservationTerminalState.ROLLED_BACK);
        fixture.inventory.returnNextRollbackAs(
                InventoryReservationResult.Status.TERMINAL_CONFLICT);

        assertThrows(RuntimeException.class, fixture::dropOne);

        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(ReservationTerminalState.PENDING,
                fixture.inventory.reservationAudit(fixture.inventoryReservationId())
                        .orElseThrow().state());
        assertThrows(WorldItemSpawnIndeterminateException.class, fixture::dropOne);
        assertEquals(1, fixture.world.reserveCalls());
    }

    @Test
    void reserveSpawnFailureRetainsBarrierWhenInventoryRollbackFails() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        IllegalStateException cleanup = new IllegalStateException("rollback failed");
        fixture.world.failReserveWith(primary);
        fixture.inventory.failNextRollbackWith(cleanup);

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class, fixture::dropOne);

        assertSame(primary, escaped);
        assertEquals(List.of(cleanup), List.of(primary.getSuppressed()));
        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(ReservationTerminalState.PENDING,
                fixture.inventory.reservationAudit(fixture.inventoryReservationId())
                        .orElseThrow().state());
    }

    @Test
    void rollbackRuntimeExceptionPreservesBarrier() {
        assertRollbackFailureRetainsBarrier(new IllegalStateException("rollback failed"));
    }

    @Test
    void rollbackErrorPreservesBarrier() {
        assertRollbackFailureRetainsBarrier(new AssertionError("rollback fatal"));
    }

    @Test
    void rollbackTerminalConflictPreservesBarrier() {
        assertRollbackResultRetainsBarrier(
                InventoryReservationResult.Status.TERMINAL_CONFLICT, false);
    }

    @Test
    void rollbackUnknownRetainsBarrierAndLock() {
        assertRollbackResultRetainsBarrier(
                InventoryReservationResult.Status.UNKNOWN_RESERVATION, false);
    }

    @Test
    void rollbackMismatchedIdentityRetainsBarrierAndLock() {
        assertRollbackResultRetainsBarrier(
                InventoryReservationResult.Status.ROLLED_BACK, true);
    }

    @Test
    void provenRollbackClearsBarrierExactlyOnce() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        fixture.world.failReserveWith(primary);
        fixture.inventory.failNextRollbackWith(new IllegalStateException("cleanup failed"));
        assertSame(primary, assertThrows(IllegalStateException.class, fixture::dropOne));
        var reservationId = fixture.inventoryReservationId();

        fixture.controller.close();

        assertTrue(!fixture.controller.hasUnresolvedTransaction());
        assertEquals(List.of(reservationId, reservationId),
                fixture.inventory.rollbackReservationIds());
        assertEquals(ReservationTerminalState.ROLLED_BACK,
                fixture.inventory.reservationAudit(reservationId).orElseThrow().state());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void shutdownRetriesOnlyTheSameReservationResolution() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        fixture.world.failReserveWith(primary);
        fixture.inventory.failNextRollbackWith(new IllegalStateException("cleanup failed"));
        assertThrows(IllegalStateException.class, fixture::dropOne);
        var reservationId = fixture.inventoryReservationId();

        fixture.controller.close();

        assertEquals(List.of(reservationId, reservationId),
                fixture.inventory.rollbackReservationIds());
        assertEquals(1, fixture.world.reserveCalls());
        assertEquals(0, fixture.world.commitCalls());
        assertEquals(0, fixture.world.rollbackCalls());
    }

    @Test
    void repeatedShutdownIsIdempotent() {
        Fixture fixture = fixture();
        fixture.world.failReserveWith(new IllegalStateException("reserve failed"));
        fixture.inventory.failNextRollbackWith(
                new IllegalStateException("cleanup failed"));
        assertThrows(IllegalStateException.class, fixture::dropOne);

        fixture.controller.close();
        fixture.controller.close();

        assertTrue(!fixture.controller.hasUnresolvedTransaction());
        assertEquals(2, fixture.inventory.rollbackCalls());
        assertEquals(1, fixture.world.reserveCalls());
    }

    @Test
    void definitelyUnappliedCommitFailureRecommitsOnlyTheSameReservation() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("before apply");
        fixture.world.failFirstCommit(CommitFailureKind.TYPED_BEFORE_APPLY, primary);

        InventoryDropResult result = fixture.dropOne();

        assertEquals(InventoryDropResult.Status.DROPPED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertSame(primary, result.failure().orElseThrow().getCause());
        assertEquals(2, fixture.world.commitCalls());
        assertEquals(0, fixture.world.rollbackCalls());
        assertEquals(0L, result.worldItem().orElseThrow().id().value());
        assertEquals(fixture.world.lastReservation().itemId(),
                result.worldItem().orElseThrow().id());
        assertConserved(fixture);
    }

    @Test
    void appliedRuntimeFailureAuditsCommittedAndFinishesInventoryOnce() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("after apply");
        fixture.world.failFirstCommit(CommitFailureKind.UNTYPED_AFTER_APPLY, primary);

        InventoryDropResult result = fixture.dropOne();

        assertEquals(InventoryDropResult.Status.DROPPED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertSame(primary, result.failure().orElseThrow());
        assertEquals(1, fixture.world.commitCalls());
        assertConserved(fixture);
    }

    @Test
    void appliedErrorFinishesInventoryThenRethrowsOriginalError() {
        Fixture fixture = fixture();
        AssertionError primary = new AssertionError("after apply fatal");
        fixture.world.failFirstCommit(CommitFailureKind.TYPED_AFTER_APPLY, primary);

        AssertionError escaped = assertThrows(AssertionError.class, fixture::dropOne);

        assertSame(primary, escaped);
        assertEquals(1, fixture.world.commitCalls());
        assertConserved(fixture);
    }

    @Test
    void pendingAuditSafelyCompletesTheSameStableIdAfterUntypedFailure() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("unknown before apply");
        fixture.world.failFirstCommit(CommitFailureKind.UNTYPED_BEFORE_APPLY, primary);

        InventoryDropResult result = fixture.dropOne();

        assertEquals(2, fixture.world.commitCalls());
        assertEquals(fixture.world.lastReservation().itemId(),
                result.worldItem().orElseThrow().id());
        assertConserved(fixture);
    }

    @Test
    void auditFailureBlocksDuplicateDropUntilIdempotentShutdownResolution() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("commit escaped");
        IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
        fixture.world.failFirstCommit(CommitFailureKind.UNTYPED_BEFORE_APPLY, primary);
        fixture.world.failAuditWith(auditFailure);

        WorldItemSpawnIndeterminateException escaped = assertThrows(
                WorldItemSpawnIndeterminateException.class, fixture::dropOne);

        assertSame(primary, escaped.getCause());
        assertEquals(List.of(auditFailure), List.of(primary.getSuppressed()));
        assertEquals(new ItemStack(DIRT, 1), escaped.inventoryReservation().reserved());
        assertEquals(fixture.world.lastReservation(), escaped.worldReservation());
        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(
                com.overlord.core.transaction.ReservationTerminalState.PENDING,
                fixture.inventory.reservationAudit(
                        escaped.inventoryReservation().id()).orElseThrow().state());
        fixture.world.clearAuditFailure();
        assertEquals(
                com.overlord.core.transaction.ReservationTerminalState.PENDING,
                fixture.world.spawnReservationAudit(
                        escaped.worldReservation().id()).orElseThrow().state());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertTrue(fixture.world.snapshots().isEmpty());
        assertThrows(WorldItemSpawnIndeterminateException.class, fixture::dropOne);
        assertEquals(1, fixture.world.reserveCalls());

        fixture.controller.close();
        fixture.controller.close();

        assertTrue(!fixture.controller.hasUnresolvedTransaction());
        assertEquals(3, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(1, fixture.world.snapshots().size());
        assertEquals(0L, fixture.world.snapshots().get(0).id().value());
        assertEquals(
                com.overlord.core.transaction.ReservationTerminalState.COMMITTED,
                fixture.inventory.reservationAudit(
                        escaped.inventoryReservation().id()).orElseThrow().state());
        assertEquals(
                com.overlord.core.transaction.ReservationTerminalState.COMMITTED,
                fixture.world.spawnReservationAudit(
                        escaped.worldReservation().id()).orElseThrow().state());
    }

    @Test
    void unresolvedAuditRegistersBarrierButPreservesOriginalFatalError() {
        Fixture fixture = fixture();
        AssertionError primary = new AssertionError("commit fatal");
        fixture.world.failFirstCommit(CommitFailureKind.UNTYPED_BEFORE_APPLY, primary);
        fixture.world.failAuditWith(new IllegalStateException("audit unavailable"));

        AssertionError escaped = assertThrows(AssertionError.class, fixture::dropOne);

        assertSame(primary, escaped);
        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertTrue(fixture.world.snapshots().isEmpty());

        fixture.world.clearAuditFailure();
        AssertionError shutdown = assertThrows(AssertionError.class,
                fixture.controller::close);
        assertSame(primary, shutdown);
        assertTrue(!fixture.controller.hasUnresolvedTransaction());
        assertConserved(fixture);
    }

    @Test
    void closeBeforeTransactionIsIdempotentAndRejectsNewWorkWithoutReservations() {
        Fixture fixture = fixture();

        fixture.controller.close();
        fixture.controller.close();
        InventoryDropResult result = fixture.dropOne();

        assertEquals(InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE, result.status());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(0, fixture.world.reserveCalls());
    }

    @Test
    void legacyDropAfterCloseCannotMutateOrSpawn() {
        Fixture fixture = fixture();
        fixture.controller.close();

        InventoryDropResult result = fixture.dropLegacy();

        assertEquals(InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE, result.status());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
        assertTrue(fixture.world.snapshots().isEmpty());
        assertEquals(0, fixture.world.reserveCalls());
        assertEquals(0, fixture.world.commitCalls());
    }

    @Test
    void legacyDropCannotBypassUnresolvedBarrier() {
        Fixture fixture = fixture();
        fixture.world.failFirstCommit(
                CommitFailureKind.UNTYPED_BEFORE_APPLY,
                new IllegalStateException("commit escaped"));
        fixture.world.failAuditWith(new IllegalStateException("audit unavailable"));
        WorldItemSpawnIndeterminateException unresolved = assertThrows(
                WorldItemSpawnIndeterminateException.class, fixture::dropOne);

        WorldItemSpawnIndeterminateException escaped = assertThrows(
                WorldItemSpawnIndeterminateException.class, fixture::dropLegacy);

        assertSame(unresolved, escaped);
        assertEquals(1, fixture.world.reserveCalls());
        assertEquals(1, fixture.world.commitCalls());
        assertTrue(fixture.world.snapshots().isEmpty());
        assertEquals(4, fixture.inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void legacyDropUsesCompleteStackSemantics() {
        Fixture fixture = fixture();

        InventoryDropResult result = fixture.dropLegacy();

        assertEquals(InventoryDropResult.Status.DROPPED, result.status());
        assertEquals(0, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(List.of(new ItemStack(DIRT, 4)), fixture.world.snapshots().stream()
                .map(com.overlord.worlditem.api.WorldItemSnapshot::stack)
                .toList());
    }

    @Test
    void legacyDropUsesTheSingleCanonicalSpawnPath() {
        Fixture fixture = fixture();

        fixture.dropLegacy();

        assertEquals(1, fixture.world.reserveCalls());
        assertEquals(1, fixture.world.commitCalls());
        assertEquals(0, fixture.world.rollbackCalls());
        assertEquals(1, fixture.world.snapshots().size());
        assertEquals(fixture.world.lastReservation().itemId(),
                fixture.world.snapshots().get(0).id());
    }

    private static void assertConserved(Fixture fixture) {
        assertEquals(3, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(1, fixture.world.snapshots().size());
        assertEquals(new ItemStack(DIRT, 1), fixture.world.snapshots().get(0).stack());
    }

    private static void assertRollbackFailureRetainsBarrier(Throwable cleanupFailure) {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        fixture.world.failReserveWith(primary);
        fixture.inventory.failNextRollbackWith(cleanupFailure);

        assertSame(primary, assertThrows(IllegalStateException.class, fixture::dropOne));

        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(List.of(cleanupFailure), List.of(primary.getSuppressed()));
        assertEquals(ReservationTerminalState.PENDING,
                fixture.inventory.reservationAudit(fixture.inventoryReservationId())
                        .orElseThrow().state());
    }

    private static void assertRollbackResultRetainsBarrier(
            InventoryReservationResult.Status status, boolean mismatchedIdentity) {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("reserve failed");
        fixture.world.failReserveWith(primary);
        if (mismatchedIdentity) {
            fixture.inventory.returnNextRollbackWithMismatchedIdentity();
        } else {
            fixture.inventory.returnNextRollbackAs(status);
        }

        assertSame(primary, assertThrows(IllegalStateException.class, fixture::dropOne));

        assertTrue(fixture.controller.hasUnresolvedTransaction());
        assertEquals(ReservationTerminalState.PENDING,
                fixture.inventory.reservationAudit(fixture.inventoryReservationId())
                        .orElseThrow().state());
    }

    private static Fixture fixture() {
        ItemFormDefinition dirt = new ItemFormDefinition(DIRT, 64, false, false);
        BodyInventoryService canonicalInventory = new BodyInventoryService(
                OWNER, id -> Optional.ofNullable(Map.of(DIRT, dirt).get(id)), ignored -> {});
        FaultInjectingInventoryService inventory =
                new FaultInjectingInventoryService(canonicalInventory);
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        FaultInjectingWorldItemService world = new FaultInjectingWorldItemService();
        List<Throwable> diagnostics = new ArrayList<>();
        InventoryDropController controller = new InventoryDropController(
                inventory, world, diagnostics::add);
        return new Fixture(inventory, world, controller, diagnostics);
    }

    private record Fixture(
            FaultInjectingInventoryService inventory,
            FaultInjectingWorldItemService world,
            InventoryDropController controller,
            List<Throwable> diagnostics) {
        private InventoryDropResult dropOne() {
            return controller.drop(
                    OWNER, BodySlot.LEFT_HAND, InventoryDropAmount.ONE,
                    1, 2, 3, 4.5, 1.25, 0, 12);
        }

        private InventoryDropResult dropLegacy() {
            return controller.drop(
                    OWNER, BodySlot.LEFT_HAND,
                    1, 2, 3, 4.5, 1.25, 0, 12);
        }

        private com.overlord.inventory.api.InventoryReservationId inventoryReservationId() {
            return inventory.rollbackReservationIds().isEmpty()
                    ? new com.overlord.inventory.api.InventoryReservationId(0L)
                    : inventory.rollbackReservationIds().get(0);
        }
    }
}
