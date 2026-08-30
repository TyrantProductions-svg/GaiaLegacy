package com.gaia.save.streaming;

import com.gaia.blocks.BlockRegistry;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveSectionId;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Deterministic game-identity codec for the mandatory-if-present DETAIL extension. */
public final class DetailBlocksCodec {
    public static final int CODEC_VERSION = 1;
    public static final int MAX_V1_ENCODED_BYTES = 108_938;
    private static final byte[] MAGIC = {'G', 'L', 'D', '1'};
    private static final int SCALE = 4;
    private static final int MAX_PALETTE_ENTRIES = 255;
    private static final int MAX_RESOURCE_LOCATION_BYTES = 128;
    private static final int ENTRY_BYTES = 2 + 8 + DetailCellState.CELL_COUNT;

    public Optional<StreamedChunkPayload.ExtensionDescriptor> encode(
            ChunkSnapshot snapshot, BlockRegistry registry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");
        DetailChunkSnapshot details = snapshot.details();
        if (details.isEmpty()) {
            return Optional.empty();
        }

        int[] parents = details.copyParentIndices();
        long[] masks = details.copyOccupancyMasks();
        byte[] ids = details.copyBlockIds();
        TreeSet<ResourceLocation> paletteSet = new TreeSet<>();
        for (int entry = 0; entry < parents.length; entry++) {
            for (int sub = 0; sub < DetailCellState.CELL_COUNT; sub++) {
                byte id = ids[entry * DetailCellState.CELL_COUNT + sub];
                if (id != 0) {
                    ResourceLocation name = registry.require(id).name();
                    if (registry.requireStoredId(name) == 0) {
                        throw new IllegalArgumentException(
                                "DETAIL palette must not contain AIR");
                    }
                    requirePaletteText(name.toString());
                    paletteSet.add(name);
                }
            }
        }
        if (paletteSet.isEmpty() || paletteSet.size() > MAX_PALETTE_ENTRIES) {
            throw new IllegalArgumentException("DETAIL palette exceeds its bound");
        }
        List<ResourceLocation> palette = List.copyOf(paletteSet);
        Map<ResourceLocation, Integer> codes = new HashMap<>();
        for (int index = 0; index < palette.size(); index++) {
            codes.put(palette.get(index), index + 1);
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeByte(SCALE);
                output.writeByte(0);
                output.writeShort(palette.size());
                output.writeInt(parents.length);
                for (ResourceLocation name : palette) {
                    byte[] text = name.toString().getBytes(StandardCharsets.UTF_8);
                    output.writeShort(text.length);
                    output.write(text);
                }
                for (int entry = 0; entry < parents.length; entry++) {
                    output.writeShort(parents[entry]);
                    output.writeLong(masks[entry]);
                    for (int sub = 0; sub < DetailCellState.CELL_COUNT; sub++) {
                        byte id = ids[entry * DetailCellState.CELL_COUNT + sub];
                        output.writeByte(id == 0
                                ? 0
                                : codes.get(registry.require(id).name()));
                    }
                }
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_V1_ENCODED_BYTES
                    || payload.length > StreamedChunkCodec.MAX_EXTENSION_BYTES) {
                throw new IllegalArgumentException(
                        "DETAIL extension exceeds its bounded size");
            }
            return Optional.of(new StreamedChunkPayload.ExtensionDescriptor(
                    SaveSectionId.DETAIL_BLOCKS,
                    CODEC_VERSION,
                    true,
                    payload));
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory DETAIL encoding failed", impossible);
        }
    }

    public DecodeResult decode(
            StreamedChunkPayload.ExtensionDescriptor extension,
            int worldHeight,
            byte[] canonicalFullVoxels,
            BlockRegistry registry) {
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(canonicalFullVoxels, "canonicalFullVoxels");
        Objects.requireNonNull(registry, "registry");
        if (!SaveSectionId.DETAIL_BLOCKS.equals(extension.sectionId())) {
            return corrupt("detail-blocks.invalid-section", "The extension ID is not detail-blocks");
        }
        if (extension.codecVersion() != CODEC_VERSION) {
            return unsupported("detail-blocks.unsupported-version",
                    "The DETAIL extension codec version is unsupported");
        }
        if (!extension.required()) {
            return corrupt("detail-blocks.invalid-required-flag",
                    "A present DETAIL extension must be required");
        }
        int expectedVoxels;
        try {
            if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
                throw new IllegalArgumentException("worldHeight is unsupported");
            }
            expectedVoxels = Math.multiplyExact(
                    Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                    GameConfig.Chunk.SIZE);
        } catch (RuntimeException malformed) {
            return corrupt("detail-blocks.parent-out-of-range",
                    "The DETAIL parent bounds are invalid");
        }
        if (canonicalFullVoxels.length != expectedVoxels) {
            return corrupt("detail-blocks.parent-out-of-range",
                    "The FULL backing length is not canonical");
        }

        byte[] bytes = extension.copyBytes();
        if (bytes.length > MAX_V1_ENCODED_BYTES
                || bytes.length > StreamedChunkCodec.MAX_EXTENSION_BYTES) {
            return corrupt("detail-blocks.size-limit",
                    "The DETAIL extension exceeds its bounded size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, readExact(input, MAGIC.length))) {
                throw failure("detail-blocks.invalid-magic",
                        "The DETAIL extension magic is invalid");
            }
            if (input.readUnsignedByte() != SCALE) {
                throw failure("detail-blocks.unsupported-scale",
                        "The DETAIL extension scale is unsupported");
            }
            if (input.readUnsignedByte() != 0) {
                throw failure("detail-blocks.invalid-flags",
                        "The DETAIL extension flags are invalid");
            }
            int paletteCount = input.readUnsignedShort();
            if (paletteCount < 1 || paletteCount > MAX_PALETTE_ENTRIES) {
                throw failure("detail-blocks.palette-bound",
                        "The DETAIL palette count exceeds its bound");
            }
            long parentCount = Integer.toUnsignedLong(input.readInt());
            if (parentCount < 1 || parentCount > Chunk.MAX_DETAIL_PARENTS_PER_CHUNK) {
                throw failure("detail-blocks.parent-count-bound",
                        "The DETAIL parent count exceeds its bound");
            }

            byte[] runtimeIds = new byte[paletteCount + 1];
            ResourceLocation previous = null;
            for (int paletteIndex = 1; paletteIndex <= paletteCount; paletteIndex++) {
                int length = input.readUnsignedShort();
                if (length < 1 || length > MAX_RESOURCE_LOCATION_BYTES) {
                    throw failure("detail-blocks.palette-bound",
                            "A DETAIL palette name exceeds its bound");
                }
                String text = decodeCanonicalUtf8(readExact(input, length));
                ResourceLocation name;
                try {
                    name = ResourceLocation.parse(text);
                } catch (RuntimeException malformed) {
                    throw failure("detail-blocks.noncanonical-palette",
                            "A DETAIL palette name is invalid");
                }
                if (previous != null && previous.compareTo(name) >= 0) {
                    throw failure("detail-blocks.noncanonical-palette",
                            "The DETAIL palette is not strictly ordered");
                }
                byte runtimeId;
                try {
                    runtimeId = registry.requireStoredId(name);
                } catch (RuntimeException unknown) {
                    throw failure("detail-blocks.unknown-material",
                            "A DETAIL palette material is unknown");
                }
                if (runtimeId == 0) {
                    throw failure("detail-blocks.noncanonical-palette",
                            "The DETAIL palette must not contain AIR");
                }
                runtimeIds[paletteIndex] = runtimeId;
                previous = name;
            }

            int count = Math.toIntExact(parentCount);
            int[] parents = new int[count];
            long[] masks = new long[count];
            byte[] ids = new byte[Math.multiplyExact(count, DetailCellState.CELL_COUNT)];
            int previousParent = -1;
            for (int entry = 0; entry < count; entry++) {
                int parent = input.readUnsignedShort();
                if (parent >= expectedVoxels) {
                    throw failure("detail-blocks.parent-out-of-range",
                            "A DETAIL parent is outside the Chunk height");
                }
                if (parent == previousParent) {
                    throw failure("detail-blocks.duplicate-parent",
                            "The DETAIL extension repeats a parent");
                }
                if (parent < previousParent) {
                    throw failure("detail-blocks.noncanonical-parent-order",
                            "DETAIL parents are not strictly ordered");
                }
                if (canonicalFullVoxels[parent] != 0) {
                    throw failure("detail-blocks.full-backing-conflict",
                            "A DETAIL parent has non-AIR FULL backing");
                }
                long mask = input.readLong();
                if (mask == 0L) {
                    throw failure("detail-blocks.empty-detail",
                            "A stored DETAIL parent must not be empty");
                }
                for (int sub = 0; sub < DetailCellState.CELL_COUNT; sub++) {
                    int code = input.readUnsignedByte();
                    boolean occupied = (mask & (1L << sub)) != 0L;
                    if (occupied != (code != 0) || code > paletteCount) {
                        throw failure("detail-blocks.invalid-occupancy-material",
                                "DETAIL occupancy and material code disagree");
                    }
                    ids[entry * DetailCellState.CELL_COUNT + sub] = runtimeIds[code];
                }
                parents[entry] = parent;
                masks[entry] = mask;
                previousParent = parent;
            }
            if (input.read() != -1) {
                throw failure("detail-blocks.trailing-bytes",
                        "The DETAIL extension contains trailing bytes");
            }
            return DecodeResult.valid(DetailChunkSnapshot.of(parents, masks, ids));
        } catch (EOFException truncated) {
            return corrupt("detail-blocks.invalid-payload",
                    "The DETAIL extension is truncated");
        } catch (CodecFailure invalid) {
            return corrupt(invalid.code, invalid.getMessage());
        } catch (IOException | RuntimeException malformed) {
            return corrupt("detail-blocks.invalid-payload",
                    "The DETAIL extension is malformed");
        }
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static String decodeCanonicalUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw failure("detail-blocks.noncanonical-palette",
                    "A DETAIL palette name is not canonical UTF-8");
        }
    }

    private static void requirePaletteText(String text) {
        int length = text.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > MAX_RESOURCE_LOCATION_BYTES) {
            throw new IllegalArgumentException("DETAIL palette name exceeds its bound");
        }
    }

    private static CodecFailure failure(String code, String message) {
        return new CodecFailure(code, message);
    }

    private static DecodeResult corrupt(String code, String message) {
        return DecodeResult.failure(
                DecodeResult.Status.CORRUPT, SaveDiagnostic.of(code, message));
    }

    private static DecodeResult unsupported(String code, String message) {
        return DecodeResult.failure(
                DecodeResult.Status.UNSUPPORTED_VERSION, SaveDiagnostic.of(code, message));
    }

    private static final class CodecFailure extends RuntimeException {
        private final String code;

        private CodecFailure(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** Closed semantic result; failures never expose a partial DETAIL snapshot. */
    public static final class DecodeResult {
        public enum Status { VALID, CORRUPT, UNSUPPORTED_VERSION }

        private final Status status;
        private final DetailChunkSnapshot details;
        private final SaveDiagnostic diagnostic;

        private DecodeResult(
                Status status, DetailChunkSnapshot details, SaveDiagnostic diagnostic) {
            this.status = Objects.requireNonNull(status, "status");
            this.details = details;
            this.diagnostic = diagnostic;
        }

        private static DecodeResult valid(DetailChunkSnapshot details) {
            return new DecodeResult(Status.VALID,
                    Objects.requireNonNull(details, "details"), null);
        }

        private static DecodeResult failure(Status status, SaveDiagnostic diagnostic) {
            return new DecodeResult(status, null,
                    Objects.requireNonNull(diagnostic, "diagnostic"));
        }

        public Status status() {
            return status;
        }

        public Optional<DetailChunkSnapshot> details() {
            return Optional.ofNullable(details);
        }

        public SaveDiagnostic diagnostic() {
            if (diagnostic == null) {
                throw new IllegalStateException("A valid DETAIL decode has no diagnostic");
            }
            return diagnostic;
        }
    }
}
