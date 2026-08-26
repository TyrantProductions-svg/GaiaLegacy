package com.gaia.save.streaming;

import com.gaia.save.format.SaveGameId;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable canonical index of persisted modified streamed Chunks. */
public final class StreamedChunkIndex {
    private static final Comparator<Entry> ENTRY_ORDER =
            Comparator.comparing(Entry::key, ChunkCoordinatePolicy.canonicalComparator());

    private final SaveGameId saveGameId;
    private final MigrationCompatibility migrationCompatibility;
    private final List<Entry> entries;
    private final List<StreamedGlobalExtension> globalExtensions;

    public StreamedChunkIndex(SaveGameId saveGameId, List<Entry> entries) {
        this(saveGameId, null, entries, List.of());
    }

    public StreamedChunkIndex(
            SaveGameId saveGameId,
            MigrationCompatibility migrationCompatibility,
            List<Entry> entries) {
        this(saveGameId, migrationCompatibility, entries, List.of());
    }

    public StreamedChunkIndex(
            SaveGameId saveGameId,
            MigrationCompatibility migrationCompatibility,
            List<Entry> entries,
            List<StreamedGlobalExtension> globalExtensions) {
        this.saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
        this.migrationCompatibility = migrationCompatibility;
        List<Entry> checked = new ArrayList<>(
                Objects.requireNonNull(entries, "entries"));
        checked.replaceAll(entry -> Objects.requireNonNull(entry, "entry"));
        checked.sort(ENTRY_ORDER);
        Set<ChunkKey> keys = new HashSet<>();
        for (Entry entry : checked) {
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("Duplicate streamed Chunk index key");
            }
        }
        this.entries = List.copyOf(checked);
        List<StreamedGlobalExtension> checkedGlobals = new ArrayList<>(
                Objects.requireNonNull(globalExtensions, "globalExtensions"));
        checkedGlobals.replaceAll(value -> Objects.requireNonNull(value, "global extension"));
        checkedGlobals.sort(Comparator.comparing(value -> value.sectionId().value()));
        Set<com.gaia.save.format.SaveSectionId> globalIds = new HashSet<>();
        long globalBytes = 0L;
        for (StreamedGlobalExtension extension : checkedGlobals) {
            if (!globalIds.add(extension.sectionId())) {
                throw new IllegalArgumentException("Duplicate global extension ID");
            }
            globalBytes = Math.addExact(
                    globalBytes, extension.canonicalEncodedSize());
        }
        if (globalBytes > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("Global extension bytes exceed their bound");
        }
        this.globalExtensions = List.copyOf(checkedGlobals);
    }

    public SaveGameId saveGameId() {
        return saveGameId;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<StreamedGlobalExtension> globalExtensions() {
        return globalExtensions;
    }

    public java.util.Optional<StreamedGlobalExtension> globalExtension(
            com.gaia.save.format.SaveSectionId sectionId) {
        Objects.requireNonNull(sectionId, "sectionId");
        return globalExtensions.stream()
                .filter(extension -> extension.sectionId().equals(sectionId))
                .findFirst();
    }

    public java.util.Optional<MigrationCompatibility> migrationCompatibility() {
        return java.util.Optional.ofNullable(migrationCompatibility);
    }

    public java.util.Optional<Entry> entry(ChunkKey key) {
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        return entries.stream().filter(entry -> entry.key().equals(checkedKey)).findFirst();
    }

    public StreamedChunkIndex with(Entry replacement) {
        Entry checked = Objects.requireNonNull(replacement, "replacement");
        List<Entry> updated = new ArrayList<>(entries.size() + 1);
        for (Entry entry : entries) {
            if (!entry.key().equals(checked.key())) {
                updated.add(entry);
            }
        }
        updated.add(checked);
        return new StreamedChunkIndex(
                saveGameId, migrationCompatibility, updated, globalExtensions);
    }

    public StreamedChunkIndex without(ChunkKey key) {
        ChunkKey checked = ChunkCoordinatePolicy.requireSafe(key);
        List<Entry> updated = entries.stream()
                .filter(entry -> !entry.key().equals(checked))
                .toList();
        return new StreamedChunkIndex(
                saveGameId, migrationCompatibility, updated, globalExtensions);
    }

    public StreamedChunkIndex withGlobalExtension(StreamedGlobalExtension replacement) {
        Objects.requireNonNull(replacement, "replacement");
        List<StreamedGlobalExtension> updated = new ArrayList<>(globalExtensions.size() + 1);
        for (StreamedGlobalExtension extension : globalExtensions) {
            if (!extension.sectionId().equals(replacement.sectionId())) {
                updated.add(extension);
            }
        }
        updated.add(replacement);
        return new StreamedChunkIndex(
                saveGameId, migrationCompatibility, entries, updated);
    }

    public StreamedChunkIndex withoutGlobalExtension(
            com.gaia.save.format.SaveSectionId sectionId) {
        Objects.requireNonNull(sectionId, "sectionId");
        List<StreamedGlobalExtension> updated = globalExtensions.stream()
                .filter(extension -> !extension.sectionId().equals(sectionId))
                .toList();
        return new StreamedChunkIndex(
                saveGameId, migrationCompatibility, entries, updated);
    }

    public StreamedChunkIndex withMigrationCompatibility(
            MigrationCompatibility compatibility) {
        MigrationCompatibility checked = Objects.requireNonNull(
                compatibility, "compatibility");
        if (migrationCompatibility != null
                && !migrationCompatibility.equals(checked)) {
            throw new IllegalArgumentException(
                    "Migration compatibility cannot be replaced");
        }
        return new StreamedChunkIndex(
                saveGameId, checked, entries, globalExtensions);
    }

    /** Immutable Phase 14 publication proof carried by every later index. */
    public record MigrationCompatibility(
            String sourceArchiveSha256, String migrationMarkerSha256) {
        public MigrationCompatibility {
            sourceArchiveSha256 = StreamedChunkPayload.requireHash(
                    sourceArchiveSha256, "sourceArchiveSha256");
            migrationMarkerSha256 = StreamedChunkPayload.requireHash(
                    migrationMarkerSha256, "migrationMarkerSha256");
        }
    }

    /** Exact immutable identity of one validated payload file. */
    public record Entry(
            ChunkKey key,
            String generatorVersion,
            String baseHash,
            long revision,
            long payloadSize,
            String payloadHash,
            boolean persistenceRequired,
            boolean voxelModified) {
        public Entry {
            key = ChunkCoordinatePolicy.requireSafe(key);
            generatorVersion = StreamedChunkPayload.requireBoundedText(
                    generatorVersion,
                    "generatorVersion",
                    StreamedChunkPayload.MAX_GENERATOR_VERSION_BYTES);
            baseHash = StreamedChunkPayload.requireHash(baseHash, "baseHash");
            if (revision <= 0) {
                throw new IllegalArgumentException("revision must be positive");
            }
            if (payloadSize <= 0 || payloadSize > StreamedChunkCodec.MAX_FILE_BYTES) {
                throw new IllegalArgumentException("payloadSize exceeds its bound");
            }
            payloadHash = StreamedChunkPayload.requireHash(
                    payloadHash, "payloadHash");
            if (!persistenceRequired) {
                throw new IllegalArgumentException(
                        "Streamed Chunk index entries must be persistence-required");
            }
        }

        /** Legacy source-compatible constructor; v2 entries were voxel-modified. */
        public Entry(
                ChunkKey key,
                String generatorVersion,
                String baseHash,
                long revision,
                long payloadSize,
                String payloadHash,
                boolean modified) {
            this(
                    key,
                    generatorVersion,
                    baseHash,
                    revision,
                    payloadSize,
                    payloadHash,
                    modified,
                    modified);
        }

        /** @deprecated use {@link #persistenceRequired()} or {@link #voxelModified()}. */
        @Deprecated
        public boolean modified() {
            return persistenceRequired;
        }
    }

}
