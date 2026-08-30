package com.overlord.voxel;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.RenderOrigin;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

public final class ChunkMeshManager implements AutoCloseable {
    public static final long MAX_CPU_MESH_MEMORY_BYTES =
            128L * 1024L * 1024L;
    private static final ChunkKey ZERO_CHUNK_KEY = new ChunkKey(0, 0);
    private final ChunkRepository repository;
    private final ChunkMesher mesher;
    private final Executor meshExecutor;
    private final ChunkRenderBackend renderBackend;
    private final MainThreadGuard mainThreadGuard;
    private final ChunkMeshBudget budget;
    private final long maxCpuMeshMemoryBytes;
    private final Deque<QueuedMeshingWork> queuedMeshing = new ArrayDeque<>();
    private final Queue<MeshingCompletion> completed =
            new ConcurrentLinkedQueue<>();
    private final Queue<MeshingFailure> failed =
            new ConcurrentLinkedQueue<>();
    private final Queue<ChunkMeshData> awaitingUpload =
            new ConcurrentLinkedQueue<>();
    private final Queue<Throwable> reportedFailures =
            new ConcurrentLinkedQueue<>();
    private final Queue<PendingDestruction> pendingDestructions =
            new ArrayDeque<>();
    private final Map<ChunkKey, ChunkMeshData> failedUploads =
            new HashMap<>();
    private final Map<ChunkKey, ChunkRenderObject> installedRenderObjects =
            new HashMap<>();
    private final Object lifecycleLock = new Object();
    private int acceptedMeshing;
    private int activeMeshing;
    private int pumpDepth;
    private int remainingUploadsInPump;
    private int remainingDestructionsInPump;
    private int remainingCompletionDrainsInPump;
    private long activeReservedBytes;
    private long completedRetainedBytes;
    private long uploadScratchBytes;
    private long peakUsedBytes;
    private long directUploadBytes;
    private long peakDirectUploadBytes;
    private long uploadedTotal;
    private long bytesUploadedTotal;
    private long destroyedTotal;
    private volatile long lastMeshLatencyNanos;
    private volatile boolean closed;
    private Throwable closeFailure;
    private RenderOrigin renderOrigin = new RenderOrigin(ZERO_CHUNK_KEY);

    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            int maxUploadsPerFrame) {
        this(repository, mesher, meshExecutor, renderBackend, mainThreadGuard,
                new ChunkMeshBudget(32, 2, maxUploadsPerFrame, 4),
                MAX_CPU_MESH_MEMORY_BYTES);
    }

    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            ChunkMeshBudget budget) {
        this(repository, mesher, meshExecutor, renderBackend,
                mainThreadGuard, budget, MAX_CPU_MESH_MEMORY_BYTES);
    }

    ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            ChunkMeshBudget budget,
            long maxCpuMeshMemoryBytes) {
        this.repository =
                Objects.requireNonNull(repository, "repository");
        this.mesher = Objects.requireNonNull(mesher, "mesher");
        this.meshExecutor =
                Objects.requireNonNull(meshExecutor, "meshExecutor");
        this.renderBackend =
                Objects.requireNonNull(renderBackend, "renderBackend");
        this.mainThreadGuard =
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (maxCpuMeshMemoryBytes <= 0L) {
            throw new IllegalArgumentException(
                    "CPU mesh memory budget must be positive");
        }
        this.maxCpuMeshMemoryBytes = maxCpuMeshMemoryBytes;
    }

    public int scheduleEligible() {
        return scheduleEligible(repository.meshingCandidates().stream()
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList(), false);
    }

    /**
     * Claims only the supplied visible candidates and keeps queued CPU work in
     * the caller-authored order. Already active work is never disturbed.
     */
    public int scheduleEligible(List<ChunkKey> orderedEligibleKeys) {
        return scheduleEligible(orderedEligibleKeys, true);
    }

    private int scheduleEligible(
            List<ChunkKey> orderedEligibleKeys,
            boolean releaseLowerPriorityQueuedClaim) {
        mainThreadGuard.assertMainThread("schedule chunk meshing");
        if (closed) {
            return 0;
        }
        List<ChunkKey> ordered = List.copyOf(Objects.requireNonNull(
                orderedEligibleKeys, "orderedEligibleKeys"));
        if (ordered.size() > 121) {
            throw new IllegalArgumentException(
                    "ordered mesh candidates exceed the desired-set bound");
        }
        Set<ChunkKey> unique = new HashSet<>();
        for (ChunkKey key : ordered) {
            if (!unique.add(ChunkCoordinatePolicy.requireSafe(key))) {
                throw new IllegalArgumentException(
                        "ordered mesh candidates repeat a Chunk key");
            }
        }
        reorderQueuedMeshing(ordered);
        int scheduled = 0;
        for (ChunkKey key : ordered) {
            boolean candidate = repository.meshingCandidates().contains(key);
            synchronized (lifecycleLock) {
                if (closed) {
                    break;
                }
            }
            if (releaseLowerPriorityQueuedClaim
                    && acceptedAtCapacity()
                    && candidate
                    && !releaseWorstQueuedClaim(key, ordered)) {
                continue;
            }
            if (acceptedAtCapacity()) {
                continue;
            }
            Optional<ChunkMeshingClaim> claimed =
                    repository.claimMeshingCapability(key);
            if (claimed.isEmpty()) {
                continue;
            }
            ChunkMeshingClaim claim = claimed.orElseThrow();
            ChunkMeshMemoryPlan memoryPlan;
            try {
                memoryPlan = Objects.requireNonNull(
                        mesher.preflight(claim.input()),
                        "mesher preflight result");
                if (memoryPlan.activeReservationBytes()
                        > maxCpuMeshMemoryBytes) {
                    throw new ChunkMeshMemoryBudgetExceededException(
                            claim.key(),
                            claim.revision(),
                            maxCpuMeshMemoryBytes,
                            memoryPlan.activeReservationBytes(),
                            memoryPlan.outputBytes());
                }
            } catch (RuntimeException | Error failure) {
                synchronized (lifecycleLock) {
                    if (!closed) {
                        acceptedMeshing++;
                        failed.add(new MeshingFailure(
                                claim.key(), claim.revision(), failure));
                    }
                }
                scheduled++;
                continue;
            }
            synchronized (lifecycleLock) {
                if (closed) {
                    break;
                }
                queuedMeshing.add(new QueuedMeshingWork(claim, memoryPlan));
                acceptedMeshing++;
            }
            if (releaseLowerPriorityQueuedClaim) {
                reorderQueuedMeshing(ordered);
            }
            DispatchOutcome outcome = dispatchOne(true);
            if (outcome != DispatchOutcome.REJECTED) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private boolean acceptedAtCapacity() {
        synchronized (lifecycleLock) {
            return acceptedMeshing >= budget.maxAccepted();
        }
    }

    private boolean releaseWorstQueuedClaim(
            ChunkKey candidate, List<ChunkKey> orderedKeys) {
        Map<ChunkKey, Integer> ranks = new HashMap<>();
        for (int index = 0; index < orderedKeys.size(); index++) {
            ranks.put(orderedKeys.get(index), index);
        }
        int candidateRank = ranks.get(candidate);
        QueuedMeshingWork selected = null;
        synchronized (lifecycleLock) {
            var descending = queuedMeshing.descendingIterator();
            while (descending.hasNext()) {
                QueuedMeshingWork work = descending.next();
                int queuedRank = ranks.getOrDefault(
                        work.claim().key(), Integer.MAX_VALUE);
                if (queuedRank > candidateRank) {
                    selected = work;
                    descending.remove();
                    break;
                }
            }
        }
        if (selected == null) {
            return false;
        }
        boolean released = repository.releaseQueuedMeshingClaim(
                selected.key(),
                selected.revision(),
                selected.claimId());
        synchronized (lifecycleLock) {
            if (released) {
                if (acceptedMeshing <= 0) {
                    throw new IllegalStateException(
                            "chunk mesh capacity token underflow");
                }
                acceptedMeshing--;
            } else if (!closed) {
                queuedMeshing.addLast(selected);
            }
        }
        if (!released) {
            reorderQueuedMeshing(orderedKeys);
        }
        return released;
    }

    private void reorderQueuedMeshing(List<ChunkKey> orderedKeys) {
        Map<ChunkKey, Integer> ranks = new HashMap<>();
        for (int index = 0; index < orderedKeys.size(); index++) {
            ranks.put(orderedKeys.get(index), index);
        }
        synchronized (lifecycleLock) {
            List<QueuedMeshingWork> reordered =
                    new java.util.ArrayList<>(queuedMeshing);
            reordered.sort(java.util.Comparator.comparingInt(work ->
                    ranks.getOrDefault(
                            work.claim().key(), Integer.MAX_VALUE)));
            queuedMeshing.clear();
            queuedMeshing.addAll(reordered);
        }
    }

    public int drainCompletedCpuWork() {
        return drainCompletedCpuWork(Integer.MAX_VALUE);
    }

    private int drainCompletedCpuWork(int maximum) {
        mainThreadGuard.assertMainThread("drain completed chunk meshes");
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must be non-negative");
        }
        if (closed) {
            return 0;
        }
        int drained = 0;
        MeshingCompletion completion;
        while (drained < maximum && (completion = completed.poll()) != null) {
            drained++;
            boolean ready =
                    repository.markReadyForUpload(
                            completion.key(), completion.revision());
            discardFailedUploadAtOrBefore(
                    completion.key(), completion.revision());
            if (ready) {
                awaitingUpload.add(completion.data());
            } else {
                releaseCompletedOutputAndAccepted(completion.data());
            }
        }

        MeshingFailure failure;
        while (drained < maximum && (failure = failed.poll()) != null) {
            drained++;
            if (repository.markMeshingFailureIfCurrent(
                    failure.key(),
                    failure.revision(),
                    failure.cause())) {
                discardFailedUploadAtOrBefore(
                        failure.key(), failure.revision());
                if (!isBoundedResourceDiagnostic(failure.cause())) {
                    reportFailure(failure.cause());
                }
            }
            releaseAccepted();
        }
        return drained;
    }

    private static boolean isBoundedResourceDiagnostic(Throwable failure) {
        return failure instanceof ChunkMeshOutputLimitExceededException
                || failure instanceof ChunkMeshMemoryBudgetExceededException;
    }

    public Optional<Throwable> pollFailure() {
        mainThreadGuard.assertMainThread("poll chunk meshing failure");
        return Optional.ofNullable(reportedFailures.poll());
    }

    public int processMainThreadWork() {
        return processMainThreadWork(budget.maxUploadsPerFrame());
    }

    /**
     * Processes owner-thread GPU work within both the fixed mesh budget and a
     * caller-supplied share of a wider frame publication budget.
     */
    public int processMainThreadWork(int maximumUploads) {
        mainThreadGuard.assertMainThread("chunk mesh upload");
        if (maximumUploads < 0) {
            throw new IllegalArgumentException(
                    "maximumUploads must be non-negative");
        }
        if (closed) {
            return 0;
        }
        boolean outermostPump = pumpDepth == 0;
        if (outermostPump) {
            remainingUploadsInPump = Math.min(
                    maximumUploads, budget.maxUploadsPerFrame());
            remainingDestructionsInPump = budget.maxDestructionsPerFrame();
            remainingCompletionDrainsInPump = remainingUploadsInPump;
        }
        pumpDepth++;
        try {
            drainDestructions();
            int completionsDrained = drainCompletedCpuWork(
                    remainingCompletionDrainsInPump);
            remainingCompletionDrainsInPump -= completionsDrained;

            int processed = 0;
            ChunkMeshData data;
            while (!closed
                    && remainingUploadsInPump > 0
                    && (data = awaitingUpload.peek()) != null) {
                if (!repository.isReadyForUpload(
                        data.key(), data.revision())) {
                    awaitingUpload.poll();
                    discardFailedUploadAtOrBefore(
                            data.key(), data.revision());
                    releaseCompletedOutputAndAccepted(data);
                    continue;
                }
                ChunkRenderBackend.UploadMemoryRequirement requirement =
                        Objects.requireNonNull(
                                renderBackend.uploadMemoryRequirement(data),
                                "upload memory requirement");
                if (!tryAcquireUploadScratch(requirement)) {
                    break;
                }
                awaitingUpload.poll();
                remainingUploadsInPump--;
                processed++;
                try {
                    if (data.isEmpty()) {
                        installEmptyMesh(data);
                    } else {
                        uploadReplacement(data);
                    }
                } finally {
                    releaseUploadScratch(requirement);
                }
            }
            drainDestructions();
            return processed;
        } finally {
            pumpDepth--;
            if (outermostPump) {
                remainingUploadsInPump = 0;
                remainingDestructionsInPump = 0;
                remainingCompletionDrainsInPump = 0;
            }
        }
    }

    public Collection<ChunkRenderObject> renderObjects() {
        mainThreadGuard.assertMainThread("read chunk render objects");
        return List.copyOf(installedRenderObjects.values());
    }

    /** Prebuilds immutable render replacements and publishes map references only at commit. */
    public PreparedOriginRebase prepareOriginRebase(
            RenderOrigin oldOrigin, RenderOrigin nextOrigin) {
        mainThreadGuard.assertMainThread("prepare chunk render origin rebase");
        Objects.requireNonNull(oldOrigin, "oldOrigin");
        Objects.requireNonNull(nextOrigin, "nextOrigin");
        if (closed) {
            throw new IllegalStateException("chunk mesh manager is closed");
        }
        if (!renderOrigin.equals(oldOrigin)) {
            throw new IllegalStateException("old render origin does not match installed renders");
        }
        ChunkKey[] keys = new ChunkKey[installedRenderObjects.size()];
        ChunkRenderObject[] replacements = new ChunkRenderObject[keys.length];
        int index = 0;
        for (Map.Entry<ChunkKey, ChunkRenderObject> entry
                : installedRenderObjects.entrySet()) {
            keys[index] = entry.getKey();
            replacements[index] = entry.getValue().forOrigin(nextOrigin);
            index++;
        }
        return () -> {
            for (int replacementIndex = 0;
                    replacementIndex < keys.length;
                    replacementIndex++) {
                installedRenderObjects.replace(
                        keys[replacementIndex], replacements[replacementIndex]);
            }
            renderOrigin = nextOrigin;
        };
    }

    public int meshQueueDepth() {
        mainThreadGuard.assertMainThread("read chunk mesh queue depth");
        synchronized (lifecycleLock) {
            return acceptedMeshing;
        }
    }

    public Metrics metrics() {
        mainThreadGuard.assertMainThread("read chunk mesh metrics");
        synchronized (lifecycleLock) {
            return new Metrics(
                    acceptedMeshing,
                    queuedMeshing.size(),
                    activeMeshing,
                    completed.size() + failed.size(),
                    awaitingUpload.size(),
                    failedUploads.size(),
                    pendingDestructions.size());
        }
    }

    /** Bounded current-state CPU-mesh memory observations. */
    public MemoryMetrics memoryMetrics() {
        mainThreadGuard.assertMainThread("read chunk mesh memory metrics");
        synchronized (lifecycleLock) {
            return new MemoryMetrics(
                    maxCpuMeshMemoryBytes,
                    activeReservedBytes,
                    completedRetainedBytes,
                    uploadScratchBytes,
                    usedBytes(),
                    peakUsedBytes,
                    memoryBlockedQueuedCount(),
                    directUploadBytes,
                    peakDirectUploadBytes);
        }
    }

    /** Current read-only phase for bounded owner-thread diagnostics. */
    public MeshPhase meshPhase(ChunkKey key) {
        mainThreadGuard.assertMainThread("read chunk mesh phase");
        ChunkKey checked = ChunkCoordinatePolicy.requireSafe(key);
        if (repository.meshOutputLimitFailure(checked).isPresent()
                || repository.meshMemoryBudgetFailure(checked).isPresent()) {
            return MeshPhase.FAILED;
        }
        synchronized (lifecycleLock) {
            if (queuedMeshing.stream().anyMatch(
                    work -> work.claim().key().equals(checked))) {
                return MeshPhase.QUEUED;
            }
            if (completed.stream().anyMatch(item -> item.key().equals(checked))) {
                return MeshPhase.COMPLETED;
            }
            if (failed.stream().anyMatch(item -> item.key().equals(checked))) {
                return MeshPhase.FAILED;
            }
            if (awaitingUpload.stream().anyMatch(data -> data.key().equals(checked))) {
                return MeshPhase.AWAITING_UPLOAD;
            }
            if (failedUploads.containsKey(checked)) {
                return MeshPhase.FAILED;
            }
            if (installedRenderObjects.containsKey(checked)) {
                return MeshPhase.INSTALLED;
            }
        }
        return repository.state(checked) == ChunkState.MESHING
                ? MeshPhase.ACTIVE
                : MeshPhase.NONE;
    }

    public boolean hasInstalledRenderObject(ChunkKey key) {
        mainThreadGuard.assertMainThread("read installed chunk render object");
        synchronized (lifecycleLock) {
            return installedRenderObjects.containsKey(
                    ChunkCoordinatePolicy.requireSafe(key));
        }
    }

    /** Current repository-owned hybrid-output diagnostic, if this revision is latched. */
    public Optional<ChunkMeshOutputLimitExceededException>
            outputLimitDiagnostic(ChunkKey key) {
        mainThreadGuard.assertMainThread(
                "read chunk mesh output-limit diagnostic");
        return repository.meshOutputLimitFailure(key);
    }

    /** Current repository-owned single-job memory diagnostic, if latched. */
    public Optional<ChunkMeshMemoryBudgetExceededException>
            memoryBudgetDiagnostic(ChunkKey key) {
        mainThreadGuard.assertMainThread(
                "read chunk mesh memory-budget diagnostic");
        return repository.meshMemoryBudgetFailure(key);
    }

    /** Monotonic owner observations used to derive immutable per-frame deltas. */
    public LifecycleMetrics lifecycleMetrics() {
        mainThreadGuard.assertMainThread("read chunk mesh lifecycle metrics");
        return new LifecycleMetrics(
                uploadedTotal,
                bytesUploadedTotal,
                destroyedTotal,
                lastMeshLatencyNanos);
    }

    public boolean retry(ChunkKey key) {
        mainThreadGuard.assertMainThread("retry chunk mesh");
        Objects.requireNonNull(key, "key");
        if (closed) {
            return false;
        }

        ChunkMeshData failedUpload = failedUploads.get(key);
        if (failedUpload != null
                && repository.isReadyForUpload(
                        key, failedUpload.revision())
                && failedUploads.remove(key, failedUpload)) {
            awaitingUpload.add(failedUpload);
            return true;
        }
        if (failedUpload != null && failedUploads.remove(key, failedUpload)) {
            releaseCompletedOutputAndAccepted(failedUpload);
            repository.retry(key);
            return true;
        }
        return repository.retry(key);
    }

    public void unload(ChunkKey key) {
        mainThreadGuard.assertMainThread("unload chunk mesh");
        Objects.requireNonNull(key, "key");
        if (!closed && repository.beginUnload(key)) {
            removeQueuedMeshing(key);
            removeCompletedWork(key);
            removeAwaitingUploads(key);
            ChunkMeshData failedUpload = failedUploads.remove(key);
            if (failedUpload != null) {
                releaseCompletedOutputAndAccepted(failedUpload);
            }
            ChunkRenderObject object = installedRenderObjects.remove(key);
            pendingDestructions.add(new PendingDestruction(key, object, true));
        }
    }

    public boolean allRenderable(Set<ChunkKey> keys) {
        mainThreadGuard.assertMainThread("check renderable chunks");
        Objects.requireNonNull(keys, "keys");
        return keys.stream().allMatch(repository::isRenderable);
    }

    @Override
    public void close() {
        mainThreadGuard.assertMainThread("close chunk mesh manager");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            acceptedMeshing = 0;
            activeMeshing = 0;
            queuedMeshing.clear();
            completed.clear();
            failed.clear();
            activeReservedBytes = 0L;
            completedRetainedBytes = 0L;
            uploadScratchBytes = 0L;
            directUploadBytes = 0L;
        }

        awaitingUpload.clear();
        failedUploads.clear();
        reportedFailures.clear();

        Throwable firstFailure = null;
        PendingDestruction pending;
        while ((pending = pendingDestructions.poll()) != null) {
            try {
                if (pending.object() != null) {
                    firstFailure = releaseForClose(pending.object(), firstFailure);
                }
            } finally {
                if (pending.completeUnload()) {
                    repository.completeUnload(pending.key());
                }
            }
        }
        for (ChunkRenderObject object :
                installedRenderObjects.values()) {
            firstFailure = releaseForClose(object, firstFailure);
        }
        installedRenderObjects.clear();

        if (firstFailure != null) {
            closeFailure = firstFailure;
            rethrow(firstFailure);
        }
    }

    private void buildMesh(QueuedMeshingWork work) {
        ChunkMeshingClaim claim = work.claim();
        ChunkMeshInput input = claim.input();
        long started = System.nanoTime();
        MeshingCompletion completion = null;
        MeshingFailure failureResult = null;
        try {
            ChunkMeshData data =
                    Objects.requireNonNull(
                            mesher.build(input, work.memoryPlan()),
                            "mesher result");
            ChunkKey claimedKey = input.center().key();
            long claimedRevision = input.center().revision();
            if (!data.key().equals(claimedKey)
                    || data.revision() != claimedRevision) {
                throw new IllegalStateException(
                        "Mesher result must match claimed chunk "
                                + claimedKey
                                + " revision "
                                + claimedRevision);
            }
            if (data.outputByteSize() > work.memoryPlan().outputBytes()) {
                throw new IllegalStateException(
                        "Mesher output exceeded its admitted preflight");
            }
            completion = new MeshingCompletion(claimedKey, claimedRevision, data);
        } catch (RuntimeException | Error failure) {
            failureResult = new MeshingFailure(
                    input.center().key(), input.center().revision(), failure);
        }
        lastMeshLatencyNanos = Math.max(1L, System.nanoTime() - started);
        synchronized (lifecycleLock) {
            if (!closed) {
                activeMeshing--;
                releaseActiveReservation(work.memoryPlan());
                if (completion != null) {
                    acquireCompletedOutput(completion.data());
                    completed.add(completion);
                } else {
                    failed.add(failureResult);
                }
            }
        }
        dispatchAvailable();
    }

    private DispatchOutcome dispatchOne(boolean ownerPublicationAllowed) {
        QueuedMeshingWork work;
        synchronized (lifecycleLock) {
            if (closed
                    || activeMeshing >= budget.maxActive()
                    || queuedMeshing.isEmpty()) {
                return DispatchOutcome.NONE;
            }
            work = queuedMeshing.peek();
            if (work.memoryPlan().activeReservationBytes()
                    > availableBytes()) {
                return DispatchOutcome.MEMORY_BLOCKED;
            }
            queuedMeshing.remove();
            activeMeshing++;
            acquireActiveReservation(work.memoryPlan());
        }
        ChunkMeshingClaim claim = work.claim();
        try {
            if (!repository.markMeshingClaimActive(
                    claim.key(),
                    claim.revision(),
                    claim.claimId())) {
                throw new IllegalStateException(
                        "queued meshing claim is no longer current");
            }
            meshExecutor.execute(() -> buildMesh(work));
            return DispatchOutcome.SUBMITTED;
        } catch (RuntimeException | Error failure) {
            boolean report;
            synchronized (lifecycleLock) {
                report = !closed;
                if (report) {
                    activeMeshing--;
                    releaseActiveReservation(work.memoryPlan());
                    if (ownerPublicationAllowed) {
                        acceptedMeshing--;
                    } else {
                        failed.add(new MeshingFailure(
                                claim.key(),
                                claim.revision(),
                                failure));
                    }
                }
            }
            if (report && ownerPublicationAllowed
                    && repository.markMeshingFailureIfCurrent(
                            claim.key(),
                            claim.revision(),
                            failure)) {
                reportFailure(failure);
            }
            return DispatchOutcome.REJECTED;
        }
    }

    private void dispatchAvailable() {
        DispatchOutcome outcome;
        do {
            outcome = dispatchOne(false);
        } while (outcome == DispatchOutcome.SUBMITTED
                || outcome == DispatchOutcome.REJECTED);
    }

    private void drainDestructions() {
        while (!closed
                && remainingDestructionsInPump > 0
                && !pendingDestructions.isEmpty()) {
            PendingDestruction pending = pendingDestructions.remove();
            remainingDestructionsInPump--;
            try {
                if (pending.object() != null) {
                    try {
                        renderBackend.release(pending.object());
                        destroyedTotal++;
                    } catch (RuntimeException | Error failure) {
                        if (closed) {
                            rethrow(failure);
                        }
                        reportFailure(failure);
                    }
                }
            } finally {
                if (pending.completeUnload()) {
                    repository.completeUnload(pending.key());
                }
            }
        }
    }

    private void installEmptyMesh(ChunkMeshData data) {
        if (!repository.markRenderable(
                data.key(), data.revision())) {
            discardFailedUploadAtOrBefore(
                    data.key(), data.revision());
            releaseCompletedOutputAndAccepted(data);
            return;
        }
        discardFailedUploadAtOrBefore(
                data.key(), data.revision());
        ChunkRenderObject previous =
                installedRenderObjects.remove(data.key());
        releaseCompletedOutputAndAccepted(data);
        if (previous != null) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), previous, false));
        }
    }

    private void uploadReplacement(ChunkMeshData data) {
        if (!repository.isReadyForUpload(
                data.key(), data.revision())) {
            discardFailedUploadAtOrBefore(
                    data.key(), data.revision());
            releaseCompletedOutputAndAccepted(data);
            return;
        }

        ChunkRenderObject replacement;
        ChunkRenderObject uploaded = null;
        try {
            uploaded =
                    Objects.requireNonNull(
                            renderBackend.upload(data),
                            "render backend upload result");
            replacement = uploaded;
            uploadedTotal++;
            bytesUploadedTotal = Math.addExact(
                    bytesUploadedTotal,
                    Math.multiplyExact((long) data.vertexCount(),
                            (long) VoxelVertexFormat.FLOATS_PER_VERTEX
                                    * Float.BYTES));
            if (!renderOrigin.chunkKey().equals(ZERO_CHUNK_KEY)) {
                replacement = replacement.forOrigin(renderOrigin);
            }
        } catch (RuntimeException | Error failure) {
            if (uploaded != null) {
                try {
                    renderBackend.release(uploaded);
                    destroyedTotal++;
                } catch (RuntimeException | Error cleanupFailure) {
                    addSuppressedIfDistinct(failure, cleanupFailure);
                }
            }
            if (closed) {
                if (closeFailure != null) {
                    addSuppressedIfDistinct(
                            closeFailure, failure);
                    rethrow(closeFailure);
                }
                return;
            }
            ChunkMeshData current =
                    failedUploads.get(data.key());
            if (current == null
                    || current.revision() <= data.revision()) {
                ChunkMeshData replaced = failedUploads.put(data.key(), data);
                if (replaced != null) {
                    releaseCompletedOutputAndAccepted(replaced);
                }
            } else {
                releaseCompletedOutputAndAccepted(data);
            }
            reportFailure(failure);
            return;
        }

        if (closed) {
            try {
                renderBackend.release(replacement);
            } catch (RuntimeException | Error cleanupFailure) {
                if (closeFailure == null) {
                    rethrow(cleanupFailure);
                } else {
                    addSuppressedIfDistinct(
                            closeFailure, cleanupFailure);
                }
            }
            if (closeFailure != null) {
                rethrow(closeFailure);
            }
            return;
        }

        boolean accepted;
        try {
            accepted =
                    repository.markRenderable(
                            data.key(), data.revision());
        } catch (RuntimeException | Error failure) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), replacement, false));
            releaseCompletedOutputAndAccepted(data);
            throw failure;
        }
        if (!accepted) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), replacement, false));
            releaseCompletedOutputAndAccepted(data);
            return;
        }

        discardFailedUploadAtOrBefore(
                data.key(), data.revision());
        ChunkRenderObject previous =
                installedRenderObjects.put(data.key(), replacement);
        releaseCompletedOutputAndAccepted(data);
        if (previous != null) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), previous, false));
        }
    }

    private void releaseAccepted() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (acceptedMeshing <= 0) {
                throw new IllegalStateException("chunk mesh capacity token underflow");
            }
            acceptedMeshing--;
        }
    }

    private void releaseCompletedOutputAndAccepted(ChunkMeshData data) {
        Objects.requireNonNull(data, "data");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            long bytes = data.outputByteSize();
            if (completedRetainedBytes < bytes) {
                throw new IllegalStateException(
                        "completed Chunk mesh byte accounting underflow");
            }
            if (acceptedMeshing <= 0) {
                throw new IllegalStateException(
                        "chunk mesh capacity token underflow");
            }
            completedRetainedBytes -= bytes;
            acceptedMeshing--;
        }
        dispatchAvailable();
    }

    private void acquireActiveReservation(ChunkMeshMemoryPlan plan) {
        activeReservedBytes = checkedBudgetAdd(
                activeReservedBytes,
                plan.activeReservationBytes());
        updatePeakUsedBytes();
    }

    private void releaseActiveReservation(ChunkMeshMemoryPlan plan) {
        long bytes = plan.activeReservationBytes();
        if (activeReservedBytes < bytes) {
            throw new IllegalStateException(
                    "active Chunk mesh reservation underflow");
        }
        activeReservedBytes -= bytes;
    }

    private void acquireCompletedOutput(ChunkMeshData data) {
        completedRetainedBytes = checkedBudgetAdd(
                completedRetainedBytes, data.outputByteSize());
        updatePeakUsedBytes();
    }

    private boolean tryAcquireUploadScratch(
            ChunkRenderBackend.UploadMemoryRequirement requirement) {
        synchronized (lifecycleLock) {
            long nextUsed = Math.addExact(
                    usedBytes(), requirement.heapScratchBytes());
            if (nextUsed > maxCpuMeshMemoryBytes) {
                return false;
            }
            uploadScratchBytes = Math.addExact(
                    uploadScratchBytes,
                    requirement.heapScratchBytes());
            directUploadBytes = Math.addExact(
                    directUploadBytes,
                    requirement.directScratchBytes());
            peakDirectUploadBytes = Math.max(
                    peakDirectUploadBytes, directUploadBytes);
            updatePeakUsedBytes();
            return true;
        }
    }

    private void releaseUploadScratch(
            ChunkRenderBackend.UploadMemoryRequirement requirement) {
        synchronized (lifecycleLock) {
            if (uploadScratchBytes < requirement.heapScratchBytes()
                    || directUploadBytes
                            < requirement.directScratchBytes()) {
                if (closed) {
                    return;
                }
                throw new IllegalStateException(
                        "Chunk mesh upload scratch accounting underflow");
            }
            uploadScratchBytes -= requirement.heapScratchBytes();
            directUploadBytes -= requirement.directScratchBytes();
        }
    }

    private long checkedBudgetAdd(long currentClassBytes, long addedBytes) {
        long nextClass = Math.addExact(currentClassBytes, addedBytes);
        long nextUsed = Math.addExact(usedBytes(), addedBytes);
        if (nextUsed > maxCpuMeshMemoryBytes) {
            throw new IllegalStateException(
                    "Chunk mesh CPU memory budget exceeded after admission");
        }
        return nextClass;
    }

    private long usedBytes() {
        return Math.addExact(
                Math.addExact(activeReservedBytes, completedRetainedBytes),
                uploadScratchBytes);
    }

    private long availableBytes() {
        return Math.subtractExact(maxCpuMeshMemoryBytes, usedBytes());
    }

    private int memoryBlockedQueuedCount() {
        QueuedMeshingWork head = queuedMeshing.peek();
        return head != null
                && head.memoryPlan().activeReservationBytes()
                        > availableBytes()
                ? queuedMeshing.size()
                : 0;
    }

    private void updatePeakUsedBytes() {
        peakUsedBytes = Math.max(peakUsedBytes, usedBytes());
    }

    private void discardFailedUploadAtOrBefore(
            ChunkKey key, long revision) {
        ChunkMeshData failedUpload = failedUploads.get(key);
        if (failedUpload != null
                && failedUpload.revision() <= revision
                && failedUploads.remove(key, failedUpload)) {
            releaseCompletedOutputAndAccepted(failedUpload);
        }
    }

    private void removeQueuedMeshing(ChunkKey key) {
        synchronized (lifecycleLock) {
            int before = queuedMeshing.size();
            queuedMeshing.removeIf(
                    work -> work.claim().key().equals(key));
            acceptedMeshing -= before - queuedMeshing.size();
        }
    }

    private void removeCompletedWork(ChunkKey key) {
        for (MeshingCompletion completion : List.copyOf(completed)) {
            if (completion.key().equals(key)
                    && completed.remove(completion)) {
                releaseCompletedOutputAndAccepted(completion.data());
            }
        }
        for (MeshingFailure failure : List.copyOf(failed)) {
            if (failure.key().equals(key) && failed.remove(failure)) {
                releaseAccepted();
            }
        }
    }

    private void removeAwaitingUploads(ChunkKey key) {
        for (ChunkMeshData data : List.copyOf(awaitingUpload)) {
            if (data.key().equals(key) && awaitingUpload.remove(data)) {
                releaseCompletedOutputAndAccepted(data);
            }
        }
    }

    private void reportFailure(Throwable failure) {
        while (reportedFailures.size() >= budget.maxAccepted()) {
            reportedFailures.poll();
        }
        reportedFailures.add(failure);
    }

    private Throwable releaseForClose(
            ChunkRenderObject object, Throwable firstFailure) {
        try {
            renderBackend.release(object);
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                return failure;
            }
            addSuppressedIfDistinct(firstFailure, failure);
        }
        return firstFailure;
    }

    private static void addSuppressedIfDistinct(
            Throwable primary, Throwable secondary) {
        if (secondary != primary) {
            primary.addSuppressed(secondary);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    public record Metrics(
            int accepted,
            int queued,
            int active,
            int completed,
            int awaitingUpload,
            int failedUploads,
            int pendingDestructions) {
        public Metrics {
            if (accepted < 0
                    || queued < 0
                    || active < 0
                    || completed < 0
                    || awaitingUpload < 0
                    || failedUploads < 0
                    || pendingDestructions < 0) {
                throw new IllegalArgumentException("chunk mesh metrics are negative");
            }
        }
    }

    public record LifecycleMetrics(
            long uploadedTotal,
            long bytesUploadedTotal,
            long destroyedTotal,
            long lastMeshLatencyNanos) {
        public LifecycleMetrics {
            if (uploadedTotal < 0L || bytesUploadedTotal < 0L
                    || destroyedTotal < 0L || lastMeshLatencyNanos < 0L) {
                throw new IllegalArgumentException(
                        "chunk mesh lifecycle metrics are negative");
            }
        }
    }

    public record MemoryMetrics(
            long budgetBytes,
            long activeReservedBytes,
            long completedRetainedBytes,
            long uploadScratchBytes,
            long usedBytes,
            long peakUsedBytes,
            int memoryBlockedQueuedCount,
            long directUploadBytes,
            long peakDirectUploadBytes) {
        public MemoryMetrics {
            if (budgetBytes <= 0L
                    || activeReservedBytes < 0L
                    || completedRetainedBytes < 0L
                    || uploadScratchBytes < 0L
                    || usedBytes < 0L
                    || usedBytes > budgetBytes
                    || peakUsedBytes < usedBytes
                    || peakUsedBytes > budgetBytes
                    || memoryBlockedQueuedCount < 0
                    || directUploadBytes < 0L
                    || peakDirectUploadBytes < directUploadBytes) {
                throw new IllegalArgumentException(
                        "invalid Chunk mesh memory metrics");
            }
        }
    }

    public enum MeshPhase {
        NONE,
        QUEUED,
        ACTIVE,
        COMPLETED,
        AWAITING_UPLOAD,
        INSTALLED,
        FAILED
    }

    @FunctionalInterface
    public interface PreparedOriginRebase {
        void commit();
    }

    private enum DispatchOutcome {
        NONE,
        MEMORY_BLOCKED,
        SUBMITTED,
        REJECTED
    }

    private record PendingDestruction(
            ChunkKey key,
            ChunkRenderObject object,
            boolean completeUnload) {
        private PendingDestruction {
            Objects.requireNonNull(key, "key");
        }
    }

    private record MeshingCompletion(
            ChunkKey key, long revision, ChunkMeshData data) {}

    private record MeshingFailure(
            ChunkKey key, long revision, Throwable cause) {}

    private record QueuedMeshingWork(
            ChunkMeshingClaim claim,
            ChunkMeshMemoryPlan memoryPlan) {
        private QueuedMeshingWork {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(memoryPlan, "memoryPlan");
        }

        private ChunkKey key() {
            return claim.key();
        }

        private long revision() {
            return claim.revision();
        }

        private long claimId() {
            return claim.claimId();
        }
    }
}
