package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.EncodedSaveSection;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionState;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkPersistenceRegressionTest {
    @Test
    void legacy81ChunksRestoreBoundedExactNeighborhoodAndGlobalHighWater() {
        SaveGameSnapshot captured = Gate14BCanonicalFixture
                .representativeLiveCapture()
                .capture()
                .snapshot()
                .orElseThrow();
        EncodedSaveGame encoded = Gate14BCanonicalFixture.codecs()
                .encode(captured, Gate14BCanonicalFixture.MODIFIED);
        SaveGameSnapshot decoded = Gate14BCanonicalFixture.codecs().decode(
                encoded.manifest(), Gate14BCanonicalFixture.payloads(encoded));

        try (var restored = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(decoded)) {
            restored.driveToReady();
            SaveGameSnapshot recaptured = restored.captureAndMarkSaved();
            List<ChunkKey> expectedKeys = decoded.chunks().chunks().stream()
                    .map(ChunkSnapshot::key)
                    .toList();
            Map<ChunkKey, ChunkSnapshot> decodedChunks = byKey(decoded.chunks());
            Map<ChunkKey, ChunkSnapshot> residentChunks = byKey(recaptured.chunks());

            assertAll(
                    () -> assertEquals(81, expectedKeys.size()),
                    () -> assertEquals(GameSessionState.READY, restored.state()),
                    () -> assertEquals(0, restored.generationInvocationCount()),
                    () -> assertEquals(95L,
                            recaptured.chunks().revisionHighWater()),
                    () -> assertEquals(25, residentChunks.size()),
                    () -> assertTrue(decodedChunks.entrySet()
                            .containsAll(residentChunks.entrySet())),
                    () -> assertEquals(decoded.metadata(), recaptured.metadata()),
                    () -> assertEquals(decoded.fixedTick(), recaptured.fixedTick()),
                    () -> assertEquals(decoded.player(), recaptured.player()),
                    () -> assertEquals(decoded.inventory(), recaptured.inventory()),
                    () -> assertEquals(decoded.worldItems(), recaptured.worldItems()));
        }
    }

    @Test
    void oneDeterministicInteriorMutationChangesOnlyChunksSectionAndExactRevision() {
        Gate14BCanonicalFixture.LiveCapture live =
                Gate14BCanonicalFixture.representativeLiveCapture();
        SaveGameSnapshot before = live.capture().snapshot().orElseThrow();
        EncodedSaveGame beforeEncoded = Gate14BCanonicalFixture.codecs()
                .encode(before, Gate14BCanonicalFixture.MODIFIED);

        live.mutateInteriorBlock();

        SaveGameSnapshot after = live.capture().snapshot().orElseThrow();
        EncodedSaveGame afterEncoded = Gate14BCanonicalFixture.codecs()
                .encode(after, Gate14BCanonicalFixture.MODIFIED);
        Map<ChunkKey, ChunkSnapshot> beforeChunks = byKey(before.chunks());
        Map<ChunkKey, ChunkSnapshot> afterChunks = byKey(after.chunks());
        List<ChunkKey> changedKeys = beforeChunks.keySet().stream()
                .filter(key -> !beforeChunks.get(key).equals(afterChunks.get(key)))
                .toList();

        assertAll(
                () -> assertEquals(List.of(new ChunkKey(0, 0)), changedKeys),
                () -> assertEquals(95L, before.chunks().revisionHighWater()),
                () -> assertEquals(96L, after.chunks().revisionHighWater()),
                () -> assertEquals(96L, afterChunks.get(new ChunkKey(0, 0)).revision()),
                () -> assertEquals(4,
                        Byte.toUnsignedInt(afterChunks.get(new ChunkKey(0, 0))
                                .getBlock(1, 3, 1))),
                () -> assertEquals(72L,
                        live.capture().capturedRevision().orElseThrow()),
                () -> assertEquals(72L, live.clock().revision()));

        for (SaveSectionId sectionId : List.of(
                SaveSectionId.CHUNKS,
                SaveSectionId.PLAYER,
                SaveSectionId.INVENTORY,
                SaveSectionId.WORLD_ITEMS)) {
            EncodedSaveSection beforeSection =
                    Gate14BCanonicalFixture.section(beforeEncoded, sectionId);
            EncodedSaveSection afterSection =
                    Gate14BCanonicalFixture.section(afterEncoded, sectionId);
            if (sectionId.equals(SaveSectionId.CHUNKS)) {
                assertFalse(java.util.Arrays.equals(
                        beforeSection.bytes(), afterSection.bytes()));
                assertNotEquals(
                        beforeSection.descriptor().sha256(),
                        afterSection.descriptor().sha256());
            } else {
                assertArrayEquals(beforeSection.bytes(), afterSection.bytes());
                assertEquals(beforeSection.descriptor(), afterSection.descriptor());
            }
        }
    }

    private static Map<ChunkKey, ChunkSnapshot> byKey(
            ChunkRepositorySnapshot snapshot) {
        LinkedHashMap<ChunkKey, ChunkSnapshot> chunks = new LinkedHashMap<>();
        snapshot.chunks().forEach(chunk -> chunks.put(chunk.key(), chunk));
        return chunks;
    }
}
