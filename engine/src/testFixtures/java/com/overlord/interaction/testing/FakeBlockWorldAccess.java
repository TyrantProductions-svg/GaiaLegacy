package com.overlord.interaction.testing;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.BlockWorldAccess;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class FakeBlockWorldAccess
        implements BlockWorldAccess {
    private final Set<ResourceLocation> known = new HashSet<>();
    private ResourceLocation block;
    private boolean withinBounds = true;
    private int writes;

    public FakeBlockWorldAccess(
            ResourceLocation initialBlock,
            Set<ResourceLocation> knownBlocks) {
        block = Objects.requireNonNull(initialBlock, "initialBlock");
        known.addAll(Set.copyOf(knownBlocks));
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
    public boolean setBlock(
            int x,
            int y,
            int z,
            ResourceLocation replacement) {
        block = Objects.requireNonNull(replacement, "replacement");
        writes++;
        return true;
    }

    public void setWithinBounds(boolean withinBounds) {
        this.withinBounds = withinBounds;
    }

    public int writes() {
        return writes;
    }
}
