package com.gaia.interaction;

import com.overlord.event.Event;
import java.util.Objects;
import java.util.function.Consumer;

public final class GameModeManager {
    private GameMode mode;
    private final Consumer<Event> eventSink;

    public GameModeManager(GameMode initialMode, Consumer<Event> eventSink) {
        mode = Objects.requireNonNull(initialMode, "initialMode");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public GameMode mode() {
        return mode;
    }

    public boolean toggle(long tick) {
        return setMode(
                mode == GameMode.SURVIVAL ? GameMode.CREATIVE : GameMode.SURVIVAL,
                tick);
    }

    public boolean setMode(GameMode requested, long tick) {
        Objects.requireNonNull(requested, "requested");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (requested == mode) {
            return false;
        }
        GameMode previous = mode;
        mode = requested;
        eventSink.accept(new GameModeChanged(previous, requested, tick));
        return true;
    }
}
