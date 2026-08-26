package com.gaia.save.streaming;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameId;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic bounded codec for one exact Chunk-owned WorldItem page. */
public final class WorldItemPageCodec {
    public static final int CODEC_VERSION = 1;
    public static final int MAX_ENTRIES =
            GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS;
    public static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    public static final int SHA256_BYTES = 32;
    private static final byte[] MAGIC = {'G', 'L', 'W', 'P'};
    private static final int VERSION = CODEC_VERSION;
    private static final int FIXED_HEADER_BYTES = 44;
    private static final int MAX_ENTRY_BYTES = 16 * 1024;
    private static final int MAX_RESOURCE_BYTES = 512;
    private static final Comparator<WorldItemRestoreEntry> ENTRY_ORDER =
            Comparator.comparingLong(value -> value.runtime().item().id().value());

    private final SaveGameId saveGameId;

    public WorldItemPageCodec() {
        this.saveGameId = null;
    }

    public WorldItemPageCodec(SaveGameId saveGameId) {
        this.saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
    }

    public byte[] encode(SaveIdentity saveIdentity, WorldItemPageSnapshot page) {
        return new WorldItemPageCodec(toSaveGameId(saveIdentity)).encode(page);
    }

    public WorldItemPageSnapshot decode(
            SaveIdentity expectedSave,
            ChunkKey expectedKey,
            byte[] bytes) {
        WorldItemPageSnapshot page = new WorldItemPageCodec(toSaveGameId(expectedSave))
                .decode(bytes)
                .page()
                .orElseThrow(() -> new IllegalArgumentException(
                        "WorldItem page failed closed decode"));
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(expectedKey);
        if (!page.chunkKey().equals(checkedKey)) {
            throw new IllegalArgumentException("WorldItem page has the wrong Chunk key");
        }
        return page;
    }

