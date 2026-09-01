package com.gaia.interaction;

import com.gaia.inventory.BodyInventoryReservationPlanner;
import com.gaia.inventory.InventoryReservationBatch;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationEventDispatchException;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread Survival orchestration over canonical detail and inventory authorities. */
public final class SurvivalDetailEditTransaction {
    private final DetailMutationService mutations;
    private final DetailTargetWorldView world;
    private final BodyInventoryReservationPlanner reservations;
    private final InventoryService inventory;
    private final EntityRef owner;
    private final Optional<DetailPlacementCollisionValidator> collision;
    private final Optional<PhysicsBody> playerBody;

    public SurvivalDetailEditTransaction(
            DetailMutationService mutations,
            DetailTargetWorldView world,
            BodyInventoryReservationPlanner reservations,
            InventoryService inventory,
            EntityRef owner) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.world = Objects.requireNonNull(world, "world");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        collision = Optional.empty();
        playerBody = Optional.empty();
    }

    public SurvivalDetailEditTransaction(
            DetailMutationService mutations,
            DetailTargetWorldView world,
            BodyInventoryReservationPlanner reservations,
            InventoryService inventory,
            EntityRef owner,
            DetailPlacementCollisionValidator collision,
            PhysicsBody playerBody) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.world = Objects.requireNonNull(world, "world");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.collision = Optional.of(Objects.requireNonNull(collision, "collision"));
        this.playerBody = Optional.of(Objects.requireNonNull(playerBody, "playerBody"));
    }

    public SurvivalDetailEditResult place(
            DetailPlacementCandidate candidate,
            DetailActionDecision decision,
            BodySlot preferredSlot,
            GaiaInteractionContext context) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        Objects.requireNonNull(context, "context");
        if (!context.actor().equals(owner)) {
            throw new IllegalArgumentException("interaction actor must own the inventory");
        }
        if (!decision.allowed()
                || decision.recoveryKind() != DetailRecoveryKind.DETAIL_UNIT
                || decision.outputItem().isEmpty()) {
            return rejected(SurvivalDetailEditResult.Status.ACTION_REJECTED);
        }
        if (candidate.status() == DetailPlacementCandidate.Status.UNKNOWN
                || candidate.status() == DetailPlacementCandidate.Status.FAILED
                || candidate.status() == DetailPlacementCandidate.Status.OUT_OF_BOUNDS) {
            return rejected(SurvivalDetailEditResult.Status.UNAVAILABLE);
        }
        if (!candidate.valid()) {
            return rejected(SurvivalDetailEditResult.Status.INVALID_CANDIDATE);
        }
        ParentCellObservation destination =
                candidate.destinationObservation().observation().orElse(null);
        if (destination == null) {
            return rejected(SurvivalDetailEditResult.Status.UNAVAILABLE);
        }
        DetailPlacementCollisionValidator collisionValidator = collision.orElseThrow(
                () -> new IllegalStateException("placement collision capability is absent"));
        PhysicsBody body = playerBody.orElseThrow(
                () -> new IllegalStateException("placement player body is absent"));
        if (collisionValidator.overlapsPlayer(candidate, body)) {
            return rejected(SurvivalDetailEditResult.Status.PLAYER_INTERSECTION);
        }

        InventoryReservationBatch held = reservations.reserveExtraction(
                owner,
                preferredSlot,
                new ItemStack(decision.outputItem().orElseThrow(), 1));
        if (held.acceptedCount() != 1 || held.remainder().isPresent()) {
            requireRollback(held, null);
            return rejected(SurvivalDetailEditResult.Status.INVENTORY_ITEM_UNAVAILABLE);
        }

        DetailMutationResult mutation;
        try {
            mutation = mutations.sculptParentSubVoxel(new SculptParentSubVoxelRequest(
                    context,
                    candidate.parentX(),
                    candidate.parentY(),
                    candidate.parentZ(),
                    destination.chunkRevision(),
                    destination.state(),
                    candidate.localPosition(),
                    Optional.of(candidate.material())));
        } catch (DetailMutationEventDispatchException failure) {
            return reconcileAppliedMutationFailure(held, failure, 1, -1);
        } catch (RuntimeException failure) {
            requireRollback(held, failure);
            throw failure;
        } catch (Error failure) {
            requireRollback(held, failure);
            throw failure;
        }
        if (mutation.status() != DetailMutationResult.Status.APPLIED) {
            requireRollback(held, null);
            return new SurvivalDetailEditResult(
                    SurvivalDetailEditResult.Status.MUTATION_REJECTED,
                    Optional.of(mutation),
                    0,
                    0,
                    Optional.empty());
        }

        Optional<Throwable> notificationFailure = commitAll(held);
        return new SurvivalDetailEditResult(
                notificationFailure.isPresent()
                        ? SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : SurvivalDetailEditResult.Status.APPLIED,
                Optional.of(mutation),
                1,
                -1,
                notificationFailure);
    }

    public SurvivalDetailEditResult removeRecoverable(
            DetailPrecisionTarget target,
            DetailActionDecision decision,
            BodySlot preferredSlot,
            GaiaInteractionContext context) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        Objects.requireNonNull(context, "context");
        if (!context.actor().equals(owner)) {
            throw new IllegalArgumentException("interaction actor must own the inventory");
        }
        if (!decision.allowed()
                || decision.recoveryKind() != DetailRecoveryKind.DETAIL_UNIT
                || decision.outputItem().isEmpty()) {
            return rejected(SurvivalDetailEditResult.Status.ACTION_REJECTED);
        }

        ParentCellObservationResult observation = Objects.requireNonNull(
                world.observeCell(target.parentX(), target.parentY(), target.parentZ()),
                "parent observation");
        if (observation.status() != ChunkAvailability.AVAILABLE) {
            return rejected(SurvivalDetailEditResult.Status.UNAVAILABLE);
        }
        ParentCellObservation parent = observation.observation().orElse(null);
        if (parent == null || !removable(parent.state(), target)) {
            return rejected(SurvivalDetailEditResult.Status.INVALID_CANDIDATE);
        }

        InventoryReservationBatch held = reservations.reserveInsertion(
                owner,
                preferredSlot,
                new ItemStack(decision.outputItem().orElseThrow(), 1));
        if (held.acceptedCount() != 1 || held.remainder().isPresent()) {
            requireRollback(held, null);
            return rejected(SurvivalDetailEditResult.Status.INVENTORY_FULL);
        }

        DetailMutationResult mutation;
        try {
            mutation = mutations.sculptParentSubVoxel(new SculptParentSubVoxelRequest(
                    context,
                    target.parentX(),
                    target.parentY(),
                    target.parentZ(),
                    target.observedChunkRevision(),
                    parent.state(),
                    target.localPosition(),
                    Optional.empty()));
        } catch (DetailMutationEventDispatchException failure) {
            return reconcileAppliedMutationFailure(held, failure, -1, 1);
        } catch (RuntimeException failure) {
            requireRollback(held, failure);
            throw failure;
        } catch (Error failure) {
            requireRollback(held, failure);
            throw failure;
        }
        if (mutation.status() != DetailMutationResult.Status.APPLIED) {
            requireRollback(held, null);
            return new SurvivalDetailEditResult(
                    SurvivalDetailEditResult.Status.MUTATION_REJECTED,
                    Optional.of(mutation),
                    0,
                    0,
                    Optional.empty());
        }

        Optional<Throwable> notificationFailure = commitAll(held);
        return new SurvivalDetailEditResult(
                notificationFailure.isPresent()
                        ? SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : SurvivalDetailEditResult.Status.APPLIED,
                Optional.of(mutation),
                -1,
                1,
                notificationFailure);
    }

    private Optional<Throwable> commitAll(InventoryReservationBatch held) {
        Throwable notificationFailure = null;
        for (InventoryReservation reservation : held.reservations()) {
            try {
                InventoryReservationResult committed = inventory.commit(reservation.id());
                if (committed.status() != InventoryReservationResult.Status.COMMITTED) {
                    throw new IllegalStateException(
                            "fresh detail reservation did not commit: " + committed.status());
                }
            } catch (InventoryEventDispatchException failure) {
                if (!failure.stateChangeApplied()) {
                    throw failure;
                }
                if (notificationFailure == null) {
                    notificationFailure = failure;
                } else if (notificationFailure != failure) {
                    notificationFailure.addSuppressed(failure);
                }
            }
        }
        return Optional.ofNullable(notificationFailure);
    }

    private SurvivalDetailEditResult reconcileAppliedMutationFailure(
            InventoryReservationBatch held,
            DetailMutationEventDispatchException failure,
            int occupiedDelta,
            int inventoryDelta) {
        if (!failure.stateChangeApplied()) {
            requireRollback(held, failure);
            throw failure;
        }
        commitAll(held).ifPresent(secondary -> {
            if (secondary != failure) {
                failure.addSuppressed(secondary);
            }
        });
        return new SurvivalDetailEditResult(
                SurvivalDetailEditResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE,
                Optional.of(failure.mutation()),
                occupiedDelta,
                inventoryDelta,
                Optional.of(failure));
    }

    private void requireRollback(InventoryReservationBatch held, Throwable primary) {
        reservations.rollbackReverse(held).ifPresent(rollbackFailure -> {
            if (primary != null && primary != rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
                return;
            }
            throw new IllegalStateException(
                    "detail inventory reservation rollback failed", rollbackFailure);
        });
    }

    private static boolean removable(ParentCellState state, DetailPrecisionTarget target) {
        if (state instanceof FullCellState full) {
            return full.blockId() != 0;
        }
        return ((DetailCellState) state).occupied(target.localPosition());
    }

    private static SurvivalDetailEditResult rejected(SurvivalDetailEditResult.Status status) {
        return new SurvivalDetailEditResult(
                status, Optional.empty(), 0, 0, Optional.empty());
    }
}
