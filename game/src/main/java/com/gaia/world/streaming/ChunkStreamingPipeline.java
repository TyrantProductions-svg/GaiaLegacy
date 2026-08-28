package com.gaia.world.streaming;

import com.gaia.save.streaming.StreamedChunkUnloadPlan;
import com.gaia.save.streaming.StreamedChunkUnloadResult;
import com.gaia.save.streaming.StreamedChunkStore;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkStreamingPublication;
import com.overlord.voxel.ChunkStreamingTicket;
import com.overlord.voxel.ChunkState;
import com.overlord.voxel.ChunkUnloadPreparation;
import com.overlord.voxel.ChunkUnloadResult;
import com.overlord.voxel.ChunkUnloadTicket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Owner-thread coordinator for bounded detached Chunk work. Workers never hold
 * repository or unload-ticket authority.
 */
public final class ChunkStreamingPipeline implements AutoCloseable {
    @FunctionalInterface
    public interface DetachedLoadWorker {
        ChunkWorkResult execute(DetachedLoadWork work) throws Exception;
    }

    @FunctionalInterface
    public interface DetachedSaveWorker {
        ChunkWorkResult execute(DetachedSaveWork work) throws Exception;
    }

    public interface UnloadLifecycle {
        PreparedUnload prepare(ChunkUnloadPreparation repositoryPreparation);

        boolean commit(
                PreparedUnload prepared, StreamedChunkUnloadResult durability);

        void cancel(PreparedUnload prepared);
    }

    public record DetachedLoadWork(
            long workId,
            ChunkKey key,
            long desiredEpoch,
            long expectedRevision,
            BooleanSupplier canceled) {
        public DetachedLoadWork {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(canceled, "canceled");
        }
    }

    public record DetachedSaveWork(
            long workId,
            ChunkKey key,
            long desiredEpoch,
            long expectedRevision,
            StreamedChunkUnloadPlan plan,
            BooleanSupplier canceled,
            DurableCompletionFence durableCompletion) {
        public DetachedSaveWork {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(canceled, "canceled");
            Objects.requireNonNull(durableCompletion, "durableCompletion");
        }

        public void markDurablePublication(StreamedChunkUnloadResult result) {
            StreamedChunkUnloadResult checked = Objects.requireNonNull(
                    result, "result");
            if (checked.status() != StreamedChunkUnloadResult.Status.SUCCESS) {
                throw new IllegalArgumentException(
                        "Only successful durability may complete the save fence");
            }
            durableCompletion.publish();
        }
    }

    public record PreparedUnload(
            StreamedChunkUnloadPlan plan, long expectedRevision) {
        public PreparedUnload {
            Objects.requireNonNull(plan, "plan");
            if (expectedRevision <= 0L) {
                throw new IllegalArgumentException(
                        "expectedRevision must be positive");
            }
        }
    }

    public record Metrics(
            int loadAccepted,
            int saveAccepted,
            long published,
            long canceled) {}

    private final ChunkRepository repository;
    private final ChunkStreamingPolicy policy;
    private final MainThreadGuard mainThreadGuard;
    private final DetachedLoadWorker loadWorker;
    private final DetachedSaveWorker saveWorker;
    private final UnloadLifecycle unloadLifecycle;
    private final ChunkWorkScheduler loadScheduler;
    private final ChunkWorkScheduler saveScheduler;
    private final Map<Long, LoadContext> loads = new LinkedHashMap<>();
    private final Map<Long, SaveContext> saves = new LinkedHashMap<>();
    private SaveContext retainedAdmissionCleanup;
    private final LinkedHashMap<ChunkKey, ChunkStreamingDiagnostic>
            diagnostics = new LinkedHashMap<>();
    private long workSequence;
    private long diagnosticSequence;
    private long published;
    private long persistedUnloads;
    private long canceled;
    private long staleResults;
    private volatile long lastLoadLatencyNanos;
    private volatile long lastGenerationLatencyNanos;
    private volatile long lastSaveLatencyNanos;
    private long desiredEpoch = 1L;
    private Set<ChunkKey> desiredPreload = Set.of();
    private boolean admissionsOpen = true;
    private boolean closed;

