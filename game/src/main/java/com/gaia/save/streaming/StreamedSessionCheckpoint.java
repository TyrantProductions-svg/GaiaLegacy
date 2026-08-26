package com.gaia.save.streaming;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import java.time.Instant;
import java.util.Objects;

/** One immutable v2 session boundary bound to an already durable WorldItem checkpoint. */
public record StreamedSessionCheckpoint(
        SaveGameId saveGameId,
        long fixedTick,
        long worldItemCheckpointRevision,
        String worldItemCheckpointDigest,
        long worldItemSourceIndexSequence,
        Instant modifiedTime,
        PlayerSaveSnapshot player,
        InventorySaveSnapshot inventory) {
    public StreamedSessionCheckpoint {
        saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
        if (fixedTick < 0L
                || worldItemCheckpointRevision <= 0L
                || worldItemSourceIndexSequence < 0L) {
            throw new IllegalArgumentException(
                    "streamed session tick, checkpoint revision, and sequence are invalid");
        }
        worldItemCheckpointDigest = Objects.requireNonNull(
                worldItemCheckpointDigest, "worldItemCheckpointDigest");
        if (!worldItemCheckpointDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "WorldItem checkpoint digest must be canonical SHA-256");
        }
        modifiedTime = Objects.requireNonNull(modifiedTime, "modifiedTime");
        player = Objects.requireNonNull(player, "player");
        inventory = Objects.requireNonNull(inventory, "inventory");
    }
}
