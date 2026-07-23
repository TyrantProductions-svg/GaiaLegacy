package com.gaia;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.lifecycle.ShutdownCoordinator;
import org.junit.jupiter.api.Test;

class GameBootstrapTest {
    @Test
    void suppressesCleanupFailureOnPrimaryFailure() {
        RuntimeException primary = new RuntimeException("startup failed");
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        GameBootstrap.closeAfterRun(coordinator, primary);

        assertSame(cleanup, primary.getSuppressed()[0]);
    }

    @Test
    void throwsCleanupFailureWhenThereIsNoPrimaryFailure() {
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> GameBootstrap.closeAfterRun(coordinator, null));

        assertSame(cleanup, thrown);
    }
}
