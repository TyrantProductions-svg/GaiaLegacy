package com.gaia.save.streaming;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.snapshot.SaveGameSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed result of conservatively importing one Phase 14 archive. */
public final class Phase14MigrationResult {
    public enum Status {
        NOT_REQUIRED,
        MIGRATED,
        FAILED,
        BLOCKING_FAILURE
    }

    private final Status status;
    private final ValidatedV2Manifest validatedManifest;
    private final StreamedChunkIndex validatedIndex;
    private final List<SaveDiagnostic> diagnostics;

    private Phase14MigrationResult(
            Status status,
            ValidatedV2Manifest validatedManifest,
            StreamedChunkIndex validatedIndex,
            List<SaveDiagnostic> diagnostics) {
        this.status = Objects.requireNonNull(status, "status");
        this.validatedManifest = validatedManifest;
        this.validatedIndex = validatedIndex;
        this.diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics"));
        boolean success = status == Status.NOT_REQUIRED || status == Status.MIGRATED;
        if (success != (validatedManifest != null && validatedIndex != null)) {
            throw new IllegalArgumentException(
                    "Only a complete migration result may publish validated authority");
        }
        if (success != this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a complete migration result may omit diagnostics");
        }
    }

    static Phase14MigrationResult migrated(PublishedMigration published) {
        Objects.requireNonNull(published, "published");
        return new Phase14MigrationResult(
                Status.MIGRATED,
                published.manifest(),
                published.index(),
                List.of());
    }

    static Phase14MigrationResult notRequired(PublishedMigration published) {
        Objects.requireNonNull(published, "published");
        return new Phase14MigrationResult(
                Status.NOT_REQUIRED,
                published.manifest(),
                published.index(),
                List.of());
    }

    static Phase14MigrationResult failed(
            Status status, SaveDiagnostic diagnostic) {
        if (status != Status.FAILED && status != Status.BLOCKING_FAILURE) {
            throw new IllegalArgumentException("A failure status is required");
        }
        return new Phase14MigrationResult(
                status,
                null,
                null,
                List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public Status status() {
        return status;
    }

    public Optional<ValidatedV2Manifest> validatedManifest() {
        return Optional.ofNullable(validatedManifest);
    }

    public Optional<StreamedChunkIndex> validatedIndex() {
        return Optional.ofNullable(validatedIndex);
    }

    public List<SaveDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** Validated metadata carried by the fixed v2 migration manifest slots. */
    public record ValidatedV2Manifest(
            SaveFormatVersion formatVersion,
            String gameVersion,
            SaveGameId saveGameId,
            String displayName,
            Instant createdAt,
            Instant modifiedAt,
            long worldSeed,
            String generatorVersion,
            String generatorConfigFingerprint,
            int chunkRadius,
            int worldHeight,
            long fixedTick,
            String summary,
            String sourceArchiveSha256,
            List<SaveSectionDescriptor> sections) {
        public ValidatedV2Manifest {
            if (!SaveFormatVersion.STREAMED_CHUNKS.equals(formatVersion)) {
                throw new IllegalArgumentException("A migration manifest must be v2");
            }
            Objects.requireNonNull(gameVersion, "gameVersion");
            Objects.requireNonNull(saveGameId, "saveGameId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(modifiedAt, "modifiedAt");
            Objects.requireNonNull(generatorVersion, "generatorVersion");
            Objects.requireNonNull(
                    generatorConfigFingerprint, "generatorConfigFingerprint");
            Objects.requireNonNull(sourceArchiveSha256, "sourceArchiveSha256");
            sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
            if (!sourceArchiveSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "The source archive hash is not canonical SHA-256");
            }
        }
    }

    /** Fully reread v2 authority used by the archive reader and repository. */
    public record PublishedMigration(
            ValidatedV2Manifest manifest,
            StreamedChunkIndex index,
            SaveGameSnapshot snapshot) {
        public PublishedMigration {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
