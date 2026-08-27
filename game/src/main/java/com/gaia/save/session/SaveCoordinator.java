package com.gaia.save.session;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.NewWorldRequest;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-thread capture, write, and exact-checkpoint publication boundary. */
public final class SaveCoordinator {
    private final SaveTargetFactory targets;

    public SaveCoordinator(SaveTargetFactory targets) {
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    public GameSessionSaveResult save(GameSession session, Instant modifiedTime) {
        PreparedSave prepared = prepareSave(session, modifiedTime);
        AtomicSaveWrite write;
        try {
            write = prepared.requiresDetachedWrite()
                    ? prepared.writeDetached()
                    : null;
        } catch (RuntimeException | Error failure) {
            return prepared.failOnOwner(failure);
        }
        return prepared.completeOnOwner(write);
    }

    public GameSessionSaveResult saveInitial(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest request) {
        PreparedSave prepared = prepareInitialSave(
                session, modifiedTime, request);
        AtomicSaveWrite write;
        try {
            write = prepared.requiresDetachedWrite()
                    ? prepared.writeDetached()
                    : null;
        } catch (RuntimeException | Error failure) {
            return prepared.failOnOwner(failure);
        }
        return prepared.completeOnOwner(write);
    }

    public PreparedSave prepareSave(
            GameSession session, Instant modifiedTime) {
        return prepare(session, modifiedTime, null);
    }

    public PreparedSave prepareInitialSave(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest request) {
        return prepare(
                session,
                modifiedTime,
                Objects.requireNonNull(request, "request"));
    }

    private PreparedSave prepare(
            GameSession suppliedSession,
            Instant suppliedModifiedTime,
            NewWorldRequest initialRequest) {
        GameSession session = Objects.requireNonNull(
                suppliedSession, "session");
        Instant modifiedTime = Objects.requireNonNull(
                suppliedModifiedTime, "modifiedTime");
        Thread ownerThread = Thread.currentThread();
        session.prepareSaveCapture();
        Optional<WorldItemPersistencePlan> persistencePlan = Optional.empty();
        try {
            persistencePlan = session.prepareWorldItemPersistence();
            SessionSaveCaptureResult capture = session.captureSave();
            if (capture.status() != SessionSaveCaptureResult.Status.CAPTURED) {
                if (persistencePlan.isPresent()) {
                    session.cancelWorldItemPersistence();
                }
                return PreparedSave.immediate(
                        ownerThread,
                        session,
                        GameSessionSaveResult.failed(
                                GameSessionSaveResult.Status.CAPTURE_REJECTED,
                                List.of(captureDiagnostic(capture.status()))));
            }
            SaveGameSnapshot snapshot = capture.snapshot().orElseThrow();
            SaveTarget target = session.streamedSaveTarget()
                    .orElseGet(() -> targets.target(snapshot.metadata().saveGameId()));
            if (initialRequest != null && !matches(initialRequest, snapshot)) {
                if (persistencePlan.isPresent()) {
                    session.cancelWorldItemPersistence();
                }
                return PreparedSave.immediate(
                        ownerThread,
                        session,
                        GameSessionSaveResult.failed(
                                GameSessionSaveResult.Status.CAPTURE_REJECTED,
                                List.of(SaveDiagnostic.of(
                                        "save-capture.initial-metadata-mismatch",
                                        "The generated session does not match the new-world request"))));
            }
            SessionPersistenceRevision revision =
                    capture.persistenceRevision().orElseThrow();
            List<PreparedDirtyChunkCapture> dirtyChunks =
                    List.copyOf(session.preparedDirtyChunks());
            Map<ChunkKey, Optional<ChunkSnapshot>> detachedChunks =
                    detachedChunks(snapshot, dirtyChunks);
            return PreparedSave.detached(
                    ownerThread,
                    session,
                    target,
                    snapshot,
                    modifiedTime,
                    persistencePlan,
                    detachedChunks,
                    dirtyChunks,
                    revision);
        } catch (RuntimeException | Error failure) {
            if (persistencePlan.isPresent()) {
                try {
                    session.cancelWorldItemPersistence();
                } catch (RuntimeException | Error cancellationFailure) {
                    addSuppressedIfDistinct(failure, cancellationFailure);
                    try {
                        session.cancelWorldItemPersistence();
                    } catch (RuntimeException | Error retryFailure) {
                        addSuppressedIfDistinct(failure, retryFailure);
                    }
                }
            }
            try {
                session.finishSaveCapture();
            } catch (RuntimeException | Error finishFailure) {
                addSuppressedIfDistinct(failure, finishFailure);
            }
            throw failure;
        }
    }

    private static Map<ChunkKey, Optional<ChunkSnapshot>> detachedChunks(
            SaveGameSnapshot snapshot,
            List<PreparedDirtyChunkCapture> dirtyChunks) {
        Map<ChunkKey, Optional<ChunkSnapshot>> chunks = new LinkedHashMap<>();
        for (ChunkSnapshot chunk : snapshot.chunks().chunks()) {
            chunks.put(chunk.key(), Optional.of(chunk));
        }
        for (PreparedDirtyChunkCapture dirty : dirtyChunks) {
            chunks.put(dirty.snapshot().key(), Optional.of(dirty.snapshot()));
        }
        return Map.copyOf(chunks);
    }

    private static void addSuppressedIfDistinct(
            Throwable primary, Throwable cleanup) {
        if (primary == cleanup) {
            return;
        }
        for (Throwable existing : primary.getSuppressed()) {
            if (existing == cleanup) {
                return;
            }
        }
        primary.addSuppressed(cleanup);
    }

    private static boolean matches(
            NewWorldRequest request, SaveGameSnapshot snapshot) {
        return snapshot.metadata().saveGameId().equals(request.saveGameId())
                && snapshot.metadata().displayName().equals(request.displayName())
                && snapshot.metadata().worldSeed() == request.seed();
    }

    public void markLoaded(GameSession session) {
        Objects.requireNonNull(session, "session");
        SessionSaveCaptureResult capture = session.captureSave();
        if (capture.status() != SessionSaveCaptureResult.Status.CAPTURED) {
            throw new IllegalStateException("loaded session could not publish its checkpoint");
        }
        session.markSaved(capture.persistenceRevision().orElseThrow());
    }

    private static SaveDiagnostic captureDiagnostic(
            SessionSaveCaptureResult.Status status) {
        return switch (status) {
            case PENDING_TRANSACTION -> SaveDiagnostic.of(
                    "save-capture.pending-transaction",
                    "The session has an in-flight canonical transaction");
            case INCONSISTENT_REVISION -> SaveDiagnostic.of(
                    "save-capture.inconsistent-revision",
                    "The session changed while the save snapshot was captured");
            case CAPTURED -> throw new IllegalArgumentException("CAPTURED is not a failure");
        };
    }

    /**
     * Owner-captured save work whose storage write is detached from mutable session
     * authority. The worker may only call {@link #writeDetached()}; publication and
     * ticket acknowledgement remain owner-thread operations.
     */
    public static final class PreparedSave {
        private final Thread ownerThread;
        private final GameSession session;
        private final GameSessionSaveResult immediateResult;
        private final SaveTarget target;
        private final SaveGameSnapshot snapshot;
        private final Instant modifiedTime;
        private final Optional<WorldItemPersistencePlan> persistencePlan;
        private final Map<ChunkKey, Optional<ChunkSnapshot>> detachedChunks;
        private final List<PreparedDirtyChunkCapture> dirtyChunks;
        private final SessionPersistenceRevision revision;
        private final AtomicBoolean writeStarted = new AtomicBoolean();
        private boolean ownerCompleted;
        private boolean pendingPersistence;

        private PreparedSave(
                Thread ownerThread,
                GameSession session,
                GameSessionSaveResult immediateResult,
                SaveTarget target,
                SaveGameSnapshot snapshot,
                Instant modifiedTime,
                Optional<WorldItemPersistencePlan> persistencePlan,
                Map<ChunkKey, Optional<ChunkSnapshot>> detachedChunks,
                List<PreparedDirtyChunkCapture> dirtyChunks,
                SessionPersistenceRevision revision) {
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
            this.session = Objects.requireNonNull(session, "session");
            this.immediateResult = immediateResult;
            this.target = target;
            this.snapshot = snapshot;
            this.modifiedTime = modifiedTime;
            this.persistencePlan = Objects.requireNonNull(
                    persistencePlan, "persistencePlan");
            this.detachedChunks = Objects.requireNonNull(
                    detachedChunks, "detachedChunks");
            this.dirtyChunks = Objects.requireNonNull(dirtyChunks, "dirtyChunks");
            this.revision = revision;
            this.pendingPersistence = persistencePlan.isPresent();
        }

        private static PreparedSave immediate(
                Thread ownerThread,
                GameSession session,
                GameSessionSaveResult result) {
            return new PreparedSave(
                    ownerThread,
                    session,
                    Objects.requireNonNull(result, "result"),
                    null,
                    null,
                    null,
                    Optional.empty(),
                    Map.of(),
                    List.of(),
                    null);
        }

        private static PreparedSave detached(
                Thread ownerThread,
                GameSession session,
                SaveTarget target,
                SaveGameSnapshot snapshot,
                Instant modifiedTime,
                Optional<WorldItemPersistencePlan> persistencePlan,
                Map<ChunkKey, Optional<ChunkSnapshot>> detachedChunks,
                List<PreparedDirtyChunkCapture> dirtyChunks,
                SessionPersistenceRevision revision) {
            return new PreparedSave(
                    ownerThread,
                    session,
                    null,
                    Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(snapshot, "snapshot"),
                    Objects.requireNonNull(modifiedTime, "modifiedTime"),
                    persistencePlan,
                    Map.copyOf(detachedChunks),
                    List.copyOf(dirtyChunks),
                    Objects.requireNonNull(revision, "revision"));
        }

        public boolean requiresDetachedWrite() {
            return immediateResult == null;
        }

        /** Performs storage work without consulting or mutating the live session. */
        public AtomicSaveWrite writeDetached() {
            if (!requiresDetachedWrite()) {
                throw new IllegalStateException("rejected capture has no detached write");
            }
            if (!writeStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("detached save write already started");
            }
            return Objects.requireNonNull(
                    target.saveAtomically(
                            snapshot,
                            modifiedTime,
                            persistencePlan,
                            key -> detachedChunks.getOrDefault(key, Optional.empty()),
                            dirtyChunks),
                    "atomic save write result");
        }

        public GameSessionSaveResult completeOnOwner(AtomicSaveWrite atomicWrite) {
            assertOwnerAndOpen();
            ownerCompleted = true;
            try {
                if (immediateResult != null) {
                    if (atomicWrite != null) {
                        throw new IllegalArgumentException(
                                "rejected capture cannot have an atomic write");
                    }
                    return immediateResult;
                }
                AtomicSaveWrite checkedWrite = Objects.requireNonNull(
                        atomicWrite, "atomicWrite");
                SaveWriteResult write = checkedWrite.writeResult();
                if (persistencePlan.isPresent()) {
                    if (checkedWrite.worldItemProof().isPresent()) {
                        try {
                            session.commitWorldItemPersistence(
                                    checkedWrite.worldItemProof().orElseThrow());
                            pendingPersistence = false;
                        } catch (RuntimeException proofFailure) {
                            cancelPending(proofFailure);
                            return GameSessionSaveResult.failed(
                                    GameSessionSaveResult.Status.WRITE_FAILED,
                                    List.of(SaveDiagnostic.of(
                                            "save-write.world-items-proof-rejected",
                                            "The world-item durable proof was rejected",
                                            proofFailure)));
                        }
                    } else if (write.status() == SaveWriteResult.Status.SUCCESS) {
                        cancelPending(null);
                        return GameSessionSaveResult.failed(
                                GameSessionSaveResult.Status.WRITE_FAILED,
                                List.of(SaveDiagnostic.of(
                                        "save-write.world-items-proof-missing",
                                        "A successful streamed root omitted its durability proof")));
                    } else {
                        cancelPending(null);
                    }
                } else if (checkedWrite.worldItemProof().isPresent()) {
                    throw new IllegalStateException(
                            "A save without a WorldItem plan returned a durability proof");
                }
                if (write.status() == SaveWriteResult.Status.SUCCESS) {
                    session.commitDirtyChunkPersistence();
                    session.markSaved(revision);
                    return GameSessionSaveResult.success(
                            write.committedManifest().orElseThrow());
                }
                return GameSessionSaveResult.failed(
                        write.status() == SaveWriteResult.Status.BLOCKING_FAILURE
                                ? GameSessionSaveResult.Status.BLOCKING_FAILURE
                                : GameSessionSaveResult.Status.WRITE_FAILED,
                        write.diagnostics());
            } catch (RuntimeException | Error failure) {
                if (pendingPersistence) {
                    cancelPending(failure);
                }
                throw failure;
            } finally {
                session.finishSaveCapture();
            }
        }

        /** Owner-side cleanup for a detached worker failure. */
        public GameSessionSaveResult failOnOwner(Throwable failure) {
            return failOnOwner(failure, false);
        }

        /** Async product path converts ordinary detached failures into safe UI results. */
        public GameSessionSaveResult failDetachedOnOwner(Throwable failure) {
            return failOnOwner(failure, true);
        }

        private GameSessionSaveResult failOnOwner(
                Throwable failure, boolean convertAnyRuntimeFailure) {
            Objects.requireNonNull(failure, "failure");
            assertOwnerAndOpen();
            ownerCompleted = true;
            try {
                if (pendingPersistence) {
                    try {
                        cancelPending(failure);
                    } catch (RuntimeException | Error cancellationFailure) {
                        addSuppressedIfDistinct(failure, cancellationFailure);
                        throw propagate(failure);
                    }
                    if (failure instanceof RuntimeException runtimeFailure) {
                        return GameSessionSaveResult.failed(
                                GameSessionSaveResult.Status.WRITE_FAILED,
                                List.of(SaveDiagnostic.of(
                                        "save-write.world-items-persistence-failed",
                                        "The streamed page and session root did not reach one durable commit",
                                        runtimeFailure)));
                    }
                }
                if (convertAnyRuntimeFailure
                        && failure instanceof RuntimeException runtimeFailure) {
                    return GameSessionSaveResult.failed(
                            GameSessionSaveResult.Status.WRITE_FAILED,
                            List.of(SaveDiagnostic.of(
                                    "save-write.detached-failed",
                                    "The detached save operation failed before acknowledgement",
                                    runtimeFailure)));
                }
                throw propagate(failure);
            } finally {
                try {
                    session.finishSaveCapture();
                } catch (RuntimeException | Error finishFailure) {
                    addSuppressedIfDistinct(failure, finishFailure);
                    throw propagate(failure);
                }
            }
        }

        /** Cancels an unpublished prepared save without performing a storage write. */
        public void cancelOnOwner() {
            assertOwnerAndOpen();
            ownerCompleted = true;
            RuntimeException failure = null;
            try {
                cancelPending(null);
            } catch (RuntimeException cancellationFailure) {
                failure = cancellationFailure;
            } finally {
                try {
                    session.finishSaveCapture();
                } catch (RuntimeException finishFailure) {
                    if (failure != null) {
                        addSuppressedIfDistinct(failure, finishFailure);
                    } else {
                        failure = finishFailure;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void cancelPending(Throwable primary) {
            if (!pendingPersistence) {
                return;
            }
            try {
                session.cancelWorldItemPersistence();
                pendingPersistence = false;
            } catch (RuntimeException | Error firstFailure) {
                if (primary != null) {
                    addSuppressedIfDistinct(primary, firstFailure);
                }
                try {
                    session.cancelWorldItemPersistence();
                    pendingPersistence = false;
                } catch (RuntimeException | Error retryFailure) {
                    if (primary != null) {
                        addSuppressedIfDistinct(primary, retryFailure);
                    } else {
                        addSuppressedIfDistinct(firstFailure, retryFailure);
                    }
                    throw firstFailure;
                }
            }
        }

        private void assertOwnerAndOpen() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "prepared save completion must run on its owner thread");
            }
            if (ownerCompleted) {
                throw new IllegalStateException("prepared save already completed");
            }
        }

        private static RuntimeException propagate(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                return runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new IllegalStateException("detached save failed", failure);
        }
    }

    @FunctionalInterface
    public interface SaveTargetFactory {
        SaveTarget target(SaveGameId saveGameId);
    }

    @FunctionalInterface
    public interface SaveTarget {
        default WorldItemDurableProof persistWorldItems(
                WorldItemPersistencePlan plan) {
            throw new UnsupportedOperationException(
                    "This save target cannot persist streamed world-item pages");
        }

        default AtomicSaveWrite saveAtomically(
                SaveGameSnapshot snapshot,
                Instant modifiedTime,
                Optional<WorldItemPersistencePlan> worldItems,
                java.util.function.Function<ChunkKey, Optional<ChunkSnapshot>> chunks) {
            Objects.requireNonNull(worldItems, "worldItems");
            Objects.requireNonNull(chunks, "chunks");
            if (worldItems.isPresent()) {
                throw new UnsupportedOperationException(
                        "A streamed WorldItem plan requires one atomic page and session root");
            }
            return new AtomicSaveWrite(save(snapshot, modifiedTime), Optional.empty());
        }

        default AtomicSaveWrite saveAtomically(
                SaveGameSnapshot snapshot,
                Instant modifiedTime,
                Optional<WorldItemPersistencePlan> worldItems,
                java.util.function.Function<ChunkKey, Optional<ChunkSnapshot>> chunks,
                List<PreparedDirtyChunkCapture> dirtyChunks) {
            List<PreparedDirtyChunkCapture> checkedDirty = List.copyOf(
                    Objects.requireNonNull(dirtyChunks, "dirtyChunks"));
            if (!checkedDirty.isEmpty()) {
                throw new UnsupportedOperationException(
                        "This save target cannot publish streamed dirty Chunks");
            }
            return saveAtomically(snapshot, modifiedTime, worldItems, chunks);
        }

        SaveWriteResult save(SaveGameSnapshot snapshot, Instant modifiedTime);
    }

    public record AtomicSaveWrite(
            SaveWriteResult writeResult,
            Optional<WorldItemDurableProof> worldItemProof) {
        public AtomicSaveWrite {
            writeResult = Objects.requireNonNull(writeResult, "writeResult");
            worldItemProof = Objects.requireNonNull(worldItemProof, "worldItemProof");
        }
    }

    /** Detached exact dirty capture plus a ticket-free live freshness capability. */
    public record PreparedDirtyChunkCapture(
            ChunkSnapshot snapshot,
            java.util.function.BooleanSupplier stillCurrent) {
        public PreparedDirtyChunkCapture {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(stillCurrent, "stillCurrent");
        }
    }
}
