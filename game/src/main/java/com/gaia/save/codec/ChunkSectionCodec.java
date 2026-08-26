package com.gaia.save.codec;

import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic binary codec for complete canonical Chunk snapshots. */
public final class ChunkSectionCodec
        implements SaveSectionCodec<ChunkRepositorySnapshot> {
    private static final byte[] MAGIC = {'G', 'L', 'C', 'H'};
    private static final int CODEC_VERSION = 1;
    private static final int MAX_SUPPORTED_RADIUS = 8;
    private static final int MAX_CHUNK_COUNT =
            Math.multiplyExact(
                    MAX_SUPPORTED_RADIUS * 2 + 1,
                    MAX_SUPPORTED_RADIUS * 2 + 1);
    private static final int HEADER_LENGTH =
            MAGIC.length
                    + Integer.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Integer.BYTES;
    private static final int CHUNK_HEADER_LENGTH =
            Integer.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Integer.BYTES;
    private static final Comparator<ChunkSnapshot> CHUNK_ORDER =
            Comparator.comparing(ChunkSnapshot::key, ChunkCoordinatePolicy.canonicalComparator());

    @Override
    public SaveSectionId sectionId() {
        return SaveSectionId.CHUNKS;
    }

    @Override
    public int codecVersion() {
        return CODEC_VERSION;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public byte[] encode(ChunkRepositorySnapshot snapshot) {
        try {
            return encodeValidated(Objects.requireNonNull(snapshot, "snapshot"));
        } catch (IOException | RuntimeException failure) {
            throw new SaveCodecException(
                    "chunks.invalid-snapshot",
                    "Invalid chunks snapshot",
                    failure);
        }
    }

    @Override
    public ChunkRepositorySnapshot decode(byte[] bytes) {
        try {
            return decodeValidated(Objects.requireNonNull(bytes, "bytes"));
        } catch (IOException | RuntimeException failure) {
            throw new SaveCodecException(
                    "chunks.invalid-payload",
                    "Invalid chunks payload",
                    failure);
        }
    }

    private byte[] encodeValidated(ChunkRepositorySnapshot snapshot)
            throws IOException {
        int worldHeight = requireSupportedWorldHeight(snapshot.worldHeight());
        long revisionHighWater =
                requireRevisionHighWater(snapshot.revisionHighWater());
        int blockLength = canonicalBlockLength(worldHeight);
        List<ChunkSnapshot> chunks = new ArrayList<>(snapshot.chunks());
        requireSupportedChunkCount(chunks.size());
        chunks.sort(CHUNK_ORDER);

        Set<ChunkKey> keys = new HashSet<>();
        List<EncodedChunk> encodedChunks = new ArrayList<>(chunks.size());
        for (ChunkSnapshot chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            ChunkKey key =
                    ChunkCoordinatePolicy.requireSafe(
                            Objects.requireNonNull(chunk.key(), "chunk key"));
            requireChunkRevision(chunk.revision(), revisionHighWater);
            if (chunk.worldHeight() != worldHeight) {
                throw new IllegalArgumentException(
                        "Chunk world height does not match repository world height");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate Chunk key");
            }
            byte[] blocks = chunk.copyBlocks();
            if (blocks.length != blockLength) {
                throw new IllegalArgumentException(
                        "Chunk block length is not canonical");
            }
            encodedChunks.add(
                    new EncodedChunk(key, chunk.revision(), blocks));
        }

        int encodedLength = encodedLength(encodedChunks.size(), blockLength);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encodedLength);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(codecVersion());
            output.writeInt(worldHeight);
            output.writeLong(revisionHighWater);
            output.writeInt(encodedChunks.size());
            for (EncodedChunk chunk : encodedChunks) {
                output.writeInt(chunk.key().x());
                output.writeInt(chunk.key().z());
                output.writeLong(chunk.revision());
                output.writeInt(blockLength);
                output.write(chunk.blocks());
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length != encodedLength) {
            throw new IllegalStateException("Unexpected encoded Chunk length");
        }
        return result;
    }

    private ChunkRepositorySnapshot decodeValidated(byte[] bytes)
            throws IOException {
        try (DataInputStream input =
                new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IllegalArgumentException("Invalid Chunk section magic");
            }
            if (input.readInt() != codecVersion()) {
                throw new IllegalArgumentException(
                        "Unsupported Chunk section codec version");
            }

            int worldHeight = requireSupportedWorldHeight(input.readInt());
            long revisionHighWater = requireRevisionHighWater(input.readLong());
            int chunkCount = requireSupportedChunkCount(input.readInt());
            int blockLength = canonicalBlockLength(worldHeight);
            int expectedLength = encodedLength(chunkCount, blockLength);
            if (bytes.length != expectedLength) {
                throw new IllegalArgumentException(
                        "Chunk section length is not canonical");
            }

            List<ChunkSnapshot> chunks = new ArrayList<>(chunkCount);
            Set<ChunkKey> keys = new HashSet<>();
            ChunkKey previousKey = null;
            for (int index = 0; index < chunkCount; index++) {
                ChunkKey key =
                        ChunkCoordinatePolicy.requireSafe(
                                new ChunkKey(input.readInt(), input.readInt()));
                long revision = input.readLong();
                requireChunkRevision(revision, revisionHighWater);
                int encodedBlockLength = input.readInt();
                if (encodedBlockLength != blockLength) {
                    throw new IllegalArgumentException(
                            "Chunk block length is not canonical");
                }
                if (!keys.add(key)) {
                    throw new IllegalArgumentException("Duplicate Chunk key");
                }
                if (previousKey != null && compareKeys(previousKey, key) >= 0) {
                    throw new IllegalArgumentException(
                            "Chunk entries are not in canonical order");
                }

                byte[] blocks = new byte[blockLength];
                input.readFully(blocks);
                chunks.add(
                        ChunkSnapshot.of(
                                key, revision, worldHeight, blocks));
                previousKey = key;
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException(
                        "Chunk section contains trailing bytes");
            }
            return new ChunkRepositorySnapshot(
                    worldHeight, revisionHighWater, chunks);
        }
    }

    private static int requireSupportedWorldHeight(int worldHeight) {
        if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException("Unsupported Chunk world height");
        }
        return worldHeight;
    }

    private static long requireRevisionHighWater(long revisionHighWater) {
        if (revisionHighWater < 0 || revisionHighWater == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Chunk revision high-water is not incrementable");
        }
        return revisionHighWater;
    }

    private static int requireSupportedChunkCount(int chunkCount) {
        if (chunkCount < 0 || chunkCount > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("Unsupported Chunk count");
        }
        return chunkCount;
    }

    private static void requireChunkRevision(
            long revision, long revisionHighWater) {
        if (revision <= 0 || revision > revisionHighWater) {
            throw new IllegalArgumentException("Invalid Chunk revision");
        }
    }

    private static int canonicalBlockLength(int worldHeight) {
        return Math.multiplyExact(
                Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                GameConfig.Chunk.SIZE);
    }

    private static int encodedLength(int chunkCount, int blockLength) {
        int chunkLength = Math.addExact(CHUNK_HEADER_LENGTH, blockLength);
        return Math.addExact(
                HEADER_LENGTH,
                Math.multiplyExact(chunkCount, chunkLength));
    }

    private static int compareKeys(ChunkKey first, ChunkKey second) {
        return ChunkCoordinatePolicy.canonicalComparator().compare(first, second);
    }

    private record EncodedChunk(ChunkKey key, long revision, byte[] blocks) {
        private EncodedChunk {
            key = Objects.requireNonNull(key, "key");
            blocks = Arrays.copyOf(
                    Objects.requireNonNull(blocks, "blocks"), blocks.length);
        }

        @Override
        public byte[] blocks() {
            return Arrays.copyOf(blocks, blocks.length);
        }
    }
}
