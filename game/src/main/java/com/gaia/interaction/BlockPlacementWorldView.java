package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;

public interface BlockPlacementWorldView {
    boolean isLoaded(int x, int y, int z);

    ResourceLocation blockAt(int x, int y, int z);
}
