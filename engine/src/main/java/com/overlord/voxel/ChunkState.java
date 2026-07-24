package com.overlord.voxel;

public enum ChunkState {
    EMPTY,
    GENERATING,
    GENERATED,
    MESHING,
    READY_FOR_UPLOAD,
    RENDERABLE,
    DIRTY,
    UNLOADING
}