    public ChunkStreamingPipeline(
            ChunkRepository repository,
            ChunkStreamingPolicy policy,
            MainThreadGuard mainThreadGuard,
            DetachedLoadWorker loadWorker,
            DetachedSaveWorker saveWorker,
            UnloadLifecycle unloadLifecycle) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy.loadGenerationQueueCapacity() > 32
                || policy.loadGenerationActiveLimit() > 4
                || policy.saveQueueCapacity() > 8
                || policy.saveActiveLimit() > 1) {
            throw new IllegalArgumentException(
                    "Task 8 lane policy exceeds its fixed hard bounds");
        }
        this.mainThreadGuard = Objects.requireNonNull(
                mainThreadGuard, "mainThreadGuard");
        this.loadWorker = Objects.requireNonNull(loadWorker, "loadWorker");
        this.saveWorker = Objects.requireNonNull(saveWorker, "saveWorker");
        this.unloadLifecycle = Objects.requireNonNull(
                unloadLifecycle, "unloadLifecycle");
        this.loadScheduler = new ChunkWorkScheduler(
                "chunk-streaming-load",
                policy.loadGenerationQueueCapacity(),
                policy.loadGenerationActiveLimit());
        this.saveScheduler = new ChunkWorkScheduler(
                "chunk-streaming-save",
                policy.saveQueueCapacity(),
                policy.saveActiveLimit());
    }

    public void apply(ChunkStreamingDecision decision) {
        assertOwner("Chunk streaming decision application");
        requireOpen();
        if (!admissionsOpen) {
            throw new IllegalStateException(
                    "Chunk streaming admissions are stopped");
        }
        ChunkStreamingDecision checked = Objects.requireNonNull(decision, "decision");
        desiredEpoch = checked.desiredEpoch();
        desiredPreload = checked.desiredSets().preload();

        cancelSavesThatBecameDesired();
        for (ChunkKey key : checked.cancellations()) {
            cancelLoad(key);
        }
        loadScheduler.reprioritizeQueued(checked.desiredPriorityOrder());
        for (ChunkKey key : checked.admissions()) {
            int priority = checked.desiredPriorityOrder().indexOf(key);
            if (priority < 0) {
                throw new IllegalStateException(
                        "admission is missing from desired priority order");
            }
            admitLoad(key, checked.desiredEpoch(), priority);
        }
        int priority = 0;
        List<ChunkKey> orderedUnloads = new ArrayList<>(
                checked.unloadCandidates().size());
        checked.unloadCandidates().stream()
                .filter(repository::voxelModified)
                .forEachOrdered(orderedUnloads::add);
        checked.unloadCandidates().stream()
                .filter(key -> !repository.voxelModified(key))
                .forEachOrdered(orderedUnloads::add);
        for (ChunkKey key : orderedUnloads) {
            admitSave(key, checked.desiredEpoch(), priority++);
        }
    }

    public void awaitWorkers(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long start = System.nanoTime();
        loadScheduler.awaitQuiescent(timeout);
        long elapsed = System.nanoTime() - start;
        Duration remaining = timeout.minusNanos(Math.min(timeout.toNanos(), elapsed));
        saveScheduler.awaitQuiescent(remaining.isZero()
                ? Duration.ofNanos(1L) : remaining);
    }

    /** Stops admissions and cancels discardable load/generation work only. */
    public void prepareShutdown() {
        assertOwner("Chunk streaming shutdown preparation");
        requireOpen();
        if (!admissionsOpen) {
            return;
        }
        admissionsOpen = false;
        cancelDiscardableLoads();
    }

    /** Cancels detached load work before a bounded owner-thread save capture. */
    public void prepareSaveCapture() {
        assertOwner("Chunk streaming save-capture preparation");
        requireOpen();
        cancelDiscardableLoads();
    }

    private void cancelDiscardableLoads() {
        List<ChunkKey> discardableLoads = loads.values().stream()
                .map(context -> context.key)
                .toList();
        discardableLoads.forEach(this::cancelLoad);
    }

    /** Awaits durability work without waiting for canceled load workers. */
    public void awaitSaveWorkers(Duration timeout) throws InterruptedException {
        saveScheduler.awaitQuiescent(Objects.requireNonNull(timeout, "timeout"));
    }

    public int drainOwnerResults() {
        return drainOwnerResults(policy.publicationBudget());
    }

    public int drainOwnerResults(int maximumPublications) {
        assertOwner("Chunk streaming result publication");
        requireOpen();
        if (maximumPublications < 0) {
            throw new IllegalArgumentException(
                    "maximumPublications must be non-negative");
        }
        long publishedBeforeDrain = published;
        int remaining = Math.min(
                maximumPublications, policy.publicationBudget());
        for (ChunkWorkResult result : loadScheduler.drainCompleted(remaining)) {
            processLoad(result);
            remaining--;
        }
        if (remaining > 0) {
            for (ChunkWorkResult result : saveScheduler.drainCompleted(remaining)) {
                processSave(result);
            }
        }
        return Math.toIntExact(published - publishedBeforeDrain);
    }

    public int publicationBudget() {
        assertOwner("Chunk streaming publication-budget observation");
        return policy.publicationBudget();
    }

    public List<ChunkStreamingDiagnostic> diagnostics() {
        assertOwner("Chunk streaming diagnostic observation");
        return List.copyOf(diagnostics.values());
    }

    public Metrics metrics() {
        assertOwner("Chunk streaming metrics observation");
        return new Metrics(
                loadScheduler.metrics().accepted(),
                saveScheduler.metrics().accepted(),
                published,
                canceled);
    }

    public ChunkWorkScheduler.Metrics loadWorkMetrics() {
        assertOwner("Chunk streaming load work metrics observation");
        return loadScheduler.metrics();
    }

    public ChunkWorkScheduler.Metrics saveWorkMetrics() {
        assertOwner("Chunk streaming save work metrics observation");
        return saveScheduler.metrics();
    }

    public long staleResultCount() {
        assertOwner("Chunk streaming stale result observation");
        return staleResults;
    }

    public long lastLoadLatencyNanos() {
        return lastLoadLatencyNanos;
    }

    public long lastGenerationLatencyNanos() {
        return lastGenerationLatencyNanos;
    }

    public long lastSaveLatencyNanos() {
        return lastSaveLatencyNanos;
    }

    public long persistedUnloadCount() {
        assertOwner("Chunk streaming persisted unload observation");
        return persistedUnloads;
    }

    public int modifiedResidentCount() {
        assertOwner("Chunk streaming resident modification observation");
        return repository.modifiedResidentCount();
    }

    public Set<ChunkKey> requestedKeys() {
        assertOwner("Chunk streaming requested-key observation");
        LinkedHashMap<ChunkKey, Boolean> requested = new LinkedHashMap<>();
        loads.values().forEach(context -> requested.put(context.key, Boolean.TRUE));
        return Set.copyOf(requested.keySet());
    }

    public Map<ChunkKey, RequestedLoadPhase> requestedLoadPhases() {
        assertOwner("Chunk streaming requested-phase observation");
        Map<Long, RequestedLoadPhase> phasesByWork =
                loadScheduler.phasesByWorkId();
        LinkedHashMap<ChunkKey, RequestedLoadPhase> current =
                new LinkedHashMap<>();
        loads.forEach((workId, context) -> {
            RequestedLoadPhase phase = phasesByWork.get(workId);
            if (phase != null) {
                current.put(context.key, phase);
            }
        });
        return Map.copyOf(current);
    }

    public boolean resident(ChunkKey key) {
        assertOwner("Chunk streaming resident observation");
        return repository.contains(Objects.requireNonNull(key, "key"));
    }

    public ChunkAvailability availability(ChunkKey key) {
        assertOwner("Chunk streaming availability observation");
        return repository.availability(Objects.requireNonNull(key, "key"));
    }

    public ChunkState chunkState(ChunkKey key) {
        assertOwner("Chunk streaming state observation");
        return repository.state(Objects.requireNonNull(key, "key"));
    }

    public boolean renderable(ChunkKey key) {
        assertOwner("Chunk streaming renderable observation");
        return repository.isRenderable(Objects.requireNonNull(key, "key"));
    }

    public int retainedWorkCount() {
        assertOwner("Chunk streaming retained work observation");
        return Math.addExact(
                Math.max(loadScheduler.metrics().accepted(), loads.size()),
                Math.max(
                        saveScheduler.metrics().accepted(),
                        Math.addExact(
                                saves.size(),
                                retainedAdmissionCleanup == null ? 0 : 1)));
    }

    public boolean retry(ChunkKey key) {
        assertOwner("Chunk streaming retry");
        return diagnostics.remove(Objects.requireNonNull(key, "key")) != null;
    }

    public boolean isTerminated() {
        return loadScheduler.isTerminated() && saveScheduler.isTerminated();
    }

    /** Exact live worker observation for this pipeline's two owned lanes. */
    public int liveWorkerCount() {
        return Math.addExact(
                loadScheduler.liveWorkerCount(), saveScheduler.liveWorkerCount());
    }

    @Override
    public void close() {
        shutdownOwnerOrdered(() -> {});
    }

    /**
     * Closes the worker lanes around owner-thread GPU cleanup while preserving
     * the load - GPU - save dependency order.
     */
    public void shutdownOwnerOrdered(Runnable gpuCleanup) {
        shutdownOwnerOrdered(() -> {}, gpuCleanup, () -> {});
    }

    /** Closes each actual owned lane and observes termination in dependency order. */
    public void shutdownOwnerOrdered(
            Runnable loadTerminated,
            Runnable gpuCleanup,
            Runnable saveTerminated) {
        assertOwner("Chunk streaming close");
        if (closed) {
            return;
        }
        Runnable checkedLoadTerminated = Objects.requireNonNull(
                loadTerminated, "loadTerminated");
        Runnable checkedGpuCleanup = Objects.requireNonNull(
                gpuCleanup, "gpuCleanup");
        Runnable checkedSaveTerminated = Objects.requireNonNull(
                saveTerminated, "saveTerminated");
        admissionsOpen = false;
        closed = true;
        Throwable failure = null;
        for (Map.Entry<Long, LoadContext> entry : List.copyOf(loads.entrySet())) {
            LoadContext context = entry.getValue();
            context.canceled.set(true);
            Throwable cancellationFailure = cancelLoadCapability(
                    entry.getKey(), context, context.ticket, null);
            if (cancellationFailure != null) {
                failure = appendFailure(failure, cancellationFailure);
            }
        }
        for (Map.Entry<Long, SaveContext> entry : List.copyOf(saves.entrySet())) {
            SaveContext context = entry.getValue();
            context.canceled.set(true);
            failure = cancelSaveParts(context, failure);
            if (context.fullyCanceled()) {
                saves.remove(entry.getKey(), context);
            }
        }
        if (retainedAdmissionCleanup != null) {
            failure = cancelSaveParts(retainedAdmissionCleanup, failure);
            if (retainedAdmissionCleanup.fullyCanceled()) {
                retainedAdmissionCleanup = null;
            }
        }
        try {
            loadScheduler.close();
            checkedLoadTerminated.run();
        } catch (RuntimeException | Error closeFailure) {
            failure = appendFailure(failure, closeFailure);
        }
        try {
            checkedGpuCleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                addSuppressed(failure, cleanupFailure);
            }
        }
        try {
            saveScheduler.close();
            checkedSaveTerminated.run();
        } catch (RuntimeException | Error closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                addSuppressed(failure, closeFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void admitLoad(ChunkKey key, long epoch, int priority) {
        if (diagnostics.containsKey(key)
                || loads.values().stream().anyMatch(context ->
                        context.key.equals(key))) {
            return;
        }
        ChunkStreamingTicket ticket = repository.request(
                key, epoch, ChunkStreamingTicket.SourcePreference.LOAD);
        try {
            long workId = nextWorkId();
            AtomicBoolean cancellation = new AtomicBoolean();
            DetachedLoadWork detached = new DetachedLoadWork(
                    workId, key, epoch, ticket.expectedRevision(), cancellation::get);
            LoadContext context = new LoadContext(key, ticket, cancellation);
            ChunkWorkScheduler.Admission admitted = loadScheduler.submit(
                    new ChunkWorkScheduler.Work(
                            workId,
                            key,
                            epoch,
                            priority,
                            ChunkWorkResult.Kind.LOAD_GENERATE,
                            ticket.expectedRevision(),
                            () -> executeLoadTimed(detached)));
            if (admitted == ChunkWorkScheduler.Admission.ADMITTED) {
                loads.put(workId, context);
            } else {
                repository.cancel(ticket);
            }
        } catch (RuntimeException | Error failure) {
            try {
                repository.cancel(ticket);
            } catch (RuntimeException | Error cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
            throw failure;
        }
    }

    private void admitSave(ChunkKey key, long epoch, int priority) {
        retryRetainedAdmissionCleanup();
        if (saves.values().stream().anyMatch(context -> context.key.equals(key))) {
            return;
        }
        ChunkUnloadPreparation repositoryPreparation =
                repository.prepareStreamingUnload(key);
        if (repositoryPreparation.status()
                != ChunkUnloadPreparation.Status.PREPARED) {
            return;
        }
        ChunkUnloadTicket ticket = repositoryPreparation.ticket().orElseThrow();
        PreparedUnload prepared;
        try {
            prepared = Objects.requireNonNull(
                    unloadLifecycle.prepare(repositoryPreparation),
                    "prepared unload");
        } catch (RuntimeException | Error failure) {
            try {
                repository.cancelStreamingUnload(ticket);
            } catch (RuntimeException | Error cancellationFailure) {
                addSuppressed(failure, cancellationFailure);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            latch(key, ChunkWorkResult.Kind.SAVE,
                    "chunk-streaming.unload-prepare-failed", failure.toString());
            return;
        }
        AtomicBoolean cancellation = new AtomicBoolean();
        DurableCompletionFence durableCompletion = new DurableCompletionFence();
        SaveContext context = new SaveContext(key, ticket, prepared, cancellation);
        long workId;
        DetachedSaveWork detached;
        try {
            workId = nextWorkId();
            StreamedChunkUnloadPlan detachedPlan = detachedPlan(
                    prepared.plan(),
                    repositoryPreparation.stillCurrent(),
                    cancellation);
            detached = new DetachedSaveWork(
                    workId, key, epoch, prepared.expectedRevision(), detachedPlan,
                    cancellation::get, durableCompletion);
        } catch (RuntimeException | Error failure) {
            context.canceled.set(true);
            cancelSaveParts(context, failure);
            if (!context.fullyCanceled()) {
                retainedAdmissionCleanup = context;
            }
            throw failure;
        }
        ChunkWorkScheduler.Admission admitted = saveScheduler.submit(
                new ChunkWorkScheduler.Work(
                        workId,
                         key,
                         epoch,
                         priority,
                          ChunkWorkResult.Kind.SAVE,
                          prepared.expectedRevision(),
                          () -> executeSaveTimed(detached),
                          durableCompletion::published));
        if (admitted == ChunkWorkScheduler.Admission.ADMITTED) {
            saves.put(workId, context);
        } else {
            Throwable cancellationFailure = cancelSaveParts(context, null);
            if (!context.fullyCanceled()) {
                retainedAdmissionCleanup = context;
            }
            if (cancellationFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cancellationFailure instanceof Error error) {
                throw error;
            }
        }
    }

    private void retryRetainedAdmissionCleanup() {
        SaveContext retained = retainedAdmissionCleanup;
        if (retained == null) {
            return;
        }
        Throwable failure = cancelSaveParts(retained, null);
        if (retained.fullyCanceled()) {
            retainedAdmissionCleanup = null;
        }
        rethrowCleanup(failure);
    }

    private void cancelLoad(ChunkKey key) {
        Optional<Map.Entry<Long, LoadContext>> selected = loads.entrySet().stream()
                .filter(entry -> entry.getValue().key.equals(key))
                .findFirst();
        if (selected.isEmpty()) {
            return;
        }
        long workId = selected.orElseThrow().getKey();
        LoadContext context = selected.orElseThrow().getValue();
        context.canceled.set(true);
        ChunkWorkScheduler.Cancellation result = loadScheduler.cancel(workId);
        if (result != ChunkWorkScheduler.Cancellation.NOT_FOUND) {
            rethrowCleanup(cancelLoadCapability(
                    workId, context, context.ticket, null));
        }
        canceled++;
    }

    private Throwable cancelLoadCapability(
            long workId,
            LoadContext context,
            ChunkStreamingTicket ticket,
            Throwable primary) {
        try {
            repository.cancel(ticket);
            loads.remove(workId, context);
            return primary;
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) {
                return cleanupFailure;
            }
            addSuppressed(primary, cleanupFailure);
            return primary;
        }
    }

    private static void rethrowCleanup(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void cancelSavesThatBecameDesired() {
        List<Long> selected = saves.entrySet().stream()
                .filter(entry -> desiredPreload.contains(entry.getValue().key))
                .map(Map.Entry::getKey)
                .toList();
        for (long workId : selected) {
            SaveContext context = saves.get(workId);
            if (context == null) {
                continue;
            }
            ChunkWorkScheduler.Cancellation result = saveScheduler.cancel(workId);
            if (result == ChunkWorkScheduler.Cancellation.REMOVED_QUEUED) {
                context.canceled.set(true);
                cancelSave(workId, context);
            } else if (result == ChunkWorkScheduler.Cancellation.MARKED_RUNNING) {
                context.canceled.set(true);
            } else if (result == ChunkWorkScheduler.Cancellation.ALREADY_COMPLETED) {
                // Durable publication may already have succeeded.  Owner drain
                // must reconcile that proof instead of rewriting it as canceled.
                continue;
            }
            canceled++;
        }
    }

    private void processLoad(ChunkWorkResult result) {
        LoadContext context = loads.get(result.workId());
        if (context == null) {
            return;
        }
        if (result.status() == ChunkWorkResult.Status.CANCELED
                || !desiredPreload.contains(result.key())) {
            rethrowCleanup(cancelLoadCapability(
                    result.workId(), context, context.ticket, null));
            if (result.status() != ChunkWorkResult.Status.CANCELED) {
                staleResults++;
            }
            canceled++;
            return;
        }
        if (result.status() != ChunkWorkResult.Status.SUCCESS) {
            rethrowCleanup(cancelLoadCapability(
                    result.workId(), context, context.ticket, null));
            latch(result, context.key);
            return;
        }
        ChunkStreamingTicket publicationTicket = context.ticket;
        try {
            ChunkGenerationData data = result.chunkData().orElseThrow(
                    () -> new IllegalStateException("load result has no Chunk data"));
            ChunkStreamingTicket.SourcePreference source =
                    result.sourcePreference().orElseThrow();
            if (publicationTicket.sourcePreference() != source) {
                repository.cancel(publicationTicket);
                publicationTicket = repository.request(
                        context.key, result.desiredEpoch(), source);
                context.ticket = publicationTicket;
            }
            if (result.expectedRevision() != publicationTicket.expectedRevision()
                    || !data.key().equals(context.key)) {
                throw new IllegalStateException("detached load identity mismatch");
            }
            ChunkStreamingPublication publication = repository.publish(
                    publicationTicket,
                    data,
                    new ChunkStreamingTicket.BaseIdentity(
                            source,
                            publicationTicket.expectedRevision(),
                            result.persistedRevision()));
            if (publication.status()
                    != ChunkStreamingPublication.Status.PUBLISHED) {
                throw new IllegalStateException("stale Chunk publication");
            }
            loads.remove(result.workId(), context);
            diagnostics.remove(context.key);
            published++;
        } catch (RuntimeException failure) {
            Throwable cleanup = cancelLoadCapability(
                    result.workId(), context, publicationTicket, failure);
            if (cleanup != failure) {
                rethrowCleanup(cleanup);
            }
            if (loads.get(result.workId()) == context) {
                throw failure;
            }
            latch(context.key, ChunkWorkResult.Kind.LOAD_GENERATE,
                    "chunk-streaming.load-publication-failed", failure.toString());
        } catch (Error failure) {
            cancelLoadCapability(
                    result.workId(), context, publicationTicket, failure);
            throw failure;
        }
    }

    private void processSave(ChunkWorkResult result) {
        SaveContext context = saves.get(result.workId());
        if (context == null) {
            return;
        }
        if (result.status() != ChunkWorkResult.Status.SUCCESS) {
            cancelSave(result.workId(), context);
            if (result.status() == ChunkWorkResult.Status.FAILED) {
                latch(result, context.key);
            } else {
                canceled++;
            }
            return;
        }
        try {
            if (result.expectedRevision() != context.prepared.expectedRevision()) {
                throw new IllegalStateException("detached save identity mismatch");
            }
            StreamedChunkUnloadResult durability = result.unloadResult().orElseThrow();
            if (durability.status() == StreamedChunkUnloadResult.Status.STALE) {
                cancelSave(result.workId(), context);
                staleResults++;
                return;
            }
            if (durability.status() != StreamedChunkUnloadResult.Status.SUCCESS) {
                throw new IllegalStateException(
                        "combined persistence did not succeed: " + durability.status());
            }
            if (durability.persistedChunkRevision().isPresent()) {
                ChunkUnloadResult acknowledged =
                        repository.acknowledgeStreamingPersistence(
                                context.ticket,
                                durability.persistedChunkRevision().orElseThrow());
                if (acknowledged.status() != ChunkUnloadResult.Status.VALID) {
                    throw new IllegalStateException(
                            "durable Chunk revision acknowledgement failed: "
                                    + acknowledged.status());
                }
            }
            ChunkUnloadResult validation =
                    repository.validateStreamingUnload(context.ticket);
            if (validation.status() != ChunkUnloadResult.Status.VALID) {
                cancelSave(result.workId(), context);
                return;
            }
            if (!unloadLifecycle.commit(context.prepared, durability)) {
                throw new IllegalStateException("WorldItem hibernation did not commit");
            }
            ChunkUnloadResult committed =
                    repository.commitStreamingUnload(context.ticket);
            if (committed.status() != ChunkUnloadResult.Status.COMMITTED) {
                throw new IllegalStateException(
                        "validated pinned Chunk unload did not commit");
            }
            saves.remove(result.workId(), context);
            persistedUnloads++;
            diagnostics.remove(context.key);
        } catch (RuntimeException failure) {
            cancelSaveAfterFailure(context, failure);
            if (saves.get(result.workId()) == context) {
                throw failure;
            }
            latch(context.key, ChunkWorkResult.Kind.SAVE,
                    "chunk-streaming.unload-commit-failed", failure.toString());
        } catch (Error failure) {
            cancelSaveAfterFailure(context, failure);
            throw failure;
        }
    }

    private void cancelSave(long workId, SaveContext context) {
        context.canceled.set(true);
        Throwable failure = cancelSaveParts(context, null);
        if (context.fullyCanceled()) {
            saves.remove(workId, context);
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void cancelSaveAfterFailure(
            SaveContext context, Throwable primary) {
        context.canceled.set(true);
        cancelSaveParts(context, primary);
        if (context.fullyCanceled()) {
            saves.values().remove(context);
        }
    }

    private Throwable cancelSaveParts(
            SaveContext context, Throwable primary) {
        Throwable failure = primary;
        if (!context.lifecycleCanceled) {
            try {
                unloadLifecycle.cancel(context.prepared);
                context.lifecycleCanceled = true;
            } catch (RuntimeException | Error lifecycleFailure) {
                if (failure == null) {
                    failure = lifecycleFailure;
                } else {
                    addSuppressed(failure, lifecycleFailure);
                }
            }
        }
        if (!context.repositoryCanceled) {
            try {
                ChunkUnloadResult canceledResult =
                        repository.cancelStreamingUnload(context.ticket);
                if (canceledResult.status() != ChunkUnloadResult.Status.CANCELED
                        && canceledResult.status() != ChunkUnloadResult.Status.STALE) {
                    throw new IllegalStateException(
                            "exact Chunk unload cancellation failed: "
                                    + canceledResult.status());
                }
                context.repositoryCanceled = true;
            } catch (RuntimeException | Error chunkFailure) {
                if (failure == null) {
                    failure = chunkFailure;
                } else {
                    addSuppressed(failure, chunkFailure);
                }
            }
        }
        return failure;
    }

    private static void addSuppressed(Throwable primary, Throwable cleanup) {
        if (primary != cleanup) {
            primary.addSuppressed(cleanup);
        }
    }

    private static Throwable appendFailure(
            Throwable primary, Throwable cleanup) {
        if (primary == null) {
            return cleanup;
        }
        addSuppressed(primary, cleanup);
        return primary;
    }

    private ChunkWorkResult executeLoadTimed(DetachedLoadWork work)
            throws Exception {
        long started = System.nanoTime();
        try {
            ChunkWorkResult result = loadWorker.execute(work);
            long elapsed = Math.max(1L, System.nanoTime() - started);
            if (result.sourcePreference().orElse(
                    ChunkStreamingTicket.SourcePreference.LOAD)
                    == ChunkStreamingTicket.SourcePreference.GENERATE) {
                lastGenerationLatencyNanos = elapsed;
            } else {
                lastLoadLatencyNanos = elapsed;
            }
            return result;
        } catch (Exception | Error failure) {
            lastLoadLatencyNanos = Math.max(1L, System.nanoTime() - started);
            throw failure;
        }
    }

    private ChunkWorkResult executeSaveTimed(DetachedSaveWork work)
            throws Exception {
        long started = System.nanoTime();
        try {
            return saveWorker.execute(work);
        } finally {
            lastSaveLatencyNanos = Math.max(1L, System.nanoTime() - started);
        }
    }

    private static StreamedChunkUnloadPlan detachedPlan(
            StreamedChunkUnloadPlan source,
            BooleanSupplier repositoryFreshness,
            AtomicBoolean canceled) {
        StreamedChunkStore.ExactChunkCapture capture = source.chunkCapture();
        StreamedChunkStore.ExactChunkCapture detachedCapture =
                new StreamedChunkStore.ExactChunkCapture(
                        capture.payload(),
                        () -> !canceled.get()
                                && repositoryFreshness.getAsBoolean());
        return new StreamedChunkUnloadPlan(
                detachedCapture,
                source.worldItems(),
                source.requiredGlobals());
    }

    private void latch(ChunkWorkResult result, ChunkKey key) {
        ChunkStreamingDiagnostic source = result.diagnostic().orElseGet(() ->
                new ChunkStreamingDiagnostic(
                        1L, key, result.kind(),
                        "chunk-streaming.worker-failure", "worker failed"));
        latch(key, result.kind(), source.code(), source.message());
    }

    private void latch(
            ChunkKey key,
            ChunkWorkResult.Kind kind,
            String code,
            String message) {
        while (diagnostics.size()
                >= ChunkStreamingDiagnostic.MAX_CURRENT_DIAGNOSTICS) {
            ChunkKey oldest = diagnostics.keySet().iterator().next();
            diagnostics.remove(oldest);
        }
        diagnostics.put(key, new ChunkStreamingDiagnostic(
                nextDiagnosticSequence(), key, kind, code, bounded(message)));
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return "streaming operation failed";
        }
        return message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512
                ? message
                : "streaming operation failed with an oversized message";
    }

    private long nextWorkId() {
        if (workSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Chunk work sequence exhausted");
        }
        return ++workSequence;
    }

    private long nextDiagnosticSequence() {
        if (diagnosticSequence == Long.MAX_VALUE) {
            diagnosticSequence = 0L;
        }
        return ++diagnosticSequence;
    }

    private void assertOwner(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Chunk streaming pipeline is closed");
        }
    }

    public static final class DurableCompletionFence {
        private final AtomicBoolean published = new AtomicBoolean();

        private void publish() {
            published.set(true);
        }

        private boolean published() {
            return published.get();
        }
    }

    private static final class LoadContext {
        private final ChunkKey key;
        private ChunkStreamingTicket ticket;
        private final AtomicBoolean canceled;

        private LoadContext(
                ChunkKey key,
                ChunkStreamingTicket ticket,
                AtomicBoolean canceled) {
            this.key = key;
            this.ticket = ticket;
            this.canceled = canceled;
        }
    }

    private static final class SaveContext {
        private final ChunkKey key;
        private final ChunkUnloadTicket ticket;
        private final PreparedUnload prepared;
        private final AtomicBoolean canceled;
        private boolean lifecycleCanceled;
        private boolean repositoryCanceled;

        private SaveContext(
                ChunkKey key,
                ChunkUnloadTicket ticket,
                PreparedUnload prepared,
                AtomicBoolean canceled) {
            this.key = key;
            this.ticket = ticket;
            this.prepared = prepared;
            this.canceled = canceled;
        }

        private boolean fullyCanceled() {
            return lifecycleCanceled && repositoryCanceled;
        }
    }
}
