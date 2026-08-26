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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread capture, write, and exact-checkpoint publication boundary. */
public final class SaveCoordinator {
    private final SaveTargetFactory targets;

    public SaveCoordinator(SaveTargetFactory targets) {
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    public GameSessionSaveResult save(GameSession session, Instant modifiedTime) {
        return saveCaptured(session, modifiedTime, null);
    }

    public GameSessionSaveResult saveInitial(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest request) {
        return saveCaptured(
                session,
                modifiedTime,
                Objects.requireNonNull(request, "request"));
    }

    private GameSessionSaveResult saveCaptured(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest initialRequest) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(modifiedTime, "modifiedTime");
        session.prepareSaveCapture();
        try {
            return savePrepared(session, modifiedTime, initialRequest);
        } finally {
            session.finishSaveCapture();
        }
    }

    private GameSessionSaveResult savePrepared(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest initialRequest) {
        Optional<WorldItemPersistencePlan> persistencePlan =
                session.prepareWorldItemPersistence();
        boolean pendingPersistence = persistencePlan.isPresent();
        FailureStage failureStage = FailureStage.CAPTURE;
        try {
            SessionSaveCaptureResult capture = session.captureSave();
            if (capture.status() != SessionSaveCaptureResult.Status.CAPTURED) {
                if (pendingPersistence) {
                    session.cancelWorldItemPersistence();
                    pendingPersistence = false;
                }
                return GameSessionSaveResult.failed(
                        GameSessionSaveResult.Status.CAPTURE_REJECTED,
                        List.of(captureDiagnostic(capture.status())));
            }
            SaveGameSnapshot snapshot = capture.snapshot().orElseThrow();
            SaveTarget target = session.streamedSaveTarget()
                    .orElseGet(() -> targets.target(snapshot.metadata().saveGameId()));
            if (initialRequest != null && !matches(initialRequest, snapshot)) {
                if (pendingPersistence) {
                    session.cancelWorldItemPersistence();
                    pendingPersistence = false;
                }
                return GameSessionSaveResult.failed(
                        GameSessionSaveResult.Status.CAPTURE_REJECTED,
                        List.of(SaveDiagnostic.of(
                                "save-capture.initial-metadata-mismatch",
                                "The generated session does not match the new-world request")));
            }
            SessionPersistenceRevision revision =
                    capture.persistenceRevision().orElseThrow();
            failureStage = FailureStage.ATOMIC_WRITE;
            AtomicSaveWrite atomicWrite = Objects.requireNonNull(
                    target.saveAtomically(
                            snapshot,
                            modifiedTime,
                            persistencePlan,
                            session::captureWorldItemChunk,
                            session.preparedDirtyChunks()),
                    "atomic save write result");
            SaveWriteResult write = atomicWrite.writeResult();
            if (persistencePlan.isPresent()) {
                if (atomicWrite.worldItemProof().isPresent()) {
                    failureStage = FailureStage.PROOF_COMMIT;
                    session.commitWorldItemPersistence(
                            atomicWrite.worldItemProof().orElseThrow());
                    pendingPersistence = false;
                } else if (write.status() == SaveWriteResult.Status.SUCCESS) {
                    session.cancelWorldItemPersistence();
                    pendingPersistence = false;
                    return GameSessionSaveResult.failed(
                            GameSessionSaveResult.Status.WRITE_FAILED,
                            List.of(SaveDiagnostic.of(
                                    "save-write.world-items-proof-missing",
                                    "A successful streamed root omitted its durability proof")));
                } else {
                    session.cancelWorldItemPersistence();
                    pendingPersistence = false;
                }
            } else if (atomicWrite.worldItemProof().isPresent()) {
                throw new IllegalStateException(
                        "A save without a WorldItem plan returned a durability proof");
            }
            failureStage = FailureStage.FINALIZE;
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
            boolean cancellationFailed = false;
            if (pendingPersistence) {
                try {
                    session.cancelWorldItemPersistence();
                } catch (RuntimeException | Error cancellationFailure) {
                    addSuppressedIfDistinct(failure, cancellationFailure);
                    try {
                        session.cancelWorldItemPersistence();
                    } catch (RuntimeException | Error retryFailure) {
                        cancellationFailed = true;
                        addSuppressedIfDistinct(failure, retryFailure);
                    }
                }
            }
            if (!cancellationFailed
                    && failure instanceof RuntimeException runtimeFailure
                    && persistencePlan.isPresent()) {
                if (failureStage == FailureStage.ATOMIC_WRITE) {
                    return GameSessionSaveResult.failed(
                            GameSessionSaveResult.Status.WRITE_FAILED,
                            List.of(SaveDiagnostic.of(
                                    "save-write.world-items-persistence-failed",
                                    "The streamed page and session root did not reach one durable commit",
                                    runtimeFailure)));
                }
                if (failureStage == FailureStage.PROOF_COMMIT) {
                    return GameSessionSaveResult.failed(
                            GameSessionSaveResult.Status.WRITE_FAILED,
                            List.of(SaveDiagnostic.of(
                                    "save-write.world-items-proof-rejected",
                                    "The world-item durable proof was rejected",
                                    runtimeFailure)));
                }
            }
            throw failure;
        }
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

    private enum FailureStage {
        CAPTURE,
        ATOMIC_WRITE,
        PROOF_COMMIT,
        FINALIZE
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
