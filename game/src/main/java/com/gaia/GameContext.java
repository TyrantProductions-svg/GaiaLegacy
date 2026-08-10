package com.gaia;

import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFactory;
import com.overlord.core.Engine;
import com.overlord.core.input.InputManager;
import com.overlord.core.time.FrameClock;
import java.util.Objects;

/** Long-lived application resources owned by the single game loop. */
public record GameContext(
        Engine engine,
        InputManager inputManager,
        FrameClock frameClock,
        GameSessionFactory sessionFactory,
        GameSessionConfig initialSessionConfig,
        RenderMetricsConsoleReporter renderMetricsReporter) {
    public GameContext {
        engine = Objects.requireNonNull(engine, "engine");
        inputManager = Objects.requireNonNull(inputManager, "inputManager");
        frameClock = Objects.requireNonNull(frameClock, "frameClock");
        sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        initialSessionConfig =
                Objects.requireNonNull(
                        initialSessionConfig, "initialSessionConfig");
        renderMetricsReporter =
                Objects.requireNonNull(
                        renderMetricsReporter, "renderMetricsReporter");
    }
}
