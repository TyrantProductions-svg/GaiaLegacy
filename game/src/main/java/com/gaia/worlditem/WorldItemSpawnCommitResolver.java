package com.gaia.worlditem;

import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnIdentity;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitException;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservationAudit;
import com.overlord.worlditem.api.WorldItemSpawnReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import java.util.Objects;
import java.util.Optional;

/** Resolves one canonical spawn reservation without allocating a replacement stable ID. */
public final class WorldItemSpawnCommitResolver {
    private final WorldItemSpawnReservations spawns;
    private final WorldItemSpawnReservationAudit audit;

    public WorldItemSpawnCommitResolver(WorldItemService worldItems) {
        Objects.requireNonNull(worldItems, "worldItems");
        if (!(worldItems instanceof WorldItemSpawnReservations reservations)
                || !(worldItems instanceof WorldItemSpawnReservationAudit reservationAudit)) {
            throw new IllegalArgumentException(
                    "world-item service must expose spawn reservations and read-only audit");
        }
        spawns = reservations;
        audit = reservationAudit;
    }

    public Resolution commit(WorldItemSpawnReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        try {
            return fromResult(reservation, spawns.commitSpawn(reservation.id()), null);
        } catch (WorldItemSpawnCommitException failure) {
            if (!failure.reservationId().equals(reservation.id())) {
                return unresolved(reservation, failure, fatalCause(failure));
            }
            if (failure.stateChangeApplied()) {
                return appliedSnapshot(reservation, failure, fatalCause(failure));
            }
            return auditAndMaybeComplete(reservation, failure, fatalCause(failure), true);
        } catch (RuntimeException | Error failure) {
            return auditAndMaybeComplete(
                    reservation,
                    failure,
                    failure instanceof Error error ? error : null,
                    true);
        }
    }

    /** Shutdown resolution starts with audit and commits a proven pending reservation once. */
    public Resolution resolve(WorldItemSpawnReservation reservation, Throwable primary) {
        Objects.requireNonNull(reservation, "reservation");
        return auditAndMaybeComplete(
                reservation,
                Objects.requireNonNull(primary, "primary"),
                primary instanceof Error error ? error : fatalCause(primary),
                true);
    }

