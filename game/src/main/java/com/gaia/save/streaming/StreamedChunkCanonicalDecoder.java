package com.gaia.save.streaming;

import com.gaia.blocks.BlockRegistry;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveSectionId;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.DetailChunkSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Fail-closed semantic decoder from one streamed payload to engine Chunk data. */
public final class StreamedChunkCanonicalDecoder {
    private final BlockRegistry blockRegistry;
    private final DetailBlocksCodec detailCodec;

    public StreamedChunkCanonicalDecoder(BlockRegistry blockRegistry) {
        this(blockRegistry, new DetailBlocksCodec());
    }

    StreamedChunkCanonicalDecoder(
            BlockRegistry blockRegistry, DetailBlocksCodec detailCodec) {
        this.blockRegistry = Objects.requireNonNull(blockRegistry, "blockRegistry");
        this.detailCodec = Objects.requireNonNull(detailCodec, "detailCodec");
    }

    public DecodeResult decode(StreamedChunkPayload payload) {
        StreamedChunkPayload checked = Objects.requireNonNull(payload, "payload");
        Optional<StreamedChunkPayload.ExtensionDescriptor> extension =
                checked.extensions().stream()
                        .filter(value -> value.sectionId().equals(
                                SaveSectionId.DETAIL_BLOCKS))
                        .findFirst();
        if (extension.isEmpty()) {
            try {
                return DecodeResult.valid(new ChunkGenerationData(
                        checked.key(),
                        checked.worldHeight(),
                        checked.copyCanonicalVoxels()));
            } catch (RuntimeException malformed) {
                return DecodeResult.failure(
                        DecodeResult.Status.CORRUPT,
                        SaveDiagnostic.of(
                                "streamed-chunk.invalid-canonical-state",
                                "The streamed Chunk canonical state is invalid",
                                malformed));
            }
        }

        DetailBlocksCodec.DecodeResult details = detailCodec.decode(
                extension.orElseThrow(),
                checked.worldHeight(),
                checked.copyCanonicalVoxels(),
                blockRegistry);
        if (details.status() != DetailBlocksCodec.DecodeResult.Status.VALID) {
            return DecodeResult.failure(
                    details.status()
                                    == DetailBlocksCodec.DecodeResult.Status.UNSUPPORTED_VERSION
                            ? DecodeResult.Status.UNSUPPORTED_EXTENSION
                            : DecodeResult.Status.CORRUPT,
                    details.diagnostic());
        }
        try {
            DetailChunkSnapshot decoded = details.details().orElseThrow();
            return DecodeResult.valid(new ChunkGenerationData(
                    checked.key(),
                    checked.worldHeight(),
                    checked.copyCanonicalVoxels(),
                    decoded));
        } catch (RuntimeException malformed) {
            return DecodeResult.failure(
                    DecodeResult.Status.CORRUPT,
                    SaveDiagnostic.of(
                            "streamed-chunk.invalid-canonical-state",
                            "The streamed Chunk canonical state is invalid",
                            malformed));
        }
    }

    /** Closed result: only VALID exposes detached canonical Chunk data. */
    public static final class DecodeResult {
        public enum Status { VALID, CORRUPT, UNSUPPORTED_EXTENSION }

        private final Status status;
        private final ChunkGenerationData chunkData;
        private final SaveDiagnostic diagnostic;

        private DecodeResult(
                Status status,
                ChunkGenerationData chunkData,
                SaveDiagnostic diagnostic) {
            this.status = Objects.requireNonNull(status, "status");
            this.chunkData = chunkData;
            this.diagnostic = diagnostic;
        }

        private static DecodeResult valid(ChunkGenerationData chunkData) {
            return new DecodeResult(
                    Status.VALID,
                    Objects.requireNonNull(chunkData, "chunkData"),
                    null);
        }

        private static DecodeResult failure(Status status, SaveDiagnostic diagnostic) {
            return new DecodeResult(
                    status, null, Objects.requireNonNull(diagnostic, "diagnostic"));
        }

        public Status status() {
            return status;
        }

        public Optional<ChunkGenerationData> chunkData() {
            return Optional.ofNullable(chunkData);
        }

        public Optional<SaveDiagnostic> diagnostic() {
            return Optional.ofNullable(diagnostic);
        }
    }
}
