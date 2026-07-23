package com.overlord.voxel;

@FunctionalInterface
public interface BlockRenderResolver {
    BlockRenderInfo resolve(int unsignedBlockId);
}
