package com.overlord.inventory.api;

import java.util.Optional;

/** Exceptional-diagnosis view that never completes or retries a reservation. */
public interface InventoryReservationAudit {
    Optional<InventoryReservationAuditSnapshot> reservationAudit(
            InventoryReservationId reservationId);
}
