package com.overlord.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultWorldMutationService
        implements WorldMutationService {
    private final MainThreadGuard mainThreadGuard;
    private final BlockWorldAccess world;
    private final BlockChangeEventPublisher events;
    private final ChunkDirtyTracker dirtyTracker;

    public DefaultWorldMutationService(
            MainThreadGuard mainThreadGuard,
            BlockWorldAccess world,
            BlockChangeEventPublisher events,
            ChunkDirtyTracker dirtyTracker) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.world = Objects.requireNonNull(world, "world");
        this.events = Objects.requireNonNull(events, "events");
        this.dirtyTracker =
                Objects.requireNonNull(
                        dirtyTracker, "dirtyTracker");
    }

    @Override
    public BlockChangeResult changeBlock(
            BlockChangeRequest request) {
        mainThreadGuard.assertMainThread("world mutation");
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

        if (!world.setBlock(
                request.x(),
                request.y(),
                request.z(),
                request.replacementBlock())) {
            return rejected(
                    request,
                    BlockChangeResult.Status.CONFLICT,
                    Optional.of(current));
        }

        ChunkKey key =
                ChunkKey.fromWorld(request.x(), request.z());
        Set<ChunkKey> dirtyChunks =
                dirtyTracker.affectedByBlock(
                        key,
                        ChunkKey.localCoordinate(request.x()),
                        ChunkKey.localCoordinate(request.z()));
        RuntimeException deliveryFailure = null;
        try {
            events.blockChanged(
                    new BlockChangedEvent(
                            request,
                            current,
                            request.replacementBlock()));
        } catch (RuntimeException failure) {
            deliveryFailure = failure;
        }
        try {
            events.chunksDirty(
                    new ChunkDirtyEvent(request, dirtyChunks));
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
                Optional.of(current),
                dirtyChunks);
    }

    private static BlockChangeResult rejected(
            BlockChangeRequest request,
            BlockChangeResult.Status status,
            Optional<ResourceLocation> observed) {
        return new BlockChangeResult(
                request, status, observed, Set.of());
    }
}
