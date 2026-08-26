package com.gaia.save.streaming;

import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.format.SaveGameId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical bounded codec for the optional streamed-v2 session checkpoint. */
public final class StreamedSessionCheckpointCodec {
    public static final int CODEC_VERSION = 1;
    public static final int MAX_BYTES = 256 * 1_024;
    private static final int MAGIC = 0x47535343;

    private final PlayerSectionCodec players = new PlayerSectionCodec();
    private final InventorySectionCodec inventories = new InventorySectionCodec();

    public byte[] encode(StreamedSessionCheckpoint checkpoint) {
        StreamedSessionCheckpoint value = Objects.requireNonNull(
                checkpoint, "checkpoint");
        byte[] player = players.encode(value.player());
        byte[] inventory = inventories.encode(value.inventory());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(CODEC_VERSION);
            output.writeUTF(value.saveGameId().value());
            output.writeLong(value.fixedTick());
            output.writeLong(value.worldItemCheckpointRevision());
            output.write(HexFormat.of().parseHex(value.worldItemCheckpointDigest()));
            output.writeLong(value.worldItemSourceIndexSequence());
            output.writeLong(value.modifiedTime().getEpochSecond());
            output.writeInt(value.modifiedTime().getNano());
            writeBytes(output, player);
            writeBytes(output, inventory);
            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES) {
                throw new IllegalArgumentException(
                        "Streamed session checkpoint exceeds its bound");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "In-memory streamed session encoding failed", impossible);
        }
    }

    public StreamedSessionCheckpoint decode(byte[] encoded) {
        byte[] bytes = Objects.requireNonNull(encoded, "encoded").clone();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Streamed session checkpoint size is invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != MAGIC || input.readInt() != CODEC_VERSION) {
                throw new IllegalArgumentException(
                        "Streamed session checkpoint header is unsupported");
            }
            SaveGameId id = SaveGameId.parse(input.readUTF());
            long fixedTick = input.readLong();
            long checkpointRevision = input.readLong();
            byte[] digestBytes = input.readNBytes(32);
            if (digestBytes.length != 32) {
                throw new IOException("Streamed session checkpoint digest is truncated");
            }
            String checkpointDigest = HexFormat.of().formatHex(digestBytes);
            long sourceSequence = input.readLong();
            long modifiedEpochSecond = input.readLong();
            int modifiedNano = input.readInt();
            byte[] player = readBytes(input);
            byte[] inventory = readBytes(input);
            if (input.read() != -1) {
                throw new IllegalArgumentException(
                        "Streamed session checkpoint has trailing bytes");
            }
            StreamedSessionCheckpoint decoded = new StreamedSessionCheckpoint(
                    id,
                    fixedTick,
                    checkpointRevision,
                    checkpointDigest,
                    sourceSequence,
                    java.time.Instant.ofEpochSecond(
                            modifiedEpochSecond, modifiedNano),
                    players.decode(player),
                    inventories.decode(inventory));
            if (!Arrays.equals(bytes, encode(decoded))) {
                throw new IllegalArgumentException(
                        "Streamed session checkpoint is not canonical");
            }
            return decoded;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Streamed session checkpoint is truncated", failure);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes)
            throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Streamed session child payload length is invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new IOException("Streamed session child payload is truncated");
        }
        return value;
    }
}
