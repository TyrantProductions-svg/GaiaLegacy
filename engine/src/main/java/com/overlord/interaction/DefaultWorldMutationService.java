package com.overlord.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BlockMutationReentrancyException;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import com.overlord.interaction.api.WorldMutationService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultWorldMutationService
        implements WorldMutationService {
    private final MainThreadGuard mainThreadGuard;
    private final BlockWorldAccess world;
    private final BlockChangeEventPublisher events;
    private boolean dispatchingBefore;

    public DefaultWorldMutationService(
            MainThreadGuard mainThreadGuard,
            BlockWorldAccess world,
            BlockChangeEventPublisher events) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.world = Objects.requireNonNull(world, "world");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public BlockChangeResult changeBlock(
            BlockChangeRequest request) {
        mainThreadGuard.assertMainThread("world mutation");
        if (dispatchingBefore) {
            throw new BlockMutationReentrancyException(
                    "world mutation is not reentrant during before-change dispatch");
        }
        Objects.requireNonNull(request, "request");

        if (!world.isWithinBounds(
                request.x(), request.y(), request.z())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.OUT_OF_BOUNDS,
                    Optional.empty());
        }
        if (!world.isKnownBlock(request.expectedBlock())
                || !world.isKnownBlock(
                        request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.UNKNOWN_BLOCK,
                    Optional.empty());
        }

        ResourceLocation current =
                Objects.requireNonNull(
                        world.blockAt(
                                request.x(),
                                request.y(),
                                request.z()),
                        "world block");
        if (!current.equals(request.expectedBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(current));
        }
        if (current.equals(request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.NO_CHANGE,
                    Optional.of(current));
        }

        BlockChangeDecision decision;
        dispatchingBefore = true;
        try {
            decision =
                    Objects.requireNonNull(
                            events.beforeChange(
                                    new BeforeBlockChangedEvent(
                                            request, current)),
                            "before-change decision");
        } catch (RuntimeException failure) {
            throw new BlockChangeDispatchException(
                    "before-change event delivery failed",
                    failure,
                    false);
        } finally {
            dispatchingBefore = false;
        }
        if (decision == BlockChangeDecision.CANCEL) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CANCELLED,
                    Optional.of(current));
        }

        ResourceLocation revalidatedCurrent =
                Objects.requireNonNull(
                        world.blockAt(
                                request.x(),
                                request.y(),
                                request.z()),
                        "world block");
        if (!revalidatedCurrent.equals(
                request.expectedBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(revalidatedCurrent));
        }

        BlockWorldMutationOutcome outcome =
                Objects.requireNonNull(
                        world.compareAndSetBlock(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedBlock(),
                                request.replacementBlock()),
                        "world mutation outcome");
        if (outcome.status()
                != BlockWorldMutationOutcome.Status.APPLIED) {
            return rejected(
                    request,
                    mapRejectedStatus(outcome.status()),
                    Optional.of(outcome.observedBlock()));
        }

        ResourceLocation previous = outcome.observedBlock();
        if (!previous.equals(request.expectedBlock())) {
            throw new BlockChangeDispatchException(
                    "applied world mutation reported an inconsistent observed block",
                    new IllegalStateException(
                            "applied outcome observed "
                                    + previous
                                    + " instead of expected "
                                    + request.expectedBlock()),
                    true);
        }

        RuntimeException deliveryFailure = null;
        try {
            events.blockChanged(
                    new BlockChangedEvent(
                            request,
                            previous,
                            request.replacementBlock()));
        } catch (RuntimeException failure) {
            deliveryFailure = failure;
        }
        try {
            events.chunksDirty(
                    new ChunkDirtyEvent(
                            request, outcome.dirtiedChunks()));
        } catch (RuntimeException failure) {
            if (deliveryFailure == null) {
                deliveryFailure = failure;
            } else if (failure != deliveryFailure) {
                deliveryFailure.addSuppressed(failure);
            }
        }
        if (deliveryFailure != null) {
            throw new BlockChangeDispatchException(
                    "post-change event delivery failed",
                    deliveryFailure,
                    true);
        }
        return new BlockChangeResult(
                request,
                BlockChangeResult.Status.APPLIED,
                Optional.of(previous),
                outcome.dirtiedChunks());
    }

    private static BlockChangeResult.Status mapRejectedStatus(
            BlockWorldMutationOutcome.Status status) {
        return switch (status) {
            case NO_CHANGE -> BlockChangeResult.Status.NO_CHANGE;
            case CONFLICT -> BlockChangeResult.Status.CONFLICT;
            case OUT_OF_BOUNDS ->
                    BlockChangeResult.Status.OUT_OF_BOUNDS;
            case APPLIED ->
                    throw new IllegalArgumentException(
                            "APPLIED is not a rejected status");
        };
    }

    private static BlockChangeResult rejected(
            BlockChangeRequest request,
            BlockChangeResult.Status status,
            Optional<ResourceLocation> observed) {
        return new BlockChangeResult(
                request, status, observed, List.of());
    }
}
