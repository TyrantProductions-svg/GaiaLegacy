package com.overlord.interaction.testing;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.BlockWorldAccess;
import com.overlord.interaction.BlockWorldMutationOutcome;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FakeBlockWorldAccess
        implements BlockWorldAccess {
    private final Set<ResourceLocation> known = new HashSet<>();
    private ResourceLocation block;
    private boolean withinBounds = true;
    private int writes;
    private BlockWorldMutationOutcome compareAndSetOutcome;

    public FakeBlockWorldAccess(
            ResourceLocation initialBlock,
            Set<ResourceLocation> knownBlocks) {
        block = Objects.requireNonNull(initialBlock, "initialBlock");
        known.addAll(Set.copyOf(knownBlocks));
        compareAndSetOutcome =
                new BlockWorldMutationOutcome(
                        BlockWorldMutationOutcome.Status.APPLIED,
                        initialBlock,
                        List.of(
                                new DirtyChunkRevision(
                                        new ChunkKey(0, 0), 1)));
    }

    @Override
    public boolean isWithinBounds(int x, int y, int z) {
        return withinBounds;
    }

    @Override
    public boolean isKnownBlock(ResourceLocation candidate) {
        return known.contains(candidate);
    }

    @Override
    public ResourceLocation blockAt(int x, int y, int z) {
        return block;
    }

    @Override
    public BlockWorldMutationOutcome compareAndSetBlock(
            int x,
            int y,
            int z,
            ResourceLocation expected,
            ResourceLocation replacement) {
        writes++;
        if (compareAndSetOutcome.status()
                == BlockWorldMutationOutcome.Status.APPLIED) {
            block =
                    Objects.requireNonNull(
                            replacement, "replacement");
        }
        return compareAndSetOutcome;
    }

    public void setWithinBounds(boolean withinBounds) {
        this.withinBounds = withinBounds;
    }

    public int writes() {
        return writes;
    }

    public void setCompareAndSetOutcome(
            BlockWorldMutationOutcome outcome) {
        compareAndSetOutcome =
                Objects.requireNonNull(outcome, "outcome");
    }

    public void forceExternalBlockChangeForTest(
            ResourceLocation replacement) {
        block = Objects.requireNonNull(replacement, "replacement");
    }
}