    /** Resolves cleanup only when the exact reservation is proven rolled back. */
    public Resolution rollback(WorldItemSpawnReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        try {
            WorldItemSpawnCommitResult result = spawns.rollbackSpawn(reservation.id());
            if ((result.status() == WorldItemSpawnCommitResult.Status.ROLLED_BACK
                            || result.status()
                                    == WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK)
                    && result.reservation().isPresent()
                    && result.reservation().orElseThrow().equals(reservation)) {
                return new Resolution(
                        Status.ROLLED_BACK,
                        reservation,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
            Throwable failure = new IllegalStateException(
                    "spawn reservation rollback is not proven for the exact identity: "
                            + result.status());
            return unresolved(reservation, failure, null);
        } catch (RuntimeException | Error failure) {
            return unresolved(
                    reservation,
                    failure,
                    failure instanceof Error error ? error : null);
        }
    }

    private Resolution auditAndMaybeComplete(
            WorldItemSpawnReservation reservation,
            Throwable primary,
            Error fatalError,
            boolean mayCompletePending) {
        Optional<WorldItemSpawnReservationAuditSnapshot> audited;
        try {
            audited = audit.spawnReservationAudit(reservation.id());
        } catch (RuntimeException | Error auditFailure) {
            suppress(primary, auditFailure);
            Error fatal = fatalError != null
                    ? fatalError
                    : auditFailure instanceof Error error ? error : null;
            return unresolved(reservation, primary, fatal);
        }
        if (audited.isEmpty()
                || !audited.orElseThrow().reservation().equals(reservation)) {
            return unresolved(reservation, primary, fatalError);
        }
        ReservationTerminalState state = audited.orElseThrow().state();
        if (state == ReservationTerminalState.COMMITTED) {
            return appliedAudit(
                    reservation, audited.orElseThrow(), primary, fatalError);
        }
        if (state == ReservationTerminalState.ROLLED_BACK) {
            return new Resolution(
                    Status.ROLLED_BACK,
                    reservation,
                    Optional.empty(),
                    Optional.of(primary),
                    Optional.ofNullable(fatalError));
        }
        if (!mayCompletePending) {
            return unresolved(reservation, primary, fatalError);
        }
        try {
            return fromResult(
                    reservation, spawns.commitSpawn(reservation.id()), primary, fatalError);
        } catch (WorldItemSpawnCommitException retryFailure) {
            suppress(primary, retryFailure);
            Error fatal = fatalError != null ? fatalError : fatalCause(retryFailure);
            if (retryFailure.reservationId().equals(reservation.id())
                    && retryFailure.stateChangeApplied()) {
                return appliedSnapshot(reservation, primary, fatal);
            }
            return auditFinal(reservation, primary, fatal);
        } catch (RuntimeException | Error retryFailure) {
            suppress(primary, retryFailure);
            Error fatal = fatalError != null
                    ? fatalError
                    : retryFailure instanceof Error error ? error : null;
            return auditFinal(reservation, primary, fatal);
        }
    }

    private Resolution auditFinal(
            WorldItemSpawnReservation reservation,
            Throwable primary,
            Error fatalError) {
        Optional<WorldItemSpawnReservationAuditSnapshot> audited;
        try {
            audited = audit.spawnReservationAudit(reservation.id());
        } catch (RuntimeException | Error auditFailure) {
            suppress(primary, auditFailure);
            Error fatal = fatalError != null
                    ? fatalError
                    : auditFailure instanceof Error error ? error : null;
            return unresolved(reservation, primary, fatal);
        }
        if (audited.isPresent() && audited.orElseThrow().reservation().equals(reservation)) {
            if (audited.orElseThrow().state() == ReservationTerminalState.COMMITTED) {
                return appliedAudit(
                        reservation, audited.orElseThrow(), primary, fatalError);
            }
            if (audited.orElseThrow().state() == ReservationTerminalState.ROLLED_BACK) {
                return new Resolution(
                        Status.ROLLED_BACK,
                        reservation,
                        Optional.empty(),
                        Optional.of(primary),
                        Optional.ofNullable(fatalError));
            }
        }
        return unresolved(reservation, primary, fatalError);
    }

    private Resolution fromResult(
            WorldItemSpawnReservation reservation,
            WorldItemSpawnCommitResult result,
            Throwable diagnostic) {
        return fromResult(reservation, result, diagnostic, fatalCause(diagnostic));
    }

    private Resolution fromResult(
            WorldItemSpawnReservation reservation,
            WorldItemSpawnCommitResult result,
            Throwable diagnostic,
            Error fatalError) {
        Objects.requireNonNull(result, "result");
        if ((result.status() == WorldItemSpawnCommitResult.Status.COMMITTED
                        || result.status() == WorldItemSpawnCommitResult.Status.ALREADY_COMMITTED)
                && result.reservation().orElseThrow().equals(reservation)) {
            return new Resolution(
                    Status.APPLIED,
                    reservation,
                    result.runtime(),
                    Optional.ofNullable(diagnostic),
                    Optional.ofNullable(fatalError));
        }
        Throwable failure = combine(diagnostic, new IllegalStateException(
                "reserved world-item spawn did not commit: " + result.status()));
        if (result.status() == WorldItemSpawnCommitResult.Status.ROLLED_BACK
                || result.status() == WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK) {
            return new Resolution(
                    Status.ROLLED_BACK,
                    reservation,
                    Optional.empty(),
                    Optional.of(failure),
                    Optional.ofNullable(fatalError));
        }
        return auditAndMaybeComplete(reservation, failure, fatalError, false);
    }

    private Resolution appliedSnapshot(
            WorldItemSpawnReservation reservation,
            Throwable diagnostic,
            Error fatalError) {
        Optional<WorldItemSpawnReservationAuditSnapshot> audited;
        try {
            audited = audit.spawnReservationAudit(reservation.id());
        } catch (RuntimeException | Error auditFailure) {
            suppress(diagnostic, auditFailure);
            Error fatal = fatalError != null
                    ? fatalError
                    : auditFailure instanceof Error error ? error : null;
            return unresolved(reservation, diagnostic, fatal);
        }
        if (audited.isEmpty()
                || !audited.orElseThrow().reservation().equals(reservation)
                || audited.orElseThrow().state() != ReservationTerminalState.COMMITTED) {
            Throwable failure = combine(diagnostic, new IllegalStateException(
                    "applied spawn is not confirmed by its exact committed audit"));
            return unresolved(reservation, failure, fatalError);
        }
        return appliedAudit(
                reservation, audited.orElseThrow(), diagnostic, fatalError);
    }

    private static Resolution appliedAudit(
            WorldItemSpawnReservation reservation,
            WorldItemSpawnReservationAuditSnapshot audited,
            Throwable diagnostic,
            Error fatalError) {
        if (!audited.reservation().equals(reservation)
                || audited.state() != ReservationTerminalState.COMMITTED
                || audited.runtime().isEmpty()) {
            Throwable failure = combine(diagnostic, new IllegalStateException(
                    "committed spawn audit does not match its reservation identity"));
            return unresolved(reservation, failure, fatalError);
        }
        return new Resolution(
                Status.APPLIED,
                reservation,
                audited.runtime(),
                Optional.ofNullable(diagnostic),
                Optional.ofNullable(fatalError));
    }

    private static Resolution unresolved(
            WorldItemSpawnReservation reservation,
            Throwable diagnostic,
            Error fatalError) {
        return new Resolution(
                Status.UNRESOLVED,
                reservation,
                Optional.empty(),
                Optional.of(diagnostic),
                Optional.ofNullable(fatalError));
    }

    private static Throwable combine(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        suppress(primary, additional);
        return primary;
    }

    private static void suppress(Throwable primary, Throwable additional) {
        if (primary != null && primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private static Error fatalCause(Throwable failure) {
        if (failure instanceof Error error) {
            return error;
        }
        return failure != null && failure.getCause() instanceof Error error ? error : null;
    }

    public enum Status {
        APPLIED,
        ROLLED_BACK,
        UNRESOLVED
    }

    public record Resolution(
            Status status,
            WorldItemSpawnReservation reservation,
            Optional<WorldItemRuntimeSnapshot> runtime,
            Optional<Throwable> diagnostic,
            Optional<Error> fatalError) {
        public Resolution {
            status = Objects.requireNonNull(status, "status");
            reservation = Objects.requireNonNull(reservation, "reservation");
            runtime = Objects.requireNonNull(runtime, "runtime");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            fatalError = Objects.requireNonNull(fatalError, "fatalError");
            if (status == Status.APPLIED && runtime.isEmpty()) {
                throw new IllegalArgumentException("APPLIED requires the committed item");
            }
            if (status != Status.APPLIED && runtime.isPresent()) {
                throw new IllegalArgumentException("non-applied resolution cannot contain item");
            }
            if (runtime.isPresent()) {
                WorldItemSpawnIdentity.requireRuntimeMatchesReservation(
                        reservation, runtime.orElseThrow());
            }
        }

        public Optional<WorldItemSnapshot> item() {
            return runtime.map(WorldItemRuntimeSnapshot::item);
        }
    }
}
