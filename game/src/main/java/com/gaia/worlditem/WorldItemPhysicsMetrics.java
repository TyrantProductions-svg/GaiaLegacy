package com.gaia.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import java.util.List;

/** Immutable projection lifecycle counters and the latest capacity report. */
public record WorldItemPhysicsMetrics(
        int liveProjections,
        long created,
        long rebuilt,
        long destroyed,
        long appliedWrites,
        long staleRejections,
        long lost,
        long capacitySkipped,
        List<WorldItemId> capacitySkippedIds,
        long recoveryFailures,
        List<WorldItemId> recoveryBlockedIds,
        int active,
        int grounded,
        int sleeping,
        int frozen) {
    public WorldItemPhysicsMetrics {
        capacitySkippedIds = List.copyOf(capacitySkippedIds);
        recoveryBlockedIds = List.copyOf(recoveryBlockedIds);
        if (liveProjections < 0
                || created < 0
                || rebuilt < 0
                || destroyed < 0
                || appliedWrites < 0
                || staleRejections < 0
                || lost < 0
                || capacitySkipped < 0
                || recoveryFailures < 0
                || active < 0
                || grounded < 0
                || sleeping < 0
                || frozen < 0) {
            throw new IllegalArgumentException("projection metrics must be non-negative");
        }
    }

    public WorldItemPhysicsMetrics(
            int liveProjections,
            long created,
            long rebuilt,
            long destroyed,
            long appliedWrites,
            long staleRejections,
            long lost,
            long capacitySkipped,
            List<WorldItemId> capacitySkippedIds,
            int active,
            int grounded,
            int sleeping,
            int frozen) {
        this(liveProjections, created, rebuilt, destroyed, appliedWrites,
                staleRejections, lost, capacitySkipped, capacitySkippedIds,
                0, List.of(), active, grounded, sleeping, frozen);
    }

    public WorldItemPhysicsMetrics(
            int liveProjections,
            long created,
            long rebuilt,
            long destroyed,
            long appliedWrites,
            long staleRejections,
            long lost,
            long capacitySkipped,
            List<WorldItemId> capacitySkippedIds) {
        this(liveProjections, created, rebuilt, destroyed, appliedWrites,
                staleRejections, lost, capacitySkipped, capacitySkippedIds,
                0, List.of(), 0, 0, 0, 0);
    }

    public WorldItemPhysicsMetrics(
            int liveProjections,
            long created,
            long rebuilt,
            long destroyed,
            long appliedWrites,
            long staleRejections) {
        this(
                liveProjections,
                created,
                rebuilt,
                destroyed,
                appliedWrites,
                staleRejections,
                0,
                0,
                List.of(),
                0,
                List.of(),
                0, 0, 0, 0);
    }
}
