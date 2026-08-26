package com.gaia.save.streaming;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable canonical payload for one persisted modified Chunk. */
public final class StreamedChunkPayload {
    static final int MAX_GENERATOR_VERSION_BYTES = 256;
    static final int MAX_EXTENSION_ID_BYTES = 128;
    static final String SHA_256_HEX = "[0-9a-f]{64}";
    static final Comparator<ExtensionDescriptor> EXTENSION_ORDER =
            Comparator.comparing(
                            (ExtensionDescriptor extension) ->
                                    extension.sectionId().value())
                    .thenComparingInt(ExtensionDescriptor::codecVersion);

    private final SaveGameId saveGameId;
    private final ChunkKey key;
    private final String generatorVersion;
    private final String baseHash;
    private final long revision;
    private final long persistedRevision;
    private final boolean persistenceRequired;
    private final boolean voxelModified;
    private final int worldHeight;
    private final byte[] canonicalVoxels;
    private final List<ExtensionDescriptor> extensions;

    public StreamedChunkPayload(
            SaveGameId saveGameId,
            ChunkKey key,
            String generatorVersion,
            String baseHash,
            long revision,
            long persistedRevision,
            boolean persistenceRequired,
            boolean voxelModified,
            int worldHeight,
            byte[] canonicalVoxels,
            List<ExtensionDescriptor> extensions) {
        this.saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
        this.key = ChunkCoordinatePolicy.requireSafe(key);
        this.generatorVersion = requireBoundedText(
                generatorVersion,
                "generatorVersion",
                MAX_GENERATOR_VERSION_BYTES);
        this.baseHash = requireHash(baseHash, "baseHash");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (persistedRevision < 0 || persistedRevision > revision) {
            throw new IllegalArgumentException(
                    "persistedRevision must be between zero and revision");
        }
        List<ExtensionDescriptor> checkedExtensions = new ArrayList<>(
                Objects.requireNonNull(extensions, "extensions"));
        checkedExtensions.replaceAll(extension ->
                Objects.requireNonNull(extension, "extension"));
        checkedExtensions.sort(EXTENSION_ORDER);
        Set<SaveSectionId> extensionIds = new HashSet<>();
        for (ExtensionDescriptor extension : checkedExtensions) {
            if (!extensionIds.add(extension.sectionId())) {
                throw new IllegalArgumentException("Duplicate extension section ID");
            }
        }
        boolean hasRequiredRuntimeExtension = checkedExtensions.stream()
                .anyMatch(ExtensionDescriptor::required);
        if (!persistenceRequired) {
            throw new IllegalArgumentException(
                    "Streamed Chunk payloads must be persistence-required");
        }
        if (persistenceRequired != (voxelModified || hasRequiredRuntimeExtension)) {
            throw new IllegalArgumentException(
                    "persistenceRequired must match voxel or required runtime state");
        }
        if (worldHeight <= 0 || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException("worldHeight is unsupported");
        }
        byte[] checkedVoxels = Objects.requireNonNull(
                canonicalVoxels, "canonicalVoxels");
        int expectedVoxelLength = Math.multiplyExact(
                Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                GameConfig.Chunk.SIZE);
        if (checkedVoxels.length != expectedVoxelLength) {
            throw new IllegalArgumentException(
                    "canonicalVoxels length does not match worldHeight");
        }
        this.revision = revision;
        this.persistedRevision = persistedRevision;
        this.persistenceRequired = persistenceRequired;
        this.voxelModified = voxelModified;
        this.worldHeight = worldHeight;
        this.canonicalVoxels = checkedVoxels.clone();
        this.extensions = List.copyOf(checkedExtensions);
    }

    /** Legacy source-compatible constructor; legacy persisted payloads are voxel-modified. */
    public StreamedChunkPayload(
            SaveGameId saveGameId,
            ChunkKey key,
            String generatorVersion,
            String baseHash,
            long revision,
            long persistedRevision,
            boolean modified,
            int worldHeight,
            byte[] canonicalVoxels,
            List<ExtensionDescriptor> extensions) {
        this(
                saveGameId,
                key,
                generatorVersion,
                baseHash,
                revision,
                persistedRevision,
                modified,
                modified,
                worldHeight,
                canonicalVoxels,
                extensions);
    }

    public SaveGameId saveGameId() {
        return saveGameId;
    }

    public ChunkKey key() {
        return key;
    }

    public String generatorVersion() {
        return generatorVersion;
    }

    public String baseHash() {
        return baseHash;
    }

    public long revision() {
        return revision;
    }

    public long persistedRevision() {
        return persistedRevision;
    }

    public boolean persistenceRequired() {
        return persistenceRequired;
    }

    public boolean voxelModified() {
        return voxelModified;
    }

    /** @deprecated use {@link #persistenceRequired()} or {@link #voxelModified()}. */
    @Deprecated
    public boolean modified() {
        return persistenceRequired;
    }

    public int worldHeight() {
        return worldHeight;
    }

    public byte[] copyCanonicalVoxels() {
        return canonicalVoxels.clone();
    }

    public List<ExtensionDescriptor> extensions() {
        return extensions;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof StreamedChunkPayload other)) {
            return false;
        }
        return revision == other.revision
                && persistedRevision == other.persistedRevision
                && persistenceRequired == other.persistenceRequired
                && voxelModified == other.voxelModified
                && worldHeight == other.worldHeight
                && saveGameId.equals(other.saveGameId)
                && key.equals(other.key)
                && generatorVersion.equals(other.generatorVersion)
                && baseHash.equals(other.baseHash)
                && Arrays.equals(canonicalVoxels, other.canonicalVoxels)
                && extensions.equals(other.extensions);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                saveGameId,
                key,
                generatorVersion,
                baseHash,
                revision,
                persistedRevision,
                persistenceRequired,
                voxelModified,
                worldHeight,
                extensions);
        return 31 * result + Arrays.hashCode(canonicalVoxels);
    }

    static String requireHash(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches(SHA_256_HEX)) {
            throw new IllegalArgumentException(
                    field + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static String requireBoundedText(
            String value, String field, int maximumUtf8Bytes) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes) {
            throw new IllegalArgumentException(field + " is blank or exceeds its bound");
        }
        return value;
    }

    /** Versioned integrity-protected optional extension payload. */
    public static final class ExtensionDescriptor {
        private final SaveSectionId sectionId;
        private final int codecVersion;
        private final boolean required;
        private final byte[] bytes;

        public ExtensionDescriptor(
                SaveSectionId sectionId,
                int codecVersion,
                boolean required,
                byte[] bytes) {
            this.sectionId = Objects.requireNonNull(sectionId, "sectionId");
            requireBoundedText(
                    sectionId.value(), "sectionId", MAX_EXTENSION_ID_BYTES);
            if (codecVersion <= 0) {
                throw new IllegalArgumentException("codecVersion must be positive");
            }
            this.codecVersion = codecVersion;
            this.required = required;
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public SaveSectionId sectionId() {
            return sectionId;
        }

        public int codecVersion() {
            return codecVersion;
        }

        public boolean required() {
            return required;
        }

        public byte[] copyBytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof ExtensionDescriptor other)) {
                return false;
            }
            return codecVersion == other.codecVersion
                    && required == other.required
                    && sectionId.equals(other.sectionId)
                    && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(sectionId, codecVersion, required)
                    + Arrays.hashCode(bytes);
        }
    }
}
