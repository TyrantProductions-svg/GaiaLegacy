package com.gaia.interaction;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.BlockWorldAccess;
import com.overlord.interaction.BlockWorldMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMutationOutcome;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.World;
import java.util.Objects;

/** Resource-identity view over the authoritative ChunkRepository mutation path. */
public final class GaiaBlockWorldAccess
        implements BlockWorldAccess, BlockPlacementWorldView {
    private final World world;
    private final ChunkRepository chunks;
    private final BlockRegistry blocks;

    public GaiaBlockWorldAccess(World world, BlockRegistry blocks) {
        this.world = Objects.requireNonNull(world, "world");
        chunks = world.chunks();
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    @Override
    public boolean isWithinBounds(int x, int y, int z) {
        return y >= 0 && y < chunks.worldHeight();
    }

    @Override
    public boolean isKnownBlock(ResourceLocation block) {
        return blocks.find(Objects.requireNonNull(block, "block")).isPresent();
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return isWithinBounds(x, y, z)
                && chunks.snapshot(ChunkKey.fromWorld(x, z)).isPresent();
    }

    @Override
    public ParentCellState parentStateAt(int x, int y, int z) {
        return world.observeCell(x, y, z)
                .observation()
                .orElseThrow(() -> new IllegalStateException(
                        "canonical parent cell is unavailable"))
                .state();
    }

    @Override
    public ResourceLocation blockAt(int x, int y, int z) {
        ParentCellState state = parentStateAt(x, y, z);
        if (!(state instanceof FullCellState full)) {
            throw new IllegalStateException(
                    "FULL block identity is unavailable for a DETAIL parent");
        }
        return blocks.require(full.blockId()).name();
    }

    @Override
    public BlockWorldMutationOutcome compareAndSetBlock(
            int x,
            int y,
            int z,
            ResourceLocation expectedBlock,
            ResourceLocation replacementBlock) {
        ChunkMutationOutcome outcome = world.compareAndSetBlock(
                x, y, z,
                blocks.requireStoredId(expectedBlock),
                blocks.requireStoredId(replacementBlock));
        return new BlockWorldMutationOutcome(
                switch (outcome.status()) {
                    case APPLIED -> BlockWorldMutationOutcome.Status.APPLIED;
                    case NO_CHANGE -> BlockWorldMutationOutcome.Status.NO_CHANGE;
                    case CONFLICT -> BlockWorldMutationOutcome.Status.CONFLICT;
                    case OUT_OF_BOUNDS -> BlockWorldMutationOutcome.Status.OUT_OF_BOUNDS;
                },
                blocks.require(outcome.observedBlock()).name(),
                outcome.dirtiedChunks());
    }
}
