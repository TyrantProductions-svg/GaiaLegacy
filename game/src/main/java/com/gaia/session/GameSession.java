package com.gaia.session;

import com.overlord.core.input.MouseDelta;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.gaia.save.session.SaveCoordinator;
import java.util.List;
import java.util.Optional;

public interface GameSession extends AutoCloseable {
    GameSessionState state();

    void pollLoad();

    /** Responsive product-loop load step; legacy sessions may use the synchronous path. */
    default void pollLoadResponsive() {
        pollLoad();
    }

    /** Owner-prepared initial archive write, when readiness awaits first persistence. */
    default Optional<SaveCoordinator.PreparedSave> preparedInitialSave() {
        return Optional.empty();
    }

    /** Publishes the result of the exact prepared initial save. */
    default void completeInitialSave(GameSessionSaveResult result) {
        throw new UnsupportedOperationException(
                "This session has no prepared initial save");
    }

    /** Cancels and clears the exact owner-prepared initial save, if present. */
    default void cancelPreparedInitialSave() {
        // A legacy session has no initial persistence ticket.
    }

    GameSessionFrame advancePlaying(
            double frameDeltaSeconds,
            MouseDelta look,
            boolean focused);

    GameSessionFrame capturePaused();

    /** Explicit owner action; diagnostics are never retried by HUD/frame capture. */
    default boolean retryChunkStreaming(ChunkKey key) {
        return false;
    }

    default SessionSaveCaptureResult captureSave() {
        throw new UnsupportedOperationException(
                "This session does not provide persistence capture");
    }

    /** Freezes streamed admissions and makes existing durability work capturable. */
    default void prepareSaveCapture() {}

    /** Releases a successful save-capture freeze without closing the session. */
    default void finishSaveCapture() {}

    default void markSaved(SessionPersistenceRevision revision) {
        throw new UnsupportedOperationException(
                "This session does not provide persistence checkpoints");
    }

    default Optional<WorldItemPersistencePlan> prepareWorldItemPersistence() {
        return Optional.empty();
    }

    default void commitWorldItemPersistence(WorldItemDurableProof proof) {
        throw new UnsupportedOperationException(
                "This session does not own paged world-item persistence");
    }

    default void cancelWorldItemPersistence() {
        // A legacy session has no paging ticket to cancel.
    }

    /** Detached dirty resident Chunks pinned by the active save-capture barrier. */
    default List<SaveCoordinator.PreparedDirtyChunkCapture> preparedDirtyChunks() {
        return List.of();
    }

    /** Acknowledges the exact prepared dirty captures after one durable root. */
    default void commitDirtyChunkPersistence() {
        // A legacy finite session has no streamed Chunk tickets to acknowledge.
    }

    /** Active-session streamed persistence target backed by this session's graph. */
    default Optional<SaveCoordinator.SaveTarget> streamedSaveTarget() {
        return Optional.empty();
    }

    /** Exact resident Chunk capture used only when a first live-item page has no Task 4 row. */
    default Optional<ChunkSnapshot> captureWorldItemChunk(ChunkKey key) {
        return Optional.empty();
    }

    default GameSessionSaveResult save() {
        throw new UnsupportedOperationException(
                "This session does not own a save coordinator");
    }

    /** Captures a regular save on the owner for detached storage execution. */
    default SaveCoordinator.PreparedSave prepareDetachedSave() {
        throw new UnsupportedOperationException(
                "This session does not own a detached save coordinator");
    }

    default boolean hasUnsavedChanges() {
        throw new UnsupportedOperationException(
                "This session does not expose persistence dirty state");
    }

    void discardFixedTime();

    @Override
    void close();
}
