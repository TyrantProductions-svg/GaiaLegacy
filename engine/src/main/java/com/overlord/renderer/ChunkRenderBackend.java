package com.overlord.renderer;

import com.overlord.voxel.ChunkMeshData;

public interface ChunkRenderBackend {
    default UploadMemoryRequirement uploadMemoryRequirement(
            ChunkMeshData data) {
        return UploadMemoryRequirement.NONE;
    }

    ChunkRenderObject upload(ChunkMeshData data);

    void release(ChunkRenderObject object);

    record UploadMemoryRequirement(
            long heapScratchBytes,
            long directScratchBytes) {
        public static final UploadMemoryRequirement NONE =
                new UploadMemoryRequirement(0L, 0L);

        public UploadMemoryRequirement {
            if (heapScratchBytes < 0L || directScratchBytes < 0L) {
                throw new IllegalArgumentException(
                        "upload memory requirements must not be negative");
            }
        }
    }
}
