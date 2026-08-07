package com.gaia.interaction;

import com.gaia.inventory.InventoryDropLocation;
import com.gaia.inventory.WorldItemSpawnIndeterminateException;
import com.gaia.worlditem.WorldItemSpawnCommitResolver;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Resolution;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Status;
import com.gaia.worlditem.WorldItemDropKinematics;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.PhysicsBody;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.joml.Vector3f;

public final class BlockBreakTransaction implements AutoCloseable {
    private final WorldMutationService mutations;
    private final EntityRef owner;
    private final WorldItemSpawnReservations worldItemSpawns;
    private final WorldItemSpawnCommitResolver spawnCommitResolver;
    private final Optional<PhysicsBody> playerBody;
    private final ResourceLocation air;
    private final Consumer<Throwable> fatalDiagnostic;
    private boolean closed;
    private UnresolvedBreak unresolved;

    public BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            ResourceLocation air) {
        this(mutations, inventory, owner, worldItems, Optional.empty(), air, failure -> {});
    }

    public BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            ResourceLocation air,
            Consumer<Throwable> fatalDiagnostic) {
        this(mutations, inventory, owner, worldItems, Optional.empty(), air, fatalDiagnostic);
    }

    public BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            PhysicsBody playerBody,
            ResourceLocation air) {
        this(mutations, inventory, owner, worldItems,
                Optional.of(Objects.requireNonNull(playerBody, "playerBody")), air,
                failure -> {});
    }

    public BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            PhysicsBody playerBody,
            ResourceLocation air,
            Consumer<Throwable> fatalDiagnostic) {
        this(mutations, inventory, owner, worldItems,
                Optional.of(Objects.requireNonNull(playerBody, "playerBody")), air,
                fatalDiagnostic);
    }

    private BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            Optional<PhysicsBody> playerBody,
            ResourceLocation air,
            Consumer<Throwable> fatalDiagnostic) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldItems, "worldItems");
        if (!(worldItems instanceof WorldItemSpawnReservations spawnReservations)) {
            throw new IllegalArgumentException(
                    "the unique WorldItemService must also reserve future spawn capacity");
        }
        worldItemSpawns = spawnReservations;
        spawnCommitResolver = new WorldItemSpawnCommitResolver(worldItems);
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.air = Objects.requireNonNull(air, "air");
        this.fatalDiagnostic = Objects.requireNonNull(fatalDiagnostic, "fatalDiagnostic");
    }

    public BlockBreakResult execute(
            BlockHitResult target,
            Optional<ItemStack> drop,
            BodySlot preferredSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        if (unresolved != null) {
            throw unresolved.failure;
        }
        if (closed) {
            throw new IllegalStateException("block-break transaction is closed");
        }
        int produced = drop.map(ItemStack::count).orElse(0);
        Optional<WorldItemSpawnReservation> worldReservation = Optional.empty();

        if (drop.isPresent()) {
            Vector3f playerPosition = playerBody
                    .map(body -> body.position(new Vector3f()))
                    .orElseGet(() -> new Vector3f(
                            target.blockX() + 0.5f,
                            target.blockY() + 0.5f,
                            target.blockZ() + 0.5f));
            long eventIdentity = eventIdentity(target, tick, timestampNanos);
            InventoryDropLocation location = WorldItemDropKinematics.blockDrop(
                    target, playerPosition, eventIdentity);
            WorldItemSpawnRequest spawnRequest = new WorldItemSpawnRequest(
                    drop.orElseThrow(),
                    location.positionX(), location.positionY(), location.positionZ(),
                    location.velocityX(), location.velocityY(), location.velocityZ(),
                    Optional.of(owner),
                    tick);
            WorldItemSpawnReserveResult reserve =
                    worldItemSpawns.reserveSpawn(spawnRequest);
            if (reserve.status() != WorldItemSpawnReserveResult.Status.RESERVED) {
                return rejected(
                        BlockBreakResult.Status.RESERVATION_REJECTED,
                        produced, Optional.empty(), Optional.empty());
            }
            worldReservation = reserve.reservation();
        }

        BlockChangeRequest request = new BlockChangeRequest(
                new GaiaInteractionContext(
                        owner, preferredSlot, InteractionAction.PRIMARY,
                        tick, timestampNanos),
                target.blockX(), target.blockY(), target.blockZ(),
                target.block(), air);
        Optional<BlockChangeResult> mutation = Optional.empty();
        Optional<Throwable> notificationFailure = Optional.empty();
        try {
            BlockChangeResult result = mutations.changeBlock(request);
            mutation = Optional.of(result);
            if (result.status() != BlockChangeResult.Status.APPLIED) {
                requireProvenRollback(
                        worldReservation,
                        new IllegalStateException(
                                "block mutation was rejected: " + result.status()));
                return rejected(
                        BlockBreakResult.Status.MUTATION_REJECTED,
                        produced, mutation, Optional.empty());
            }
        } catch (BlockChangeDispatchException failure) {
            if (!failure.mutationApplied()) {
                requireProvenRollback(worldReservation, failure);
                return rejected(
                        BlockBreakResult.Status.MUTATION_REJECTED,
                        produced, Optional.empty(), Optional.of(failure));
            }
            notificationFailure = Optional.of(failure);
        }

        CommitWorldOutcome worldOutcome = commitWorld(worldReservation);
        Throwable diagnostic = combine(
                notificationFailure.orElse(null), worldOutcome.diagnostic.orElse(null));
        if (worldOutcome.fatalError.isPresent()) {
            Error fatal = worldOutcome.fatalError.orElseThrow();
            if (diagnostic != null && diagnostic != fatal) {
                suppress(fatal, diagnostic);
            }
            reportFatalPreserving(diagnostic == null ? fatal : diagnostic, fatal);
            throw fatal;
        }
        return new BlockBreakResult(
                diagnostic != null
                        ? BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : BlockBreakResult.Status.APPLIED,
                mutation,
                produced,
                0,
                worldOutcome.worldCommitted,
                Optional.ofNullable(diagnostic));
    }

    private CommitWorldOutcome commitWorld(
            Optional<WorldItemSpawnReservation> worldReservation) {
        if (worldReservation.isEmpty()) {
            return new CommitWorldOutcome(0, Optional.empty(), Optional.empty());
        }
        WorldItemSpawnReservation reservation = worldReservation.orElseThrow();
        Resolution resolution = spawnCommitResolver.commit(reservation);
        if (resolution.status() != Status.APPLIED) {
            Throwable primary = resolution.diagnostic().orElseGet(() ->
                    new IllegalStateException("canonical block-drop spawn is unresolved"));
            throw registerUnresolved(
                    reservation, ResolutionIntent.COMMIT, primary,
                    resolution.fatalError().orElse(null));
        }
        return new CommitWorldOutcome(
                reservation.request().stack().count(),
                resolution.diagnostic(),
                resolution.fatalError());
    }

    private void requireProvenRollback(
            Optional<WorldItemSpawnReservation> worldReservation, Throwable primary) {
        if (worldReservation.isEmpty()) {
            return;
        }
        WorldItemSpawnReservation reservation = worldReservation.orElseThrow();
        Resolution resolution = spawnCommitResolver.rollback(reservation);
        if (resolution.status() == Status.ROLLED_BACK) {
            return;
        }
        Throwable diagnostic = resolution.diagnostic().orElse(primary);
        if (diagnostic != primary
                && resolution.fatalError().orElse(null) != diagnostic) {
            suppress(primary, diagnostic);
        }
        throw registerUnresolved(
                reservation,
                ResolutionIntent.ROLLBACK,
                primary,
                resolution.fatalError().orElse(null));
    }

    public boolean hasUnresolvedTransaction() {
        return unresolved != null;
    }

    @Override
    public void close() {
        closed = true;
        if (unresolved == null) {
            return;
        }
        UnresolvedBreak pending = unresolved;
        Resolution resolution = pending.intent == ResolutionIntent.COMMIT
                ? spawnCommitResolver.resolve(
                        pending.reservation, pending.failure.getCause())
                : spawnCommitResolver.rollback(pending.reservation);
        Status required = pending.intent == ResolutionIntent.COMMIT
                ? Status.APPLIED
                : Status.ROLLED_BACK;
        if (resolution.status() != required) {
            if (resolution.fatalError().isPresent()) {
                Error fatal = resolution.fatalError().orElseThrow();
                suppress(fatal, pending.failure);
                reportFatalPreserving(pending.failure, fatal);
                throw fatal;
            }
            throw pending.failure;
        }
        unresolved = null;
        if (resolution.fatalError().isPresent()) {
            throw resolution.fatalError().orElseThrow();
        }
    }

    private WorldItemSpawnIndeterminateException registerUnresolved(
            WorldItemSpawnReservation reservation,
            ResolutionIntent intent,
            Throwable primary,
            Error fatalError) {
        WorldItemSpawnIndeterminateException failure =
                new WorldItemSpawnIndeterminateException(
                        intent == ResolutionIntent.COMMIT
                                ? "committed block mutation has an unresolved canonical loot spawn"
                                : "rejected block mutation has an unresolved canonical loot rollback",
                        primary,
                        Optional.empty(),
                        reservation,
                        0);
        unresolved = new UnresolvedBreak(reservation, intent, failure);
        if (fatalError != null) {
            suppress(fatalError, failure);
            reportFatalPreserving(failure, fatalError);
            throw fatalError;
        }
        try {
            fatalDiagnostic.accept(failure);
        } catch (RuntimeException | Error reportingFailure) {
            suppress(primary, reportingFailure);
        }
        return failure;
    }

    private void reportFatalPreserving(Throwable diagnostic, Error fatal) {
        try {
            fatalDiagnostic.accept(diagnostic);
        } catch (RuntimeException | Error reportingFailure) {
            suppress(fatal, reportingFailure);
        }
    }

    private static Throwable combine(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional != null) {
            suppress(primary, additional);
        }
        return primary;
    }

    private static void suppress(Throwable primary, Throwable additional) {
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private static BlockBreakResult rejected(
            BlockBreakResult.Status status,
            int produced,
            Optional<BlockChangeResult> mutation,
            Optional<Throwable> failure) {
        return new BlockBreakResult(
                status, mutation, produced, 0, 0, failure);
    }

    private static long eventIdentity(
            BlockHitResult target, long tick, long timestampNanos) {
        long coordinates = ((long) target.blockX() * 0x9E3779B97F4A7C15L)
                ^ ((long) target.blockY() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) target.blockZ() * 0x165667B19E3779F9L);
        return coordinates ^ Long.rotateLeft(tick, 17) ^ timestampNanos;
    }

    private record CommitWorldOutcome(
            int worldCommitted,
            Optional<Throwable> diagnostic,
            Optional<Error> fatalError) {}

    private record UnresolvedBreak(
            WorldItemSpawnReservation reservation,
            ResolutionIntent intent,
            WorldItemSpawnIndeterminateException failure) {}

    private enum ResolutionIntent {
        COMMIT,
        ROLLBACK
    }
}
