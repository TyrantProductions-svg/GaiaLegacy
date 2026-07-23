package com.overlord.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShutdownCoordinatorTest {
    @Test
    void closesResourcesInReverseInitializationOrder() {
        List<String> events = new ArrayList<>();
        ShutdownCoordinator coordinator = new ShutdownCoordinator();

        events.add("init-engine");
        coordinator.register("engine", () -> events.add("close-engine"));
        events.add("init-worker");
        coordinator.register("worker", () -> events.add("close-worker"));
        events.add("init-load");
        coordinator.register("load", () -> events.add("close-load"));

        coordinator.close();

        assertEquals(
                List.of(
                        "init-engine",
                        "init-worker",
                        "init-load",
                        "close-load",
                        "close-worker",
                        "close-engine"),
                events);
    }

    @Test
    void closeIsIdempotent() {
        List<String> events = new ArrayList<>();
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register("resource", () -> events.add("closed"));

        coordinator.close();
        coordinator.close();

        assertEquals(List.of("closed"), events);
    }

    @Test
    void runsEveryActionAndSuppressesLaterFailures() {
        List<String> events = new ArrayList<>();
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException laterFailure = new RuntimeException("later");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "recording",
                () -> {
                    events.add("recording");
                    throw laterFailure;
                });
        coordinator.register("middle", () -> events.add("middle"));
        coordinator.register(
                "last",
                () -> {
                    events.add("last");
                    throw firstFailure;
                });

        RuntimeException thrown = assertThrows(RuntimeException.class, coordinator::close);

        assertSame(firstFailure, thrown);
        assertEquals(List.of("last", "middle", "recording"), events);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(laterFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void rejectsRegistrationAfterClose() {
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.close();

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.register("late", () -> {}));
    }

    @Test
    void continuesCleanupAfterAnError() {
        List<String> events = new ArrayList<>();
        AssertionError firstFailure = new AssertionError("first");
        RuntimeException laterFailure = new RuntimeException("later");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "later",
                () -> {
                    events.add("later");
                    throw laterFailure;
                });
        coordinator.register(
                "first",
                () -> {
                    events.add("first");
                    throw firstFailure;
                });

        AssertionError thrown = assertThrows(AssertionError.class, coordinator::close);

        assertSame(firstFailure, thrown);
        assertEquals(List.of("first", "later"), events);
        assertSame(laterFailure, thrown.getSuppressed()[0]);
    }
}
