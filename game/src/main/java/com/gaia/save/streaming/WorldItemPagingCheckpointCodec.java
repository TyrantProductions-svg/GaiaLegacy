package com.gaia.save.streaming;

import com.gaia.save.codec.SaveCodecException;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deterministic bounded codec for the complete WorldItem paging checkpoint. */
public final class WorldItemPagingCheckpointCodec {
    public static final int CODEC_VERSION = 1;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final byte[] MAGIC = {'G', 'L', 'W', 'C'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int FIXED_HEADER_BYTES = 57;
    private static final int DESCRIPTOR_BYTES = 56;

    public byte[] encode(WorldItemPagingCheckpoint checkpoint) {
        WorldItemPagingCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        long length = Math.addExact(
                FIXED_HEADER_BYTES + CHECKSUM_BYTES,
                Math.multiplyExact((long) checked.pages().size(), DESCRIPTOR_BYTES));
        if (length > MAX_FILE_BYTES) {
            throw failure(
                    "world-item-checkpoint.file-size-limit",
                    "The WorldItem checkpoint exceeds its file bound",
                    new IllegalArgumentException("checkpoint too large"));
        }
        try {
            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream(
                    Math.toIntExact(length));
            try (DataOutputStream output = new DataOutputStream(outputBytes)) {
                output.write(MAGIC);
                output.writeInt(CODEC_VERSION);
                writeUuid(output, checked.saveIdentity().value());
                output.writeLong(checked.checkpointRevision());
                output.writeLong(checked.worldTick());
                output.writeLong(checked.nextItemId());
                output.writeBoolean(checked.itemIdsExhausted());
                output.writeInt(checked.totalLiveItemCount());
                output.writeInt(checked.pages().size());
                for (WorldItemPageDescriptor descriptor : checked.pages()) {
                    output.writeInt(descriptor.chunkKey().x());
                    output.writeInt(descriptor.chunkKey().z());
                    output.writeLong(descriptor.pageRevision());
                    output.write(HexFormat.of().parseHex(descriptor.pageHash()));
                    output.writeInt(descriptor.encodedEntryCount());
                    output.writeInt(descriptor.expectedLiveCountAtCheckpointTick());
                }
            }
            byte[] withoutChecksum = outputBytes.toByteArray();
            outputBytes.write(StreamedChunkCodec.sha256(withoutChecksum));
            return outputBytes.toByteArray();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof SaveCodecException codecFailure) {
                throw codecFailure;
            }
            throw failure(
                    "world-item-checkpoint.invalid-snapshot",
                    "The WorldItem checkpoint is invalid",
                    failure);
        }
    }

    public WorldItemPagingCheckpoint decode(SaveIdentity expectedSave, byte[] bytes) {
        Objects.requireNonNull(expectedSave, "expectedSave");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_FILE_BYTES) {
            throw failure(
                    "world-item-checkpoint.file-size-limit",
                    "The WorldItem checkpoint exceeds its file bound",
                    new IllegalArgumentException("checkpoint too large"));
        }
        if (bytes.length < FIXED_HEADER_BYTES + CHECKSUM_BYTES) {
            throw failure(
                    "world-item-checkpoint.invalid-payload",
                    "The WorldItem checkpoint is truncated or malformed",
                    new EOFException("checkpoint header is truncated"));
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw failure(
                        "world-item-checkpoint.invalid-magic",
                        "Invalid WorldItem checkpoint magic",
                        new IllegalArgumentException("invalid magic"));
            }
            if (input.readInt() != CODEC_VERSION) {
                throw failure(
                        "world-item-checkpoint.unsupported-version",
                        "Unsupported WorldItem checkpoint codec version",
                        new IllegalArgumentException("unsupported version"));
            }
            SaveIdentity actualSave = new SaveIdentity(readUuid(input));
            if (!expectedSave.equals(actualSave)) {
                throw failure(
                        "world-item-checkpoint.wrong-save",
                        "The WorldItem checkpoint belongs to another save",
                        new IllegalArgumentException("wrong save"));
            }
            long revision = input.readLong();
            long worldTick = input.readLong();
            long nextItemId = input.readLong();
            int exhaustedFlag = input.readUnsignedByte();
            if (exhaustedFlag > 1) {
                throw failure(
                        "world-item-checkpoint.noncanonical-allocator",
                        "The WorldItem checkpoint allocator flag is invalid",
                        new IllegalArgumentException("invalid allocator flag"));
            }
            int totalLive = input.readInt();
            int pageCount = input.readInt();
            if (pageCount < 0
                    || pageCount > WorldItemPagingCheckpoint.MAX_PAGE_DESCRIPTORS) {
                throw failure(
                        "world-item-checkpoint.page-count-limit",
                        "The WorldItem checkpoint page count exceeds its bound",
                        new IllegalArgumentException("page count exceeds bound"));
            }
            List<WorldItemPageDescriptor> pages = new ArrayList<>(pageCount);
            ChunkKey previous = null;
            for (int index = 0; index < pageCount; index++) {
                ChunkKey key = ChunkCoordinatePolicy.requireSafe(
                        new ChunkKey(input.readInt(), input.readInt()));
                if (previous != null
                        && ChunkCoordinatePolicy.canonicalComparator()
                                .compare(previous, key) >= 0) {
                    throw failure(
                            "world-item-checkpoint.noncanonical-pages",
                            "WorldItem checkpoint pages are not strictly ordered",
                            new IllegalArgumentException("noncanonical page order"));
                }
                previous = key;
                long pageRevision = input.readLong();
                byte[] hash = new byte[32];
                input.readFully(hash);
                pages.add(new WorldItemPageDescriptor(
                        key,
                        pageRevision,
                        HexFormat.of().formatHex(hash),
                        input.readInt(),
                        input.readInt()));
            }
            if (input.available() != CHECKSUM_BYTES) {
                throw failure(
                        "world-item-checkpoint.trailing-bytes",
                        "The WorldItem checkpoint contains trailing bytes",
                        new IllegalArgumentException("trailing bytes"));
            }
            byte[] expectedChecksum = new byte[CHECKSUM_BYTES];
            input.readFully(expectedChecksum);
            byte[] actualChecksum = StreamedChunkCodec.sha256(
                    Arrays.copyOf(bytes, bytes.length - CHECKSUM_BYTES));
            if (!Arrays.equals(expectedChecksum, actualChecksum)) {
                throw failure(
                        "world-item-checkpoint.checksum-mismatch",
                        "The WorldItem checkpoint checksum does not match",
                        new IllegalArgumentException("checksum mismatch"));
            }
            return new WorldItemPagingCheckpoint(
                    actualSave,
                    revision,
                    worldTick,
                    nextItemId,
                    exhaustedFlag == 1,
                    totalLive,
                    pages);
        } catch (SaveCodecException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure(
                    "world-item-checkpoint.invalid-payload",
                    "The WorldItem checkpoint is truncated or malformed",
                    failure);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static SaveCodecException failure(String code, String message, Throwable cause) {
        return new SaveCodecException(code, message, cause);
    }
}
