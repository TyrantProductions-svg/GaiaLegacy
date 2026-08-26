package com.overlord.voxel;

public enum ChunkState {
    EMPTY,
    LOADING,
    GENERATING,
    GENERATED,
    MESHING,
    READY_FOR_UPLOAD,
    RENDERABLE,
    DIRTY,
    UNLOADING
}
