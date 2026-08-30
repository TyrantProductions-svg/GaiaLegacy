package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkSnapshot {
    private final ChunkKey key;
    private final long revision;
    private final int worldHeight;
    private final byte[] blocks;
    private final DetailChunkSnapshot details;

    private ChunkSnapshot(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks,
            DetailChunkSnapshot details) {
        this.key = Objects.requireNonNull(key, "key");
        this.revision = revision;
        this.worldHeight = requireValidWorldHeight(worldHeight);
        Objects.requireNonNull(blocks, "blocks");
        int expectedLength =
                Math.multiplyExact(
                        Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                        GameConfig.Chunk.SIZE);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks length must be " + expectedLength);
        }
        this.blocks = Arrays.copyOf(blocks, blocks.length);
        DetailChunkSnapshot checkedDetails =
                Objects.requireNonNull(details, "details");
        validateDetailBacking(checkedDetails, this.blocks);
        this.details = checkedDetails.isEmpty() ? null : checkedDetails;
    }

    public ChunkKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public int worldHeight() {
        return worldHeight;
    }

    public byte[] copyBlocks() {
        if (details != null) {
            throw new IllegalStateException(
                    "byte snapshot copy cannot represent DETAIL parents");
        }
        return copyFullBlocks();
    }

    byte[] copyFullBlocks() {
        return Arrays.copyOf(blocks, blocks.length);
    }

    public DetailChunkSnapshot details() {
        return details == null
                ? DetailChunkSnapshot.emptyView()
                : details;
    }

    public ParentCellState cellState(int localX, int y, int localZ) {
        if (!validCoordinate(localX, y, localZ)) {
            return new FullCellState((byte) 0);
        }
        int index = index(localX, y, localZ);
        if (details != null) {
            java.util.Optional<DetailCellState> detail =
                    details.stateAtParentIndex(index);
            if (detail.isPresent()) {
                return detail.orElseThrow();
            }
        }
        return new FullCellState(blocks[index]);
    }

    public byte getBlock(int localX, int y, int localZ) {
        if (!validCoordinate(localX, y, localZ)) {
            return 0;
        }
        int index = index(localX, y, localZ);
        if (details != null
                && details.stateAtParentIndex(index).isPresent()) {
            throw new IllegalStateException(
                    "byte block access cannot represent a DETAIL parent");
        }
        return blocks[index];
    }

    QuarterVoxelSample quarterSample(
            int localX, int y, int localZ, int subIndex) {
        if (!validCoordinate(localX, y, localZ)) {
            return QuarterVoxelSample.full((byte) 0);
        }
        if (subIndex < 0 || subIndex >= DetailCellState.CELL_COUNT) {
            throw new IllegalArgumentException(
                    "subIndex must be between 0 and 63");
        }
        int parentIndex = index(localX, y, localZ);
        if (details != null) {
            int detailBlockId = details.blockIdAt(parentIndex, subIndex);
            if (detailBlockId >= 0) {
                return QuarterVoxelSample.detail((byte) detailBlockId);
            }
        }
        return QuarterVoxelSample.full(blocks[parentIndex]);
    }

    public boolean canonicalContentEquals(ChunkSnapshot other) {
        Objects.requireNonNull(other, "other");
        return worldHeight == other.worldHeight
                && Arrays.equals(blocks, other.blocks)
                && Objects.equals(details, other.details);
    }

    public byte[] canonicalContentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES)
                            .putInt(worldHeight)
                            .array());
            digest.update(blocks);
            DetailChunkSnapshot detailView = details();
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES)
                            .putInt(detailView.entryCount())
                            .array());
            int[] parentIndices = detailView.copyParentIndices();
            long[] occupancyMasks = detailView.copyOccupancyMasks();
            byte[] detailIds = detailView.copyBlockIds();
            for (int entry = 0; entry < parentIndices.length; entry++) {
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES)
                                .putInt(parentIndices[entry])
                                .array());
                digest.update(
                        ByteBuffer.allocate(Long.BYTES)
                                .putLong(occupancyMasks[entry])
                                .array());
                digest.update(
                        detailIds,
                        entry * DetailCellState.CELL_COUNT,
                        DetailCellState.CELL_COUNT);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ChunkSnapshot other)) {
            return false;
        }
        return revision == other.revision
                && worldHeight == other.worldHeight
                && key.equals(other.key)
                && Arrays.equals(blocks, other.blocks)
                && Objects.equals(details, other.details);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(key, revision, worldHeight);
        result = 31 * result + Arrays.hashCode(blocks);
        return 31 * result + Objects.hashCode(details);
    }

    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks) {
        return new ChunkSnapshot(
                key,
                revision,
                worldHeight,
                blocks,
                DetailChunkSnapshot.emptyView());
    }

    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks,
            DetailChunkSnapshot details) {
        return new ChunkSnapshot(
                key, revision, worldHeight, blocks, details);
    }

    public static ChunkSnapshot empty(
            ChunkKey key, long revision, int worldHeight) {
        int validatedHeight = requireValidWorldHeight(worldHeight);
        return new ChunkSnapshot(
                key,
                revision,
                validatedHeight,
                new byte[
                        Math.multiplyExact(
                                Math.multiplyExact(
                                        GameConfig.Chunk.SIZE,
                                        validatedHeight),
                                GameConfig.Chunk.SIZE)],
                DetailChunkSnapshot.emptyView());
    }

    private boolean validCoordinate(int localX, int y, int localZ) {
        return localX >= 0
                && localX < GameConfig.Chunk.SIZE
                && y >= 0
                && y < worldHeight
                && localZ >= 0
                && localZ < GameConfig.Chunk.SIZE;
    }

    private int index(int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * worldHeight;
    }

    private static void validateDetailBacking(
            DetailChunkSnapshot details, byte[] blocks) {
        for (int parentIndex : details.copyParentIndices()) {
            if (parentIndex >= blocks.length) {
                throw new IllegalArgumentException(
                        "DETAIL parent index is outside snapshot height");
            }
            if (blocks[parentIndex] != 0) {
                throw new IllegalArgumentException(
                        "DETAIL parent backing FULL byte must be AIR");
            }
        }
    }

    private static int requireValidWorldHeight(int worldHeight) {
        if (worldHeight <= 0
                || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException(
                    "worldHeight must be between 1 and "
                            + GameConfig.Chunk.MAX_HEIGHT);
        }
        return worldHeight;
    }
}
