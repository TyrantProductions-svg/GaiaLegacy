package com.gaia.interaction;

import com.overlord.event.Event;
import java.util.Objects;

/** Post-commit notification for an applied game-mode transition. */
public final class GameModeChanged extends Event {
    private final GameMode previousMode;
    private final GameMode mode;
    private final long tick;

    public GameModeChanged(GameMode previousMode, GameMode mode, long tick) {
        this.previousMode = Objects.requireNonNull(previousMode, "previousMode");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (previousMode == mode) {
            throw new IllegalArgumentException("game mode transition must change mode");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        this.tick = tick;
    }

    public GameMode previousMode() {
        return previousMode;
    }

    public GameMode mode() {
        return mode;
    }

    public long tick() {
        return tick;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void cancel() {
        // A post-commit observation cannot veto committed history.
    }

    @Override
    public String getEventType() {
        return "game_mode_changed";
    }
}
