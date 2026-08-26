package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.overlord.voxel.ChunkKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedPrepublicationStagingTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final String BASE_HASH = "44".repeat(32);

    @TempDir Path tempDirectory;

    @Test
    void sixtyFiveWritesUseMultipleBoundedBatchesAndPublishOnce() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("sixty-five"));
        StreamedChunkStore store = store(root);
        List<StreamedChunkMutation> mutations = mutations(65, 1);

        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations.subList(0, 32)).status());
            try (StreamedChunkStore.PinnedReadView invisible =
                    store.openPinnedReadView()) {
                assertTrue(invisible.index().entries().isEmpty(),
                        "staged payload slots must not be reader-visible");
            }
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations.subList(32, 64)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations.subList(64, 65)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
            assertEquals(32, staged.metrics().maximumBatchMutations());
            assertEquals(64, staged.metrics().maximumBatchPhysicalBlobs());
            assertTrue(staged.metrics().maximumBatchBytes()
                    <= StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
        }

        try (StreamedChunkStore.PinnedReadView published =
                store(root).openPinnedReadView()) {
            assertEquals(65, published.index().entries().size());
            assertEquals(1L, published.sequence());
        }
    }

    @Test
    void sixtyFiveDistinctPhysicalBlobWritesSplitAndPublishAtomically()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("sixty-five-physical"));
        StreamedChunkStore store = store(root);
        StreamedChunkMutation existing = mutations(2_000, 1, 1).get(0);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(existing), List.of(), () -> true)).status());

        List<StreamedChunkMutation> first = new ArrayList<>();
        first.add(nextRevision(existing));
        first.addAll(mutations(0, 31, 1));
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(first).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations(31, 1, 1)).status());
            try (StreamedChunkStore.PinnedReadView old = store.openPinnedReadView()) {
                assertEquals(1, old.index().entries().size());
                assertEquals(1L, old.sequence());
            }
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
            assertEquals(63, staged.metrics().maximumBatchPhysicalBlobs());
        }
        try (StreamedChunkStore.PinnedReadView published =
                store(root).openPinnedReadView()) {
            assertEquals(33, published.index().entries().size());
            assertEquals(2L, published.sequence());
        }
    }

    @Test
    void legalOneThousandTwentyFourOwnerCandidateKeepsOneBoundedBatchResident()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("one-thousand-twenty-four"));
        StreamedChunkStore store = store(root);
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            for (int start = 0; start < 1_024; start += 32) {
                List<StreamedChunkMutation> batch = mutations(start, 32, 1);
                assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                        staged.stageBatch(batch).status());
            }
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
            assertEquals(1_024, staged.metrics().stagedMutations());
            assertEquals(32, staged.metrics().maximumBatchMutations());
            assertEquals(64, staged.metrics().maximumBatchPhysicalBlobs());
            assertTrue(staged.metrics().maximumBatchBytes()
                    <= StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
        }

        try (StreamedChunkStore.PinnedReadView published =
                store(root).openPinnedReadView()) {
            assertEquals(1_024, published.index().entries().size());
        }
    }

    @Test
    void candidateAboveSixtyFourMiBUsesMultipleBoundedPhysicalBatches()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("above-sixty-four-mib"));
        StreamedChunkStore store = store(root);
        long totalBytes = 0L;
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            List<StreamedChunkMutation> first = largeMutations(0, 3);
            totalBytes += encodedBytes(first);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(first).status());
            first = List.of();
            List<StreamedChunkMutation> second = largeMutations(3, 2);
            totalBytes += encodedBytes(second);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(second).status());
            assertTrue(totalBytes
                    > StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
            assertEquals(3, staged.metrics().maximumBatchMutations());
            assertTrue(staged.metrics().maximumBatchBytes()
                    <= StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
        }
        try (StreamedChunkStore.PinnedReadView published =
                store(root).openPinnedReadView()) {
            assertEquals(5, published.index().entries().size());
        }
    }

    @Test
    void onePhysicalBatchRejectsSixtyFiveMutationsOrMoreThanSixtyFourMiB()
            throws Exception {
        Path countRoot = Files.createDirectory(tempDirectory.resolve("batch-count-bound"));
        try (StreamedChunkStore.StagedTransaction staged =
                store(countRoot).beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.FAILED,
                    staged.stageBatch(mutations(65, 1)).status());
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(countRoot).openPinnedReadView()) {
            assertTrue(reopened.index().entries().isEmpty());
        }

        Path byteRoot = Files.createDirectory(tempDirectory.resolve("batch-byte-bound"));
        try (StreamedChunkStore.StagedTransaction staged =
                store(byteRoot).beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.FAILED,
                    staged.stageBatch(largeMutations(0, 4)).status());
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(byteRoot).openPinnedReadView()) {
            assertTrue(reopened.index().entries().isEmpty());
        }
    }

    @Test
    void newOwnerBatchRejectsMoreThanSixtyFourDistinctPhysicalPayloadBlobs()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("physical-blob-bound"));
        try (StreamedChunkStore.StagedTransaction staged =
                store(root).beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.FAILED,
                    staged.stageBatch(mutations(33, 1)).status(),
                    "33 new owners require 66 A/B managed payload blobs");
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(root).openPinnedReadView()) {
            assertTrue(reopened.index().entries().isEmpty());
        }
    }

    @Test
    void physicalBatchBoundIsObservedFromActualPayloadPathsAndWrittenBytes()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("observed-physical-bound"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = store(root, files);
        files.clear();
        List<StreamedChunkMutation> batch = mutations(32, 1);

        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(batch).status());
            List<ProtocolEvent> payloadWrites = files.mutations().stream()
                    .filter(event -> switch (event.stage()) {
                        case CREATE_PAYLOAD_A, CREATE_PAYLOAD_B,
                                WRITE_PAYLOAD_A, WRITE_PAYLOAD_B -> true;
                        default -> false;
                    })
                    .toList();
            assertEquals(64L, payloadWrites.stream()
                    .map(ProtocolEvent::path).distinct().count());
            assertEquals(encodedBytes(batch), payloadWrites.stream()
                    .mapToLong(ProtocolEvent::byteCount).sum());
            assertTrue(payloadWrites.stream().mapToLong(ProtocolEvent::byteCount).sum()
                    <= StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
        }
    }

    @Test
    void transientPayloadResidencyUsesAConservativeMeasuredUpperBound()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("transient-bound"));
        try (StreamedChunkStore.StagedTransaction staged =
                store(root).beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(largeMutations(0, 3)).status());
            assertTrue(staged.metrics().maximumTransientPayloadBytesUpperBound()
                    > 128L * 1024L * 1024L,
                    "the old 128 MiB claim was not a valid structural bound");
            assertTrue(staged.metrics().maximumTransientPayloadBytesUpperBound()
                    <= StreamedChunkStore.MAX_STAGING_TRANSIENT_PAYLOAD_BYTES);
        }
    }

    @Test
    void constructorRecoveryRepairCannotBypassAnActiveRootWriter()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("constructor-gate"));
        StreamedChunkStore first = store(root);
        try (StreamedChunkStore.StagedTransaction staged =
                first.beginStagedTransaction(() -> true)) {
            Path recovery = root.resolve(SAVE_ID.value())
                    .resolve("streamed-chunks.prev.idx");
            Files.write(recovery, new byte[] {1, 2, 3},
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            assertThrows(IllegalArgumentException.class, () -> store(root));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(recovery),
                    "a competing constructor must not repair authority outside the gate");
        }
        try (StreamedChunkStore.PinnedReadView repaired =
                store(root).openPinnedReadView()) {
            assertTrue(repaired.index().entries().isEmpty());
        }
    }

    @Test
    void cancelAndStaleGenerationCannotPublish() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("cancel-stale"));
        StreamedChunkStore store = store(root);
        AtomicBoolean current = new AtomicBoolean(true);
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(current::get)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations(1, 1)).status());
            current.set(false);
            assertEquals(StreamedChunkStore.CommitResult.Status.STALE,
                    staged.publish(List.of()).status());
        }
        try (StreamedChunkStore.PinnedReadView old = store(root).openPinnedReadView()) {
            assertTrue(old.index().entries().isEmpty());
        }

        try (StreamedChunkStore.StagedTransaction canceled =
                store.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    canceled.stageBatch(mutations(1, 1)).status());
            canceled.cancel();
            assertEquals(StreamedChunkStore.CommitResult.Status.STALE,
                    canceled.publish(List.of()).status());
        }
        try (StreamedChunkStore.PinnedReadView old = store(root).openPinnedReadView()) {
            assertTrue(old.index().entries().isEmpty());
        }
    }

    @Test
    void failureInFirstMiddleOrFinalStagingBatchLeavesOldRoot() throws Exception {
        for (int failingBatch = 0; failingBatch < 3; failingBatch++) {
            Path root = Files.createDirectory(tempDirectory.resolve(
                    "batch-failure-" + failingBatch));
            ProtocolFileOperations files = new ProtocolFileOperations(
                    new JdkSaveFileOperations());
            StreamedChunkStore store = store(root, files);
            List<StreamedChunkMutation> mutations = mutations(65, 1);
            try (StreamedChunkStore.StagedTransaction staged =
                    store.beginStagedTransaction(() -> true)) {
                for (int batch = 0; batch < 3; batch++) {
                    int start = batch * 32;
                    int end = Math.min(start + 32, mutations.size());
                    if (batch == failingBatch) {
                        files.before(ProtocolStage.WRITE_PAYLOAD_A, ignored -> {
                            throw new java.io.IOException(
                                    "injected staging batch failure");
                        });
                    }
                    StreamedChunkStore.CommitResult result = staged.stageBatch(
                            mutations.subList(start, end));
                    if (batch == failingBatch) {
                        assertEquals(
                                StreamedChunkStore.CommitResult.Status.FAILED,
                                result.status());
                        break;
                    }
                    assertEquals(
                            StreamedChunkStore.CommitResult.Status.SUCCESS,
                            result.status());
                }
            }
            try (StreamedChunkStore.PinnedReadView reopened =
                    store(root).openPinnedReadView()) {
                assertTrue(reopened.index().entries().isEmpty());
            }
        }
    }

    @Test
    void crashImmediatelyBeforeOrAfterFinalPublicationIsOldOrCompleteNew()
            throws Exception {
        Path beforeRoot = Files.createDirectory(tempDirectory.resolve("before-publish"));
        ProtocolFileOperations beforeFiles = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore beforeStore = store(beforeRoot, beforeFiles);
        try (StreamedChunkStore.StagedTransaction staged =
                beforeStore.beginStagedTransaction(() -> true)) {
            List<StreamedChunkMutation> candidate = mutations(65, 1);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(0, 32)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(32, 64)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(64, 65)).status());
            beforeFiles.before(ProtocolStage.WRITE_RECOVERY, ignored -> {
                throw new java.io.IOException("kill before final publication");
            });
            assertEquals(StreamedChunkStore.CommitResult.Status.FAILED,
                    staged.publish(List.of()).status());
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(beforeRoot).openPinnedReadView()) {
            assertTrue(reopened.index().entries().isEmpty());
        }

        Path afterRoot = Files.createDirectory(tempDirectory.resolve("after-publish"));
        ProtocolFileOperations afterFiles = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore afterStore = store(afterRoot, afterFiles);
        SimulatedProcessKill kill = new SimulatedProcessKill();
        try (StreamedChunkStore.StagedTransaction staged =
                afterStore.beginStagedTransaction(() -> true)) {
            List<StreamedChunkMutation> candidate = mutations(65, 1);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(0, 32)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(32, 64)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(candidate.subList(64, 65)).status());
            afterFiles.after(ProtocolStage.FORCE_MAIN, ignored -> {
                throw kill;
            });
            assertEquals(kill, assertThrows(
                    SimulatedProcessKill.class,
                    () -> staged.publish(List.of())));
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(afterRoot).openPinnedReadView()) {
            assertEquals(65, reopened.index().entries().size());
        }
    }

    @Test
    void latePayloadMismatchAndOrphanCleanupFailureCannotChangeAuthority()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("late-mismatch"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = store(root, files);
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations(1, 1)).status());
            Path stagedPath = files.mutations().stream()
                    .filter(event -> event.stage() == ProtocolStage.WRITE_PAYLOAD_A
                            || event.stage() == ProtocolStage.WRITE_PAYLOAD_B)
                    .reduce((first, second) -> second)
                    .orElseThrow().path();
            Files.write(
                    stagedPath,
                    new byte[] {1, 2, 3},
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            assertEquals(StreamedChunkStore.CommitResult.Status.FAILED,
                    staged.publish(List.of()).status());
            files.failIfDeleteOrOptionalDirectoryForceIsCalled();
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(root).openPinnedReadView()) {
            assertTrue(reopened.index().entries().isEmpty(),
                    "restart must ignore unreachable inactive-slot remnants");
        }
    }

    @Test
    void sharedWriterCapabilityRejectsCompetingHandleBeforeItCanOverwriteStaging()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("newer-root"));
        StreamedChunkStore first = store(root);
        try (StreamedChunkStore.StagedTransaction staged =
                first.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(mutations(1, 1)).status());

            StreamedChunkMutation winner = mutations(1, 1).get(0);
            StreamedPersistenceTransaction competing =
                    new StreamedPersistenceTransaction(
                            List.of(winner), List.of(), () -> true);
            assertEquals(StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                    store(root).commitTransaction(competing).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
        }
        try (StreamedChunkStore.PinnedReadView reopened =
                store(root).openPinnedReadView()) {
            assertEquals(1, reopened.index().entries().size());
            assertEquals(1L, reopened.sequence());
        }
    }

    @Test
    void boundedGenerationViewResolvesPayloadsLazilyUnderWriterBarrier()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("bounded-read"));
        StreamedChunkStore store = store(root);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        mutations(2, 1), List.of(), () -> true)).status());

        try (StreamedChunkStore.BoundedReadView view = store.openBoundedReadView()) {
            assertEquals(2, view.index().entries().size());
            assertEquals(0, view.residentPayloadCount());
            assertTrue(view.payload(new ChunkKey(-7, -512)) != null);
            assertTrue(view.residentPayloadCount() <= 1);
            StreamedChunkMutation second = nextRevision(mutations(2, 1).get(1));
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    store(root).commitTransaction(new StreamedPersistenceTransaction(
                            List.of(second), List.of(), () -> true)).status());
            assertEquals(1L, view.payload(new ChunkKey(-7, -511)).revision());
            StreamedChunkMutation third = nextRevision(second);
            assertEquals(StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                    store(root).commitTransaction(new StreamedPersistenceTransaction(
                            List.of(third), List.of(), () -> true)).status());
        }
    }

    @Test
    void singleBatchStagingRemainsCompatibleWithLegacyTransactionOutcome()
            throws Exception {
        Path legacyRoot = Files.createDirectory(tempDirectory.resolve("legacy-single"));
        Path stagedRoot = Files.createDirectory(tempDirectory.resolve("staged-single"));
        StreamedChunkMutation mutation = mutations(1, 1).get(0);
        StreamedChunkStore legacy = store(legacyRoot);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                legacy.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(mutation), List.of(), () -> true)).status());

        StreamedChunkStore stagedStore = store(stagedRoot);
        try (StreamedChunkStore.StagedTransaction staged =
                stagedStore.beginStagedTransaction(() -> true)) {
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.stageBatch(List.of(mutation)).status());
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    staged.publish(List.of()).status());
        }

        try (StreamedChunkStore.PinnedReadView legacyView =
                        store(legacyRoot).openPinnedReadView();
                StreamedChunkStore.PinnedReadView stagedView =
                        store(stagedRoot).openPinnedReadView()) {
            assertEquals(legacyView.sequence(), stagedView.sequence());
            assertArrayEquals(
                    new StreamedChunkIndexCodec().encode(legacyView.index()),
                    new StreamedChunkIndexCodec().encode(stagedView.index()));
            ChunkKey key = ((StreamedChunkMutation.Upsert) mutation)
                    .capture().payload().key();
            assertEquals(legacyView.payload(key), stagedView.payload(key));
        }
    }

    private static List<StreamedChunkMutation> mutations(int count, int worldHeight) {
        return mutations(0, count, worldHeight);
    }

    private static List<StreamedChunkMutation> mutations(
            int first,
            int count,
            int worldHeight) {
        List<StreamedChunkMutation> mutations = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = first + offset;
            StreamedChunkPayload payload = new StreamedChunkPayload(
                    SAVE_ID,
                    new ChunkKey(-7, index - 512),
                    "v15",
                    BASE_HASH,
                    1L,
                    0L,
                    true,
                    true,
                    worldHeight,
                    new byte[16 * 16 * worldHeight],
                    List.of());
            mutations.add(new StreamedChunkMutation.Upsert(
                    new StreamedChunkStore.ExactChunkCapture(payload, () -> true)));
        }
        return mutations;
    }

    private static List<StreamedChunkMutation> largeMutations(
            int first,
            int count) {
        List<StreamedChunkMutation> mutations = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = first + offset;
            List<StreamedChunkPayload.ExtensionDescriptor> extensions =
                    new ArrayList<>(16);
            for (int extension = 0; extension < 16; extension++) {
                extensions.add(new StreamedChunkPayload.ExtensionDescriptor(
                        new SaveSectionId("staging-" + extension),
                        1,
                        false,
                        new byte[1024 * 1024]));
            }
            StreamedChunkPayload payload = new StreamedChunkPayload(
                    SAVE_ID,
                    new ChunkKey(-8, index),
                    "v15",
                    BASE_HASH,
                    1L,
                    0L,
                    true,
                    true,
                    1,
                    new byte[16 * 16],
                    extensions);
            mutations.add(new StreamedChunkMutation.Upsert(
                    new StreamedChunkStore.ExactChunkCapture(payload, () -> true)));
        }
        return mutations;
    }

    private static StreamedChunkMutation nextRevision(
            StreamedChunkMutation mutation) {
        StreamedChunkPayload previous = ((StreamedChunkMutation.Upsert) mutation)
                .capture().payload();
        StreamedChunkPayload next = new StreamedChunkPayload(
                previous.saveGameId(),
                previous.key(),
                previous.generatorVersion(),
                previous.baseHash(),
                Math.addExact(previous.revision(), 1L),
                previous.revision(),
                previous.persistenceRequired(),
                previous.voxelModified(),
                previous.worldHeight(),
                previous.copyCanonicalVoxels(),
                previous.extensions());
        return new StreamedChunkMutation.Upsert(
                new StreamedChunkStore.ExactChunkCapture(next, () -> true));
    }

    private static long encodedBytes(List<StreamedChunkMutation> mutations) {
        return mutations.stream().mapToLong(mutation ->
                StreamedChunkCodec.canonicalEncodedSize(
                        ((StreamedChunkMutation.Upsert) mutation)
                                .capture().payload())).sum();
    }

    private static StreamedChunkStore store(Path root) {
        return store(root, new JdkSaveFileOperations());
    }

    private static StreamedChunkStore store(
            Path root,
            com.gaia.save.store.SaveFileOperations files) {
        return new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
    }

    private static final class SimulatedProcessKill extends Error {}
}
