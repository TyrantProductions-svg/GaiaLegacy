package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Detached immutable bytes/plan for one combined streaming unload commit. */
public record StreamedChunkUnloadPlan(
        StreamedChunkStore.ExactChunkCapture chunkCapture,
        Optional<WorldItemPersistencePlan> worldItems,
        List<StreamedGlobalExtensionMutation> requiredGlobals,
        boolean voxelModified) {
    public StreamedChunkUnloadPlan(
            StreamedChunkStore.ExactChunkCapture chunkCapture,
            Optional<WorldItemPersistencePlan> worldItems,
            List<StreamedGlobalExtensionMutation> requiredGlobals) {
        this(chunkCapture, worldItems, requiredGlobals,
                chunkCapture.payload().voxelModified());
    }

    public StreamedChunkUnloadPlan {
        Objects.requireNonNull(chunkCapture, "chunkCapture");
        worldItems = Objects.requireNonNull(worldItems, "worldItems");
        requiredGlobals = List.copyOf(
                Objects.requireNonNull(requiredGlobals, "requiredGlobals"));
        if (requiredGlobals.size() > 1) {
            throw new IllegalArgumentException(
                    "combined unload accepts at most one session checkpoint");
        }
        for (StreamedGlobalExtensionMutation mutation : requiredGlobals) {
            if (!(mutation instanceof StreamedGlobalExtensionMutation.Upsert upsert)
                    || !SaveSectionId.STREAMED_SESSION_CHECKPOINT.equals(
                            upsert.extension().sectionId())
                    || !upsert.extension().required()
                    || upsert.extension().codecVersion()
                            != StreamedSessionCheckpointCodec.CODEC_VERSION
                    || upsert.extension().dependency().isPresent()
                    || upsert.extension().canonicalEncodedSize()
                            > StreamedGlobalExtension.MAX_CANONICAL_BYTES) {
                throw new IllegalArgumentException(
                        "combined unload global must be one required session checkpoint upsert");
            }
        }
        if (worldItems.isEmpty() && !requiredGlobals.isEmpty()) {
            throw new IllegalArgumentException(
                    "chunk-only unload cannot replace session authority");
        }
        if (worldItems.isPresent()) {
            if (requiredGlobals.size() != 1) {
                throw new IllegalArgumentException(
                        "WorldItem unload requires one session checkpoint input");
            }
            StreamedSessionCheckpoint session = decodeSession(requiredGlobals.get(0));
            var checkpoint = worldItems.orElseThrow().intendedCheckpoint();
            if (!session.saveGameId().equals(chunkCapture.payload().saveGameId())
                    || !session.saveGameId().value().equals(
                            checkpoint.saveIdentity().value().toString())
                    || session.fixedTick() != checkpoint.worldTick()) {
                throw new IllegalArgumentException(
                        "session input identity/tick does not match combined authority");
            }
        }
    }

    public Optional<StreamedSessionCheckpoint> sessionCheckpoint() {
        return requiredGlobals.isEmpty()
                ? Optional.empty()
                : Optional.of(decodeSession(requiredGlobals.get(0)));
    }

    private static StreamedSessionCheckpoint decodeSession(
            StreamedGlobalExtensionMutation mutation) {
        StreamedGlobalExtension extension =
                ((StreamedGlobalExtensionMutation.Upsert) mutation).extension();
        return new StreamedSessionCheckpointCodec().decode(
                extension.copyPayloadBytes());
    }
}
