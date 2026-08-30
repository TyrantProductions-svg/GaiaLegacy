package com.overlord.voxel;

import com.overlord.config.GameConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Chunk {
    public static final int MAX_DETAIL_PARENTS_PER_CHUNK = 1024;
    
    private final int worldHeight;
    private final int numSubChunks;
    private Map<Integer, SubChunk> subChunks;
    private DetailStorage detailStorage;
    
    public Chunk() {
        this(GameConfig.Chunk.MAX_HEIGHT);
    }
    
    public Chunk(int worldHeight) {
        if (worldHeight <= 0
                || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException(
                    "worldHeight must be between 1 and "
                            + GameConfig.Chunk.MAX_HEIGHT);
        }
        this.worldHeight = worldHeight;
        this.numSubChunks = worldHeight / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        this.subChunks = new HashMap<>();
    }
    
    public synchronized byte getBlock(int x, int y, int z) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= worldHeight || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return 0;
        }
        if (detailStorage != null
                && detailStorage.contains(parentIndex(x, y, z))) {
            throw new IllegalStateException(
                    "byte block access cannot represent a DETAIL parent");
        }
        return rawFullBlock(x, y, z);
    }

    private byte rawFullBlock(int x, int y, int z) {
        int sectionIndex = y / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        int localY = y % GameConfig.Chunk.SUBCHUNK_HEIGHT;
        
        SubChunk subChunk = subChunks.get(sectionIndex);
        if (subChunk == null) {
            return 0;
        }
        
        return subChunk.getBlock(x, localY, z);
    }
    
    public synchronized void setBlock(int x, int y, int z, byte blockType) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= worldHeight || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return;
        }
        if (detailStorage != null
                && detailStorage.contains(parentIndex(x, y, z))) {
            throw new IllegalStateException(
                    "byte block mutation cannot replace a DETAIL parent");
        }
        setFullBlock(x, y, z, blockType);
    }

    private void setFullBlock(int x, int y, int z, byte blockType) {
        int sectionIndex = y / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        int localY = y % GameConfig.Chunk.SUBCHUNK_HEIGHT;
        
        if (blockType == 0) {
            SubChunk subChunk = subChunks.get(sectionIndex);
            if (subChunk != null) {
                subChunk.setBlock(x, localY, z, (byte) 0);
                if (subChunk.isEmpty()) {
                    subChunks.remove(sectionIndex);
                }
            }
        } else {
            SubChunk subChunk = subChunks.computeIfAbsent(sectionIndex, k -> new SubChunk());
            subChunk.setBlock(x, localY, z, blockType);
        }
    }
    
    SubChunk getSubChunk(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= numSubChunks) {
            return null;
        }
        return subChunks.get(sectionIndex);
    }
    
    public int getWorldHeight() {
        return worldHeight;
    }
    
    public int getNumSubChunks() {
        return numSubChunks;
    }

    static Chunk fromCanonicalBytes(
            int worldHeight, byte[] blocks) {
        return fromCanonicalState(
                worldHeight,
                blocks,
                DetailChunkSnapshot.emptyView());
    }

    static Chunk fromCanonicalState(
            int worldHeight,
            byte[] blocks,
            DetailChunkSnapshot details) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(details, "details");
        int expectedLength =
                Math.multiplyExact(
                        Math.multiplyExact(
                                GameConfig.Chunk.SIZE, worldHeight),
                        GameConfig.Chunk.SIZE);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks must contain exactly "
                            + expectedLength
                            + " bytes");
        }

        Chunk chunk = new Chunk(worldHeight);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int y = 0; y < worldHeight; y++) {
                for (int localX = 0;
                        localX < GameConfig.Chunk.SIZE;
                        localX++) {
                    int index =
                            localX
                                    + y * GameConfig.Chunk.SIZE
                                    + localZ
                                            * GameConfig.Chunk.SIZE
                                            * worldHeight;
                    chunk.setBlock(
                            localX, y, localZ, blocks[index]);
                }
            }
        }
        int[] parentIndices = details.copyParentIndices();
        long[] masks = details.copyOccupancyMasks();
        byte[] detailIds = details.copyBlockIds();
        for (int entry = 0; entry < parentIndices.length; entry++) {
            int parentIndex = parentIndices[entry];
            if (parentIndex >= expectedLength || blocks[parentIndex] != 0) {
                throw new IllegalArgumentException(
                        "DETAIL parent must address an AIR-backed canonical cell");
            }
            int localZ =
                    parentIndex
                            / (GameConfig.Chunk.SIZE * worldHeight);
            int remainder =
                    parentIndex
                            % (GameConfig.Chunk.SIZE * worldHeight);
            int y = remainder / GameConfig.Chunk.SIZE;
            int localX = remainder % GameConfig.Chunk.SIZE;
            byte[] ids =
                    java.util.Arrays.copyOfRange(
                            detailIds,
                            entry * DetailCellState.CELL_COUNT,
                            (entry + 1) * DetailCellState.CELL_COUNT);
            chunk.replaceCanonicalCell(
                    localX,
                    y,
                    localZ,
                    new DetailCellState(masks[entry], ids));
        }
        return chunk;
    }

    synchronized ParentCellState cellState(int x, int y, int z) {
        if (!validCoordinate(x, y, z)) {
            return new FullCellState((byte) 0);
        }
        int parentIndex = parentIndex(x, y, z);
        if (detailStorage != null) {
            DetailCellState detail = detailStorage.stateAt(parentIndex);
            if (detail != null) {
                return detail;
            }
        }
        return new FullCellState(rawFullBlock(x, y, z));
    }

    synchronized void replaceCanonicalCell(
            int x, int y, int z, ParentCellState replacement) {
        requireCoordinate(x, y, z);
        Objects.requireNonNull(replacement, "replacement");
        int parentIndex = parentIndex(x, y, z);
        if (replacement instanceof DetailCellState detail) {
            DetailStorage nextStorage =
                    detailStorage == null
                            ? DetailStorage.single(parentIndex, detail)
                            : detailStorage.put(
                                    parentIndex,
                                    detail,
                                    MAX_DETAIL_PARENTS_PER_CHUNK);
            setFullBlock(x, y, z, (byte) 0);
            detailStorage = nextStorage;
            return;
        }

        FullCellState full = (FullCellState) replacement;
        DetailStorage nextStorage =
                detailStorage == null
                        ? null
                        : detailStorage.remove(parentIndex);
        setFullBlock(x, y, z, full.blockId());
        detailStorage = nextStorage;
    }

    synchronized int detailParentCount() {
        return detailStorage == null ? 0 : detailStorage.size();
    }

    synchronized int[] copyDetailParentIndicesForSnapshot() {
        return detailStorage == null
                ? new int[0]
                : detailStorage.copyParentIndices();
    }

    synchronized long[] copyDetailOccupancyMasksForSnapshot() {
        return detailStorage == null
                ? new long[0]
                : detailStorage.copyOccupancyMasks();
    }

    synchronized byte[] copyDetailBlockIdsForSnapshot() {
        return detailStorage == null
                ? new byte[0]
                : detailStorage.copyBlockIds();
    }

    synchronized DetailChunkSnapshot detailSnapshotForCapture() {
        return detailStorage == null
                ? DetailChunkSnapshot.emptyView()
                : detailStorage.snapshot();
    }

    synchronized byte rawFullBlockForInvariant(int x, int y, int z) {
        requireCoordinate(x, y, z);
        return rawFullBlock(x, y, z);
    }

    synchronized void copyBlocksTo(byte[] target) {
        for (Map.Entry<Integer, SubChunk> entry : subChunks.entrySet()) {
            entry.getValue()
                    .copyBlocksTo(
                            target,
                            entry.getKey()
                                    * GameConfig.Chunk.SUBCHUNK_HEIGHT,
                            worldHeight);
        }
    }

    private int parentIndex(int x, int y, int z) {
        return x
                + y * GameConfig.Chunk.SIZE
                + z * GameConfig.Chunk.SIZE * worldHeight;
    }

    private boolean validCoordinate(int x, int y, int z) {
        return x >= 0
                && x < GameConfig.Chunk.SIZE
                && y >= 0
                && y < worldHeight
                && z >= 0
                && z < GameConfig.Chunk.SIZE;
    }

    private void requireCoordinate(int x, int y, int z) {
        if (!validCoordinate(x, y, z)) {
            throw new IllegalArgumentException(
                    "parent coordinate is outside this Chunk");
        }
    }
}
