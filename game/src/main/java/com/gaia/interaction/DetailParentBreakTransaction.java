package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.InventoryDropLocation;
import com.gaia.inventory.WorldItemSpawnIndeterminateException;
import com.gaia.worlditem.WorldItemDropKinematics;
import com.gaia.worlditem.WorldItemSpawnCommitResolver;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Resolution;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Status;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationEventDispatchException;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import com.overlord.voxel.DetailCellState;
import java.util.Objects;
import java.util.Optional;

public final class DetailParentBreakTransaction implements AutoCloseable {
    private final DetailMutationService mutations;
    private final EntityRef owner;
    private final Optional<BlockRegistry> blocks;
    private final Optional<DetailActionPolicy> policy;
    private final Optional<WorldItemSpawnReservations> worldItemSpawns;
    private final Optional<WorldItemSpawnCommitResolver> spawnResolver;
    private boolean closed;
    private UnresolvedSpawn unresolved;

    public DetailParentBreakTransaction(
            DetailMutationService mutations,
            EntityRef owner) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.owner = Objects.requireNonNull(owner, "owner");
        blocks = Optional.empty();
        policy = Optional.empty();
        worldItemSpawns = Optional.empty();
        spawnResolver = Optional.empty();
    }

    public DetailParentBreakTransaction(
            DetailMutationService mutations,
            EntityRef owner,
            BlockRegistry blocks,
            DetailActionPolicy policy,
            WorldItemService worldItems) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.blocks = Optional.of(Objects.requireNonNull(blocks, "blocks"));
        this.policy = Optional.of(Objects.requireNonNull(policy, "policy"));
        Objects.requireNonNull(worldItems, "worldItems");
        if (!(worldItems instanceof WorldItemSpawnReservations reservations)) {
            throw new IllegalArgumentException(
                    "the unique WorldItemService must reserve future spawn capacity");
        }
        worldItemSpawns = Optional.of(reservations);
        spawnResolver = Optional.of(new WorldItemSpawnCommitResolver(worldItems));
    }

    public DetailParentBreakResult executeCreative(
            BlockHitResult target,
            DetailCellState expectedState,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(activeSlot, "activeSlot");
        requireAvailable();
        if (!(target.target() instanceof DetailRaycastTarget)
                || target.chunkRevision() <= 0L) {
            return new DetailParentBreakResult(
                    DetailParentBreakResult.Status.INVALID_TARGET,
                    Optional.empty(),
                    0);
        }
        DetailMutationResult mutation = mutations.removeDetailParent(
                new RemoveDetailParentRequest(
                        new GaiaInteractionContext(
                                owner,
                                activeSlot,
                                InteractionAction.PRIMARY,
                                tick,
                                timestampNanos),
                        target.blockX(),
                        target.blockY(),
                        target.blockZ(),
                        target.chunkRevision(),
                        expectedState));
        return new DetailParentBreakResult(
                mutation.status() == DetailMutationResult.Status.APPLIED
                        ? DetailParentBreakResult.Status.APPLIED
                        : DetailParentBreakResult.Status.MUTATION_REJECTED,
                Optional.of(mutation),
                0);
    }

    public DetailParentBreakResult executeSurvival(
            BlockHitResult target,
            DetailCellState expectedState,
            Optional<ResourceLocation> activeItem,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expectedState, "expectedState");
        activeItem = Objects.requireNonNull(activeItem, "activeItem");
        Objects.requireNonNull(activeSlot, "activeSlot");
        requireOperational();
        if (!(target.target() instanceof DetailRaycastTarget)
                || target.chunkRevision() <= 0L) {
            return result(
                    DetailParentBreakResult.Status.INVALID_TARGET,
                    Optional.empty(), 0, 0, Optional.empty());
        }

        DetailParentComposition composition;
        try {
            composition = DetailParentComposition.fromSupported(
                    expectedState, blocks.orElseThrow());
        } catch (IllegalArgumentException failure) {
            return result(
                    DetailParentBreakResult.Status.ACTION_REJECTED,
                    Optional.empty(), 0, 0, Optional.of(failure));
        }
        DetailActionDecision decision = policy.orElseThrow().decide(
                GameMode.SURVIVAL,
                DetailAction.COARSE_REMOVE,
                activeItem,
                composition.hardestMaterial(),
                composition.fullCompatible());
        if (!decision.allowed()) {
            return result(
                    DetailParentBreakResult.Status.ACTION_REJECTED,
                    Optional.empty(), 0, 0, Optional.empty());
        }

        Optional<ItemStack> output = decision.recoveryKind() == DetailRecoveryKind.FULL_BLOCK
                ? Optional.of(new ItemStack(decision.outputItem().orElseThrow(), 1))
                : Optional.empty();
        Optional<WorldItemSpawnReservation> reservation = reserveOutput(
                target, output, tick, timestampNanos);
        if (output.isPresent() && reservation.isEmpty()) {
            return result(
                    DetailParentBreakResult.Status.RESERVATION_REJECTED,
                    Optional.empty(), 0, 0, Optional.empty());
        }

        DetailMutationResult mutation;
        try {
            mutation = remove(target, expectedState, activeSlot, tick, timestampNanos);
        } catch (DetailMutationEventDispatchException failure) {
            if (!failure.stateChangeApplied()) {
                requireRollback(reservation, failure);
                throw failure;
            }
            return finishApplied(
                    failure.mutation(), reservation, output, Optional.of(failure));
        } catch (RuntimeException failure) {
            requireRollback(reservation, failure);
            throw failure;
        } catch (Error failure) {
            requireRollback(reservation, failure);
            throw failure;
        }
        if (mutation.status() != DetailMutationResult.Status.APPLIED) {
            requireRollback(
                    reservation,
                    new IllegalStateException("detail parent mutation rejected: " + mutation.status()));
            return result(
                    DetailParentBreakResult.Status.MUTATION_REJECTED,
                    Optional.of(mutation), 0,
                    0, Optional.empty());
        }

        return finishApplied(mutation, reservation, output, Optional.empty());
    }

    private DetailParentBreakResult finishApplied(
            DetailMutationResult mutation,
            Optional<WorldItemSpawnReservation> reservation,
            Optional<ItemStack> output,
            Optional<Throwable> initialDiagnostic) {
        Optional<Resolution> committed = commit(reservation);
        Optional<Throwable> diagnostic = mergeDiagnostic(
                initialDiagnostic, committed.flatMap(Resolution::diagnostic));
        Optional<Error> fatalError = committed.flatMap(Resolution::fatalError);
        if (fatalError.isPresent()) {
            throw fatalError.orElseThrow();
        }
        int committedCount = reservation
                .map(value -> value.request().stack().count())
                .orElse(0);
        return result(
                diagnostic.isPresent()
                        ? DetailParentBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : DetailParentBreakResult.Status.APPLIED,
                Optional.of(mutation),
                output.map(ItemStack::count).orElse(0),
                committedCount,
                diagnostic);
    }

    private static Optional<Throwable> mergeDiagnostic(
            Optional<Throwable> primary,
            Optional<Throwable> additional) {
        if (primary.isEmpty()) {
            return additional;
        }
        additional.ifPresent(value -> suppress(primary.orElseThrow(), value));
        return primary;
    }

    private DetailMutationResult remove(
            BlockHitResult target,
            DetailCellState expectedState,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        return mutations.removeDetailParent(new RemoveDetailParentRequest(
                new GaiaInteractionContext(
                        owner, activeSlot, InteractionAction.PRIMARY, tick, timestampNanos),
                target.blockX(), target.blockY(), target.blockZ(),
                target.chunkRevision(), expectedState));
    }

    private Optional<WorldItemSpawnReservation> reserveOutput(
            BlockHitResult target,
            Optional<ItemStack> output,
            long tick,
            long timestampNanos) {
        if (output.isEmpty()) {
            return Optional.empty();
        }
        InventoryDropLocation location = WorldItemDropKinematics.blockDrop(
                target,
                new org.joml.Vector3f(
                        target.blockX() + 0.5f,
                        target.blockY() + 0.5f,
                        target.blockZ() + 0.5f),
                eventIdentity(target, tick, timestampNanos));
        WorldItemSpawnRequest request = new WorldItemSpawnRequest(
                output.orElseThrow(),
                location.positionX(), location.positionY(), location.positionZ(),
                location.velocityX(), location.velocityY(), location.velocityZ(),
                Optional.of(owner), tick);
        WorldItemSpawnReserveResult reserved =
                worldItemSpawns.orElseThrow().reserveSpawn(request);
        return reserved.status() == WorldItemSpawnReserveResult.Status.RESERVED
                ? reserved.reservation()
                : Optional.empty();
    }

    private Optional<Resolution> commit(Optional<WorldItemSpawnReservation> reservation) {
        if (reservation.isEmpty()) {
            return Optional.empty();
        }
        Resolution resolution = spawnResolver.orElseThrow().commit(reservation.orElseThrow());
        if (resolution.status() != Status.APPLIED) {
            throw registerUnresolved(
                    reservation.orElseThrow(), ResolutionIntent.COMMIT,
                    resolution.diagnostic().orElseGet(() ->
                            new IllegalStateException("detail drop commit unresolved")),
                    resolution.fatalError().orElse(null));
        }
        return Optional.of(resolution);
    }

    private void requireRollback(
            Optional<WorldItemSpawnReservation> reservation, Throwable primary) {
        if (reservation.isEmpty()) {
            return;
        }
        Resolution resolution = spawnResolver.orElseThrow().rollback(reservation.orElseThrow());
        if (resolution.status() == Status.ROLLED_BACK) {
            return;
        }
        Throwable diagnostic = resolution.diagnostic().orElse(primary);
        if (diagnostic != primary && primary != null) {
            primary.addSuppressed(diagnostic);
        }
        throw registerUnresolved(
                reservation.orElseThrow(), ResolutionIntent.ROLLBACK,
                primary == null ? diagnostic : primary,
                resolution.fatalError().orElse(null));
    }

    private WorldItemSpawnIndeterminateException registerUnresolved(
            WorldItemSpawnReservation reservation,
            ResolutionIntent intent,
            Throwable cause,
            Error fatalError) {
        WorldItemSpawnIndeterminateException failure =
                new WorldItemSpawnIndeterminateException(
                        "detail parent output reservation is unresolved",
                        cause,
                        Optional.empty(),
                        reservation,
                        0);
        unresolved = new UnresolvedSpawn(reservation, intent, failure);
        if (fatalError != null) {
            suppress(fatalError, failure);
            throw fatalError;
        }
        return failure;
    }

    private void requireOperational() {
        requireAvailable();
        if (blocks.isEmpty() || policy.isEmpty() || worldItemSpawns.isEmpty()) {
            throw new IllegalStateException("Survival detail-parent capabilities are absent");
        }
    }

    private void requireAvailable() {
        if (closed) {
            throw new IllegalStateException("detail-parent break transaction is closed");
        }
        if (unresolved != null) {
            throw unresolved.failure();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (unresolved == null) {
            return;
        }
        UnresolvedSpawn pending = unresolved;
        Resolution resolution = pending.intent() == ResolutionIntent.COMMIT
                ? spawnResolver.orElseThrow().resolve(
                        pending.reservation(), pending.failure().getCause())
                : spawnResolver.orElseThrow().rollback(pending.reservation());
        Status required = pending.intent() == ResolutionIntent.COMMIT
                ? Status.APPLIED
                : Status.ROLLED_BACK;
        if (resolution.status() != required) {
            if (resolution.fatalError().isPresent()) {
                Error fatal = resolution.fatalError().orElseThrow();
                suppress(fatal, pending.failure());
                throw fatal;
            }
            throw pending.failure();
        }
        unresolved = null;
        resolution.fatalError().ifPresent(failure -> { throw failure; });
    }

    private static void suppress(Throwable primary, Throwable additional) {
        if (primary != null && additional != null && primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private static DetailParentBreakResult result(
            DetailParentBreakResult.Status status,
            Optional<DetailMutationResult> mutation,
            int produced,
            int committed,
            Optional<Throwable> diagnostic) {
        return new DetailParentBreakResult(
                status, mutation, produced, committed, diagnostic);
    }

    private static long eventIdentity(
            BlockHitResult target, long tick, long timestampNanos) {
        long coordinates = ((long) target.blockX() * 0x9E3779B97F4A7C15L)
                ^ ((long) target.blockY() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) target.blockZ() * 0x165667B19E3779F9L);
        return coordinates ^ Long.rotateLeft(tick, 17) ^ timestampNanos;
    }

    private record UnresolvedSpawn(
            WorldItemSpawnReservation reservation,
            ResolutionIntent intent,
            WorldItemSpawnIndeterminateException failure) {}

    private enum ResolutionIntent { COMMIT, ROLLBACK }
}
