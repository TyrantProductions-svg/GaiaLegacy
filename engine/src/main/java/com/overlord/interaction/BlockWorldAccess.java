package com.overlord.interaction;

import com.overlord.assets.ResourceLocation;

public interface BlockWorldAccess {
    boolean isWithinBounds(int x, int y, int z);

    boolean isKnownBlock(ResourceLocation block);

    ResourceLocation blockAt(int x, int y, int z);

    boolean setBlock(
            int x, int y, int z, ResourceLocation block);
}
