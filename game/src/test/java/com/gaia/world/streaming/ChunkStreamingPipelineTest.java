package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ChunkStreamingPipelineTest {
    @Test
    void loadGenerationLaneCapsAcceptedAt32AndActiveAt4() throws Exception {
        CountDownLatch active = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-load", 32, 4)) {
            for (int index = 0; index < 32; index++) {
                assertEquals(
                        ChunkWorkScheduler.Admission.ADMITTED,
                        scheduler.submit(blockedWork(index, active, release)));
            }
            assertTrue(active.await(5, TimeUnit.SECONDS));

            ChunkWorkScheduler.Metrics metrics = scheduler.metrics();
            assertEquals(32, metrics.accepted());
            assertEquals(4, metrics.active());
            assertEquals(28, metrics.queued());
            assertEquals(0, metrics.completed());
            assertEquals(
                    ChunkWorkScheduler.Admission.REJECTED_CAPACITY,
                    scheduler.submit(successWork(100L)));
            release.countDown();
        }
    }

    @Test
    void saveLaneCapsAcceptedAt8AndActiveAt1() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-save", 8, 1)) {
            for (int index = 0; index < 8; index++) {
                assertEquals(
                        ChunkWorkScheduler.Admission.ADMITTED,
                        scheduler.submit(blockedWork(index, active, release)));
            }
            assertTrue(active.await(5, TimeUnit.SECONDS));

            ChunkWorkScheduler.Metrics metrics = scheduler.metrics();
            assertEquals(8, metrics.accepted());
            assertEquals(1, metrics.active());
            assertEquals(7, metrics.queued());
            assertEquals(
                    ChunkWorkScheduler.Admission.REJECTED_CAPACITY,
                    scheduler.submit(successWork(80L)));
            release.countDown();
        }
    }

    @Test
    void completedUndrainedResultsContinueToOwnCapacityTokens() throws Exception {
        CountDownLatch completed = new CountDownLatch(32);
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-completed", 32, 4)) {
            for (int index = 0; index < 32; index++) {
                long workId = index + 1L;
                scheduler.submit(new ChunkWorkScheduler.Work(
                        workId,
                        new ChunkKey(index, 0),
                        1L,
                        index,
                        () -> {
                            completed.countDown();
                            return result(workId);
                        }));
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            scheduler.awaitQuiescent(Duration.ofSeconds(5));

            assertEquals(32, scheduler.metrics().accepted());
            assertEquals(32, scheduler.metrics().completed());
            assertEquals(
                    ChunkWorkScheduler.Admission.REJECTED_CAPACITY,
                    scheduler.submit(successWork(100L)));

            assertEquals(1, scheduler.drainCompleted(1).size());
            assertEquals(31, scheduler.metrics().accepted());
            assertEquals(
                    ChunkWorkScheduler.Admission.ADMITTED,
                    scheduler.submit(successWork(100L)));
        }
    }

    @Test
    void rapidTravelCancelsQueuedFarWorkBeforeAdmittingReplacement() throws Exception {
        CountDownLatch active = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-travel", 32, 4)) {
            for (int index = 0; index < 32; index++) {
                scheduler.submit(blockedWork(index, active, release));
            }
            assertTrue(active.await(5, TimeUnit.SECONDS));

            for (long workId = 5L; workId <= 32L; workId++) {
                assertEquals(
                        ChunkWorkScheduler.Cancellation.REMOVED_QUEUED,
                        scheduler.cancel(workId));
            }
            for (int index = 0; index < 28; index++) {
                assertEquals(
                        ChunkWorkScheduler.Admission.ADMITTED,
                        scheduler.submit(successWork(100L + index)));
                assertTrue(scheduler.metrics().accepted() <= 32);
                assertTrue(scheduler.metrics().active() <= 4);
            }
            assertEquals(32, scheduler.metrics().accepted());
            release.countDown();
        }
    }

    @Test
    void queuedReprioritizationRebuildsHeapWithoutChangingWorkIdentity()
            throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "gate-f2-repriority", 4, 1);
        ChunkKey blocker = new ChunkKey(0, 0);
        ChunkKey far = new ChunkKey(5, 0);
        ChunkKey near = new ChunkKey(1, 0);
        try {
            scheduler.submit(observedWork(
                    1L, blocker, 0, executionOrder, active, release));
            assertTrue(active.await(5, TimeUnit.SECONDS));
            scheduler.submit(observedWork(
                    2L, far, 1, executionOrder, null, null));
            scheduler.submit(observedWork(
                    3L, near, 2, executionOrder, null, null));

            assertDoesNotThrow(() -> scheduler.getClass()
                    .getMethod("reprioritizeQueued", List.class)
                    .invoke(scheduler, List.of(near, far)));

            assertEquals(3, scheduler.metrics().accepted());
            assertEquals(1, scheduler.metrics().active());
            assertEquals(2, scheduler.metrics().queued());
            release.countDown();
            scheduler.awaitQuiescent(Duration.ofSeconds(5));

            List<ChunkWorkResult> drained = scheduler.drainCompleted(3);
            assertEquals(List.of(1L, 3L, 2L),
                    drained.stream().map(ChunkWorkResult::workId).toList());
            assertEquals(List.of(1L, 3L, 2L), executionOrder);
            assertEquals(List.of(blocker, near, far),
                    drained.stream().map(ChunkWorkResult::key).toList());
            assertEquals(0, scheduler.metrics().accepted());
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    void invalidReprioritizationLeavesOriginalHeapOrderUntouched() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "gate-f2-invalid-repriority", 4, 1);
        ChunkKey blocker = new ChunkKey(0, 0);
        ChunkKey first = new ChunkKey(1, 0);
        ChunkKey second = new ChunkKey(2, 0);
        try {
            scheduler.submit(observedWork(
                    1L, blocker, 0, executionOrder, active, release));
            assertTrue(active.await(5, TimeUnit.SECONDS));
            scheduler.submit(observedWork(
                    2L, first, 1, executionOrder, null, null));
            scheduler.submit(observedWork(
                    3L, second, 2, executionOrder, null, null));

            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.reprioritizeQueued(List.of(second)));

            release.countDown();
            scheduler.awaitQuiescent(Duration.ofSeconds(5));
            assertEquals(List.of(1L, 2L, 3L),
                    scheduler.drainCompleted(3).stream()
                            .map(ChunkWorkResult::workId).toList());
            assertEquals(List.of(1L, 2L, 3L), executionOrder);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    void runningCancellationTurnsLateCompletionIntoOwnerDiscard() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-cancel", 2, 1)) {
            scheduler.submit(blockedWork(0, active, release));
            assertTrue(active.await(5, TimeUnit.SECONDS));

            assertEquals(
                    ChunkWorkScheduler.Cancellation.MARKED_RUNNING,
                    scheduler.cancel(1L));
            assertEquals(1, scheduler.metrics().accepted());
            release.countDown();
            scheduler.awaitQuiescent(Duration.ofSeconds(5));

            List<ChunkWorkResult> drained = scheduler.drainCompleted(2);
            assertEquals(1, drained.size());
            assertEquals(ChunkWorkResult.Status.CANCELED, drained.get(0).status());
            assertEquals(0, scheduler.metrics().accepted());
        }
    }

    @Test
    void completedButUndrainedSuccessCannotBeRewrittenAsCanceled()
            throws Exception {
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-completed-cancel", 2, 1)) {
            scheduler.submit(successWork(1L));
            scheduler.awaitQuiescent(Duration.ofSeconds(5));

            assertEquals("ALREADY_COMPLETED", scheduler.cancel(1L).name());
            assertEquals(1, scheduler.metrics().accepted());
            assertEquals(
                    ChunkWorkResult.Status.SUCCESS,
                    scheduler.drainCompleted(1).get(0).status());
            assertEquals(0, scheduler.metrics().accepted());
        }
    }

    @Test
    void saveSuccessCannotBeRewrittenAfterDurableOperationReturns()
            throws Exception {
        CountDownLatch durableOperationReturned = new CountDownLatch(1);
        CountDownLatch allowWorkerHandoff = new CountDownLatch(1);
        AtomicBoolean durableCompletion = new AtomicBoolean();
        try (ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task12-durable-save-cancel", 1, 1)) {
            assertEquals(
                    ChunkWorkScheduler.Admission.ADMITTED,
                    scheduler.submit(new ChunkWorkScheduler.Work(
                            1L,
                            new ChunkKey(0, 0),
                            1L,
                            0,
                            ChunkWorkResult.Kind.SAVE,
                            7L,
                            () -> {
                                ChunkWorkResult durableSuccess =
                                        ChunkWorkResult.success(
                                                1L,
                                                new ChunkKey(0, 0),
                                                1L,
                                                 ChunkWorkResult.Kind.SAVE,
                                                 7L);
                                durableCompletion.set(true);
                                durableOperationReturned.countDown();
                                assertTrue(allowWorkerHandoff.await(
                                        5, TimeUnit.SECONDS));
                                return durableSuccess;
                            },
                            durableCompletion::get)));
            assertTrue(durableOperationReturned.await(5, TimeUnit.SECONDS));

            assertEquals(
                    ChunkWorkScheduler.Cancellation.ALREADY_COMPLETED,
                    scheduler.cancel(1L));
            allowWorkerHandoff.countDown();
            scheduler.awaitQuiescent(Duration.ofSeconds(5));

            List<ChunkWorkResult> drained = scheduler.drainCompleted(1);
            assertEquals(1, drained.size());
            assertEquals(ChunkWorkResult.Status.SUCCESS, drained.get(0).status());
            assertEquals(ChunkWorkResult.Kind.SAVE, drained.get(0).kind());
        }
    }

    @Test
    void shutdownClearsQueuedActiveAndCompletedWorkAndTerminatesWorkers()
            throws Exception {
        CountDownLatch active = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task8-close", 8, 2);
        for (int index = 0; index < 8; index++) {
            scheduler.submit(interruptibleWork(index, active, release));
        }
        assertTrue(active.await(5, TimeUnit.SECONDS));

        scheduler.close();

        assertTrue(scheduler.isTerminated());
        assertEquals(0, scheduler.metrics().accepted());
        assertEquals(0, scheduler.metrics().queued());
        assertEquals(0, scheduler.metrics().active());
        assertEquals(0, scheduler.metrics().completed());
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith("task8-close")));
    }

    @Test
    void closeCannotReportSuccessWhileAnOwnedWorkerRemainsAlive()
            throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChunkWorkScheduler scheduler = new ChunkWorkScheduler(
                "task11-uncooperative", 1, 1);
        scheduler.submit(new ChunkWorkScheduler.Work(
                1L,
                new ChunkKey(0, 0),
                1L,
                0,
                () -> {
                    active.countDown();
                    while (release.getCount() != 0L) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return result(1L);
                }));
        assertTrue(active.await(5, TimeUnit.SECONDS));
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, scheduler::close);
            assertTrue(failure.getMessage().contains("worker"));
            assertFalse(scheduler.isTerminated());
            assertEquals(1, scheduler.liveWorkerCount());
            assertThrows(IllegalStateException.class, scheduler::close,
                    "a repeated close must not report success while the worker is live");
        } finally {
            release.countDown();
            for (int spin = 0;
                    spin < 1_000_000 && scheduler.liveWorkerCount() != 0;
                    spin++) {
                Thread.yield();
            }
            assertEquals(0, scheduler.liveWorkerCount(),
                    "the deliberately uncooperative fixture must not leak its daemon");
        }
    }

    private static ChunkWorkScheduler.Work blockedWork(
            int index, CountDownLatch active, CountDownLatch release) {
        long workId = index + 1L;
        return new ChunkWorkScheduler.Work(
                workId,
                new ChunkKey(index, 0),
                1L,
                index,
                () -> {
                    active.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("blocked work was not released");
                    }
                    return result(workId);
                });
    }

    private static ChunkWorkScheduler.Work observedWork(
            long workId,
            ChunkKey key,
            int priority,
            List<Long> executionOrder,
            CountDownLatch active,
            CountDownLatch release) {
        return new ChunkWorkScheduler.Work(
                workId,
                key,
                1L,
                priority,
                () -> {
                    executionOrder.add(workId);
                    if (active != null) {
                        active.countDown();
                    }
                    if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("blocked work was not released");
                    }
                    return ChunkWorkResult.success(
                            workId,
                            key,
                            1L,
                            ChunkWorkResult.Kind.LOAD_GENERATE,
                            0L);
                });
    }

    private static ChunkWorkScheduler.Work interruptibleWork(
            int index, CountDownLatch active, CountDownLatch release) {
        long workId = index + 1L;
        return new ChunkWorkScheduler.Work(
                workId,
                new ChunkKey(index, 0),
                1L,
                index,
                () -> {
                    active.countDown();
                    release.await();
                    return result(workId);
                });
    }

    private static ChunkWorkScheduler.Work successWork(long workId) {
        return new ChunkWorkScheduler.Work(
                workId,
                new ChunkKey(Math.toIntExact(workId - 1L), 0),
                1L,
                Math.toIntExact(workId),
                () -> result(workId));
    }

    private static ChunkWorkResult result(long workId) {
        return ChunkWorkResult.success(
                workId,
                new ChunkKey(Math.toIntExact(workId - 1L), 0),
                1L,
                ChunkWorkResult.Kind.LOAD_GENERATE,
                0L);
    }
}
