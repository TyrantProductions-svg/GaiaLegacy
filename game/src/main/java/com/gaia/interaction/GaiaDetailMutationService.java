package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.DetailMutationRequest;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.DetailToFullRequest;
import com.overlord.interaction.api.FullToDetailRequest;
import com.overlord.interaction.api.InteractionContext;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.voxel.ChunkDetailMutation;
import com.overlord.voxel.ChunkDetailMutationOutcome;
import com.overlord.voxel.ChunkRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GaiaDetailMutationService
        implements DetailMutationService {
    private final MainThreadGuard mainThreadGuard;
    private final BlockRegistry blocks;
    private final ChunkRepository chunks;

    public GaiaDetailMutationService(
            MainThreadGuard mainThreadGuard,
            BlockRegistry blocks,
            ChunkRepository chunks) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    @Override
    public DetailMutationResult convertFullToDetail(
            FullToDetailRequest request) {
        mainThreadGuard.assertMainThread("detail mutation");
        Objects.requireNonNull(request, "request");
        Optional<Byte> expectedId =
                storedId(request.expectedFullBlock());
        if (expectedId.isEmpty()) {
            return unknownMaterial(request.context());
        }
        return map(
                request.context(),
                chunks.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedChunkRevision(),
                                expectedId.orElseThrow())));
    }

    @Override
    public DetailMutationResult setSubVoxel(
            DetailMutationRequest request) {
        mainThreadGuard.assertMainThread("detail mutation");
        Objects.requireNonNull(request, "request");
        byte replacementId = 0;
        if (request.replacementBlock().isPresent()) {
            Optional<Byte> resolved =
                    storedId(request.replacementBlock().orElseThrow());
            if (resolved.isEmpty()) {
                return unknownMaterial(request.context());
            }
            replacementId = resolved.orElseThrow();
        }
        return map(
                request.context(),
                chunks.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedChunkRevision(),
                                request.expectedState(),
                                request.position(),
                                replacementId)));
    }

    @Override
    public DetailMutationResult removeDetailParent(
            RemoveDetailParentRequest request) {
        mainThreadGuard.assertMainThread("detail mutation");
        Objects.requireNonNull(request, "request");
        return map(
                request.context(),
                chunks.mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedChunkRevision(),
                                request.expectedState())));
    }

    @Override
    public DetailMutationResult sculptParentSubVoxel(
            SculptParentSubVoxelRequest request) {
        mainThreadGuard.assertMainThread("detail mutation");
        Objects.requireNonNull(request, "request");
        byte replacementId = 0;
        if (request.replacementBlock().isPresent()) {
            Optional<Byte> resolved =
                    storedId(request.replacementBlock().orElseThrow());
            if (resolved.isEmpty() || resolved.orElseThrow() == 0) {
                return unknownMaterial(request.context());
            }
            replacementId = resolved.orElseThrow();
        }
        return map(
                request.context(),
                chunks.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedChunkRevision(),
                                request.expectedState(),
                                request.position(),
                                replacementId)));
    }

    @Override
    public DetailMutationResult compactDetailToFull(
            DetailToFullRequest request) {
        mainThreadGuard.assertMainThread("detail mutation");
        Objects.requireNonNull(request, "request");
        Optional<Byte> replacementId =
                storedId(request.replacementFullBlock());
        if (replacementId.isEmpty()) {
            return unknownMaterial(request.context());
        }
        return map(
                request.context(),
                chunks.mutateDetail(
                        new ChunkDetailMutation.CompactDetailToFull(
                                request.x(),
                                request.y(),
                                request.z(),
                                request.expectedChunkRevision(),
                                request.expectedState(),
                                replacementId.orElseThrow())));
    }

    private Optional<Byte> storedId(ResourceLocation name) {
        return blocks.find(name)
                .map(definition -> (byte) definition.id());
    }

    private static DetailMutationResult map(
            InteractionContext context,
            ChunkDetailMutationOutcome outcome) {
        return new DetailMutationResult(
                context,
                DetailMutationResult.Status.valueOf(
                        outcome.status().name()),
                outcome.oldState(),
                outcome.newState(),
                outcome.observedChunkRevision(),
                outcome.resultingChunkRevision(),
                outcome.dirtiedChunks());
    }

    private static DetailMutationResult unknownMaterial(
            InteractionContext context) {
        return new DetailMutationResult(
                context,
                DetailMutationResult.Status.UNKNOWN_MATERIAL,
                Optional.empty(),
                Optional.empty(),
                0L,
                0L,
                List.of());
    }
}
