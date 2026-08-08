package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WindowVsyncContractTest {
    @Test
    void releaseCandidateMakesContextCurrentBeforeEnablingVsync() {
        List<String> calls = new ArrayList<>();

        Window.configureContext(
                MainThreadGuard.captureCurrentThread(),
                42L,
                GameConfig.Window.VSYNC,
                handle -> calls.add("current:" + handle),
                interval -> calls.add("interval:" + interval));

        assertEquals(List.of("current:42", "interval:1"), calls);
    }

    @Test
    void disabledVsyncUsesExplicitIntervalZero() {
        List<String> calls = new ArrayList<>();

        Window.configureContext(
                MainThreadGuard.captureCurrentThread(),
                7L,
                false,
                handle -> calls.add("current:" + handle),
                interval -> calls.add("interval:" + interval));

        assertEquals(List.of("current:7", "interval:0"), calls);
    }

    @Test
    void contextConfigurationRejectsWorkerBeforeBackendCalls()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        List<String> calls = new ArrayList<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            Window.configureContext(
                                                                    guard,
                                                                    9L,
                                                                    true,
                                                                    handle ->
                                                                            calls.add("current"),
                                                                    interval ->
                                                                            calls.add("interval")))
                                            .get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(calls.isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
