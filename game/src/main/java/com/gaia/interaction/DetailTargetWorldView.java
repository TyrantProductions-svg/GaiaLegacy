package com.gaia.interaction;

import com.overlord.voxel.ParentCellObservationResult;

@FunctionalInterface
public interface DetailTargetWorldView {
    ParentCellObservationResult observeCell(int x, int y, int z);
}
