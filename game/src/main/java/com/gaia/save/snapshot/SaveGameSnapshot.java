package com.gaia.save.snapshot;

import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveMetadataValidation;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deeply immutable aggregate authority captured before save encoding begins. */
public record SaveGameSnapshot(
        StaticMetadata metadata,
        long fixedTick,
        ChunkRepositorySnapshot chunks,
        PlayerSaveSnapshot player,
        InventorySaveSnapshot inventory,
        WorldItemsSaveSnapshot worldItems) {
    public SaveGameSnapshot {
        metadata = Objects.requireNonNull(metadata, "metadata");
        fixedTick = SaveMetadataValidation.requireNonnegativeFixedTick(fixedTick);
        chunks = detachedChunks(Objects.requireNonNull(chunks, "chunks"));
        player = Objects.requireNonNull(player, "player");
        inventory = Objects.requireNonNull(inventory, "inventory");
        worldItems = Objects.requireNonNull(worldItems, "worldItems");

        validateChunks(chunks);
        if (metadata.worldHeight() != chunks.worldHeight()) {
            throw new IllegalArgumentException(
                    "metadata worldHeight must match the chunk snapshot");
        }
        if (fixedTick != worldItems.fixedTick()) {
            throw new IllegalArgumentException(
                    "aggregate fixedTick must match the world-item snapshot");
        }
        if (!player.owner().equals(inventory.owner())) {
            throw new IllegalArgumentException(
                    "player and inventory snapshots must have the same owner");
        }
    }

    private static ChunkRepositorySnapshot detachedChunks(
            ChunkRepositorySnapshot chunks) {
        return new ChunkRepositorySnapshot(
                chunks.worldHeight(), chunks.revisionHighWater(), chunks.chunks());
    }

    private static void validateChunks(ChunkRepositorySnapshot chunks) {
        if (chunks.revisionHighWater() < 0
                || chunks.revisionHighWater() == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "chunk revision high-water must be nonnegative and incrementable");
        }
        Set<ChunkKey> keys = new HashSet<>();
        for (ChunkSnapshot chunk : chunks.chunks()) {
            if (chunk.worldHeight() != chunks.worldHeight()) {
                throw new IllegalArgumentException(
                        "each chunk worldHeight must match its repository snapshot");
            }
            if (chunk.revision() <= 0
                    || chunk.revision() > chunks.revisionHighWater()) {
                throw new IllegalArgumentException(
                        "chunk revisions must be positive and at or below the high-water value");
            }
            if (!keys.add(chunk.key())) {
                throw new IllegalArgumentException("chunk keys must be unique");
            }
        }
    }

    /** Metadata fixed at world creation or assigned once to the save identity. */
    public record StaticMetadata(
            SaveFormatVersion formatVersion,
            String gameVersion,
            SaveGameId saveGameId,
            String displayName,
            Instant createdAt,
            long worldSeed,
            String generatorVersion,
            String generatorConfigFingerprint,
            int chunkRadius,
            int worldHeight,
            Optional<String> summary) {
        public StaticMetadata {
            formatVersion = SaveMetadataValidation.requireCurrentFormat(formatVersion);
            gameVersion = SaveMetadataValidation.requireNonblank(
                    gameVersion, "gameVersion");
            saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
            displayName = SaveMetadataValidation.requireDisplayName(displayName);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            generatorVersion = SaveMetadataValidation.requireNonblank(
                    generatorVersion, "generatorVersion");
            generatorConfigFingerprint =
                    SaveMetadataValidation.requireGeneratorConfigFingerprint(
                            generatorConfigFingerprint);
            chunkRadius = SaveMetadataValidation.requireSupportedChunkRadius(chunkRadius);
            worldHeight = SaveMetadataValidation.requirePositiveWorldHeight(worldHeight);
            summary = Objects.requireNonNull(summary, "summary");
            summary.ifPresent(SaveMetadataValidation::requireSummaryWithinV1Bound);
        }
    }
}
