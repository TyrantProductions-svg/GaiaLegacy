package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.streaming.StreamedChunkPayload;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedChunkUnloadPlan;
import com.gaia.save.streaming.StreamedChunkUnloadResult;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkStreamingTicket;
import com.overlord.voxel.ChunkUnloadPreparation;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemHibernateTicket;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ChunkStreamingFaultTest {
    private static final int WORLD_HEIGHT = 1;
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174111");

    @Test
    void persistenceFailureCancelsBothPreparationsAndKeepsResidentState()
            throws Exception {
        ChunkRepository repository = repositoryWith(new ChunkKey(0, 0), (byte) 3);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveFailure(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        diagnostic(work.key(), "save-failed")),
                unloads);

        pipeline.apply(unloadDecision(new ChunkKey(0, 0), 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(repository.contains(new ChunkKey(0, 0)));
        assertEquals(1, unloads.canceled.get());
        assertEquals(0, unloads.committed.get());
        assertEquals(1, pipeline.diagnostics().size());
        assertEquals(0, pipeline.metrics().saveAccepted());
        pipeline.close();
    }

    @Test
    void physicalHibernationFailureRollsBackAndCancelsChunkUnload()
            throws Exception {
        ChunkKey key = new ChunkKey(1, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 4);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        unloads.failCommit.set(true);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveSuccess(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        StreamedChunkUnloadResult.success(Optional.empty())),
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(unloads.rollbackObserved.get());
        assertEquals(1, unloads.canceled.get());
        assertTrue(repository.contains(key));
        assertEquals(4, Byte.toUnsignedInt(repository.getBlock(
                key.worldOriginX(), 0, key.worldOriginZ())));
        pipeline.close();
    }

    @Test
    void staleTicketIsRejectedBeforePhysicalHibernation() throws Exception {
        ChunkKey key = new ChunkKey(2, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 5);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    active.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertFalse(work.plan().chunkCapture()
                            .stillCurrent().getAsBoolean(),
                            "repository mutation must stale final publication");
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(
                                    Optional.empty(), work.expectedRevision()));
                },
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        assertTrue(active.await(5, TimeUnit.SECONDS));
        assertTrue(repository.setBlock(
                key.worldOriginX(), 0, key.worldOriginZ(), (byte) 8));
        release.countDown();
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertEquals(0, unloads.committed.get());
        assertEquals(1, unloads.canceled.get());
        assertTrue(repository.contains(key));
        assertEquals(8, Byte.toUnsignedInt(repository.getBlock(
                key.worldOriginX(), 0, key.worldOriginZ())));
        ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
        assertEquals(1L, retry.persistedRevision(),
                "a durable old capture must become the retry persistence floor");
        repository.cancelStreamingUnload(retry.ticket().orElseThrow());
        pipeline.close();
    }

    @Test
    void backendStaleSaveIsSafeCancellationWithoutLatchedWorldFailure()
            throws Exception {
        ChunkKey key = new ChunkKey(21, -3);
        ChunkRepository repository = repositoryWith(key, (byte) 5);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveSuccess(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        new StreamedChunkUnloadResult(
                                StreamedChunkUnloadResult.Status.STALE,
                                Optional.empty())),
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(repository.contains(key));
        assertEquals(0, unloads.committed.get());
        assertEquals(1, unloads.canceled.get());
        assertTrue(pipeline.diagnostics().isEmpty(),
                "a stale detached save is expected churn, not a latched world failure");
        assertTrue(pipeline.staleResultCount() > 0L);
        pipeline.close();
    }

    @Test
    void modifiedUnloadCannotStarveBehindCanonicalCleanCandidatesAtCapacity()
            throws Exception {
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        List<ChunkKey> candidates = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            ChunkKey key = new ChunkKey(x, 0);
            repository.generate(key, chunk -> {});
            candidates.add(key);
        }
        ChunkKey modified = new ChunkKey(100, 0);
        repository.generate(modified, chunk -> {});
        assertTrue(repository.setBlock(
                modified.worldOriginX(), 0, modified.worldOriginZ(), (byte) 7));
        candidates.add(modified);
        Set<ChunkKey> executed = ConcurrentHashMap.newKeySet();
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    executed.add(work.key());
                    active.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(Optional.empty()));
                },
                new FakeUnloadLifecycle());
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(), Set.of(), Set.of()),
                1L, List.of(), List.of(), List.of(), candidates);

        pipeline.apply(decision);
        assertTrue(active.await(5, TimeUnit.SECONDS));
        assertEquals(8, pipeline.metrics().saveAccepted());
        release.countDown();
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(executed.contains(modified),
                "the sole modified resident must be admitted ahead of clean backlog");
        pipeline.close();
    }

    @Test
    void successfulUnloadOrdersDurabilityBeforeHibernationAndRemoval()
            throws Exception {
        ChunkKey key = new ChunkKey(3, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 6);
        List<String> ordering = java.util.Collections.synchronizedList(
                new ArrayList<>());
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        unloads.ordering = ordering;
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    ordering.add("durable");
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(Optional.empty()));
                },
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        assertTrue(repository.contains(key));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        assertTrue(repository.contains(key), "worker durability cannot evict");
        pipeline.drainOwnerResults();

        assertEquals(List.of("durable", "hibernate"), ordering);
        assertEquals(1, unloads.committed.get());
        assertFalse(repository.contains(key));
        pipeline.close();
    }

    @Test
    void completedDurableSaveIsReconciledWhenChunkBecomesDesiredBeforeDrain()
            throws Exception {
        ChunkKey key = new ChunkKey(19, -3);
        ChunkRepository repository = repositoryWith(key, (byte) 7);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveSuccess(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        StreamedChunkUnloadResult.success(Optional.empty())),
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        assertEquals(1, pipeline.saveWorkMetrics().completed());
        pipeline.apply(desiredWithoutAdmission(key, 2L));
        pipeline.drainOwnerResults();

        assertEquals(1, unloads.committed.get(), () ->
                "canceled=" + unloads.canceled.get()
                        + " metrics=" + pipeline.metrics()
                        + " diagnostics=" + pipeline.diagnostics());
        assertEquals(0, unloads.canceled.get());
        assertFalse(repository.contains(key));
        pipeline.close();
    }

    @Test
    void realWorldItemLifecycleOrdersDurableProofThenLinkedCommitThenExactChunkUnload()
            throws Exception {
        ChunkKey key = new ChunkKey(4, -1);
        ChunkRepository repository = repositoryWith(key, (byte) 7);
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        AtomicReference<WorldItemHibernateResult> preparedItems =
                new AtomicReference<>();
        List<String> ordering = java.util.Collections.synchronizedList(
                new ArrayList<>());
        LogicalWorldItemService logical = pagedLogicalService(guard, 2);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 0L)).item().orElseThrow();
        PhysicsWorld physics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical, physics, guard, new WorldItemPhysicsConfig(0.50f, 2));
        physical.reconcileRestoredCanonicalState(1L);
        ChunkStreamingPipeline.UnloadLifecycle lifecycle =
                new ChunkStreamingPipeline.UnloadLifecycle() {
                    @Override
                    public ChunkStreamingPipeline.PreparedUnload prepare(
                            ChunkUnloadPreparation repositoryPreparation) {
                        var capture = repositoryPreparation.capture().orElseThrow();
                        WorldItemHibernateResult prepared = logical.prepareHibernate(
                                key, Map.of(item.id(), item.revision()));
                        assertEquals(WorldItemHibernateResult.Status.PREPARED,
                                prepared.status());
                        preparedItems.set(prepared);
                        StreamedChunkStore.ExactChunkCapture exact =
                                new StreamedChunkStore.ExactChunkCapture(
                                        new StreamedChunkPayload(
                                                SAVE_ID, key, "task8-v1", "51".repeat(32),
                                                capture.revision(), 0L, true, true,
                                                capture.worldHeight(), capture.copyBlocks(),
                                                List.of()),
                                        () -> true);
                        return new ChunkStreamingPipeline.PreparedUnload(
                                new StreamedChunkUnloadPlan(
                                        exact, Optional.empty(), List.of()),
                                capture.revision());
                    }

                    @Override
                    public boolean commit(
                            ChunkStreamingPipeline.PreparedUnload prepared,
                            StreamedChunkUnloadResult durability) {
                        assertEquals(List.of("durable"), ordering);
                        assertTrue(repository.contains(key),
                                "linked commit must precede exact Chunk removal");
                        WorldItemHibernateResult worldItems = preparedItems.get();
                        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                                commitLinked(physical, logical,
                                        worldItems.ticket().orElseThrow(),
                                        worldItems.persistenceTicket().orElseThrow(),
                                        durability.durableProof().orElseThrow()).status());
                        ordering.add("linked-commit");
                        return true;
                    }

                    @Override
                    public void cancel(
                            ChunkStreamingPipeline.PreparedUnload prepared) {
                        throw new AssertionError("successful lifecycle must not cancel");
                    }
                };
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    WorldItemHibernateResult prepared = preparedItems.get();
                    var plan = prepared.persistencePlan().orElseThrow();
                    ordering.add("durable");
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(Optional.of(
                                    new BackendProof(
                                            plan.intendedCheckpoint().checkpointRevision(),
                                            plan.transactionDigest()))));
                },
                lifecycle);

        try {
            pipeline.apply(unloadDecision(key, 1L));
            pipeline.awaitWorkers(Duration.ofSeconds(5));
            assertTrue(repository.contains(key));
            pipeline.drainOwnerResults();

            assertEquals(List.of("durable", "linked-commit"), ordering);
            assertFalse(repository.contains(key));
            assertTrue(logical.snapshot(item.id()).isEmpty());
            assertTrue(physics.bodies().isEmpty());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void canceledLoadCompletingLateCannotPublish() throws Exception {
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        ChunkKey key = new ChunkKey(-4, 0);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> {
                    active.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return ChunkWorkResult.loadSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            ChunkStreamingTicket.SourcePreference.GENERATE,
                            data(key, (byte) 9));
                },
                work -> unexpectedSave(),
                new FakeUnloadLifecycle());

        pipeline.apply(admissionDecision(key, 1L));
        assertTrue(active.await(5, TimeUnit.SECONDS));
        pipeline.apply(cancellationDecision(key, 2L));
        release.countDown();
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertFalse(repository.contains(key));
        assertEquals(0, pipeline.metrics().published());
        assertTrue(pipeline.metrics().canceled() > 0);
        pipeline.close();
    }

    @Test
    void desiredChunkCancelsBlockedSaveBeforeItCanPublishOrEvict()
            throws Exception {
        ChunkKey key = new ChunkKey(-5, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 7);
        FakeUnloadLifecycle unloads = new FakeUnloadLifecycle();
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    active.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertTrue(work.canceled().getAsBoolean());
                    assertFalse(work.plan().chunkCapture()
                            .stillCurrent().getAsBoolean());
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(Optional.empty()));
                },
                unloads);

        pipeline.apply(unloadDecision(key, 1L));
        assertTrue(active.await(5, TimeUnit.SECONDS));
        pipeline.apply(desiredWithoutAdmission(key, 2L));
        release.countDown();
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(repository.contains(key));
        assertEquals(0, unloads.committed.get());
        assertEquals(1, unloads.canceled.get());
        pipeline.close();
    }

    @Test
    void corruptLoadFailsClosedAndGenerationExceptionLatchesBoundedDiagnostic()
            throws Exception {
        ChunkRepository corruptRepository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        ChunkKey corruptKey = new ChunkKey(5, 0);
        ChunkStreamingPipeline corrupt = pipeline(
                corruptRepository,
                work -> ChunkWorkResult.loadFailure(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        diagnostic(work.key(), "corrupt-load")),
                work -> unexpectedSave(),
                new FakeUnloadLifecycle());
        corrupt.apply(admissionDecision(corruptKey, 1L));
        corrupt.awaitWorkers(Duration.ofSeconds(5));
        corrupt.drainOwnerResults();
        assertFalse(corruptRepository.contains(corruptKey));
        assertEquals("corrupt-load", corrupt.diagnostics().get(0).code());
        corrupt.close();

        ChunkRepository generationRepository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        ChunkKey generationKey = new ChunkKey(6, 0);
        AtomicInteger attempts = new AtomicInteger();
        ChunkStreamingPipeline generation = pipeline(
                generationRepository,
                work -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("generation exploded");
                },
                work -> unexpectedSave(),
                new FakeUnloadLifecycle());
        ChunkStreamingDecision decision = admissionDecision(generationKey, 1L);
        generation.apply(decision);
        generation.awaitWorkers(Duration.ofSeconds(5));
        generation.drainOwnerResults();
        generation.apply(decision);
        assertEquals(1, attempts.get(), "latched failure cannot retry every frame");
        assertEquals(1, generation.diagnostics().size());
        assertThrows(UnsupportedOperationException.class,
                () -> generation.diagnostics().clear());
        assertTrue(generation.retry(generationKey));
        generation.apply(decision);
        generation.awaitWorkers(Duration.ofSeconds(5));
        generation.drainOwnerResults();
        assertEquals(2, attempts.get());
        assertTrue(generation.diagnostics().size()
                <= ChunkStreamingDiagnostic.MAX_CURRENT_DIAGNOSTICS);
        generation.close();
    }

    @Test
    void closeCancelsAllPipelineStatesAndLeavesNoWorkerThreads() throws Exception {
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        CountDownLatch active = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> {
                    active.countDown();
                    while (!work.canceled().getAsBoolean()) {
                        Thread.onSpinWait();
                    }
                    return ChunkWorkResult.loadSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            ChunkStreamingTicket.SourcePreference.GENERATE,
                            data(work.key(), (byte) 1));
                },
                work -> unexpectedSave(),
                new FakeUnloadLifecycle());
        pipeline.apply(admissionDecision(new ChunkKey(9, 0), 1L));
        assertTrue(active.await(5, TimeUnit.SECONDS));

        pipeline.close();

        assertEquals(0, pipeline.metrics().loadAccepted());
        assertEquals(0, pipeline.metrics().saveAccepted());
        assertTrue(pipeline.isTerminated());
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith("chunk-streaming-")));
    }

    @Test
    void thrownSaveWorkerFailureRetainsSaveKindInBoundedDiagnostic()
            throws Exception {
        ChunkKey key = new ChunkKey(10, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 2);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> {
                    throw new IllegalStateException("save worker exploded");
                },
                new FakeUnloadLifecycle());

        pipeline.apply(unloadDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        pipeline.drainOwnerResults();

        assertTrue(repository.contains(key));
        assertEquals(1, pipeline.diagnostics().size());
        assertEquals(ChunkWorkResult.Kind.SAVE,
                pipeline.diagnostics().get(0).kind());
        pipeline.close();
    }

    @Test
    void assertionAfterRepositoryPrepareCancelsExactChunkTicketBeforeEscaping() {
        ChunkKey key = new ChunkKey(11, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 3);
        AssertionError primary = new AssertionError("unload prepare fatal");
        ChunkStreamingPipeline.UnloadLifecycle lifecycle =
                new ThrowingUnloadLifecycle(primary, null, null);
        ChunkStreamingPipeline pipeline = pipeline(
                repository, work -> unexpectedLoad(), work -> unexpectedSave(), lifecycle);
        try {
            AssertionError thrown = assertThrows(AssertionError.class,
                    () -> pipeline.apply(unloadDecision(key, 1L)));

            assertEquals(primary, thrown);
            ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
            assertEquals(ChunkUnloadPreparation.Status.PREPARED, retry.status(),
                    "fatal lifecycle prepare must not retain the repository pin");
            repository.cancelStreamingUnload(retry.ticket().orElseThrow());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void assertionDuringLinkedCommitRunsBothCleanupsAndSuppressesCancelFailure()
            throws Exception {
        ChunkKey key = new ChunkKey(12, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 4);
        AssertionError primary = new AssertionError("linked commit fatal");
        RuntimeException cleanup = new RuntimeException("lifecycle cancel failed");
        ThrowingUnloadLifecycle lifecycle =
                new ThrowingUnloadLifecycle(null, primary, cleanup);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveSuccess(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        StreamedChunkUnloadResult.success(Optional.empty())),
                lifecycle);
        try {
            pipeline.apply(unloadDecision(key, 1L));
            pipeline.awaitWorkers(Duration.ofSeconds(5));

            AssertionError thrown = assertThrows(
                    AssertionError.class, pipeline::drainOwnerResults);

            assertEquals(primary, thrown);
            assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
            assertEquals(1, lifecycle.cancels.get());
            ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
            assertEquals(ChunkUnloadPreparation.Status.PREPARED, retry.status(),
                    "fatal linked commit must release the exact Chunk ticket");
            repository.cancelStreamingUnload(retry.ticket().orElseThrow());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void assertionDuringLifecycleCancelStillCancelsExactChunkTicket()
            throws Exception {
        ChunkKey key = new ChunkKey(13, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 5);
        AssertionError primary = new AssertionError("lifecycle cancel fatal");
        ThrowingUnloadLifecycle lifecycle =
                new ThrowingUnloadLifecycle(null, null, primary);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveFailure(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(), diagnostic(key, "save-failed")),
                lifecycle);
        try {
            pipeline.apply(unloadDecision(key, 1L));
            pipeline.awaitWorkers(Duration.ofSeconds(5));

            AssertionError thrown = assertThrows(
                    AssertionError.class, pipeline::drainOwnerResults);

            assertEquals(primary, thrown);
            assertEquals(1, lifecycle.cancels.get());
            ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
            assertEquals(ChunkUnloadPreparation.Status.PREPARED, retry.status(),
                    "Chunk cancellation must run even when lifecycle cancellation fails");
            repository.cancelStreamingUnload(retry.ticket().orElseThrow());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void shutdownPreservesCancellationPrimaryWhenLaterLaneCloseFails()
            throws Exception {
        ChunkKey key = new ChunkKey(14, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 6);
        RuntimeException primary = new RuntimeException("lifecycle cancel failed");
        RuntimeException later = new RuntimeException("load lane close failed");
        ThrowingUnloadLifecycle lifecycle =
                new ThrowingUnloadLifecycle(null, null, primary);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> ChunkWorkResult.saveFailure(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(), diagnostic(key, "save-failed")),
                lifecycle);
        pipeline.apply(unloadDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> pipeline.shutdownOwnerOrdered(
                        () -> { throw later; }, () -> {}, () -> {}));

        assertSame(primary, thrown);
        assertEquals(List.of(later), List.of(thrown.getSuppressed()));
        assertEquals(0, pipeline.liveWorkerCount());
    }

    @Test
    void fatalLoadPublicationErrorCancelsExactRemovedContextCapability()
            throws Exception {
        ChunkKey key = new ChunkKey(15, 0);
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        AssertionError fatal = new AssertionError("publication fatal");
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> ChunkWorkResult.loadSuccess(
                        work.workId(), work.key(), work.desiredEpoch(),
                        work.expectedRevision(),
                        ChunkStreamingTicket.SourcePreference.LOAD,
                        data(work.key(), (byte) 7)),
                work -> unexpectedSave(), new FakeUnloadLifecycle());
        pipeline.apply(admissionDecision(key, 1L));
        pipeline.awaitWorkers(Duration.ofSeconds(5));
        ChunkStreamingTicket original = repository.request(
                key, 1L, ChunkStreamingTicket.SourcePreference.LOAD);
        Field entries = ChunkRepository.class.getDeclaredField("entries");
        entries.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<ChunkKey, Object> prior =
                (ConcurrentHashMap<ChunkKey, Object>) entries.get(repository);
        ConcurrentHashMap<ChunkKey, Object> fatalEntries =
                new ConcurrentHashMap<>(prior) {
                    @Override
                    public Object putIfAbsent(ChunkKey ignored, Object value) {
                        throw fatal;
                    }
                };
        entries.set(repository, fatalEntries);
        try {
            assertSame(fatal, assertThrows(AssertionError.class,
                    pipeline::drainOwnerResults));
        } finally {
            entries.set(repository, prior);
        }
        ChunkStreamingTicket retry = repository.request(
                key, 1L, ChunkStreamingTicket.SourcePreference.LOAD);
        assertNotSame(original, retry,
                "fatal publication must cancel the exact capability removed from context");
        repository.cancel(retry);
        repository.cancel(original);
        pipeline.close();
    }

    @Test
    void postPrepareAdmissionFailureRunsBothCleanupsAndSuppressesCancelFailure()
            throws Exception {
        ChunkKey key = new ChunkKey(16, 0);
        ChunkRepository repository = repositoryWith(key, (byte) 8);
        RuntimeException cleanup = new RuntimeException("linked cancel failed");
        ThrowingUnloadLifecycle lifecycle =
                new ThrowingUnloadLifecycle(null, null, cleanup);
        ChunkStreamingPipeline pipeline = pipeline(
                repository, work -> unexpectedLoad(), work -> unexpectedSave(), lifecycle);
        Field sequence = ChunkStreamingPipeline.class.getDeclaredField("workSequence");
        sequence.setAccessible(true);
        sequence.setLong(pipeline, Long.MAX_VALUE);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pipeline.apply(unloadDecision(key, 1L)));

        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertEquals(1, lifecycle.cancels.get());
        ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
        assertEquals(ChunkUnloadPreparation.Status.PREPARED, retry.status(),
                "post-prepare failure must release the exact Chunk ticket");
        repository.cancelStreamingUnload(retry.ticket().orElseThrow());
        pipeline.close();
    }

    @Test
    void postRequestLoadAdmissionFailureCancelsExactCapability() throws Exception {
        ChunkKey key = new ChunkKey(17, 0);
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        ChunkStreamingTicket original = repository.request(
                key, 1L, ChunkStreamingTicket.SourcePreference.LOAD);
        ChunkStreamingPipeline pipeline = pipeline(
                repository,
                work -> unexpectedLoad(),
                work -> unexpectedSave(),
                new FakeUnloadLifecycle());
        Field sequence = ChunkStreamingPipeline.class.getDeclaredField("workSequence");
        sequence.setAccessible(true);
        sequence.setLong(pipeline, Long.MAX_VALUE);

        assertThrows(IllegalStateException.class,
                () -> pipeline.apply(admissionDecision(key, 1L)));

        ChunkStreamingTicket retry = repository.request(
                key, 1L, ChunkStreamingTicket.SourcePreference.LOAD);
        assertNotSame(original, retry,
                "post-request admission failure must cancel the exact load capability");
        repository.cancel(retry);
        pipeline.close();
    }

    private static ChunkStreamingPipeline pipeline(
            ChunkRepository repository,
            ChunkStreamingPipeline.DetachedLoadWorker load,
            ChunkStreamingPipeline.DetachedSaveWorker save,
            ChunkStreamingPipeline.UnloadLifecycle unloads) {
        return new ChunkStreamingPipeline(
                repository,
                ChunkStreamingPolicy.productionDefaults(),
                MainThreadGuard.captureCurrentThread(),
                load,
                save,
                unloads);
    }

    private static ChunkStreamingDecision admissionDecision(ChunkKey key, long epoch) {
        ChunkDesiredSets desired = new ChunkDesiredSets(Set.of(key), Set.of(key), Set.of(key));
        return new ChunkStreamingDecision(
                desired, epoch, List.of(key), List.of(), List.of(), List.of());
    }

    private static ChunkStreamingDecision cancellationDecision(ChunkKey key, long epoch) {
        return new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(), Set.of(), Set.of()),
                epoch,
                List.of(),
                List.of(key),
                List.of(),
                List.of());
    }

    private static ChunkStreamingDecision desiredWithoutAdmission(
            ChunkKey key, long epoch) {
        ChunkDesiredSets desired =
                new ChunkDesiredSets(Set.of(key), Set.of(key), Set.of(key));
        return new ChunkStreamingDecision(
                desired, epoch, List.of(), List.of(), List.of(), List.of());
    }

    private static ChunkStreamingDecision unloadDecision(ChunkKey key, long epoch) {
        return new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(), Set.of(), Set.of()),
                epoch,
                List.of(),
                List.of(),
                List.of(),
                List.of(key));
    }

    private static ChunkRepository repositoryWith(ChunkKey key, byte value) {
        ChunkRepository repository = new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
        repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, value));
        return repository;
    }

    private static ChunkGenerationData data(ChunkKey key, byte value) {
        byte[] blocks = new byte[16 * 16];
        java.util.Arrays.fill(blocks, value);
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkStreamingDiagnostic diagnostic(ChunkKey key, String code) {
        return new ChunkStreamingDiagnostic(
                1L,
                key,
                ChunkWorkResult.Kind.LOAD_GENERATE,
                code,
                "bounded diagnostic");
    }

    private static ChunkWorkResult unexpectedLoad() {
        throw new AssertionError("load worker must not run");
    }

    private static ChunkWorkResult unexpectedSave() {
        throw new AssertionError("save worker must not run");
    }

    private static LogicalWorldItemService pagedLogicalService(
            MainThreadGuard guard, int capacity) {
        return new LogicalWorldItemService(
                guard,
                capacity,
                0L,
                new SaveIdentity(UUID.fromString(
                        "123e4567-e89b-12d3-a456-426614174111")),
                new WorldItemPageCachePolicy(
                        1_024, 32, 16L * 1_024L * 1_024L,
                        64, 1_024, 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                (ticket, plan, proof) -> {
                    if (!(proof instanceof BackendProof checked)
                            || checked.checkpointRevision()
                                    != plan.intendedCheckpoint().checkpointRevision()
                            || !checked.transactionDigest()
                                    .equals(plan.transactionDigest())) {
                        throw new IllegalArgumentException("proof mismatch");
                    }
                },
                ChunkStreamingFaultTest::descriptor);
    }

    private static WorldItemPageDescriptor descriptor(WorldItemPageSnapshot page) {
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
                page.chunkKey(),
                page.pageRevision(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().id()).toList(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().revision()).toList()));
        return new WorldItemPageDescriptor(
                page.chunkKey(), page.pageRevision(), String.format("%064x", token),
                page.entries().size(), page.entries().size());
    }

    private static WorldItemHibernateResult commitLinked(
            PhysicalWorldItemSystem physical,
            LogicalWorldItemService logical,
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            WorldItemDurableProof proof) {
        try {
            var method = PhysicalWorldItemSystem.class.getMethod(
                    "commitLinkedHibernate",
                    LogicalWorldItemService.class,
                    WorldItemHibernateTicket.class,
                    WorldItemPersistenceTicket.class,
                    WorldItemDurableProof.class);
            return (WorldItemHibernateResult) method.invoke(
                    physical, logical, hibernateTicket, persistenceTicket, proof);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "missing rollback-safe physical linked hibernate API", missing);
        } catch (InvocationTargetException invoked) {
            Throwable cause = invoked.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private record BackendProof(
            long checkpointRevision,
            String transactionDigest) implements WorldItemDurableProof {}

    private static final class FakeUnloadLifecycle
            implements ChunkStreamingPipeline.UnloadLifecycle {
        private final AtomicInteger committed = new AtomicInteger();
        private final AtomicInteger canceled = new AtomicInteger();
        private final AtomicBoolean failCommit = new AtomicBoolean();
        private final AtomicBoolean rollbackObserved = new AtomicBoolean();
        private List<String> ordering = new ArrayList<>();

        @Override
        public ChunkStreamingPipeline.PreparedUnload prepare(
                ChunkUnloadPreparation repositoryPreparation) {
            var capture = repositoryPreparation.capture().orElseThrow();
            byte[] blocks = capture.copyBlocks();
            StreamedChunkStore.ExactChunkCapture exact =
                    new StreamedChunkStore.ExactChunkCapture(
                            new StreamedChunkPayload(
                                    SAVE_ID,
                                    capture.key(),
                                    "task8-v1",
                                    "51".repeat(32),
                                    capture.revision(),
                                    0L,
                                    true,
                                    true,
                                    capture.worldHeight(),
                                    blocks,
                                    List.of()),
                            () -> true);
            return new ChunkStreamingPipeline.PreparedUnload(
                    new StreamedChunkUnloadPlan(
                            exact, Optional.empty(), List.of()),
                    capture.revision());
        }

        @Override
        public boolean commit(
                ChunkStreamingPipeline.PreparedUnload prepared,
                StreamedChunkUnloadResult durability) {
            if (failCommit.get()) {
                rollbackObserved.set(true);
                throw new IllegalStateException("physical hibernation failed");
            }
            ordering.add("hibernate");
            committed.incrementAndGet();
            return true;
        }

        @Override
        public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {
            canceled.incrementAndGet();
        }
    }

    private static final class ThrowingUnloadLifecycle
            implements ChunkStreamingPipeline.UnloadLifecycle {
        private final Throwable prepareFailure;
        private final Throwable commitFailure;
        private final Throwable cancelFailure;
        private final AtomicBoolean cancelFailurePending;
        private final AtomicInteger cancels = new AtomicInteger();

        private ThrowingUnloadLifecycle(
                Throwable prepareFailure,
                Throwable commitFailure,
                Throwable cancelFailure) {
            this.prepareFailure = prepareFailure;
            this.commitFailure = commitFailure;
            this.cancelFailure = cancelFailure;
            cancelFailurePending = new AtomicBoolean(cancelFailure != null);
        }

        @Override
        public ChunkStreamingPipeline.PreparedUnload prepare(
                ChunkUnloadPreparation repositoryPreparation) {
            rethrow(prepareFailure);
            var capture = repositoryPreparation.capture().orElseThrow();
            return new ChunkStreamingPipeline.PreparedUnload(
                    new StreamedChunkUnloadPlan(
                            new StreamedChunkStore.ExactChunkCapture(
                                    new StreamedChunkPayload(
                                            SAVE_ID, capture.key(), "task11-fatal",
                                            "66".repeat(32), capture.revision(), 0L,
                                            true, true, capture.worldHeight(),
                                            capture.copyBlocks(), List.of()),
                                    repositoryPreparation.stillCurrent()),
                            Optional.empty(), List.of()),
                    capture.revision());
        }

        @Override
        public boolean commit(
                ChunkStreamingPipeline.PreparedUnload prepared,
                StreamedChunkUnloadResult durability) {
            rethrow(commitFailure);
            return true;
        }

        @Override
        public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {
            cancels.incrementAndGet();
            if (cancelFailurePending.compareAndSet(true, false)) {
                rethrow(cancelFailure);
            }
        }

        private static void rethrow(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) failure;
        }
    }
}
