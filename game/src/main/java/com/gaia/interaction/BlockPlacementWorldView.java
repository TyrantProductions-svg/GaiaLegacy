package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ParentCellState;

public interface BlockPlacementWorldView {
    boolean isLoaded(int x, int y, int z);

    ParentCellState parentStateAt(int x, int y, int z);

    ResourceLocation blockAt(int x, int y, int z);
}
