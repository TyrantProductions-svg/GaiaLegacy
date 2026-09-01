package com.overlord.voxel;

import java.util.Objects;

public sealed interface ChunkDetailMutation
        permits ChunkDetailMutation.ConvertFullToDetail,
                ChunkDetailMutation.SetSubVoxel,
                ChunkDetailMutation.RemoveDetailParent,
                ChunkDetailMutation.SculptParentSubVoxel,
                ChunkDetailMutation.CompactDetailToFull {
    int x();

    int y();

    int z();

    long expectedRevision();

    record ConvertFullToDetail(
            int x,
            int y,
            int z,
            long expectedRevision,
            byte expectedFullId)
            implements ChunkDetailMutation {
        public ConvertFullToDetail {
            requireRevision(expectedRevision);
        }
    }

    record SetSubVoxel(
            int x,
            int y,
            int z,
            long expectedRevision,
            ParentCellState expectedState,
            LocalSubVoxelPosition position,
            byte replacementId)
            implements ChunkDetailMutation {
        public SetSubVoxel {
            requireRevision(expectedRevision);
            expectedState =
                    Objects.requireNonNull(
                            expectedState, "expectedState");
            position = Objects.requireNonNull(position, "position");
        }
    }

    record CompactDetailToFull(
            int x,
            int y,
            int z,
            long expectedRevision,
            DetailCellState expectedState,
            byte replacementFullId)
            implements ChunkDetailMutation {
        public CompactDetailToFull {
            requireRevision(expectedRevision);
            expectedState =
                    Objects.requireNonNull(
                            expectedState, "expectedState");
        }
    }

    record RemoveDetailParent(
            int x,
            int y,
            int z,
            long expectedRevision,
            DetailCellState expectedState)
            implements ChunkDetailMutation {
        public RemoveDetailParent {
            requireRevision(expectedRevision);
            expectedState = Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    record SculptParentSubVoxel(
            int x,
            int y,
            int z,
            long expectedRevision,
            ParentCellState expectedState,
            LocalSubVoxelPosition position,
            byte replacementId)
            implements ChunkDetailMutation {
        public SculptParentSubVoxel {
            requireRevision(expectedRevision);
            expectedState = Objects.requireNonNull(expectedState, "expectedState");
            position = Objects.requireNonNull(position, "position");
        }
    }

    private static void requireRevision(long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }
    }
}
