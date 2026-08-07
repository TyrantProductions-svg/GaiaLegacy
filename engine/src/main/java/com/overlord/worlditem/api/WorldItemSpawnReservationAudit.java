package com.overlord.worlditem.api;

import java.util.Optional;

/** Read-only exceptional-diagnosis view that never completes a spawn reservation. */
public interface WorldItemSpawnReservationAudit {
    Optional<WorldItemSpawnReservationAuditSnapshot> spawnReservationAudit(
            WorldItemSpawnReservationId reservationId);
}
