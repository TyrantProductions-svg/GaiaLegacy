package com.gaia.session;

import com.overlord.core.input.MouseDelta;

public interface GameSession extends AutoCloseable {
    GameSessionState state();

    void pollLoad();

    GameSessionFrame advancePlaying(
            double frameDeltaSeconds,
            MouseDelta look,
            boolean focused);

    GameSessionFrame capturePaused();

    default SessionSaveCaptureResult captureSave() {
        throw new UnsupportedOperationException(
                "This session does not provide persistence capture");
    }

    default void markSaved(SessionPersistenceRevision revision) {
        throw new UnsupportedOperationException(
                "This session does not provide persistence checkpoints");
    }

    default GameSessionSaveResult save() {
        throw new UnsupportedOperationException(
                "This session does not own a save coordinator");
    }

    default boolean hasUnsavedChanges() {
        throw new UnsupportedOperationException(
                "This session does not expose persistence dirty state");
    }

    void discardFixedTime();

    @Override
    void close();
}
