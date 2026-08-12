package com.gaia.save.snapshot;

import static com.gaia.session.SessionSaveCaptureResult.Status.CAPTURED;
import static com.gaia.session.SessionSaveCaptureResult.Status.INCONSISTENT_REVISION;
import static com.gaia.session.SessionSaveCaptureResult.Status.PENDING_TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.session.SessionPersistenceTestFixture;
import com.gaia.session.SessionSaveCaptureResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkRepositorySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionSaveCaptureContractTest {
    @Test
    void captureResultOnlyRepresentsClosedContractStates() {
        SaveGameSnapshot snapshot = snapshot();

        SessionSaveCaptureResult captured =
                SessionPersistenceTestFixture.captured(snapshot, 12);
        SessionSaveCaptureResult pending =
                SessionSaveCaptureResult.pendingTransaction();
        SessionSaveCaptureResult inconsistent =
                SessionSaveCaptureResult.inconsistentRevision();

        assertAll(
                () -> assertEquals(CAPTURED, captured.status()),
                () -> assertEquals(snapshot, captured.snapshot().orElseThrow()),
                () -> assertEquals(12, captured.capturedRevision().orElseThrow()),
                () -> assertEquals(
                        12,
                        captured.persistenceRevision().orElseThrow().value()),
                () -> assertEquals(PENDING_TRANSACTION, pending.status()),
                () -> assertFalse(pending.snapshot().isPresent()),
                () -> assertFalse(pending.capturedRevision().isPresent()),
                () -> assertFalse(pending.persistenceRevision().isPresent()),
                () -> assertEquals(INCONSISTENT_REVISION, inconsistent.status()),
                () -> assertFalse(inconsistent.snapshot().isPresent()),
                () -> assertFalse(inconsistent.capturedRevision().isPresent()),
                () -> assertFalse(inconsistent.persistenceRevision().isPresent()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SessionPersistenceTestFixture.captured(
                                snapshot, -1)),
                () -> assertTrue(captured.snapshot().isPresent()));
    }

    private static SaveGameSnapshot snapshot() {
        EntityRef owner = new EntityRef(3);
        int worldHeight = 16;
        long fixedTick = 9;
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000"),
                        "World One",
                        Instant.parse("2026-08-10T12:00:00Z"),
                        42L,
                        "v1",
                        "a".repeat(64),
                        4,
                        worldHeight,
                        Optional.empty()),
                fixedTick,
                new ChunkRepositorySnapshot(worldHeight, 0, List.of()),
                new PlayerSaveSnapshot(
                        owner,
                        1, 2, 3,
                        0, 0, 0,
                        -90, 0,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        owner, Map.of(), BodySlot.LEFT_HAND, false, 0),
                new WorldItemsSaveSnapshot(fixedTick, List.of(), 0, false));
    }
}
