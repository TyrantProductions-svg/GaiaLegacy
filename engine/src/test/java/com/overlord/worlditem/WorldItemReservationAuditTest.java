package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorldItemReservationAuditTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void auditReportsPendingCommittedAndRepeatedCommitWithoutMutating() {
        LogicalWorldItemService service = service();
        var item = service.spawn(request(2)).item().orElseThrow();
        WorldItemReservation reservation = service.reserve(item.id(), 1)
                .reservation().orElseThrow();

        assertEquals(ReservationTerminalState.PENDING,
                service.reservationAudit(reservation.id()).orElseThrow().state());
        assertEquals(2, service.snapshot(item.id()).orElseThrow().stack().count());

        service.commit(reservation.id());
        assertEquals(ReservationTerminalState.COMMITTED,
                service.reservationAudit(reservation.id()).orElseThrow().state());
        assertEquals(1, service.snapshot(item.id()).orElseThrow().stack().count());

        service.commit(reservation.id());
        assertEquals(ReservationTerminalState.COMMITTED,
                service.reservationAudit(reservation.id()).orElseThrow().state());
        assertEquals(1, service.snapshot(item.id()).orElseThrow().stack().count());
    }

    @Test
    void auditReportsRollbackAndUnknownWithoutCompletingAnything() {
        LogicalWorldItemService service = service();
        var item = service.spawn(request(2)).item().orElseThrow();
        WorldItemReservation reservation = service.reserve(item.id(), 1)
                .reservation().orElseThrow();

        service.rollback(reservation.id());

        assertEquals(ReservationTerminalState.ROLLED_BACK,
                service.reservationAudit(reservation.id()).orElseThrow().state());
        assertEquals(2, service.snapshot(item.id()).orElseThrow().stack().count());
        assertTrue(service.reservationAudit(new WorldItemReservationId(999)).isEmpty());
    }

    private static LogicalWorldItemService service() {
        return new LogicalWorldItemService(MainThreadGuard.captureCurrentThread(), 2, 0);
    }

    private static WorldItemSpawnRequest request(int count) {
        return new WorldItemSpawnRequest(new ItemStack(DIRT, count),
                0, 1, 0, 0, 0, 0, Optional.empty(), 0);
    }
}
