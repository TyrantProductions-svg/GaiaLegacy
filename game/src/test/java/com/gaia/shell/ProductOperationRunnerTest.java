package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProductOperationRunnerTest {
    @Test
    void blockedWorkerRetainsOneCapacityTokenUntilOwnerDrainsCompletion()
            throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-operation")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            long generation = runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "READING SAVE",
                            "Reading immutable archive bytes",
                            true),
                    context -> {
                        entered.countDown();
                        release.await();
                        context.update(OperationProgressUpdate.indeterminate(
                                1,
                                "VALIDATING MANIFEST",
                                "Checking world identity",
                                false));
                        return "candidate";
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, runner.acceptedCount());
            assertEquals(generation, runner.activeGeneration().orElseThrow());
            assertThrows(IllegalStateException.class, () -> runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.SAVE_WORLD,
                            "PREPARING",
                            "Cannot overlap",
                            false),
                    ignored -> "other"));

            release.countDown();
            ProductOperationRunner.Completion completion =
                    awaitCompletion(runner);
            assertEquals(1, runner.acceptedCount(),
                    "completed-undrained work must retain capacity");
            assertEquals("candidate", completion.value().orElseThrow());

            assertEquals(OperationProgressSnapshot.TerminalState.RUNNING,
                    runner.progress().orElseThrow().terminalState(),
                    "worker completion is detached data, not owner publication success");
            assertTrue(runner.finishSuccess(generation));
            assertTrue(runner.releaseTerminal(generation));
            assertEquals(0, runner.acceptedCount());
            assertTrue(runner.activeGeneration().isEmpty());
        }
    }

    @Test
    void cancellationPublishesOneCanceledCompletionAndRejectsLateWorkerUpdate()
            throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-cancel")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            long generation = runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "READING SAVE",
                            "Cancelable read",
                            true),
                    context -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        context.update(OperationProgressUpdate.indeterminate(
                                1,
                                "STALE",
                                "Must not replace canceled progress",
                                false));
                        return "late";
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertTrue(runner.cancel(generation));
            release.countDown();
            ProductOperationRunner.Completion canceled = awaitCompletion(runner);

            assertTrue(canceled.canceled());
            assertTrue(canceled.value().isEmpty());
            assertEquals(
                    OperationProgressSnapshot.TerminalState.CANCELED,
                    runner.progress().orElseThrow().terminalState());
            assertFalse(runner.progress().orElseThrow().phase().equals("STALE"));
            assertTrue(runner.releaseTerminal(generation));
        }
    }

    @Test
    void eachOperationReceivesANewPositivePublishedIdAndSequence()
            throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-publication-id")) {
            runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "READING SAVE",
                            "First operation",
                            true),
                    context -> "first");
            awaitCompletion(runner);
            OperationProgressSnapshot first = runner.progress().orElseThrow();
            long firstId = publishedLong(first, "operationId");
            long firstSequence = publishedLong(first, "sequence");
            assertTrue(runner.finishSuccess(firstId));
            assertTrue(runner.releaseTerminal(firstId));

            runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.SAVE_WORLD,
                            "WRITING SAVE",
                            "Second operation",
                            false),
                    context -> "second");
            awaitCompletion(runner);
            OperationProgressSnapshot second = runner.progress().orElseThrow();

            assertTrue(firstId > 0L);
            assertTrue(firstSequence > 0L);
            assertTrue(publishedLong(second, "operationId") > 0L);
            assertTrue(publishedLong(second, "sequence") > 0L);
            assertNotEquals(firstId, publishedLong(second, "operationId"));
            long secondId = publishedLong(second, "operationId");
            assertTrue(runner.finishSuccess(secondId));
            assertTrue(runner.releaseTerminal(secondId));
        }
    }

    @Test
    void workerUpdateValueContainsNoPublicationIdentityOrSequence() {
        Class<?> updateType = assertDoesNotThrow(() -> Class.forName(
                "com.gaia.shell.OperationProgressUpdate"));

        assertTrue(updateType.isRecord());
        Set<String> componentNames = Arrays.stream(updateType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(componentNames.contains("operationId"));
        assertFalse(componentNames.contains("sequence"));
        assertFalse(Arrays.stream(ProductOperationRunner.WorkerContext.class.getMethods())
                .anyMatch(method -> method.getName().equals("generation")),
                "workers must not receive publication identity authority");
        Class<?> actualParameter = Arrays.stream(
                        ProductOperationRunner.WorkerContext.class.getMethods())
                .filter(method -> method.getName().equals("update"))
                .findFirst()
                .orElseThrow()
                .getParameterTypes()[0];
        assertEquals(updateType, actualParameter,
                "workers report facts; only the runner publishes identity and sequence");
    }

    @Test
    void workerPhaseChangeAdvancesOrdinalAndSequenceOnlyThroughRunner()
            throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-phase-animation")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch update = new CountDownLatch(1);
            CountDownLatch updated = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "READING SAVE",
                            "Reading immutable bytes",
                            true),
                    context -> {
                        entered.countDown();
                        update.await();
                        context.update(OperationProgressUpdate.indeterminate(
                                1,
                                "VALIDATING MANIFEST",
                                "Checking world identity",
                                true));
                        updated.countDown();
                        finish.await();
                        return "candidate";
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OperationProgressSnapshot before = runner.progress().orElseThrow();

            update.countDown();
            assertTrue(updated.await(5, TimeUnit.SECONDS));

            OperationProgressSnapshot after = runner.progress().orElseThrow();
            assertEquals(1, after.phaseOrdinal());
            assertTrue(after.sequence() > before.sequence());
            assertEquals(before.operationId(), after.operationId());
            finish.countDown();
            ProductOperationRunner.Completion completion = awaitCompletion(runner);
            assertTrue(runner.finishSuccess(completion.generation()));
            assertTrue(runner.releaseTerminal(completion.generation()));
        }
    }

    @Test
    void ownerPublishesTerminalSuccessExactlyOnceAfterDetachedCompletion()
            throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-success-terminal")) {
            runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            "PUBLISHING",
                            "Publishing fresh session",
                            false),
                    context -> "ready");

            ProductOperationRunner.Completion completion = awaitCompletion(runner);

            assertEquals("ready", completion.value().orElseThrow());
            assertEquals(
                    OperationProgressSnapshot.TerminalState.RUNNING,
                    runner.progress().orElseThrow().terminalState());
            assertTrue(runner.finishSuccess(completion.generation()));
            assertFalse(runner.finishSuccess(completion.generation()));
            assertFalse(runner.cancel(completion.generation()));
            OperationProgressSnapshot terminal = runner.progress().orElseThrow();
            assertFalse(runner.ownerUpdate(
                    completion.generation(),
                    OperationProgressUpdate.indeterminate(
                            2,
                            "STALE WORKER PHASE",
                            "Must not publish after terminal success",
                            false)));
            assertEquals(terminal, runner.progress().orElseThrow(),
                    "terminal success rejects every later progress fact");
            assertEquals(OperationProgressSnapshot.TerminalState.SUCCESS,
                    runner.progress().orElseThrow().terminalState());
            assertTrue(runner.releaseTerminal(completion.generation()));
            assertTrue(runner.progress().isEmpty());
        }
    }

    @Test
    void phaseAndExactCountsCannotRegressWithinOneOperation() throws Exception {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-product-monotonic-progress")) {
            CountDownLatch release = new CountDownLatch(1);
            long generation = runner.start(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.LOAD_WORLD,
                            0,
                            "READING SAVE",
                            "Reading immutable bytes",
                            true),
                    context -> {
                        release.await();
                        return "candidate";
                    });

            assertTrue(runner.ownerUpdate(generation, new OperationProgressUpdate(
                    1,
                    "VALIDATING MANIFEST",
                    "Checking descriptors",
                    java.util.OptionalLong.of(8),
                    java.util.OptionalLong.of(10),
                    false,
                    java.util.Optional.empty())));
            OperationProgressSnapshot accepted = runner.progress().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> runner.ownerUpdate(
                    generation,
                    OperationProgressUpdate.indeterminate(
                            0, "READING SAVE", "Cannot go backward", false)));
            assertThrows(IllegalArgumentException.class, () -> runner.ownerUpdate(
                    generation,
                    new OperationProgressUpdate(
                            1,
                            "VALIDATING MANIFEST",
                            "Cannot decrease exact units",
                            java.util.OptionalLong.of(7),
                            java.util.OptionalLong.of(10),
                            false,
                            java.util.Optional.empty())));
            assertThrows(IllegalArgumentException.class, () -> runner.ownerUpdate(
                    generation,
                    new OperationProgressUpdate(
                            1,
                            "VALIDATING MANIFEST",
                            "Cannot change exact total",
                            java.util.OptionalLong.of(8),
                            java.util.OptionalLong.of(11),
                            false,
                            java.util.Optional.empty())));
            assertEquals(accepted, runner.progress().orElseThrow(),
                    "rejected updates must not publish or consume a sequence");

            release.countDown();
            awaitCompletion(runner);
            assertTrue(runner.finishSuccess(generation));
            assertTrue(runner.releaseTerminal(generation));
        }
    }

    @Test
    void ownerOnlyFailurePublishesTerminalAndReleasesWithoutWorkerCompletion() {
        try (ProductOperationRunner runner = ProductOperationRunner.createForOwner(
                Thread.currentThread(), "test-owner-only-failure")) {
            long generation = runner.startOwner(
                    OperationProgressSnapshot.indeterminate(
                            OperationProgressSnapshot.Kind.CREATE_WORLD,
                            "CREATING WORLD",
                            "Preparing canonical state",
                            true));
            IllegalStateException failure = new IllegalStateException(
                    "owner restore failed");

            assertTrue(runner.finishFailure(generation, failure));
            assertEquals(OperationProgressSnapshot.TerminalState.FAILED,
                    runner.progress().orElseThrow().terminalState());
            assertFalse(runner.ownerUpdate(
                    generation,
                    OperationProgressUpdate.indeterminate(
                            1, "STALE", "Must be rejected", false)));
            assertTrue(runner.releaseTerminal(generation));
            assertEquals(0, runner.acceptedCount());
        }
    }

    private static long publishedLong(
            OperationProgressSnapshot snapshot, String accessor) {
        Object value = assertDoesNotThrow(() -> snapshot.getClass()
                .getMethod(accessor)
                .invoke(snapshot));
        return (Long) value;
    }

    private static ProductOperationRunner.Completion awaitCompletion(
            ProductOperationRunner runner) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var completion = runner.peekCompletion();
            if (completion.isPresent()) {
                return completion.orElseThrow();
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("operation did not complete");
    }
}
