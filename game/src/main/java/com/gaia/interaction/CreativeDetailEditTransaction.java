package com.gaia.interaction;

import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import com.overlord.physics.PhysicsBody;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import com.overlord.voxel.ParentCellState;
import java.util.Objects;
import java.util.Optional;

public final class CreativeDetailEditTransaction {
    private final DetailMutationService mutations;
    private final DetailTargetWorldView world;
    private final DetailPlacementCollisionValidator collision;
    private final PhysicsBody playerBody;
    private final EntityRef owner;

    public CreativeDetailEditTransaction(
            DetailMutationService mutations,
            DetailTargetWorldView world,
            DetailPlacementCollisionValidator collision,
            PhysicsBody playerBody,
            EntityRef owner) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.world = Objects.requireNonNull(world, "world");
        this.collision = Objects.requireNonNull(collision, "collision");
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public DetailEditResult executeRemove(
            DetailPrecisionTarget target,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(activeSlot, "activeSlot");
        ParentCellObservationResult observed =
                Objects.requireNonNull(
                        world.observeCell(
                                target.parentX(),
                                target.parentY(),
                                target.parentZ()),
                        "parent observation");
        if (observed.status()
                != com.overlord.voxel.ChunkAvailability.AVAILABLE) {
            return rejected(DetailEditResult.Status.UNAVAILABLE);
        }
        ParentCellObservation parent = observed.observation().orElse(null);
        if (parent == null || !removable(parent.state(), target)) {
            return rejected(DetailEditResult.Status.INVALID_CANDIDATE);
        }
        DetailMutationResult mutation = mutations.sculptParentSubVoxel(
                new SculptParentSubVoxelRequest(
                        context(activeSlot, InteractionAction.PRIMARY, tick, timestampNanos),
                        target.parentX(),
                        target.parentY(),
                        target.parentZ(),
                        target.observedChunkRevision(),
                        parent.state(),
                        target.localPosition(),
                        Optional.empty()));
        return fromMutation(mutation);
    }

    public DetailEditResult executePlace(
            DetailPlacementCandidate candidate,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(activeSlot, "activeSlot");
        if (candidate.status() == DetailPlacementCandidate.Status.UNKNOWN
                || candidate.status() == DetailPlacementCandidate.Status.FAILED
                || candidate.status() == DetailPlacementCandidate.Status.OUT_OF_BOUNDS) {
            return rejected(DetailEditResult.Status.UNAVAILABLE);
        }
        if (!candidate.valid()) {
            return rejected(DetailEditResult.Status.INVALID_CANDIDATE);
        }
        ParentCellObservation destination =
                candidate.destinationObservation().observation().orElse(null);
        if (destination == null) {
            return rejected(DetailEditResult.Status.UNAVAILABLE);
        }
        if (collision.overlapsPlayer(candidate, playerBody)) {
            return rejected(DetailEditResult.Status.PLAYER_INTERSECTION);
        }
        DetailMutationResult mutation = mutations.sculptParentSubVoxel(
                new SculptParentSubVoxelRequest(
                        context(activeSlot, InteractionAction.SECONDARY, tick, timestampNanos),
                        candidate.parentX(),
                        candidate.parentY(),
                        candidate.parentZ(),
                        destination.chunkRevision(),
                        destination.state(),
                        candidate.localPosition(),
                        Optional.of(candidate.material())));
        return fromMutation(mutation);
    }

    private GaiaInteractionContext context(
            BodySlot activeSlot,
            InteractionAction action,
            long tick,
            long timestampNanos) {
        return new GaiaInteractionContext(
                owner, activeSlot, action, tick, timestampNanos);
    }

    private static boolean removable(
            ParentCellState state,
            DetailPrecisionTarget target) {
        if (state instanceof FullCellState full) {
            return full.blockId() != 0;
        }
        return ((DetailCellState) state).occupied(target.localPosition());
    }

    private static DetailEditResult fromMutation(
            DetailMutationResult mutation) {
        return mutation.status() == DetailMutationResult.Status.APPLIED
                ? new DetailEditResult(
                        DetailEditResult.Status.APPLIED,
                        Optional.of(mutation))
                : new DetailEditResult(
                        DetailEditResult.Status.MUTATION_REJECTED,
                        Optional.of(mutation));
    }

    private static DetailEditResult rejected(
            DetailEditResult.Status status) {
        return new DetailEditResult(status, Optional.empty());
    }
}
