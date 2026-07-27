package com.gaia.interaction;

import java.util.Objects;
import java.util.Optional;

/** Pure fixed-step break progress; it never mutates World or Chunk state. */
public final class BlockBreakTracker {
    private BlockBreakSession session;
    private GameMode sessionMode;

    public BreakTrackerResult update(BreakUpdate update, double fixedDeltaSeconds) {
        Objects.requireNonNull(update, "update");
        if (!Double.isFinite(fixedDeltaSeconds) || fixedDeltaSeconds <= 0) {
            throw new IllegalArgumentException(
                    "fixedDeltaSeconds must be finite and positive");
        }

        if (session != null) {
            if (!update.primaryHeld()
                    || update.blocked()
                    || update.target().isEmpty()
                    || sessionMode != update.gameMode()
                    || !session.matches(
                            update.target().orElseThrow(), update.chunkRevision())) {
                clear();
                return result(BreakTrackerResult.Status.CANCELLED);
            }
            session = session.advance(fixedDeltaSeconds);
            return result(session.complete()
                    ? BreakTrackerResult.Status.COMPLETED
                    : BreakTrackerResult.Status.ADVANCED);
        }

        if (!update.primaryHeld() || update.blocked() || update.target().isEmpty()) {
            return result(BreakTrackerResult.Status.IDLE);
        }
        BreakRule rule = update.rule().orElseThrow();
        if (!rule.breakable()) {
            return result(BreakTrackerResult.Status.UNBREAKABLE);
        }
        sessionMode = update.gameMode();
        session = BlockBreakSession.start(
                        update.target().orElseThrow(),
                        update.chunkRevision(),
                        rule.requiredSeconds())
                .advance(fixedDeltaSeconds);
        return result(session.complete()
                ? BreakTrackerResult.Status.COMPLETED
                : BreakTrackerResult.Status.STARTED);
    }

    public Optional<BlockBreakSession> session() {
        return Optional.ofNullable(session);
    }

    public void clear() {
        session = null;
        sessionMode = null;
    }

    private BreakTrackerResult result(BreakTrackerResult.Status status) {
        return new BreakTrackerResult(status, session());
    }
}
