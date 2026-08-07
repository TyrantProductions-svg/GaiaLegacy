package com.overlord.worlditem.api;

import java.util.Optional;

/** Exceptional-diagnosis view that never completes or retries a reservation. */
public interface WorldItemReservationAudit {
    Optional<WorldItemReservationAuditSnapshot> reservationAudit(
            WorldItemReservationId reservationId);
}
