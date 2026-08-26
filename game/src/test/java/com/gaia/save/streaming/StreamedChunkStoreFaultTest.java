package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveFileOperations;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class StreamedChunkStoreFaultTest {
    static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    static final ChunkKey KEY = new ChunkKey(-2, 7);
    static final String GENERATOR_VERSION = "v15";
    static final String BASE_HASH = "11".repeat(32);
    static final StreamedChunkIndex.MigrationCompatibility MIGRATION_PROOF =
            new StreamedChunkIndex.MigrationCompatibility(
                    "22".repeat(32), "33".repeat(32));
    static final StreamedChunkIndex.MigrationCompatibility CONFLICTING_PROOF =
            new StreamedChunkIndex.MigrationCompatibility(
                    "44".repeat(32), "55".repeat(32));

    @TempDir Path tempDirectory;

    @Test
    void openingEmptyStorageDurablySeedsTwoExactEmptyAuthorities()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("empty"));

        reopen(root);

        byte[] main = Files.readAllBytes(mainIndex(root));
        byte[] recovery = Files.readAllBytes(recoveryIndex(root));
        assertArrayEquals(main, recovery);
        assertEquals(0L, slotSequence(main));
        assertTrue(slotIndex(main).entries().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(InitializationCrash.class)
    void tornFirstInitializationAlwaysReopensWithExactEmptyLastKnownGood(
            InitializationCrash crash) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "init-" + crash.name().toLowerCase(Locale.ROOT)));
        ProtocolFileOperations files = operations();
        files.after(crash.stage, ignored -> {
            crash.corruptIfTorn(ignored);
            throw new IOException("injected initialization interruption");
        });

        assertThrows(IllegalArgumentException.class, () -> store(root, files));

        StreamedChunkStore reopened = reopen(root);
        assertEquals(
                StreamedChunkStore.ReadResult.Status.NOT_FOUND,
                read(reopened).status());
        byte[] main = Files.readAllBytes(mainIndex(root));
        byte[] recovery = Files.readAllBytes(recoveryIndex(root));
        assertArrayEquals(main, recovery);
        assertEquals(0L, slotSequence(main));
    }

    @ParameterizedTest
    @EnumSource(InitializationRetryBoundary.class)
    void interruptedInitializationIsReprovenOnRetryBeforePublication(
            InitializationRetryBoundary boundary) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "init-retry-" + boundary.name().toLowerCase(Locale.ROOT)));
        ProtocolFileOperations interruptedFiles = operations();
        boundary.arm(interruptedFiles, root);

        if (boundary.constructorBoundary()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StreamedChunkStore(
                            root,
                            SAVE_ID,
                            new StreamedChunkCodec(),
                            new StreamedChunkIndexCodec(),
                            interruptedFiles));
        } else {
            StreamedChunkStore interrupted = store(root, interruptedFiles);
            boundary.arm(interruptedFiles, root);
            assertFalse(interrupted.commitModified(
                    capture(1L, 0L, (byte) 8), hibernation()).unloadAuthorized());
        }

        ProtocolFileOperations retryFiles = operations();
        StreamedChunkStore retry = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                retryFiles);
        if (!boundary.constructorBoundary()) {
            retryFiles.clear();
        }
        assertSuccess(retry.commitModified(
                capture(1L, 0L, (byte) 8), hibernation()));

        boundary.assertRetryProofPrecedesPublication(retryFiles.mutations(), root);
        StreamedChunkPayload reopened = read(reopen(root)).payload().orElseThrow();
        assertEquals(1L, reopened.revision());
    }

    @Test
    void firstAndReplacementCommitsUseOnlyTwoFixedPayloadEntries()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("fixed-pool"));
        StreamedChunkStore store = reopen(root);
        assertSuccess(store.commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        assertSuccess(store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation()));

        Path a = payloadSlot(root, 'a');
        Path b = payloadSlot(root, 'b');
        assertTrue(Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS));
        try (var paths = Files.list(a.getParent())) {
            assertEquals(2L, paths
                    .filter(path -> path.getFileName().toString().endsWith(".glchunk"))
                    .count());
        }
        assertEquals(8L, read(reopen(root)).payload().orElseThrow().revision());
        assertArrayEquals(
                Files.readAllBytes(mainIndex(root)),
                Files.readAllBytes(recoveryIndex(root)));
    }

    @Test
    void batchImportPublishesOneCompleteIndexPairWithLinearIdentityWork()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("batch-linear"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        AtomicInteger chunkFreshness = new AtomicInteger();
        AtomicInteger itemFreshness = new AtomicInteger();

        StreamedChunkStore.CommitResult result = store.commitModifiedBatch(
                batchCaptures(81, chunkFreshness),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> {
                            itemFreshness.incrementAndGet();
                            return true;
                        }));

        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS, result.status());
        assertTrue(result.unloadAuthorized());
        assertEquals(81, chunkFreshness.get());
        assertEquals(1, itemFreshness.get());
        assertEquals(81L, mutationCount(files, ProtocolStage.WRITE_PAYLOAD_A)
                + mutationCount(files, ProtocolStage.WRITE_PAYLOAD_B));
        assertEquals(1L, mutationCount(files, ProtocolStage.WRITE_RECOVERY));
        assertEquals(1L, mutationCount(files, ProtocolStage.WRITE_MAIN));
        assertTrue(files.managedIdentityReads() < 4_000,
                "81-Chunk import must not rescan the growing pool per item");
        StreamedChunkIndex reopenedIndex = reopenedBatchIndex(root);
        assertEquals(81, reopenedIndex.entries().size());
        StreamedChunkStore.BatchReadResult reread = reopen(root)
                .readModifiedBatch(SAVE_ID, reopenedIndex);
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, reread.status());
        assertEquals(81, reread.payloads().size());
        assertTrue(reread.diagnostics().isEmpty());
    }

    @Test
    void singleKeyReadHasConstantManagedIdentityWorkAcrossWideAuthority()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("single-read-bounded"));
        assertSuccess(reopen(root).commitModifiedBatch(
                batchCaptures(64, new AtomicInteger()), hibernation()));

        ProtocolFileOperations files = operations();
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
        files.clear();

        StreamedChunkStore.ReadResult result = store.read(
                SAVE_ID,
                new ChunkKey(0, 37),
                new StreamedChunkStore.ExpectedBase(
                        GENERATOR_VERSION,
                        "51bfdecc27f132dbaa4fe679971698431"
                                + "7bad083e45104ed3f6e361e0ea1938d"));

        assertEquals(StreamedChunkStore.ReadResult.Status.FOUND, result.status());
        int managedIdentityReads = files.managedIdentityReads();
        assertTrue(
                managedIdentityReads <= 20,
                "single-key read must prove only two index slots and the target's "
                        + "two payload slots with fixed overhead; actual="
                        + managedIdentityReads + " bound=20");
    }

    @Test
    void currentIndexReadHasConstantIdentityWorkAndNoPayloadReadsAcrossWideAuthority()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("current-index-bounded"));
        assertSuccess(reopen(root).commitModifiedBatch(
                batchCaptures(64, new AtomicInteger()), hibernation()));

        ProtocolFileOperations files = operations();
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
        files.clear();

        StreamedChunkIndex index = store.readCurrentIndex();

        assertEquals(SAVE_ID, index.saveGameId());
        assertEquals(64, index.entries().size());
        StreamedChunkIndex.Entry present = index.entry(new ChunkKey(0, 37))
                .orElseThrow();
        assertEquals(GENERATOR_VERSION, present.generatorVersion());
        assertEquals(
                "51bfdecc27f132dbaa4fe679971698431"
                        + "7bad083e45104ed3f6e361e0ea1938d",
                present.baseHash());
        assertThrows(UnsupportedOperationException.class, index.entries()::clear);
        assertEquals(0, files.payloadReads(),
                "structural index observation must not read any Chunk payload");
        int managedIdentityReads = files.managedIdentityReads();
        assertTrue(
                managedIdentityReads <= 8,
                "structural index observation must prove only two index slots; actual="
                        + managedIdentityReads + " bound=8");
    }

    @Test
    void batchStaleItemRejectsEveryMutationBeforeFreshnessCallbacks()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("batch-stale"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        assertTrue(store.commitModified(
                batchCapture(new ChunkKey(0, 0), 1L, 0L, new AtomicInteger()),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true)).unloadAuthorized());
        files.clear();
        AtomicInteger callbacks = new AtomicInteger();

        StreamedChunkStore.CommitResult result = store.commitModifiedBatch(
                List.of(
                        batchCapture(new ChunkKey(1, 0), 2L, 1L, callbacks),
                        batchCapture(new ChunkKey(0, 0), 2L, 1L, callbacks)),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> {
                            callbacks.incrementAndGet();
                            return true;
                        }));

        assertEquals(StreamedChunkStore.CommitResult.Status.STALE, result.status());
        assertEquals(0, callbacks.get());
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void batchRejectsEmptyAndDuplicateKeysBeforeMutation() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("batch-invalid"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        AtomicInteger callbacks = new AtomicInteger();
        StreamedChunkStore.ExactChunkCapture duplicate = batchCapture(
                new ChunkKey(0, 0), 1L, 0L, callbacks);

        StreamedChunkStore.CommitResult empty = store.commitModifiedBatch(
                List.of(),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));
        StreamedChunkStore.CommitResult duplicates = store.commitModifiedBatch(
                List.of(duplicate, duplicate),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));

        assertEquals(StreamedChunkStore.CommitResult.Status.FAILED, empty.status());
        assertEquals(
                StreamedChunkStore.CommitResult.Status.FAILED,
                duplicates.status());
        assertEquals(0, callbacks.get());
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void batchFreshnessErrorEscapesExactlyBeforeMutation() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("batch-error"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        AssertionError exact = new AssertionError("exact batch freshness error");
        StreamedChunkStore.ExactChunkCapture capture =
                new StreamedChunkStore.ExactChunkCapture(
                        batchCapture(
                                new ChunkKey(0, 0),
                                1L,
                                0L,
                                new AtomicInteger()).payload(),
                        () -> {
                            throw exact;
                        });

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> store.commitModifiedBatch(
                        List.of(capture),
                        new StreamedChunkStore.WorldItemHibernatePayload(
                                new byte[0], () -> true)));

        assertSame(exact, thrown);
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void batchReadValidatesIdenticalIndexSlotsAndPayloadSetOnlyOnce()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("batch-read-once"));
        assertTrue(reopen(root).commitModifiedBatch(
                batchCaptures(3, new AtomicInteger()),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true)).unloadAuthorized());
        StreamedChunkIndex expected = reopenedBatchIndex(root);
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);

        StreamedChunkStore.BatchReadResult result = store.readModifiedBatch(
                SAVE_ID, expected);

        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, result.status());
        assertEquals(3, result.payloads().size());
        assertTrue(
                files.managedIdentityReads() <= 40,
                () -> "equal index slots repeated payload validation: "
                        + files.managedIdentityReads());
    }

    @Test
    void middleBatchPayloadFaultKeepsOldAuthorityAndRetryPublishesCompleteNew()
            throws Exception {
        Path root = Files.createDirectory(
                tempDirectory.resolve("batch-middle-fault"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        List<StreamedChunkStore.ExactChunkCapture> captures =
                batchCaptures(81, new AtomicInteger());
        files.beforeMatching(
                ProtocolStage.WRITE_PAYLOAD_A,
                path -> path.getFileName().toString().startsWith("p00000028."),
                ignored -> {
                    throw new IOException("injected middle batch fault");
                });

        StreamedChunkStore.CommitResult failed = store.commitModifiedBatch(
                captures,
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));

        assertFalse(failed.unloadAuthorized());
        assertTrue(reopenedBatchIndex(root).entries().isEmpty());
        files.clear();
        StreamedChunkStore.CommitResult retried = store.commitModifiedBatch(
                captures,
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));
        assertTrue(retried.unloadAuthorized());
        assertEquals(81, reopenedBatchIndex(root).entries().size());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = ProtocolStage.class,
            names = {"WRITE_RECOVERY", "WRITE_MAIN"})
    void batchIndexPublicationFaultReopensOnlyOldOrCompleteNew(
            ProtocolStage faultStage) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "batch-index-fault-" + faultStage));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.after(faultStage, ignored -> {
            throw new IOException("injected batch index publication fault");
        });

        StreamedChunkStore.CommitResult result = store.commitModifiedBatch(
                batchCaptures(3, new AtomicInteger()),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));

        assertFalse(result.unloadAuthorized());
        int reopenedCount = reopenedBatchIndex(root).entries().size();
        assertTrue(reopenedCount == 0 || reopenedCount == 3);
    }

    @Test
    void replacementCommitHasNoDirectoryEntryMutationAfterPoolInitialization()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("no-entry-mutation"));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        assertSuccess(store.commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        files.clear();

        assertSuccess(store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation()));

        assertFalse(files.mutations().stream().anyMatch(event ->
                event.stage().createsOrRemovesDirectoryEntry()));
        assertEquals(
                List.of(
                        ProtocolStage.WRITE_PAYLOAD_B,
                        ProtocolStage.FORCE_PAYLOAD_B,
                        ProtocolStage.WRITE_RECOVERY,
                        ProtocolStage.FORCE_RECOVERY,
                        ProtocolStage.WRITE_MAIN,
                        ProtocolStage.FORCE_MAIN),
                files.mutations().stream()
                        .map(ProtocolEvent::stage)
                        .toList());
    }

    @ParameterizedTest
    @EnumSource(PrePublicationTear.class)
    void tornFirstCommitAlwaysRetainsValidEmptyAuthority(
            PrePublicationTear tear) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "first-tear-" + tear.name().toLowerCase(Locale.ROOT)));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.after(tear.stage, path -> {
            tear.corrupt(path);
            throw new IOException("injected first-commit tear");
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(1L, 0L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        assertEquals(
                StreamedChunkStore.ReadResult.Status.NOT_FOUND,
                read(reopen(root)).status());
    }

    @Test
    void lostUnpublishedPayloadEntryKeepsOldAuthorityAndIsDurablyRecreated()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("entry-loss"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.after(ProtocolStage.WRITE_PAYLOAD_B, path -> {
            Files.delete(path);
            throw new IOException("simulated directory-entry loss before authority");
        });

        StreamedChunkStore.CommitResult failed = store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation());

        assertFalse(failed.unloadAuthorized());
        assertEquals(7L, read(reopen(root)).payload().orElseThrow().revision());
        StreamedChunkStore repaired = reopen(root);
        assertSuccess(repaired.commitModified(
                capture(8L, 7L, (byte) 8), hibernation()));
        assertEquals(8L, read(reopen(root)).payload().orElseThrow().revision());
        assertTrue(Files.isRegularFile(payloadSlot(root, 'b')));
    }

    @Test
    void lostPublishedPayloadEntryFailsClosedWithoutObservationMutation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("published-loss"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        Files.delete(payloadSlot(root, 'a'));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.clear();

        StreamedChunkStore.ReadResult result = read(store);

        assertEquals(StreamedChunkStore.ReadResult.Status.CORRUPT, result.status());
        assertTrue(files.mutations().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ReplacementTear.class)
    void everyTornReplacementReopensExactOldOrNewAndNeverAuthorizesUnload(
            ReplacementTear tear) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "replacement-tear-" + tear.name().toLowerCase(Locale.ROOT)));
        Exact old = exact(7L, 6L, (byte) 3);
        Exact intended = exact(8L, 7L, (byte) 8);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.after(tear.stage, path -> {
            tear.corrupt(path);
            throw new IOException("injected replacement tear");
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                intended.capture, hibernation());

        assertFalse(result.unloadAuthorized());
        StreamedChunkPayload reopened = read(reopen(root)).payload().orElseThrow();
        assertTrue(
                exactEquals(old.payload, reopened)
                        || exactEquals(intended.payload, reopened));
    }

    @Test
    void observationIsReadOnlyAndNextCommitConvergesATornIndexSlot()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("converge"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        Files.write(recoveryIndex(root), new byte[] {1, 2, 3});
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.clear();

        assertEquals(7L, read(store).payload().orElseThrow().revision());
        assertTrue(files.mutations().isEmpty());
        assertSuccess(store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation()));
        assertArrayEquals(
                Files.readAllBytes(mainIndex(root)),
                Files.readAllBytes(recoveryIndex(root)));
        assertEquals(8L, read(reopen(root)).payload().orElseThrow().revision());
    }

    @Test
    void repeatedInterruptedPublicationsConvergeAndContinue()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("repeated"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));

        ProtocolFileOperations firstFiles = operations();
        StreamedChunkStore first = store(root, firstFiles);
        firstFiles.after(ProtocolStage.WRITE_RECOVERY, ignored -> {
            throw new IOException("interrupt after complete recovery write");
        });
        assertFalse(first.commitModified(
                capture(8L, 7L, (byte) 8), hibernation()).unloadAuthorized());
        assertEquals(8L, read(reopen(root)).payload().orElseThrow().revision());

        ProtocolFileOperations secondFiles = operations();
        StreamedChunkStore second = store(root, secondFiles);
        // The first main write converges the stale slot.  Target the following
        // publication write explicitly so the marker cannot hit remediation.
        secondFiles.after(ProtocolStage.WRITE_MAIN, ignored -> {});
        secondFiles.after(ProtocolStage.WRITE_MAIN, ignored -> {
            throw new IOException("interrupt after complete main write");
        });
        assertFalse(second.commitModified(
                capture(9L, 8L, (byte) 9), hibernation()).unloadAuthorized());
        assertEquals(9L, read(reopen(root)).payload().orElseThrow().revision());

        StreamedChunkStore finalStore = reopen(root);
        assertSuccess(finalStore.commitModified(
                capture(10L, 9L, (byte) 10), hibernation()));
        assertEquals(10L, read(reopen(root)).payload().orElseThrow().revision());
    }

    @Test
    void equalSequenceUnequalAuthoritiesBlockBeforeCallbacksOrMutation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("equal-conflict"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        long sequence = slotSequence(Files.readAllBytes(mainIndex(root)));
        Files.write(
                recoveryIndex(root),
                slotBytes(sequence, new StreamedChunkIndex(SAVE_ID, List.of())));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.clear();
        int[] callbacks = {0};

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8, () -> {
                    callbacks[0]++;
                    return true;
                }),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[] {1},
                        () -> {
                            callbacks[0]++;
                            return true;
                        }));

        assertEquals(
                StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                result.status());
        assertEquals(0, callbacks[0]);
        assertTrue(files.mutations().isEmpty());
    }

    @ParameterizedTest(name = "migration-proof-{0}-{1}-blocks-without-mutation")
    @MethodSource("migrationProofAuthorityAttacks")
    void migrationCompatibilityIsMonotonicAcrossReadCommitAndPublish(
            MigrationProofAuthorityAttack attack,
            MigrationProofAuthorityOperation operation) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "migration-proof-" + attack + "-" + operation));
        StreamedChunkStore initial = reopen(root);
        assertSuccess(initial.commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        assertSuccess(initial.publishMigrationCompatibility(MIGRATION_PROOF));
        StreamedChunkIndex published = slotIndex(Files.readAllBytes(mainIndex(root)));
        long sequence = Math.max(
                slotSequence(Files.readAllBytes(mainIndex(root))),
                slotSequence(Files.readAllBytes(recoveryIndex(root))));
        attack.apply(root, published, sequence);
        ProtocolFileOperations files = operations();
        StreamedChunkStore attacked = store(root, files);
        files.clear();
        byte[] exactMain = Files.readAllBytes(mainIndex(root));
        byte[] exactRecovery = Files.readAllBytes(recoveryIndex(root));
        int[] callbacks = {0};

        operation.assertBlocked(attacked, callbacks);

        assertEquals(0, callbacks[0]);
        assertTrue(files.mutations().isEmpty());
        assertArrayEquals(exactMain, Files.readAllBytes(mainIndex(root)));
        assertArrayEquals(exactRecovery, Files.readAllBytes(recoveryIndex(root)));
    }

    private static Stream<Arguments> migrationProofAuthorityAttacks() {
        return Stream.of(MigrationProofAuthorityAttack.values())
                .flatMap(attack -> Stream.of(MigrationProofAuthorityOperation.values())
                        .map(operation -> Arguments.of(attack, operation)));
    }

    @Test
    void sequenceExhaustionPrecedesConvergenceCallbacksAndEveryMutation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("maximum"));
        Exact old = exact(7L, 6L, (byte) 3);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        StreamedChunkIndex index = indexFor(old.payload);
        Files.write(mainIndex(root), slotBytes(Long.MAX_VALUE, index));
        Files.write(recoveryIndex(root), slotBytes(1L, index));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.clear();
        int[] callbacks = {0};

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8, () -> {
                    callbacks[0]++;
                    return true;
                }),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[] {1},
                        () -> {
                            callbacks[0]++;
                            return true;
                        }));

        assertEquals(StreamedChunkStore.CommitResult.Status.FAILED, result.status());
        assertEquals(
                "streamed-chunk-store.sequence-exhausted",
                result.diagnostics().get(0).code());
        assertEquals(0, callbacks[0]);
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void preexistingPayloadHardLinkIsRejectedWithoutTouchingExternalBytes()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("payload-link"));
        reopen(root);
        Path shard = Files.createDirectory(shard(root));
        Path external = tempDirectory.resolve("external-payload.bin");
        byte[] sentinel = "external payload sentinel".getBytes();
        Files.write(external, sentinel, StandardOpenOption.CREATE_NEW);
        createHardLinkOrSkip(payloadSlot(root, 'a'), external);
        Files.write(payloadSlot(root, 'b'), new byte[0], StandardOpenOption.CREATE_NEW);

        StreamedChunkStore.CommitResult result = reopen(root).commitModified(
                capture(1L, 0L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        assertArrayEquals(sentinel, Files.readAllBytes(external));
    }

    @Test
    void preexistingIndexHardLinkIsRejectedBeforeInitializationWrite()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("index-link"));
        Path world = Files.createDirectory(root.resolve(SAVE_ID.value()));
        Path external = tempDirectory.resolve("external-index.bin");
        byte[] sentinel = "external index sentinel".getBytes();
        Files.write(external, sentinel, StandardOpenOption.CREATE_NEW);
        createHardLinkOrSkip(world.resolve("streamed-chunks.idx"), external);

        assertThrows(IllegalArgumentException.class, () -> reopen(root));
        assertArrayEquals(sentinel, Files.readAllBytes(external));
    }

    @Test
    void replacementByExternalHardLinkBeforeWriteNeverTouchesSentinel()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("replacement-link"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        Path external = tempDirectory.resolve("replacement-external.bin");
        byte[] sentinel = "replacement sentinel".getBytes();
        Files.write(external, sentinel, StandardOpenOption.CREATE_NEW);
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.before(ProtocolStage.WRITE_PAYLOAD_B, path -> {
            Files.delete(path);
            createHardLinkOrSkip(path, external);
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        assertArrayEquals(sentinel, Files.readAllBytes(external));
        assertEquals(
                StreamedChunkStore.ReadResult.Status.CORRUPT,
                read(reopen(root)).status());
    }

    @ParameterizedTest
    @EnumSource(DirectoryReplacement.class)
    void anchoredDirectoryReplacementInsideWriteGuardCannotReachSentinel(
            DirectoryReplacement replacement) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "directory-replacement-"
                        + replacement.name().toLowerCase(Locale.ROOT)));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        byte[] sentinel = ("directory sentinel " + replacement.name()).getBytes();
        files.before(ProtocolStage.WRITE_PAYLOAD_B, attacked -> {
            Path target = replacement.target(root);
            Path displaced = target.resolveSibling(
                    target.getFileName() + "-displaced");
            Files.move(target, displaced);
            Files.createDirectories(attacked.getParent());
            Files.write(attacked, sentinel, StandardOpenOption.CREATE_NEW);
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        assertArrayEquals(sentinel, Files.readAllBytes(payloadSlot(root, 'b')));
    }

    @ParameterizedTest
    @EnumSource(IndexHardLinkReplacement.class)
    void indexReplacementByExternalHardLinkBeforeWriteNeverTouchesSentinel(
            IndexHardLinkReplacement replacement) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "index-replacement-" + replacement.name().toLowerCase(Locale.ROOT)));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        Path external = tempDirectory.resolve(
                "index-replacement-external-" + replacement.name() + ".bin");
        byte[] sentinel = ("index replacement " + replacement.name()).getBytes();
        Files.write(external, sentinel, StandardOpenOption.CREATE_NEW);
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.before(replacement.stage, path -> {
            Files.delete(path);
            createHardLinkOrSkip(path, external);
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        assertArrayEquals(sentinel, Files.readAllBytes(external));
    }

    @Test
    void nullDirectoryProviderIdentityFailsClosedWithoutProofFallback()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("null-directory-key"));
        ProtocolFileOperations files = operations();
        files.returnNullDirectoryKeys();

        assertThrows(IllegalArgumentException.class, () -> store(root, files));
    }

    @Test
    void stockWindowsProviderPerformsARealDurableDirectoryFlush()
            throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path directory = Files.createDirectory(tempDirectory.resolve("durable-directory"));

        assertDoesNotThrow(() -> new JdkSaveFileOperations()
                .forceDirectoryDurably(directory, () -> {}));
    }

    @Test
    void stockWindowsProviderRejectsJunctionSaveRoot()
            throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path target = Files.createDirectory(tempDirectory.resolve("junction-target"));
        Path junction = tempDirectory.resolve("junction-root");
        Process process = new ProcessBuilder(
                        "cmd.exe",
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        junction.toString(),
                        target.toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            Assumptions.abort("junction fixture unavailable: " + new String(output));
        }

        assertThrows(IllegalArgumentException.class, () -> reopen(junction));
    }

    @Test
    void unrelatedLegacyNamesAndUnsupportedOptionalCleanupCannotBlockRead()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("unowned-cleanup"));
        Exact current = exact(7L, 6L, (byte) 3);
        assertSuccess(reopen(root).commitModified(current.capture, hibernation()));
        Path foreignTemp = shard(root).resolve("foreign.glchunk.1.tmp");
        Path foreignPayload = shard(root).resolve(
                "p00000007-r0000000000000063-" + "22".repeat(32) + ".glchunk");
        Files.write(foreignTemp, new byte[] {1});
        Files.write(foreignPayload, new byte[] {2});
        ProtocolFileOperations files = operations();
        files.failIfDeleteOrOptionalDirectoryForceIsCalled();
        StreamedChunkStore store = store(root, files);
        files.clear();

        StreamedChunkStore.ReadResult read = read(store);

        assertEquals(StreamedChunkStore.ReadResult.Status.FOUND, read.status());
        assertExact(current.payload, read.payload().orElseThrow());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(foreignTemp));
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(foreignPayload));
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void staleRevisionAndBaseMismatchArePreMutationCasFailures()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("cas"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.clear();

        StreamedChunkStore.CommitResult stale = store.commitModified(
                capture(8L, 6L, (byte) 8), hibernation());
        StreamedChunkStore.CommitResult base = store.commitModified(
                new StreamedChunkStore.ExactChunkCapture(
                        payload("other", "22".repeat(32), 8L, 7L, (byte) 8),
                        () -> true),
                hibernation());

        assertEquals(StreamedChunkStore.CommitResult.Status.STALE, stale.status());
        assertEquals(StreamedChunkStore.CommitResult.Status.FAILED, base.status());
        assertTrue(files.mutations().isEmpty());
        assertEquals(7L, read(reopen(root)).payload().orElseThrow().revision());
    }

    @Test
    void readRequiresExactWorldKeyAndBase()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("read-cas"));
        StreamedChunkStore store = reopen(root);
        assertSuccess(store.commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));

        assertEquals(
                StreamedChunkStore.ReadResult.Status.IDENTITY_MISMATCH,
                store.read(
                                SaveGameId.parse("223e4567-e89b-12d3-a456-426614174000"),
                                KEY,
                                expectedBase())
                        .status());
        assertEquals(
                StreamedChunkStore.ReadResult.Status.BASE_MISMATCH,
                store.read(
                                SAVE_ID,
                                KEY,
                                new StreamedChunkStore.ExpectedBase(
                                        GENERATOR_VERSION, "22".repeat(32)))
                        .status());
        assertEquals(
                StreamedChunkStore.ReadResult.Status.NOT_FOUND,
                store.read(SAVE_ID, new ChunkKey(9, 9), expectedBase()).status());
    }

    @Test
    void exactErrorEscapesUnwrappedAndOldAuthorityRemainsReadable()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("error"));
        assertSuccess(reopen(root).commitModified(
                capture(7L, 6L, (byte) 3), hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        AssertionError exact = new AssertionError("exact error");
        files.before(ProtocolStage.WRITE_PAYLOAD_B, ignored -> {
            throw exact;
        });

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> store.commitModified(
                        capture(8L, 7L, (byte) 8), hibernation()));

        assertSame(exact, thrown);
        assertEquals(7L, read(reopen(root)).payload().orElseThrow().revision());
    }

    @ParameterizedTest
    @EnumSource(FreshnessSource.class)
    void eachFreshnessSupplierIsFrozenOnceBeforeAuthorityMutation(
            FreshnessSource source) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "freshness-once-" + source.name().toLowerCase(Locale.ROOT)));
        Exact old = exact(7L, 6L, (byte) 3);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        AssertionError late = new AssertionError("freshness callback ran after mutation began");
        int[] calls = {0};
        java.util.function.BooleanSupplier guarded = () -> {
            if (++calls[0] != 1) {
                throw late;
            }
            return true;
        };

        StreamedChunkStore.CommitResult result = store.commitModified(
                source.capture(8L, 7L, (byte) 8, guarded),
                source.hibernation(guarded));

        assertSuccess(result);
        assertEquals(1, calls[0]);
        assertEquals(8L, read(reopen(root)).payload().orElseThrow().revision());
    }

    @ParameterizedTest
    @EnumSource(FreshnessSource.class)
    void falseFreshnessAtLinearizationPreservesExactOldAuthority(
            FreshnessSource source) throws Exception {
        assertFreshnessFailurePreservesOld(source, () -> false, null);
    }

    @ParameterizedTest
    @EnumSource(FreshnessSource.class)
    void runtimeFreshnessFailureAtLinearizationPreservesExactOldAuthority(
            FreshnessSource source) throws Exception {
        assertFreshnessFailurePreservesOld(
                source, () -> { throw new IllegalStateException("runtime freshness"); }, null);
    }

    @ParameterizedTest
    @EnumSource(FreshnessSource.class)
    void exactErrorAtFreshnessLinearizationEscapesBeforeEveryMutation(
            FreshnessSource source) throws Exception {
        AssertionError exact = new AssertionError("exact freshness error");
        assertFreshnessFailurePreservesOld(source, () -> { throw exact; }, exact);
    }

    private void assertFreshnessFailurePreservesOld(
            FreshnessSource source,
            java.util.function.BooleanSupplier failure,
            AssertionError exactError) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "freshness-failure-"
                        + source.name().toLowerCase(Locale.ROOT)
                        + "-"
                        + (exactError == null ? failure.getClass().getName().hashCode() : "error")));
        Exact old = exact(7L, 6L, (byte) 3);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);

        if (exactError == null) {
            StreamedChunkStore.CommitResult result = store.commitModified(
                    source.capture(8L, 7L, (byte) 8, failure),
                    source.hibernation(failure));
            assertFalse(result.unloadAuthorized());
        } else {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> store.commitModified(
                            source.capture(8L, 7L, (byte) 8, failure),
                            source.hibernation(failure)));
            assertSame(exactError, thrown);
        }
        assertTrue(files.mutations().isEmpty());
        assertExact(old.payload, read(reopen(root)).payload().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(PreSideEffectFault.class)
    void everyPreSideEffectFaultPreservesExactLastKnownGoodAndDeniesUnload(
            PreSideEffectFault fault) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "fault-" + fault.name().toLowerCase(Locale.ROOT)));
        Exact old = exact(7L, 6L, (byte) 3);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        ProtocolFileOperations files = operations();
        StreamedChunkStore store = store(root, files);
        files.before(fault.stage, ignored -> {
            throw new IOException("injected fixed-slot fault");
        });

        StreamedChunkStore.CommitResult result = store.commitModified(
                capture(8L, 7L, (byte) 8), hibernation());

        assertFalse(result.unloadAuthorized());
        StreamedChunkPayload reopened = read(reopen(root)).payload().orElseThrow();
        assertTrue(
                exactEquals(old.payload, reopened)
                        || exactEquals(
                                capture(8L, 7L, (byte) 8).payload(), reopened));
    }

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void stockWindowsSubprocessCrashAtEveryActualWriteAndForceStageReopensOldOrNew(
            CrashPoint point) throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path root = Files.createDirectory(tempDirectory.resolve(
                "crash-" + point.name().toLowerCase(Locale.ROOT)));
        Exact old = exact(7L, 6L, (byte) 3);
        Exact intended = exact(8L, 7L, (byte) 8);
        assertSuccess(reopen(root).commitModified(old.capture, hibernation()));
        Path marker = tempDirectory.resolve("marker-" + point.name());

        Process child = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        testRuntimeClasspath(),
                        StreamedChunkFixedSlotCrashFixture.class.getName(),
                        root.toString(),
                        point.name(),
                        marker.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(child.waitFor(30, TimeUnit.SECONDS), point.name());
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(
                StreamedChunkFixedSlotCrashFixture.CRASH_EXIT,
                child.exitValue(),
                point + " output=" + output);
        assertEquals(
                point.stage.name() + "|" + point.expectedFileName,
                Files.readString(marker));
        StreamedChunkPayload reopened = read(reopen(root)).payload().orElseThrow();
        assertTrue(
                exactEquals(old.payload, reopened)
                        || exactEquals(intended.payload, reopened));
    }

    @ParameterizedTest
    @EnumSource(FirstPublicationCrashPoint.class)
    void stockWindowsFirstPublicationCrashMatrixReopensExactEmptyOrNew(
            FirstPublicationCrashPoint point) throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path root = Files.createDirectory(tempDirectory.resolve(
                "first-crash-" + point.name().toLowerCase(Locale.ROOT)));
        Path marker = tempDirectory.resolve("first-marker-" + point.name());

        Process child = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        testRuntimeClasspath(),
                        StreamedChunkFirstPublicationCrashFixture.class.getName(),
                        root.toString(),
                        point.name(),
                        marker.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(child.waitFor(30, TimeUnit.SECONDS), point.name());
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(
                StreamedChunkFirstPublicationCrashFixture.CRASH_EXIT,
                child.exitValue(),
                point + " output=" + output);
        assertEquals(
                point.stage.name() + "|" + point.expectedFileName,
                Files.readString(marker));
        StreamedChunkStore.ReadResult reopened = read(reopen(root));
        assertTrue(
                reopened.status() == StreamedChunkStore.ReadResult.Status.NOT_FOUND
                        || (reopened.status() == StreamedChunkStore.ReadResult.Status.FOUND
                                && reopened.payload().orElseThrow().revision() == 1L));
    }

    @ParameterizedTest
    @EnumSource(InitializationSubprocessPoint.class)
    void stockWindowsInitializationCrashIsReprovenOnRetryBeforeCommit(
            InitializationSubprocessPoint point) throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path root = Files.createDirectory(tempDirectory.resolve(
                "init-process-" + point.name().toLowerCase(Locale.ROOT)));
        Path marker = tempDirectory.resolve("init-marker-" + point.name());

        Process child = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        testRuntimeClasspath(),
                        StreamedChunkInitializationCrashFixture.class.getName(),
                        root.toString(),
                        point.name(),
                        marker.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(child.waitFor(30, TimeUnit.SECONDS), point.name());
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(
                StreamedChunkInitializationCrashFixture.CRASH_EXIT,
                child.exitValue(),
                point + " output=" + output);
        assertEquals(point.expectedMarker(root), Files.readString(marker));
        StreamedChunkStore retry = reopen(root);
        assertEquals(StreamedChunkStore.ReadResult.Status.NOT_FOUND, read(retry).status());
        assertSuccess(retry.commitModified(
                capture(1L, 0L, (byte) 8), hibernation()));
        assertEquals(1L, read(reopen(root)).payload().orElseThrow().revision());
    }

    private static StreamedChunkStore store(
            Path root, ProtocolFileOperations files) {
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
        files.clear();
        return store;
    }

    private static StreamedChunkStore reopen(Path root) {
        return new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
    }

    private static ProtocolFileOperations operations() {
        return new ProtocolFileOperations(new JdkSaveFileOperations());
    }

    private static StreamedChunkStore.ReadResult read(StreamedChunkStore store) {
        return store.read(SAVE_ID, KEY, expectedBase());
    }

    private static StreamedChunkStore.ExpectedBase expectedBase() {
        return new StreamedChunkStore.ExpectedBase(GENERATOR_VERSION, BASE_HASH);
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            long revision, long persistedRevision, byte value) {
        return capture(revision, persistedRevision, value, () -> true);
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            long revision,
            long persistedRevision,
            byte value,
            java.util.function.BooleanSupplier current) {
        return new StreamedChunkStore.ExactChunkCapture(
                payload(
                        GENERATOR_VERSION,
                        BASE_HASH,
                        revision,
                        persistedRevision,
                        value),
                current);
    }

    private static List<StreamedChunkStore.ExactChunkCapture> batchCaptures(
            int count, AtomicInteger callbacks) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ordinal -> batchCapture(
                        new ChunkKey(0, ordinal), 1L, 0L, callbacks))
                .toList();
    }

    private static StreamedChunkStore.ExactChunkCapture batchCapture(
            ChunkKey key,
            long revision,
            long persistedRevision,
            AtomicInteger callbacks) {
        byte[] voxels = new byte[16 * 1 * 16];
        voxels[Math.floorMod(key.z(), voxels.length)] = (byte) (key.z() + 1);
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                key,
                GENERATOR_VERSION,
                StreamedChunkCodec.sha256Hex(
                        ("batch-base-" + key.x() + "-" + key.z()).getBytes()),
                revision,
                persistedRevision,
                true,
                true,
                1,
                voxels,
                List.of());
        return new StreamedChunkStore.ExactChunkCapture(payload, () -> {
            callbacks.incrementAndGet();
            return true;
        });
    }

    private static long mutationCount(
            ProtocolFileOperations files, ProtocolStage stage) {
        return files.mutations().stream()
                .filter(event -> event.stage() == stage)
                .count();
    }

    private static StreamedChunkIndex reopenedBatchIndex(Path root)
            throws Exception {
        byte[] main = Files.readAllBytes(mainIndex(root));
        byte[] recovery = Files.readAllBytes(recoveryIndex(root));
        return slotIndex(slotSequence(main) >= slotSequence(recovery)
                ? main
                : recovery);
    }

    static StreamedChunkPayload payload(
            String generator,
            String baseHash,
            long revision,
            long persistedRevision,
            byte value) {
        byte[] voxels = new byte[16 * 1 * 16];
        voxels[2] = value;
        return new StreamedChunkPayload(
                SAVE_ID,
                KEY,
                generator,
                baseHash,
                revision,
                persistedRevision,
                true,
                true,
                1,
                voxels,
                List.of());
    }

    private static Exact exact(
            long revision, long persistedRevision, byte value) {
        StreamedChunkStore.ExactChunkCapture capture = capture(
                revision, persistedRevision, value);
        return new Exact(capture, capture.payload());
    }

    static StreamedChunkStore.WorldItemHibernatePayload hibernation() {
        return new StreamedChunkStore.WorldItemHibernatePayload(
                new byte[] {1}, () -> true);
    }

    private static StreamedChunkIndex indexFor(StreamedChunkPayload payload) {
        byte[] bytes = new StreamedChunkCodec().encode(payload);
        return new StreamedChunkIndex(
                SAVE_ID,
                List.of(new StreamedChunkIndex.Entry(
                        payload.key(),
                        payload.generatorVersion(),
                        payload.baseHash(),
                        payload.revision(),
                        bytes.length,
                        StreamedChunkCodec.sha256Hex(bytes),
                        true,
                        true)));
    }

    static Path world(Path root) {
        return root.resolve(SAVE_ID.value());
    }

    static Path mainIndex(Path root) {
        return world(root).resolve("streamed-chunks.idx");
    }

    static Path recoveryIndex(Path root) {
        return world(root).resolve("streamed-chunks.prev.idx");
    }

    static Path shard(Path root) {
        return world(root).resolve("streamed-chunks").resolve(signed(KEY.x()));
    }

    static Path payloadSlot(Path root, char slot) {
        return shard(root).resolve(signed(KEY.z()) + "." + slot + ".glchunk");
    }

    private static String signed(int coordinate) {
        return (coordinate < 0 ? "n" : "p")
                + String.format(Locale.ROOT, "%08x", Math.abs((long) coordinate));
    }

    private static byte[] slotBytes(long sequence, StreamedChunkIndex index)
            throws Exception {
        byte[] indexBytes = new StreamedChunkIndexCodec().encode(index);
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(0x47495332);
            output.writeInt(2);
            output.writeLong(sequence);
            output.writeInt(indexBytes.length);
            output.write(indexBytes);
        }
        byte[] body = bodyBytes.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(body);
        encoded.write(MessageDigest.getInstance("SHA-256").digest(body));
        return encoded.toByteArray();
    }

    private static long slotSequence(byte[] encoded) {
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        assertEquals(0x47495332, input.getInt());
        assertEquals(2, input.getInt());
        return input.getLong();
    }

    private static StreamedChunkIndex slotIndex(byte[] encoded) throws Exception {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            assertEquals(0x47495332, input.readInt());
            assertEquals(2, input.readInt());
            input.readLong();
            int length = input.readInt();
            assertTrue(length > 0 && length <= encoded.length - 52);
            byte[] index = new byte[length];
            input.readFully(index);
            return new StreamedChunkIndexCodec().decode(index);
        }
    }

    private static void assertSuccess(StreamedChunkStore.CommitResult result) {
        assertEquals(
                StreamedChunkStore.CommitResult.Status.SUCCESS,
                result.status(),
                () -> result.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code()
                                + ":"
                                + diagnostic.cause()
                                        .map(Throwable::toString)
                                        .orElse("none"))
                        .toList()
                        .toString());
        assertTrue(result.unloadAuthorized());
    }

    private enum MigrationProofAuthorityAttack {
        HIGHER_PROOF_FREE_LOWER_PROOF {
            @Override
            void apply(Path root, StreamedChunkIndex published, long sequence)
                    throws Exception {
                StreamedChunkIndex proofFree = new StreamedChunkIndex(
                        SAVE_ID, published.entries());
                Files.write(mainIndex(root), slotBytes(sequence + 1L, proofFree));
                Files.write(recoveryIndex(root), slotBytes(sequence, published));
            }
        },
        CONFLICTING_PROOF_VALUES {
            @Override
            void apply(Path root, StreamedChunkIndex published, long sequence)
                    throws Exception {
                StreamedChunkIndex conflicting = new StreamedChunkIndex(
                        SAVE_ID, CONFLICTING_PROOF, published.entries());
                Files.write(mainIndex(root), slotBytes(sequence + 1L, conflicting));
                Files.write(recoveryIndex(root), slotBytes(sequence, published));
            }
        };

        abstract void apply(
                Path root, StreamedChunkIndex published, long sequence) throws Exception;
    }

    private enum MigrationProofAuthorityOperation {
        READ {
            @Override
            void assertBlocked(StreamedChunkStore store, int[] callbacks) {
                assertEquals(
                        StreamedChunkStore.CurrentAuthorityReadResult.Status.CORRUPT,
                        store.readCurrentAuthority(SAVE_ID).status());
            }
        },
        COMMIT {
            @Override
            void assertBlocked(StreamedChunkStore store, int[] callbacks) {
                StreamedChunkStore.CommitResult result = store.commitModified(
                        capture(8L, 7L, (byte) 8, () -> {
                            callbacks[0]++;
                            return true;
                        }),
                        new StreamedChunkStore.WorldItemHibernatePayload(
                                new byte[] {1},
                                () -> {
                                    callbacks[0]++;
                                    return true;
                                }));
                assertEquals(
                        StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                        result.status());
            }
        },
        PUBLISH {
            @Override
            void assertBlocked(StreamedChunkStore store, int[] callbacks) {
                assertEquals(
                        StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                        store.publishMigrationCompatibility(MIGRATION_PROOF).status());
            }
        };

        abstract void assertBlocked(StreamedChunkStore store, int[] callbacks);
    }

    private static void assertExact(
            StreamedChunkPayload expected, StreamedChunkPayload actual) {
        assertTrue(exactEquals(expected, actual));
    }

    private static boolean exactEquals(
            StreamedChunkPayload expected, StreamedChunkPayload actual) {
        return expected.saveGameId().equals(actual.saveGameId())
                && expected.key().equals(actual.key())
                && expected.generatorVersion().equals(actual.generatorVersion())
                && expected.baseHash().equals(actual.baseHash())
                && expected.revision() == actual.revision()
                && expected.persistedRevision() == actual.persistedRevision()
                && expected.persistenceRequired() == actual.persistenceRequired()
                && expected.voxelModified() == actual.voxelModified()
                && expected.worldHeight() == actual.worldHeight()
                && Arrays.equals(
                        expected.copyCanonicalVoxels(), actual.copyCanonicalVoxels());
    }

    private static void createHardLinkOrSkip(Path link, Path existing)
            throws IOException {
        try {
            Files.createLink(link, existing);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("hard-link fixture unavailable: " + unavailable.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static String javaExecutable() {
        return Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static String testRuntimeClasspath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        String declared = System.getProperty("java.class.path", "");
        if (!declared.isBlank()) {
            entries.addAll(Arrays.asList(declared.split(
                    java.util.regex.Pattern.quote(File.pathSeparator))));
        }
        for (ClassLoader loader = StreamedChunkStoreFaultTest.class.getClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        entries.add(Path.of(StreamedChunkStoreFaultTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString());
        entries.add(Path.of(StreamedChunkStore.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString());
        return String.join(File.pathSeparator, entries);
    }

    private record Exact(
            StreamedChunkStore.ExactChunkCapture capture,
            StreamedChunkPayload payload) {}

    enum FreshnessSource {
        CAPTURE,
        ITEMS;

        private StreamedChunkStore.ExactChunkCapture capture(
                long revision,
                long persistedRevision,
                byte value,
                java.util.function.BooleanSupplier selected) {
            return StreamedChunkStoreFaultTest.capture(
                    revision,
                    persistedRevision,
                    value,
                    this == CAPTURE ? selected : () -> true);
        }

        private StreamedChunkStore.WorldItemHibernatePayload hibernation(
                java.util.function.BooleanSupplier selected) {
            return new StreamedChunkStore.WorldItemHibernatePayload(
                    new byte[] {1}, this == ITEMS ? selected : () -> true);
        }
    }

    enum InitializationRetryBoundary {
        WORLD_DIRECTORY(true),
        CHUNK_DIRECTORY(true),
        MAIN_FILE(true),
        MAIN_PARENT(true),
        RECOVERY_FILE(true),
        RECOVERY_PARENT(true),
        SHARD_DIRECTORY(false),
        PAYLOAD_A_FILE(false),
        PAYLOAD_B_FILE(false),
        PAYLOAD_POOL_PARENT(false);

        private final boolean constructorBoundary;

        InitializationRetryBoundary(boolean constructorBoundary) {
            this.constructorBoundary = constructorBoundary;
        }

        private boolean constructorBoundary() {
            return constructorBoundary;
        }

        private void arm(ProtocolFileOperations files, Path root) {
            AtomicBoolean created = new AtomicBoolean();
            switch (this) {
                case WORLD_DIRECTORY -> files.beforeMatching(
                        ProtocolStage.FORCE_DIRECTORY,
                        path -> path.equals(root),
                        ignored -> { throw new IOException("world parent force failed"); });
                case CHUNK_DIRECTORY -> files.beforeMatching(
                        ProtocolStage.FORCE_DIRECTORY,
                        path -> path.equals(world(root)),
                        ignored -> { throw new IOException("chunk parent force failed"); });
                case MAIN_FILE -> files.before(
                        ProtocolStage.FORCE_MAIN,
                        ignored -> { throw new IOException("main file force failed"); });
                case MAIN_PARENT -> {
                    files.after(ProtocolStage.FORCE_MAIN, ignored -> created.set(true));
                    files.beforeMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> created.get() && path.equals(world(root)),
                            ignored -> { throw new IOException("main parent force failed"); });
                }
                case RECOVERY_FILE -> files.before(
                        ProtocolStage.FORCE_RECOVERY,
                        ignored -> { throw new IOException("recovery file force failed"); });
                case RECOVERY_PARENT -> {
                    files.after(ProtocolStage.FORCE_RECOVERY, ignored -> created.set(true));
                    files.beforeMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> created.get() && path.equals(world(root)),
                            ignored -> { throw new IOException("recovery parent force failed"); });
                }
                case SHARD_DIRECTORY -> files.beforeMatching(
                        ProtocolStage.FORCE_DIRECTORY,
                        path -> path.equals(world(root).resolve("streamed-chunks")),
                        ignored -> { throw new IOException("shard parent force failed"); });
                case PAYLOAD_A_FILE -> files.before(
                        ProtocolStage.FORCE_PAYLOAD_A,
                        ignored -> { throw new IOException("payload A file force failed"); });
                case PAYLOAD_B_FILE -> files.before(
                        ProtocolStage.FORCE_PAYLOAD_B,
                        ignored -> { throw new IOException("payload B file force failed"); });
                case PAYLOAD_POOL_PARENT -> files.beforeMatching(
                        ProtocolStage.FORCE_DIRECTORY,
                        path -> path.equals(shard(root)),
                        ignored -> { throw new IOException("payload pool parent force failed"); });
            }
        }

        private void assertRetryProofPrecedesPublication(
                List<ProtocolEvent> events, Path root) {
            ProtocolEvent proof = switch (this) {
                case WORLD_DIRECTORY -> new ProtocolEvent(ProtocolStage.FORCE_DIRECTORY, root);
                case CHUNK_DIRECTORY ->
                        new ProtocolEvent(ProtocolStage.FORCE_DIRECTORY, world(root));
                case MAIN_FILE, MAIN_PARENT ->
                        new ProtocolEvent(ProtocolStage.FORCE_MAIN, mainIndex(root));
                case RECOVERY_FILE, RECOVERY_PARENT ->
                        new ProtocolEvent(ProtocolStage.FORCE_RECOVERY, recoveryIndex(root));
                case SHARD_DIRECTORY -> new ProtocolEvent(
                        ProtocolStage.FORCE_DIRECTORY,
                        world(root).resolve("streamed-chunks"));
                case PAYLOAD_A_FILE ->
                        new ProtocolEvent(ProtocolStage.FORCE_PAYLOAD_A, payloadSlot(root, 'a'));
                case PAYLOAD_B_FILE ->
                        new ProtocolEvent(ProtocolStage.FORCE_PAYLOAD_B, payloadSlot(root, 'b'));
                case PAYLOAD_POOL_PARENT ->
                        new ProtocolEvent(ProtocolStage.FORCE_DIRECTORY, shard(root));
            };
            ProtocolEvent publication = switch (this) {
                case WORLD_DIRECTORY -> new ProtocolEvent(
                        ProtocolStage.CREATE_DIRECTORY,
                        world(root).resolve("streamed-chunks"));
                case CHUNK_DIRECTORY ->
                        new ProtocolEvent(ProtocolStage.CREATE_MAIN, mainIndex(root));
                case MAIN_FILE, MAIN_PARENT, RECOVERY_FILE, RECOVERY_PARENT ->
                        new ProtocolEvent(ProtocolStage.WRITE_PAYLOAD_A, payloadSlot(root, 'a'));
                case SHARD_DIRECTORY ->
                        new ProtocolEvent(ProtocolStage.CREATE_PAYLOAD_A, payloadSlot(root, 'a'));
                case PAYLOAD_A_FILE, PAYLOAD_B_FILE, PAYLOAD_POOL_PARENT ->
                        new ProtocolEvent(ProtocolStage.WRITE_PAYLOAD_A, payloadSlot(root, 'a'));
            };
            int proofIndex = canonicalEventIndex(events, proof, false);
            int publicationIndex = canonicalEventIndex(events, publication, false);
            assertTrue(
                    proofIndex >= 0 && publicationIndex >= 0 && proofIndex < publicationIndex,
                    () -> name() + " events=" + events);
            if (this == MAIN_PARENT || this == RECOVERY_PARENT) {
                ProtocolEvent parentProof = new ProtocolEvent(
                        ProtocolStage.FORCE_DIRECTORY, world(root));
                int parentIndex = canonicalEventIndex(events, parentProof, true);
                assertTrue(parentIndex >= proofIndex && parentIndex < publicationIndex,
                        () -> name() + " events=" + events);
            }
        }

        private static int canonicalEventIndex(
                List<ProtocolEvent> events,
                ProtocolEvent expected,
                boolean last) {
            int found = -1;
            for (int index = 0; index < events.size(); index++) {
                ProtocolEvent event = events.get(index);
                if (event.stage() == expected.stage()
                        && canonicalPath(event.path()).equals(
                                canonicalPath(expected.path()))) {
                    found = index;
                    if (!last) {
                        return found;
                    }
                }
            }
            return found;
        }

        private static Path canonicalPath(Path path) {
            try {
                return path.toFile().getCanonicalFile().toPath();
            } catch (IOException failure) {
                return path.toAbsolutePath().normalize();
            }
        }
    }

    enum InitializationCrash {
        AFTER_TORN_MAIN_CREATE(ProtocolStage.CREATE_MAIN, true),
        AFTER_MAIN_FORCE(ProtocolStage.FORCE_MAIN, false),
        AFTER_TORN_RECOVERY_CREATE(ProtocolStage.CREATE_RECOVERY, true),
        AFTER_RECOVERY_FORCE(ProtocolStage.FORCE_RECOVERY, false);

        private final ProtocolStage stage;
        private final boolean torn;

        InitializationCrash(ProtocolStage stage, boolean torn) {
            this.stage = stage;
            this.torn = torn;
        }

        private void corruptIfTorn(Path path) throws IOException {
            if (torn) {
                Files.write(
                        path,
                        new byte[] {1, 2, 3},
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
    }

    enum PrePublicationTear {
        PAYLOAD(ProtocolStage.WRITE_PAYLOAD_A),
        RECOVERY(ProtocolStage.WRITE_RECOVERY);

        private final ProtocolStage stage;

        PrePublicationTear(ProtocolStage stage) {
            this.stage = stage;
        }

        private void corrupt(Path path) throws IOException {
            Files.write(
                    path,
                    new byte[] {4, 5, 6},
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    enum ReplacementTear {
        PAYLOAD(ProtocolStage.WRITE_PAYLOAD_B),
        RECOVERY(ProtocolStage.WRITE_RECOVERY),
        MAIN(ProtocolStage.WRITE_MAIN);

        private final ProtocolStage stage;

        ReplacementTear(ProtocolStage stage) {
            this.stage = stage;
        }

        private void corrupt(Path path) throws IOException {
            Files.write(
                    path,
                    new byte[] {7, 8, 9},
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    enum PreSideEffectFault {
        PAYLOAD_WRITE(ProtocolStage.WRITE_PAYLOAD_B),
        PAYLOAD_FORCE(ProtocolStage.FORCE_PAYLOAD_B),
        RECOVERY_WRITE(ProtocolStage.WRITE_RECOVERY),
        RECOVERY_FORCE(ProtocolStage.FORCE_RECOVERY),
        MAIN_WRITE(ProtocolStage.WRITE_MAIN),
        MAIN_FORCE(ProtocolStage.FORCE_MAIN);

        private final ProtocolStage stage;

        PreSideEffectFault(ProtocolStage stage) {
            this.stage = stage;
        }
    }

    enum IndexHardLinkReplacement {
        RECOVERY(ProtocolStage.WRITE_RECOVERY),
        MAIN(ProtocolStage.WRITE_MAIN);

        private final ProtocolStage stage;

        IndexHardLinkReplacement(ProtocolStage stage) {
            this.stage = stage;
        }
    }

    enum DirectoryReplacement {
        ROOT,
        WORLD,
        CHUNK_DIRECTORY,
        SHARD;

        private Path target(Path root) {
            return switch (this) {
                case ROOT -> root;
                case WORLD -> world(root);
                case CHUNK_DIRECTORY -> world(root).resolve("streamed-chunks");
                case SHARD -> shard(root);
            };
        }
    }

    enum CrashPoint {
        AFTER_PAYLOAD_WRITE(ProtocolStage.WRITE_PAYLOAD_B, "p00000007.b.glchunk"),
        AFTER_PAYLOAD_FORCE(ProtocolStage.FORCE_PAYLOAD_B, "p00000007.b.glchunk"),
        AFTER_RECOVERY_WRITE(ProtocolStage.WRITE_RECOVERY, "streamed-chunks.prev.idx"),
        AFTER_RECOVERY_FORCE(ProtocolStage.FORCE_RECOVERY, "streamed-chunks.prev.idx"),
        AFTER_MAIN_WRITE(ProtocolStage.WRITE_MAIN, "streamed-chunks.idx"),
        AFTER_MAIN_FORCE(ProtocolStage.FORCE_MAIN, "streamed-chunks.idx");

        final ProtocolStage stage;
        final String expectedFileName;

        CrashPoint(ProtocolStage stage, String expectedFileName) {
            this.stage = stage;
            this.expectedFileName = expectedFileName;
        }
    }

    enum FirstPublicationCrashPoint {
        AFTER_PAYLOAD_WRITE(ProtocolStage.WRITE_PAYLOAD_A, "p00000007.a.glchunk"),
        AFTER_PAYLOAD_FORCE(ProtocolStage.FORCE_PAYLOAD_A, "p00000007.a.glchunk"),
        AFTER_RECOVERY_WRITE(ProtocolStage.WRITE_RECOVERY, "streamed-chunks.prev.idx"),
        AFTER_RECOVERY_FORCE(ProtocolStage.FORCE_RECOVERY, "streamed-chunks.prev.idx"),
        AFTER_MAIN_WRITE(ProtocolStage.WRITE_MAIN, "streamed-chunks.idx"),
        AFTER_MAIN_FORCE(ProtocolStage.FORCE_MAIN, "streamed-chunks.idx");

        final ProtocolStage stage;
        final String expectedFileName;

        FirstPublicationCrashPoint(ProtocolStage stage, String expectedFileName) {
            this.stage = stage;
            this.expectedFileName = expectedFileName;
        }
    }

    enum InitializationSubprocessPoint {
        WORLD_CREATE(ProtocolStage.CREATE_DIRECTORY, "world"),
        WORLD_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "root"),
        CHUNK_CREATE(ProtocolStage.CREATE_DIRECTORY, "chunk"),
        CHUNK_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "world"),
        MAIN_CREATE(ProtocolStage.CREATE_MAIN, "main"),
        MAIN_FILE_FORCE(ProtocolStage.FORCE_MAIN, "main"),
        MAIN_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "world"),
        RECOVERY_CREATE(ProtocolStage.CREATE_RECOVERY, "recovery"),
        RECOVERY_FILE_FORCE(ProtocolStage.FORCE_RECOVERY, "recovery"),
        RECOVERY_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "world"),
        SHARD_CREATE(ProtocolStage.CREATE_DIRECTORY, "shard"),
        SHARD_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "chunk"),
        PAYLOAD_A_CREATE(ProtocolStage.CREATE_PAYLOAD_A, "a"),
        PAYLOAD_A_FILE_FORCE(ProtocolStage.FORCE_PAYLOAD_A, "a"),
        PAYLOAD_B_CREATE(ProtocolStage.CREATE_PAYLOAD_B, "b"),
        PAYLOAD_B_FILE_FORCE(ProtocolStage.FORCE_PAYLOAD_B, "b"),
        PAYLOAD_POOL_PARENT_FORCE(ProtocolStage.FORCE_DIRECTORY, "shard");

        final ProtocolStage stage;
        final String target;

        InitializationSubprocessPoint(ProtocolStage stage, String target) {
            this.stage = stage;
            this.target = target;
        }

        Path target(Path root) {
            return switch (target) {
                case "root" -> root;
                case "world" -> world(root);
                case "chunk" -> world(root).resolve("streamed-chunks");
                case "main" -> mainIndex(root);
                case "recovery" -> recoveryIndex(root);
                case "shard" -> shard(root);
                case "a" -> payloadSlot(root, 'a');
                case "b" -> payloadSlot(root, 'b');
                default -> throw new AssertionError(target);
            };
        }

        String expectedMarker(Path root) {
            return stage.name() + "|" + target(root).getFileName();
        }
    }

}

enum ProtocolStage {
    CREATE_DIRECTORY(true),
    CREATE_MAIN(true),
    CREATE_RECOVERY(true),
    CREATE_PAYLOAD_A(true),
    CREATE_PAYLOAD_B(true),
    WRITE_PAYLOAD_A(false),
    WRITE_PAYLOAD_B(false),
    FORCE_PAYLOAD_A(false),
    FORCE_PAYLOAD_B(false),
    WRITE_RECOVERY(false),
    FORCE_RECOVERY(false),
    WRITE_MAIN(false),
    FORCE_MAIN(false),
    FORCE_DIRECTORY(false),
    MOVE(true),
    COPY(true),
    DELETE(true);

    private final boolean directoryEntryMutation;

    ProtocolStage(boolean directoryEntryMutation) {
        this.directoryEntryMutation = directoryEntryMutation;
    }

    boolean createsOrRemovesDirectoryEntry() {
        return directoryEntryMutation;
    }
}

record ProtocolEvent(ProtocolStage stage, Path path, long byteCount) {
    ProtocolEvent(ProtocolStage stage, Path path) {
        this(stage, path, 0L);
    }
}

@FunctionalInterface
interface ProtocolAction {
    void run(Path path) throws IOException;
}

final class ProtocolFileOperations implements SaveFileOperations {
    private final SaveFileOperations delegate;
    private final List<ProtocolEvent> mutations = new java.util.ArrayList<>();
    private final Map<ProtocolStage, Deque<ProtocolAction>> before =
            new EnumMap<>(ProtocolStage.class);
    private final Map<ProtocolStage, Deque<ProtocolAction>> after =
            new EnumMap<>(ProtocolStage.class);
    private final List<MatchingAction> beforeMatching = new java.util.ArrayList<>();
    private final List<MatchingAction> afterMatching = new java.util.ArrayList<>();
    private boolean nullDirectoryKeys;
    private boolean failUnexpectedCleanup;
    private int managedIdentityReads;
    private int payloadReads;

    ProtocolFileOperations(SaveFileOperations delegate) {
        this.delegate = delegate;
    }

    @Override
    public void createDirectory(Path directory, MutationGuard guard)
            throws IOException {
        mutate(ProtocolStage.CREATE_DIRECTORY, directory, () ->
                delegate.createDirectory(directory, guard));
    }

    @Override
    public void createBounded(
            Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
            throws IOException {
        ProtocolStage stage = createStage(file);
        mutate(stage, file, bytes.length, () ->
                delegate.createBounded(file, bytes, maximumBytes, guard));
    }

    @Override
    public void writeExistingBounded(
            Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
            throws IOException {
        ProtocolStage stage = writeStage(file);
        mutate(stage, file, bytes.length, () ->
                delegate.writeExistingBounded(file, bytes, maximumBytes, guard));
    }

    @Override
    public void forceFile(Path file, MutationGuard guard) throws IOException {
        ProtocolStage stage = forceStage(file);
        mutate(stage, file, () -> delegate.forceFile(file, guard));
    }

    @Override
    public byte[] readBounded(Path file, long maximumBytes, MutationGuard guard)
            throws IOException {
        if (file.getFileName().toString().endsWith(".glchunk")) {
            payloadReads++;
        }
        return delegate.readBounded(file, maximumBytes, guard);
    }

    @Override
    public void forceDirectoryDurably(Path directory, MutationGuard guard)
            throws IOException {
        mutate(ProtocolStage.FORCE_DIRECTORY, directory, () ->
                delegate.forceDirectoryDurably(directory, guard));
    }

    @Override
    public void forceDirectoryBestEffort(Path directory, MutationGuard guard)
            throws IOException {
        if (failUnexpectedCleanup) {
            throw new IOException("optional cleanup must not be called");
        }
        mutate(ProtocolStage.FORCE_DIRECTORY, directory, () ->
                delegate.forceDirectoryBestEffort(directory, guard));
    }

    @Override
    public ManagedFileIdentity readManagedFileIdentity(
            Path path, long maximumBytes, MutationGuard guard) throws IOException {
        managedIdentityReads++;
        return delegate.readManagedFileIdentity(path, maximumBytes, guard);
    }

    @Override
    public Object readFileIdentity(
            Path path, long maximumBytes, MutationGuard guard) throws IOException {
        return delegate.readFileIdentity(path, maximumBytes, guard);
    }

    @Override
    public Object readFileKey(Path path, MutationGuard guard) throws IOException {
        return delegate.readFileKey(path, guard);
    }

    @Override
    public Object readDirectoryKey(Path path, MutationGuard guard)
            throws IOException {
        guard.validate();
        if (nullDirectoryKeys) {
            return null;
        }
        return delegate.readDirectoryKey(path, guard);
    }

    @Override
    public Path createSiblingTemp(
            Path directory, String targetName, MutationGuard guard)
            throws IOException {
        throw new AssertionError("fixed-slot protocol must not create temporaries");
    }

    @Override
    public void moveAtomicReplacing(
            Path source, Path destination, MutationGuard guard) throws IOException {
        mutate(ProtocolStage.MOVE, destination, () ->
                delegate.moveAtomicReplacing(source, destination, guard));
    }

    @Override
    public void moveReplacing(
            Path source, Path destination, MutationGuard guard) throws IOException {
        mutate(ProtocolStage.MOVE, destination, () ->
                delegate.moveReplacing(source, destination, guard));
    }

    @Override
    public void copyReplacing(
            Path source, Path destination, MutationGuard guard) throws IOException {
        mutate(ProtocolStage.COPY, destination, () ->
                delegate.copyReplacing(source, destination, guard));
    }

    @Override
    public boolean deleteIfExists(Path path, MutationGuard guard)
            throws IOException {
        if (failUnexpectedCleanup) {
            throw new IOException("optional cleanup must not be called");
        }
        ProtocolStage stage = ProtocolStage.DELETE;
        run(before, stage, path);
        mutations.add(new ProtocolEvent(stage, path));
        boolean deleted = delegate.deleteIfExists(path, guard);
        run(after, stage, path);
        return deleted;
    }

    void before(ProtocolStage stage, ProtocolAction action) {
        before.computeIfAbsent(stage, ignored -> new ArrayDeque<>()).add(action);
    }

    void after(ProtocolStage stage, ProtocolAction action) {
        after.computeIfAbsent(stage, ignored -> new ArrayDeque<>()).add(action);
    }

    void beforeMatching(
            ProtocolStage stage,
            Predicate<Path> path,
            ProtocolAction action) {
        beforeMatching.add(new MatchingAction(stage, path, action));
    }

    void afterMatching(
            ProtocolStage stage,
            Predicate<Path> path,
            ProtocolAction action) {
        afterMatching.add(new MatchingAction(stage, path, action));
    }

    void returnNullDirectoryKeys() {
        nullDirectoryKeys = true;
    }

    void failIfDeleteOrOptionalDirectoryForceIsCalled() {
        failUnexpectedCleanup = true;
    }

    List<ProtocolEvent> mutations() {
        return List.copyOf(mutations);
    }

    int managedIdentityReads() {
        return managedIdentityReads;
    }

    int payloadReads() {
        return payloadReads;
    }

    void clear() {
        mutations.clear();
        managedIdentityReads = 0;
        payloadReads = 0;
        before.clear();
        after.clear();
        beforeMatching.clear();
        afterMatching.clear();
    }

    private void mutate(ProtocolStage stage, Path path, IoRunnable mutation)
            throws IOException {
        mutate(stage, path, 0L, mutation);
    }

    private void mutate(
            ProtocolStage stage,
            Path path,
            long byteCount,
            IoRunnable mutation) throws IOException {
        runMatching(beforeMatching, stage, path);
        run(before, stage, path);
        mutations.add(new ProtocolEvent(stage, path, byteCount));
        mutation.run();
        run(after, stage, path);
        runMatching(afterMatching, stage, path);
    }

    private static void runMatching(
            List<MatchingAction> actions,
            ProtocolStage stage,
            Path path) throws IOException {
        for (int index = 0; index < actions.size(); index++) {
            MatchingAction candidate = actions.get(index);
            if (candidate.stage == stage && candidate.path.test(path)) {
                actions.remove(index);
                candidate.action.run(path);
                return;
            }
        }
    }

    private static void run(
            Map<ProtocolStage, Deque<ProtocolAction>> actions,
            ProtocolStage stage,
            Path path) throws IOException {
        Deque<ProtocolAction> queued = actions.get(stage);
        ProtocolAction action = queued == null ? null : queued.pollFirst();
        if (queued != null && queued.isEmpty()) {
            actions.remove(stage);
        }
        if (action != null) {
            action.run(path);
        }
    }

    private static ProtocolStage createStage(Path path) {
        String name = path.getFileName().toString();
        if (name.equals("streamed-chunks.idx")) {
            return ProtocolStage.CREATE_MAIN;
        }
        if (name.equals("streamed-chunks.prev.idx")) {
            return ProtocolStage.CREATE_RECOVERY;
        }
        return name.endsWith(".a.glchunk")
                ? ProtocolStage.CREATE_PAYLOAD_A
                : ProtocolStage.CREATE_PAYLOAD_B;
    }

    private static ProtocolStage writeStage(Path path) {
        String name = path.getFileName().toString();
        if (name.equals("streamed-chunks.idx")) {
            return ProtocolStage.WRITE_MAIN;
        }
        if (name.equals("streamed-chunks.prev.idx")) {
            return ProtocolStage.WRITE_RECOVERY;
        }
        return name.endsWith(".a.glchunk")
                ? ProtocolStage.WRITE_PAYLOAD_A
                : ProtocolStage.WRITE_PAYLOAD_B;
    }

    private static ProtocolStage forceStage(Path path) {
        String name = path.getFileName().toString();
        if (name.equals("streamed-chunks.idx")) {
            return ProtocolStage.FORCE_MAIN;
        }
        if (name.equals("streamed-chunks.prev.idx")) {
            return ProtocolStage.FORCE_RECOVERY;
        }
        return name.endsWith(".a.glchunk")
                ? ProtocolStage.FORCE_PAYLOAD_A
                : ProtocolStage.FORCE_PAYLOAD_B;
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    private record MatchingAction(
            ProtocolStage stage,
            Predicate<Path> path,
            ProtocolAction action) {}
}

final class StreamedChunkFixedSlotCrashFixture {
    static final int CRASH_EXIT = 83;

    private StreamedChunkFixedSlotCrashFixture() {}

    public static void main(String[] arguments) {
        try {
            Path root = Path.of(arguments[0]);
            StreamedChunkStoreFaultTest.CrashPoint point =
                    StreamedChunkStoreFaultTest.CrashPoint.valueOf(arguments[1]);
            Path marker = Path.of(arguments[2]);
            ProtocolFileOperations files = new ProtocolFileOperations(
                    new JdkSaveFileOperations());
            StreamedChunkStore store = new StreamedChunkStore(
                    root,
                    StreamedChunkStoreFaultTest.SAVE_ID,
                    new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(),
                    files);
            files.clear();
            files.after(point.stage, path -> {
                Files.writeString(
                        marker,
                        point.stage.name() + "|" + path.getFileName());
                Runtime.getRuntime().halt(CRASH_EXIT);
            });
            StreamedChunkPayload payload = StreamedChunkStoreFaultTest.payload(
                    StreamedChunkStoreFaultTest.GENERATOR_VERSION,
                    StreamedChunkStoreFaultTest.BASE_HASH,
                    8L,
                    7L,
                    (byte) 8);
            StreamedChunkStore.CommitResult result = store.commitModified(
                    new StreamedChunkStore.ExactChunkCapture(payload, () -> true),
                    StreamedChunkStoreFaultTest.hibernation());
            System.err.println("crash stage not reached: " + result.status());
            System.exit(84);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(85);
        }
    }
}

final class StreamedChunkFirstPublicationCrashFixture {
    static final int CRASH_EXIT = 86;

    private StreamedChunkFirstPublicationCrashFixture() {}

    public static void main(String[] arguments) {
        try {
            Path root = Path.of(arguments[0]);
            StreamedChunkStoreFaultTest.FirstPublicationCrashPoint point =
                    StreamedChunkStoreFaultTest.FirstPublicationCrashPoint.valueOf(
                            arguments[1]);
            Path marker = Path.of(arguments[2]);
            ProtocolFileOperations files = new ProtocolFileOperations(
                    new JdkSaveFileOperations());
            StreamedChunkStore store = new StreamedChunkStore(
                    root,
                    StreamedChunkStoreFaultTest.SAVE_ID,
                    new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(),
                    files);
            files.clear();
            if (point.stage == ProtocolStage.FORCE_PAYLOAD_A) {
                // The first force initializes fixed A; the second forces the
                // first authoritative payload publication.
                files.after(point.stage, ignored -> {});
            }
            files.after(point.stage, path -> crash(marker, point.stage, path));
            StreamedChunkPayload payload = StreamedChunkStoreFaultTest.payload(
                    StreamedChunkStoreFaultTest.GENERATOR_VERSION,
                    StreamedChunkStoreFaultTest.BASE_HASH,
                    1L,
                    0L,
                    (byte) 8);
            StreamedChunkStore.CommitResult result = store.commitModified(
                    new StreamedChunkStore.ExactChunkCapture(payload, () -> true),
                    StreamedChunkStoreFaultTest.hibernation());
            System.err.println("first-publication crash stage not reached: " + result.status());
            System.exit(87);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(88);
        }
    }

    private static void crash(Path marker, ProtocolStage stage, Path path)
            throws IOException {
        Files.writeString(marker, stage.name() + "|" + path.getFileName());
        Runtime.getRuntime().halt(CRASH_EXIT);
    }
}

final class StreamedChunkInitializationCrashFixture {
    static final int CRASH_EXIT = 89;

    private StreamedChunkInitializationCrashFixture() {}

    public static void main(String[] arguments) {
        try {
            Path root = Path.of(arguments[0]);
            StreamedChunkStoreFaultTest.InitializationSubprocessPoint point =
                    StreamedChunkStoreFaultTest.InitializationSubprocessPoint.valueOf(
                            arguments[1]);
            Path marker = Path.of(arguments[2]);
            ProtocolFileOperations files = new ProtocolFileOperations(
                    new JdkSaveFileOperations());
            arm(point, root, marker, files);
            StreamedChunkStore store = new StreamedChunkStore(
                    root,
                    StreamedChunkStoreFaultTest.SAVE_ID,
                    new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(),
                    files);
            StreamedChunkPayload payload = StreamedChunkStoreFaultTest.payload(
                    StreamedChunkStoreFaultTest.GENERATOR_VERSION,
                    StreamedChunkStoreFaultTest.BASE_HASH,
                    1L,
                    0L,
                    (byte) 8);
            StreamedChunkStore.CommitResult result = store.commitModified(
                    new StreamedChunkStore.ExactChunkCapture(payload, () -> true),
                    StreamedChunkStoreFaultTest.hibernation());
            System.err.println("initialization crash stage not reached: " + result.status());
            System.exit(90);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(91);
        }
    }

    private static void arm(
            StreamedChunkStoreFaultTest.InitializationSubprocessPoint point,
            Path root,
            Path marker,
            ProtocolFileOperations files) {
        Path target = point.target(root);
        ProtocolAction crash = path -> {
            Files.writeString(marker, point.stage.name() + "|" + path.getFileName());
            Runtime.getRuntime().halt(CRASH_EXIT);
        };
        switch (point) {
            case WORLD_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.CREATE_DIRECTORY,
                    path -> path.equals(StreamedChunkStoreFaultTest.world(root)),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            case CHUNK_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.CREATE_DIRECTORY,
                    path -> path.equals(StreamedChunkStoreFaultTest.world(root)
                            .resolve("streamed-chunks")),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            case MAIN_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.FORCE_MAIN,
                    path -> path.equals(StreamedChunkStoreFaultTest.mainIndex(root)),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            case RECOVERY_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.FORCE_RECOVERY,
                    path -> path.equals(StreamedChunkStoreFaultTest.recoveryIndex(root)),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            case SHARD_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.CREATE_DIRECTORY,
                    path -> path.equals(StreamedChunkStoreFaultTest.shard(root)),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            case PAYLOAD_POOL_PARENT_FORCE -> files.afterMatching(
                    ProtocolStage.FORCE_PAYLOAD_B,
                    path -> path.equals(StreamedChunkStoreFaultTest.payloadSlot(root, 'b')),
                    ignored -> files.afterMatching(
                            ProtocolStage.FORCE_DIRECTORY,
                            path -> path.equals(target),
                            crash));
            default -> files.afterMatching(
                    point.stage, path -> path.equals(target), crash);
        }
    }
}
