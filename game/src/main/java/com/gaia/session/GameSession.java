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

    void discardFixedTime();

    @Override
    void close();
}