    public byte[] encode(WorldItemPageSnapshot page) {
        if (saveGameId == null) {
            throw new IllegalStateException("Save identity is required for encoding");
        }
        WorldItemPageSnapshot checked = Objects.requireNonNull(page, "page");
        List<WorldItemRestoreEntry> entries = new ArrayList<>(checked.entries());
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("WorldItem page exceeds its entry bound");
        }
        entries.sort(ENTRY_ORDER);
        Set<WorldItemId> ids = new HashSet<>();
        List<byte[]> bodies = new ArrayList<>(entries.size());
        long length = FIXED_HEADER_BYTES + SHA256_BYTES;
        for (WorldItemRestoreEntry entry : entries) {
            WorldItemId id = Objects.requireNonNull(entry, "entry").runtime().item().id();
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate WorldItem ID in page");
            }
            byte[] body = encodeEntry(entry);
            bodies.add(body);
            length = Math.addExact(length, Integer.BYTES + body.length);
        }
        if (length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("WorldItem page exceeds its file bound");
        }
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(Math.toIntExact(length));
            try (DataOutputStream output = new DataOutputStream(encoded)) {
                output.write(MAGIC);
                output.writeInt(VERSION);
                writeUuid(output, saveGameId);
                output.writeInt(checked.chunkKey().x());
                output.writeInt(checked.chunkKey().z());
                output.writeLong(checked.pageRevision());
                output.writeInt(bodies.size());
                for (byte[] body : bodies) {
                    output.writeInt(body.length);
                    output.write(body);
                }
            }
            byte[] bodyAndHeader = encoded.toByteArray();
            byte[] entryPayload = Arrays.copyOfRange(
                    bodyAndHeader, FIXED_HEADER_BYTES, bodyAndHeader.length);
            encoded.write(StreamedChunkCodec.sha256(entryPayload));
            return encoded.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory WorldItem page encoding failed", impossible);
        }
    }

    public DecodeResult decode(byte[] bytes) {
        if (saveGameId == null) {
            throw new IllegalStateException("Save identity is required for decoding");
        }
        try {
            return DecodeResult.valid(decodeChecked(Objects.requireNonNull(bytes, "bytes")));
        } catch (PageFailure failure) {
            return DecodeResult.corrupt(failure.diagnostic);
        } catch (RuntimeException | IOException failure) {
            return DecodeResult.corrupt(diagnostic(
                    "world-item-page.invalid-payload",
                    "The WorldItem page is truncated or malformed",
                    failure));
        }
    }

    private WorldItemPageSnapshot decodeChecked(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) {
            throw failure("world-item-page.file-size-limit",
                    "The WorldItem page exceeds its file bound");
        }
        if (bytes.length < FIXED_HEADER_BYTES + SHA256_BYTES) {
            throw failure("world-item-page.invalid-payload",
                    "The WorldItem page is truncated or malformed");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = readExact(input, MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw failure("world-item-page.invalid-magic", "Invalid WorldItem page magic");
            }
            if (input.readInt() != VERSION) {
                throw failure("world-item-page.unsupported-version",
                        "Unsupported WorldItem page codec version");
            }
            if (!readUuid(input).equals(saveGameId)) {
                throw failure("world-item-page.wrong-save",
                        "The WorldItem page belongs to another save");
            }
            final ChunkKey key;
            try {
                key = ChunkCoordinatePolicy.requireSafe(
                        new ChunkKey(input.readInt(), input.readInt()));
            } catch (IllegalArgumentException unsafe) {
                throw failure("world-item-page.invalid-key",
                        "The WorldItem page key is outside the safe envelope", unsafe);
            }
            long revision = input.readLong();
            if (revision <= 0L) {
                throw failure("world-item-page.invalid-revision",
                        "The WorldItem page revision must be positive");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw failure("world-item-page.entry-count-limit",
                        "The WorldItem page entry count exceeds its bound");
            }
            int checksumOffset = bytes.length - SHA256_BYTES;
            List<WorldItemRestoreEntry> entries = new ArrayList<>(count);
            long previousId = -1L;
            for (int index = 0; index < count; index++) {
                int length = input.readInt();
                if (length <= 0 || length > MAX_ENTRY_BYTES
                        || length > checksumOffset - (bytes.length - input.available())) {
                    throw failure("world-item-page.entry-size-limit",
                            "A WorldItem page entry exceeds its bound");
                }
                WorldItemRestoreEntry entry = decodeEntry(readExact(input, length));
                long id = entry.runtime().item().id().value();
                if (id <= previousId) {
                    throw failure(
                            id == previousId
                                    ? "world-item-page.duplicate-id"
                                    : "world-item-page.noncanonical-order",
                            "WorldItem page IDs are not strictly increasing");
                }
                previousId = id;
                entries.add(entry);
            }
            if (input.available() != SHA256_BYTES) {
                throw failure("world-item-page.trailing-bytes",
                        "The WorldItem page contains trailing bytes");
            }
            byte[] expectedChecksum = readExact(input, SHA256_BYTES);
            byte[] actualChecksum = StreamedChunkCodec.sha256(
                    Arrays.copyOfRange(bytes, FIXED_HEADER_BYTES, checksumOffset));
            if (!Arrays.equals(expectedChecksum, actualChecksum)) {
                throw failure("world-item-page.checksum-mismatch",
                        "The WorldItem page checksum does not match");
            }
            return new WorldItemPageSnapshot(key, revision, entries);
        } catch (EOFException truncated) {
            throw failure("world-item-page.invalid-payload",
                    "The WorldItem page is truncated or malformed", truncated);
        }
    }

    private static byte[] encodeEntry(WorldItemRestoreEntry entry) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                WorldItemRuntimeSnapshot runtime = entry.runtime();
                WorldItemSnapshot item = runtime.item();
                output.writeLong(item.id().value());
                byte[] resource = item.stack().itemId().toString()
                        .getBytes(StandardCharsets.UTF_8);
                if (resource.length == 0 || resource.length > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("WorldItem resource ID exceeds its bound");
                }
                output.writeInt(resource.length);
                output.write(resource);
                output.writeInt(item.stack().count());
                output.writeDouble(item.positionX());
                output.writeDouble(item.positionY());
                output.writeDouble(item.positionZ());
                output.writeDouble(item.velocityX());
                output.writeDouble(item.velocityY());
                output.writeDouble(item.velocityZ());
                output.writeLong(item.revision());
                output.writeBoolean(runtime.source().isPresent());
                if (runtime.source().isPresent()) {
                    output.writeInt(runtime.source().orElseThrow().id());
                }
                output.writeLong(runtime.spawnTick());
                output.writeLong(runtime.pickupAvailableTick());
                output.writeLong(runtime.expiresAtWorldTick());
                output.writeByte(entry.physicalState().ordinal());
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("WorldItem entry exceeds its bound");
            }
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory WorldItem entry encoding failed", impossible);
        }
    }

    private static WorldItemRestoreEntry decodeEntry(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            WorldItemId id = new WorldItemId(input.readLong());
            int resourceLength = input.readInt();
            if (resourceLength <= 0 || resourceLength > MAX_RESOURCE_BYTES) {
                throw new IllegalArgumentException("WorldItem resource ID exceeds its bound");
            }
            byte[] resourceBytes = readExact(input, resourceLength);
            String encodedResource = new String(resourceBytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(resourceBytes, encodedResource.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("WorldItem resource ID is not canonical UTF-8");
            }
            ItemStack stack = new ItemStack(
                    ResourceLocation.parse(encodedResource), input.readInt());
            WorldItemSnapshot item = new WorldItemSnapshot(
                    id,
                    stack,
                    input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readLong());
            int sourcePresent = input.readUnsignedByte();
            if (sourcePresent > 1) {
                throw new IllegalArgumentException("WorldItem source flag is not canonical");
            }
            Optional<EntityRef> source = sourcePresent == 1
                    ? Optional.of(new EntityRef(input.readInt()))
                    : Optional.empty();
            WorldItemRuntimeSnapshot runtime = new WorldItemRuntimeSnapshot(
                    item,
                    source,
                    input.readLong(),
                    input.readLong(),
                    input.readLong());
            int stateOrdinal = input.readUnsignedByte();
            WorldItemPhysicalState[] states = WorldItemPhysicalState.values();
            if (stateOrdinal >= states.length || input.read() != -1) {
                throw new IllegalArgumentException("WorldItem entry is malformed");
            }
            return new WorldItemRestoreEntry(runtime, states[stateOrdinal]);
        }
    }

    private static void writeUuid(DataOutputStream output, SaveGameId id)
            throws IOException {
        UUID uuid = UUID.fromString(id.value());
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static SaveGameId readUuid(DataInputStream input) throws IOException {
        return SaveGameId.parse(new UUID(input.readLong(), input.readLong()).toString());
    }

    private static SaveGameId toSaveGameId(SaveIdentity identity) {
        return SaveGameId.parse(Objects.requireNonNull(identity, "saveIdentity")
                .value().toString());
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }

    private static PageFailure failure(String code, String message) {
        return new PageFailure(SaveDiagnostic.of(code, message));
    }

    private static PageFailure failure(String code, String message, Throwable cause) {
        return new PageFailure(SaveDiagnostic.of(code, message, cause));
    }

    private static SaveDiagnostic diagnostic(String code, String message, Throwable cause) {
        return SaveDiagnostic.of(code, message, cause);
    }

    private static final class PageFailure extends RuntimeException {
        private final SaveDiagnostic diagnostic;

        private PageFailure(SaveDiagnostic diagnostic) {
            super(diagnostic.message(), diagnostic.cause().orElse(null), false, false);
            this.diagnostic = diagnostic;
        }
    }

    /** Closed decode result that never publishes a partial page. */
    public static final class DecodeResult {
        public enum Status { VALID, CORRUPT }

        private final Status status;
        private final WorldItemPageSnapshot page;
        private final List<SaveDiagnostic> diagnostics;

        private DecodeResult(
                Status status,
                WorldItemPageSnapshot page,
                List<SaveDiagnostic> diagnostics) {
            this.status = status;
            this.page = page;
            this.diagnostics = List.copyOf(diagnostics);
        }

        private static DecodeResult valid(WorldItemPageSnapshot page) {
            return new DecodeResult(Status.VALID, page, List.of());
        }

        private static DecodeResult corrupt(SaveDiagnostic diagnostic) {
            return new DecodeResult(Status.CORRUPT, null, List.of(diagnostic));
        }

        public Status status() { return status; }

        public Optional<WorldItemPageSnapshot> page() { return Optional.ofNullable(page); }

        public List<SaveDiagnostic> diagnostics() { return diagnostics; }
    }
}
