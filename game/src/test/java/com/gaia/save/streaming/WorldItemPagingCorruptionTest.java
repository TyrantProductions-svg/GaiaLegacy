package com.gaia.save.streaming;

import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.SAVE;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.backend;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.checkpoint;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.entry;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.page;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.publish;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.service;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldItemPagingCorruptionTest {
    @TempDir Path tempDirectory;

    @Test
    void duplicateLiveIdAcrossPagesFailsClosedInEveryLoadOrder() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("duplicate-live-id"));
        ChunkKey negative = new ChunkKey(-9, -2);
        ChunkKey positive = new ChunkKey(8, 3);
        var first = page(
                negative, 1L, List.of(entry(negative, 77L, 1, 0L, 18_000L)));
        var second = page(
                positive, 1L, List.of(entry(positive, 77L, 2, 0L, 18_000L)));
        publish(root, checkpoint(1L, 100L, 78L, List.of(first, second)),
                List.of(first, second));
        StreamedWorldItemPageBackend persisted = backend(root);

        for (Comparator<ChunkKey> order : List.of(
                ChunkCoordinatePolicy.canonicalComparator(),
                ChunkCoordinatePolicy.canonicalComparator().reversed())) {
            var fresh = service(persisted);
            assertThrows(IllegalStateException.class,
                    () -> persisted.restoreFresh(fresh, SAVE, 100L, order, ignored -> {}));
            assertTrue(fresh.liveMetadata().isEmpty());
            assertTrue(fresh.canonicalSnapshot().entries().isEmpty());
        }
    }

    @Test
    void lateDescriptorMismatchCannotPartiallyPublishEarlierValidPages()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("late-mismatch"));
        ChunkKey firstKey = new ChunkKey(-4, 0);
        ChunkKey secondKey = new ChunkKey(4, 0);
        var old = page(
                firstKey, 1L, List.of(entry(firstKey, 10L, 1, 0L, 18_000L)));
        publish(root, checkpoint(1L, 100L, 11L, List.of(old)), List.of(old));
        var second = page(
                secondKey, 1L, List.of(entry(secondKey, 20L, 1, 0L, 18_000L)));
        var badSecond = new com.overlord.worlditem.api.WorldItemPageDescriptor(
                second.descriptor().chunkKey(),
                second.descriptor().pageRevision(),
                "ff".repeat(32),
                second.descriptor().encodedEntryCount(),
                second.descriptor().expectedLiveCountAtCheckpointTick());
        var malformed = new com.overlord.worlditem.api.WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 21L, false, 2,
                List.of(old.descriptor(), badSecond));
        StreamedWorldItemPageBackend persisted = backend(root);

        assertThrows(IllegalStateException.class,
                () -> persisted.persist(new WorldItemPersistencePlan(
                        1L,
                        malformed,
                        List.of(new WorldItemPageMutation.Upsert(
                                second.page(), Optional.empty())),
                        "33".repeat(32),
                        () -> true)));

        try (var view = backend(root).openReadView()) {
            assertEquals(1L, view.checkpoint().checkpointRevision());
            assertEquals(List.of(old.descriptor()), view.checkpoint().pages());
            assertEquals(old.page(), view.read(old.descriptor()));
        }
        var oldAuthority = service(backend(root));
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                backend(root).restoreFresh(oldAuthority, SAVE, 100L).status());
        assertEquals(List.of(new com.overlord.worlditem.api.WorldItemId(10L)),
                oldAuthority.liveMetadata().stream().map(
                        com.overlord.worlditem.api.WorldItemLiveMetadata::id).toList());
    }

    @Test
    void duplicateWithinOnePageFailsClosedBeforeFreshPublication() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("duplicate-within-page"));
        ChunkKey key = new ChunkKey(-1, 1);
        var old = page(key, 1L, List.of(entry(key, 7L, 1, 0L, 18_000L)));
        publish(root, checkpoint(1L, 100L, 8L, List.of(old)), List.of(old));

        assertThrows(IllegalArgumentException.class, () -> page(key, 2L, List.of(
                entry(key, 42L, 1, 0L, 18_000L),
                entry(key, 42L, 2, 0L, 18_000L))));
        try (var view = backend(root).openReadView()) {
            assertEquals(1L, view.checkpoint().checkpointRevision());
            assertEquals(old.page(), view.read(old.descriptor()));
        }
    }

    @Test
    void pinnedReaderSeesOldAuthorityWhileOnePublicationMakesNewRootVisible()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("pinned-old-view"));
        ChunkKey key = new ChunkKey(-2, 2);
        var old = page(key, 1L, List.of(entry(key, 15L, 1, 0L, 18_000L)));
        publish(root, checkpoint(1L, 100L, 16L, List.of(old)), List.of(old));
        var replacement = page(
                key, 2L, List.of(entry(key, 15L, 2, 0L, 18_000L)));
        var intended = checkpoint(2L, 100L, 16L, List.of(replacement));
        StreamedWorldItemPageBackend persisted = backend(root);

        try (var oldView = persisted.openReadView()) {
            var proof = persisted.persist(new WorldItemPersistencePlan(
                    1L,
                    intended,
                    List.of(new WorldItemPageMutation.Upsert(
                            replacement.page(), Optional.of(old.descriptor()))),
                    "44".repeat(32),
                    () -> true));
            assertTrue(proof != null);
            assertEquals(1L, oldView.checkpoint().checkpointRevision());
            assertEquals(old.page(), oldView.read(old.descriptor()));
        }
        try (var newView = backend(root).openReadView()) {
            assertEquals(2L, newView.checkpoint().checkpointRevision());
            assertEquals(replacement.page(), newView.read(replacement.descriptor()));
        }
    }

    @Test
    void descriptorCountRevisionHashAndDependencyAttacksFailBeforePublication()
            throws Exception {
        ChunkKey key = new ChunkKey(-6, 7);
        var valid = page(key, 3L, List.of(entry(key, 9L, 1, 0L, 18_000L)));
        List<Attack> attacks = List.of(
                new Attack("raw-count", new com.overlord.worlditem.api.WorldItemPageDescriptor(
                        key, 3L, valid.descriptor().pageHash(), 2, 1), 1),
                new Attack("survivor-count", new com.overlord.worlditem.api.WorldItemPageDescriptor(
                        key, 3L, valid.descriptor().pageHash(), 1, 0), 1),
                new Attack("revision", new com.overlord.worlditem.api.WorldItemPageDescriptor(
                        key, 4L, valid.descriptor().pageHash(), 1, 1), 1),
                new Attack("hash", new com.overlord.worlditem.api.WorldItemPageDescriptor(
                        key, 3L, "ee".repeat(32), 1, 1), 1),
                new Attack("dependency", valid.descriptor(), 0));

        for (Attack attack : attacks) {
            Path root = Files.createDirectory(tempDirectory.resolve(attack.name()));
            var malformed = new com.overlord.worlditem.api.WorldItemPagingCheckpoint(
                    SAVE,
                    1L,
                    100L,
                    10L,
                    false,
                    attack.descriptor().expectedLiveCountAtCheckpointTick(),
                    List.of(attack.descriptor()));
            StreamedChunkStore.CommitResult committed =
                    WorldItemPagingAcceptanceFixture.publish(
                            root, malformed, List.of(valid), attack.dependencyCount());
            var fresh = service(backend(root));
            if (committed.status() == StreamedChunkStore.CommitResult.Status.SUCCESS) {
                assertThrows(IllegalStateException.class,
                        () -> backend(root).restoreFresh(fresh, SAVE, 100L));
                assertTrue(fresh.liveMetadata().isEmpty(), attack.name());
                assertTrue(fresh.canonicalSnapshot().entries().isEmpty(), attack.name());
            } else {
                assertThrows(IllegalStateException.class,
                        () -> backend(root).openReadView(), attack.name());
            }
        }
    }

    private record Attack(
            String name,
            com.overlord.worlditem.api.WorldItemPageDescriptor descriptor,
            int dependencyCount) {}
}
