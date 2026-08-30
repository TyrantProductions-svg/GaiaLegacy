package com.gaia.save.streaming;

import com.gaia.blocks.BlockRegistry;
import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact detached translation between one canonical ChunkSnapshot and streamed bytes. */
public final class ChunkDetailPersistence {
    private static final DetailBlocksCodec DETAIL_CODEC = new DetailBlocksCodec();

    private ChunkDetailPersistence() {}

    public static byte[] canonicalFullVoxels(ChunkSnapshot snapshot) {
        ChunkSnapshot exact = Objects.requireNonNull(snapshot, "snapshot");
        byte[] full = new byte[Math.multiplyExact(
                Math.multiplyExact(GameConfig.Chunk.SIZE, exact.worldHeight()),
                GameConfig.Chunk.SIZE)];
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            for (int y = 0; y < exact.worldHeight(); y++) {
                for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                    ParentCellState state = exact.cellState(x, y, z);
                    if (state instanceof FullCellState value) {
                        int index = x
                                + y * GameConfig.Chunk.SIZE
                                + z * GameConfig.Chunk.SIZE * exact.worldHeight();
                        full[index] = value.blockId();
                    }
                }
            }
        }
        return full;
    }

    public static List<StreamedChunkPayload.ExtensionDescriptor> mergeDetailExtension(
            ChunkSnapshot snapshot,
            List<StreamedChunkPayload.ExtensionDescriptor> existing,
            BlockRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<StreamedChunkPayload.ExtensionDescriptor> merged = new ArrayList<>();
        for (StreamedChunkPayload.ExtensionDescriptor extension
                : List.copyOf(Objects.requireNonNull(existing, "existing"))) {
            if (!extension.sectionId().equals(SaveSectionId.DETAIL_BLOCKS)) {
                merged.add(extension);
            }
        }
        if (!snapshot.details().isEmpty()) {
            DETAIL_CODEC.encode(
                    snapshot, Objects.requireNonNull(
                            registry, "A DETAIL snapshot requires BlockRegistry translation"))
                    .ifPresent(merged::add);
        }
        merged.sort(StreamedChunkPayload.EXTENSION_ORDER);
        return List.copyOf(merged);
    }

    public static boolean canonicalStateEquals(
            ChunkSnapshot snapshot,
            StreamedChunkPayload payload,
            BlockRegistry registry) {
        Objects.requireNonNull(payload, "payload");
        if (snapshot.worldHeight() != payload.worldHeight()
                || !Arrays.equals(
                        canonicalFullVoxels(snapshot), payload.copyCanonicalVoxels())) {
            return false;
        }
        List<StreamedChunkPayload.ExtensionDescriptor> expected =
                mergeDetailExtension(snapshot, List.of(), registry);
        return detailExtension(expected).equals(detailExtension(payload.extensions()));
    }

    static boolean canonicalStateEquals(
            StreamedChunkPayload first, StreamedChunkPayload second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return first.worldHeight() == second.worldHeight()
                && Arrays.equals(
                        first.copyCanonicalVoxels(), second.copyCanonicalVoxels())
                && detailExtension(first.extensions())
                        .equals(detailExtension(second.extensions()));
    }

    private static java.util.Optional<StreamedChunkPayload.ExtensionDescriptor>
            detailExtension(List<StreamedChunkPayload.ExtensionDescriptor> extensions) {
        return extensions.stream()
                .filter(extension -> extension.sectionId().equals(
                        SaveSectionId.DETAIL_BLOCKS))
                .findFirst();
    }
}
