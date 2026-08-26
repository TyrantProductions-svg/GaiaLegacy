package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionState;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldItemPersistenceRegressionTest {
    @Test
    void partialRemainderStableIdAllocatorGapAndCanonicalStatesSurviveRestoreReconciliation() {
        Gate14BCanonicalFixture.LiveCapture live =
                Gate14BCanonicalFixture.representativeLiveCapture();
        SaveGameSnapshot captured = live.capture().snapshot().orElseThrow();
        EncodedSaveGame encoded = Gate14BCanonicalFixture.codecs()
                .encode(captured, Gate14BCanonicalFixture.MODIFIED);
        SaveGameSnapshot decoded = Gate14BCanonicalFixture.codecs().decode(
                encoded.manifest(), Gate14BCanonicalFixture.payloads(encoded));

        assertAll(
                () -> assertEquals(List.of(3L, 7L, 11L, 50L),
                        decoded.worldItems().entries().stream()
                                .map(entry -> entry.runtime().item().id().value())
                                .toList()),
                () -> assertEquals(100L, decoded.worldItems().nextItemId()),
                () -> assertFalse(decoded.worldItems().itemIdsExhausted()),
                () -> assertEquals(6, item(decoded, 7).stack().count()),
                () -> assertEquals(5L, item(decoded, 7).revision()),
                () -> assertEquals(10, 4 + item(decoded, 7).stack().count()),
                () -> assertEquals(425L, entry(decoded, 7).runtime().pickupAvailableTick()),
                () -> assertEquals(WorldItemPhysicalState.ACTIVE,
                        entry(decoded, 3).physicalState()),
                () -> assertEquals(WorldItemPhysicalState.GROUNDED,
                        entry(decoded, 7).physicalState()),
                () -> assertEquals(WorldItemPhysicalState.SLEEPING,
                        entry(decoded, 11).physicalState()),
                () -> assertEquals(WorldItemPhysicalState.FROZEN_UNLOADED,
                        entry(decoded, 50).physicalState()),
                () -> assertTrue(decoded.worldItems().entries().stream()
                        .noneMatch(entry -> entry.runtime().item().id().equals(new WorldItemId(99)))));

        try (var restored = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(decoded)) {
            restored.driveToReady();
            SaveGameSnapshot recaptured = restored.captureAndMarkSaved();
            assertAll(
                    () -> assertEquals(GameSessionState.READY, restored.state()),
                    () -> assertEquals(decoded.metadata(), recaptured.metadata()),
                    () -> assertEquals(decoded.fixedTick(), recaptured.fixedTick()),
                    () -> assertEquals(decoded.player(), recaptured.player()),
                    () -> assertEquals(decoded.inventory(), recaptured.inventory()),
                    () -> assertEquals(decoded.worldItems(), recaptured.worldItems()),
                    () -> assertEquals(25, recaptured.chunks().chunks().size()),
                    () -> assertEquals(decoded.chunks().revisionHighWater(),
                            recaptured.chunks().revisionHighWater()),
                    () -> assertEquals(3, restored.physicsBodyCount()),
                    () -> assertEquals(0, restored.transientPresentationCount()),
                    () -> assertEquals(0, restored.inventoryPendingReservations()),
                    () -> assertEquals(0, restored.worldItemPendingReservations()),
                    () -> assertEquals(100L, recaptured.worldItems().nextItemId()),
                    () -> assertFalse(recaptured.worldItems().itemIdsExhausted()));
        }
    }

    private static com.overlord.worlditem.api.WorldItemRestoreEntry entry(
            SaveGameSnapshot snapshot, long id) {
        return snapshot.worldItems().entries().stream()
                .filter(entry -> entry.runtime().item().id().value() == id)
                .findFirst()
                .orElseThrow();
    }

    private static com.overlord.worlditem.api.WorldItemSnapshot item(
            SaveGameSnapshot snapshot, long id) {
        return entry(snapshot, id).runtime().item();
    }
}
