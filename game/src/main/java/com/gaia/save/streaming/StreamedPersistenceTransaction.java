package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bounded all-or-nothing candidate set for Chunk and inline global bytes. */
public final class StreamedPersistenceTransaction {
    public static final int MAX_CHUNKS = 64;
    public static final long MAX_CANDIDATE_BYTES = 64L * 1024L * 1024L;

    private final List<StreamedChunkMutation> chunks;
    private final List<StreamedGlobalExtensionMutation> globalExtensionMutations;
    private final BooleanSupplier stillCurrent;

    public StreamedPersistenceTransaction(
            List<StreamedChunkMutation> chunks,
            List<StreamedGlobalExtensionMutation> globalExtensionMutations,
            BooleanSupplier stillCurrent) {
        this.chunks = validateChunkMutations(chunks);
        this.globalExtensionMutations = validateGlobalMutations(
                globalExtensionMutations);
        this.stillCurrent = Objects.requireNonNull(stillCurrent, "stillCurrent");
        if (encodedCandidateBytes() > MAX_CANDIDATE_BYTES) {
            throw new IllegalArgumentException(
                    "Transaction candidate bytes exceed its bound");
        }
    }

    public List<StreamedChunkMutation> chunks() {
        return chunks;
    }

    public List<StreamedGlobalExtensionMutation> globalExtensionMutations() {
        return globalExtensionMutations;
    }

    public BooleanSupplier stillCurrent() {
        return stillCurrent;
    }

    public long encodedCandidateBytes() {
        long bytes = 0L;
        for (StreamedChunkMutation mutation : chunks) {
            if (mutation instanceof StreamedChunkMutation.Upsert upsert) {
                bytes = Math.addExact(
                        bytes,
                        StreamedChunkCodec.canonicalEncodedSize(
                                upsert.capture().payload()));
            }
        }
        for (StreamedGlobalExtensionMutation mutation : globalExtensionMutations) {
            if (mutation instanceof StreamedGlobalExtensionMutation.Upsert upsert) {
                bytes = Math.addExact(
                        bytes, upsert.extension().canonicalEncodedSize());
            }
        }
        return bytes;
    }

    private static List<StreamedChunkMutation> validateChunkMutations(
            List<StreamedChunkMutation> source) {
        Objects.requireNonNull(source, "chunks");
        if (source.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException(
                    "Transaction Chunk count exceeds its bound");
        }
        List<StreamedChunkMutation> checked = List.copyOf(source);
        Set<ChunkKey> keys = new HashSet<>();
        for (StreamedChunkMutation mutation : checked) {
            Objects.requireNonNull(mutation, "Chunk mutation");
            ChunkKey key = mutation instanceof StreamedChunkMutation.Upsert upsert
                    ? upsert.capture().payload().key()
                    : ((StreamedChunkMutation.Remove) mutation).key();
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "Transaction repeats a Chunk mutation");
            }
        }
        return checked;
    }

    private static List<StreamedGlobalExtensionMutation> validateGlobalMutations(
            List<StreamedGlobalExtensionMutation> source) {
        Objects.requireNonNull(source, "globalExtensionMutations");
        List<StreamedGlobalExtensionMutation> checked = List.copyOf(source);
        Set<SaveSectionId> ids = new HashSet<>();
        long retainedBytes = 0L;
        for (StreamedGlobalExtensionMutation mutation : checked) {
            Objects.requireNonNull(mutation, "global extension mutation");
            SaveSectionId id;
            if (mutation instanceof StreamedGlobalExtensionMutation.Upsert upsert) {
                id = upsert.extension().sectionId();
                retainedBytes = Math.addExact(
                        retainedBytes, upsert.extension().canonicalEncodedSize());
            } else {
                id = ((StreamedGlobalExtensionMutation.Remove) mutation).sectionId();
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "Transaction repeats a global extension mutation");
            }
        }
        if (retainedBytes > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                    "Transaction global extension bytes exceed their bound");
        }
        return checked;
    }
}
