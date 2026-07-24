package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockMutationReentrancyException;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.BeforeBlockChangedEvent;
import com.overlord.interaction.api.ChunkDirtyEvent;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.ItemUseContext;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DefaultWorldMutationServiceTest {
    private static final ResourceLocation AIR =
            ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT =
            ResourceLocation.parse("gaia:dirt");
    private static final DirtyChunkRevision CENTER_REVISION =
            new DirtyChunkRevision(new ChunkKey(0, 0), 7);
    private static final DirtyChunkRevision EAST_REVISION =
            new DirtyChunkRevision(new ChunkKey(1, 0), 11);

    @Test
    void appliesInBeforeCompareAndSetChangedDirtyOrderWithExactRevisions() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.outcome =
                applied(
                        STONE,
                        List.of(CENTER_REVISION, EAST_REVISION));
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        BlockChangeRequest request = request(15, 4, 3);

        BlockChangeResult result = service.changeBlock(request);

        assertEquals(BlockChangeResult.Status.APPLIED, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(STONE), result.observedBlock());
        assertEquals(
                List.of(CENTER_REVISION, EAST_REVISION),
                result.dirtiedChunks());
        assertEquals(AIR, access.block);
        assertEquals(1, access.writeAttempts);
        assertEquals(1, access.successfulWrites);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
        assertEquals(
                new BeforeBlockChangedEvent(request, STONE),
                events.beforeEvent);
        assertEquals(
                new BlockChangedEvent(request, STONE, AIR),
                events.changedEvent);
        assertEquals(
                new ChunkDirtyEvent(
                        request,
                        List.of(CENTER_REVISION, EAST_REVISION)),
                events.dirtyEvent);
    }

    @Test
    void boundaryMutationPublishesOnlyRepositoryReportedLoadedChunk() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.outcome =
                applied(STONE, List.of(CENTER_REVISION));
        RecordingPublisher events = new RecordingPublisher(order);

        BlockChangeResult result =
                service(access, events)
                        .changeBlock(request(15, 4, 3));

        assertEquals(
                List.of(CENTER_REVISION),
                result.dirtiedChunks());
        assertEquals(Set.of(new ChunkKey(0, 0)), result.dirtyChunks());
        assertEquals(
                List.of(CENTER_REVISION),
                events.dirtyEvent.dirtiedChunks());
        assertFalse(
                result.dirtyChunks().contains(new ChunkKey(1, 0)),
                "service must not add the theoretical east neighbor");
    }

    @Test
    void cancellationRejectsWithoutWriteOrSuccessEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        events.decision = BlockChangeDecision.CANCEL;
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(BlockChangeResult.Status.CANCELLED, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(STONE), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(0, access.writeAttempts);
        assertEquals(STONE, access.block);
        assertEquals(List.of("before"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void revalidatesExpectedBlockAfterBeforeChangeBeforeOuterWrite() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.known.add(DIRT);
        RecordingPublisher events = new RecordingPublisher(order);
        events.beforeAction =
                () ->
                        access.forceExternalBlockChangeForTest(
                                DIRT);
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(BlockChangeResult.Status.CONFLICT, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(DIRT), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(2, access.reads);
        assertEquals(0, access.writeAttempts);
        assertEquals(0, access.successfulWrites);
        assertTrue(access.writtenBlocks.isEmpty());
        assertEquals(DIRT, access.block);
        assertEquals(List.of("before"), order);
        assertEquals(
                new BeforeBlockChangedEvent(request, STONE),
                events.beforeEvent);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void staleExpectedBlockRejectsAsConflictWithoutWriteOrEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, AIR);
        RecordingPublisher events = new RecordingPublisher(order);
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(BlockChangeResult.Status.CONFLICT, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(AIR), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(0, access.writeAttempts);
        assertEquals(AIR, access.block);
        assertTrue(order.isEmpty());
        assertEquals(null, events.beforeEvent);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void matchingReplacementRejectsAsNoChangeWithoutWriteOrEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        BlockChangeRequest request =
                request(2, 4, 3, STONE, STONE);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(BlockChangeResult.Status.NO_CHANGE, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(STONE), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(0, access.writeAttempts);
        assertEquals(STONE, access.block);
        assertTrue(order.isEmpty());
        assertEquals(null, events.beforeEvent);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void outOfBoundsRejectsBeforeWorldReadWriteOrEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.withinBounds = false;
        RecordingPublisher events = new RecordingPublisher(order);
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(
                BlockChangeResult.Status.OUT_OF_BOUNDS,
                result.status());
        assertSame(request, result.request());
        assertEquals(Optional.empty(), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(1, access.boundsChecks);
        assertEquals(0, access.knownChecks);
        assertEquals(0, access.reads);
        assertEquals(0, access.writeAttempts);
        assertTrue(order.isEmpty());
        assertEquals(null, events.beforeEvent);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void unknownExpectedOrReplacementRejectsBeforeReadWriteOrEvents() {
        List<String> expectedOrder = new ArrayList<>();
        RecordingAccess expectedAccess =
                new RecordingAccess(expectedOrder, STONE);
        expectedAccess.known.remove(STONE);
        RecordingPublisher expectedEvents =
                new RecordingPublisher(expectedOrder);
        BlockChangeRequest expectedRequest = request(2, 4, 3);

        BlockChangeResult unknownExpected =
                service(expectedAccess, expectedEvents)
                        .changeBlock(expectedRequest);

        assertEquals(
                BlockChangeResult.Status.UNKNOWN_BLOCK,
                unknownExpected.status());
        assertSame(expectedRequest, unknownExpected.request());
        assertEquals(
                Optional.empty(), unknownExpected.observedBlock());
        assertTrue(unknownExpected.dirtyChunks().isEmpty());
        assertEquals(0, expectedAccess.reads);
        assertEquals(0, expectedAccess.writeAttempts);
        assertTrue(expectedOrder.isEmpty());
        assertEquals(null, expectedEvents.beforeEvent);
        assertEquals(null, expectedEvents.changedEvent);
        assertEquals(null, expectedEvents.dirtyEvent);

        List<String> replacementOrder = new ArrayList<>();
        RecordingAccess replacementAccess =
                new RecordingAccess(replacementOrder, STONE);
        replacementAccess.known.remove(AIR);
        RecordingPublisher replacementEvents =
                new RecordingPublisher(replacementOrder);
        BlockChangeRequest replacementRequest = request(2, 4, 3);

        BlockChangeResult unknownReplacement =
                service(replacementAccess, replacementEvents)
                        .changeBlock(replacementRequest);

        assertEquals(
                BlockChangeResult.Status.UNKNOWN_BLOCK,
                unknownReplacement.status());
        assertSame(replacementRequest, unknownReplacement.request());
        assertEquals(
                Optional.empty(), unknownReplacement.observedBlock());
        assertTrue(unknownReplacement.dirtyChunks().isEmpty());
        assertEquals(0, replacementAccess.reads);
        assertEquals(0, replacementAccess.writeAttempts);
        assertTrue(replacementOrder.isEmpty());
        assertEquals(null, replacementEvents.beforeEvent);
        assertEquals(null, replacementEvents.changedEvent);
        assertEquals(null, replacementEvents.dirtyEvent);
    }

    @Test
    void conflictOutcomeRejectsWithoutSuccessEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.outcome =
                outcome(
                        BlockWorldMutationOutcome.Status.CONFLICT,
                        DIRT);
        RecordingPublisher events = new RecordingPublisher(order);
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(BlockChangeResult.Status.CONFLICT, result.status());
        assertSame(request, result.request());
        assertEquals(Optional.of(DIRT), result.observedBlock());
        assertTrue(result.dirtyChunks().isEmpty());
        assertEquals(1, access.writeAttempts);
        assertEquals(0, access.successfulWrites);
        assertEquals(STONE, access.block);
        assertEquals(
                List.of("before", "compare-and-set"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void noChangeOutcomeMapsWithoutSuccessEventsOrDirtyRevisions() {
        assertNonAppliedOutcomeMaps(
                BlockWorldMutationOutcome.Status.NO_CHANGE,
                BlockChangeResult.Status.NO_CHANGE);
    }

    @Test
    void outOfBoundsOutcomeMapsWithoutSuccessEventsOrDirtyRevisions() {
        assertNonAppliedOutcomeMaps(
                BlockWorldMutationOutcome.Status.OUT_OF_BOUNDS,
                BlockChangeResult.Status.OUT_OF_BOUNDS);
    }

    @Test
    void inconsistentAppliedOutcomeReportsCommittedWithoutSuccessEvents() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.outcome =
                applied(AIR, List.of(CENTER_REVISION));
        RecordingPublisher events = new RecordingPublisher(order);

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(
                                                request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertInstanceOf(
                IllegalStateException.class, failure.getCause());
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of("before", "compare-and-set"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void recursiveBeforeMutationFailsBeforeNestedWorldAccess() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        events.beforeAction =
                () -> service.changeBlock(request(3, 4, 3));

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () -> service.changeBlock(request(2, 4, 3)));

        assertFalse(failure.mutationApplied());
        assertInstanceOf(
                BlockMutationReentrancyException.class,
                failure.getCause());
        assertEquals(1, access.boundsChecks);
        assertEquals(2, access.knownChecks);
        assertEquals(1, access.reads);
        assertEquals(0, access.writeAttempts);
        assertEquals(List.of("before"), order);
    }

    @Test
    void beforeGuardResetsAfterCancellation() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        events.decision = BlockChangeDecision.CANCEL;

        assertEquals(
                BlockChangeResult.Status.CANCELLED,
                service.changeBlock(request(2, 4, 3)).status());

        events.decision = BlockChangeDecision.ALLOW;
        assertEquals(
                BlockChangeResult.Status.APPLIED,
                service.changeBlock(request(2, 4, 3)).status());
        assertEquals(1, access.successfulWrites);
    }

    @Test
    void beforeGuardResetsAfterSubscriberException() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        events.beforeFailure =
                new IllegalStateException("before listener");

        assertThrows(
                BlockChangeDispatchException.class,
                () -> service.changeBlock(request(2, 4, 3)));

        events.beforeFailure = null;
        assertEquals(
                BlockChangeResult.Status.APPLIED,
                service.changeBlock(request(2, 4, 3)).status());
        assertEquals(1, access.successfulWrites);
    }

    @Test
    void beforeChangeFailureReportsUncommittedMutationAndDoesNotWrite() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        RuntimeException listenerFailure =
                new IllegalStateException("before listener");
        events.beforeFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertFalse(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, access.writeAttempts);
        assertEquals(STONE, access.block);
        assertEquals(List.of("before"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void beforeChangeErrorReportsUncommittedMutationAndResetsGuard() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        AssertionError listenerFailure =
                new AssertionError("before listener");
        events.beforeFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () -> service.changeBlock(request(2, 4, 3)));

        assertFalse(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, access.writeAttempts);
        assertEquals(STONE, access.block);
        assertEquals(List.of("before"), order);

        events.beforeFailure = null;
        assertEquals(
                BlockChangeResult.Status.APPLIED,
                service.changeBlock(request(2, 4, 3)).status());
        assertEquals(1, access.successfulWrites);
    }

    @Test
    void nullBeforeChangeDecisionReportsUncommittedMutation() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        events.decision = null;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertFalse(failure.mutationApplied());
        assertInstanceOf(
                NullPointerException.class, failure.getCause());
        assertEquals(0, access.writeAttempts);
        assertEquals(STONE, access.block);
        assertEquals(List.of("before"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    @Test
    void changedFailureStillAttemptsDirtyEventAndReportsCommittedMutation() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        RuntimeException listenerFailure =
                new IllegalStateException("changed listener");
        events.changedFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
        assertEquals(
                Set.of(new ChunkKey(0, 0)),
                events.dirtyEvent.dirtyChunks());
    }

    @Test
    void changedErrorStillAttemptsDirtyEventAndReportsCommittedMutation() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        AssertionError listenerFailure =
                new AssertionError("changed listener");
        events.changedFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
        assertEquals(
                Set.of(new ChunkKey(0, 0)),
                events.dirtyEvent.dirtyChunks());
    }

    @Test
    void dirtyFailureReportsCommittedMutationAfterChangedEvent() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        RuntimeException listenerFailure =
                new IllegalArgumentException("dirty listener");
        events.dirtyFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
        assertEquals(STONE, events.changedEvent.previousBlock());
        assertEquals(AIR, events.changedEvent.currentBlock());
    }

    @Test
    void dirtyErrorReportsCommittedMutationAfterChangedEvent() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        AssertionError listenerFailure =
                new AssertionError("dirty listener");
        events.dirtyFailure = listenerFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(listenerFailure, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
        assertEquals(STONE, events.changedEvent.previousBlock());
        assertEquals(AIR, events.changedEvent.currentBlock());
    }

    @Test
    void bothPostChangeFailuresKeepFirstAndSuppressSecond() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        RuntimeException changedFailure =
                new IllegalStateException("changed listener");
        RuntimeException dirtyFailure =
                new IllegalArgumentException("dirty listener");
        events.changedFailure = changedFailure;
        events.dirtyFailure = dirtyFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(changedFailure, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertSame(
                dirtyFailure,
                failure.getCause().getSuppressed()[0]);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
    }

    @Test
    void mixedPostChangeFailuresKeepFirstAndSuppressSecond() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        RuntimeException changedFailure =
                new IllegalStateException("changed listener");
        AssertionError dirtyFailure =
                new AssertionError("dirty listener");
        events.changedFailure = changedFailure;
        events.dirtyFailure = dirtyFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(changedFailure, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertSame(
                dirtyFailure,
                failure.getCause().getSuppressed()[0]);
        assertEquals(1, access.successfulWrites);
        assertEquals(AIR, access.block);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
    }

    @Test
    void sharedPostChangeFailureIsNotSelfSuppressed() {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        AssertionError sharedFailure =
                new AssertionError("shared listener failure");
        events.changedFailure = sharedFailure;
        events.dirtyFailure = sharedFailure;

        BlockChangeDispatchException failure =
                assertThrows(
                        BlockChangeDispatchException.class,
                        () ->
                                service(access, events)
                                        .changeBlock(request(2, 4, 3)));

        assertTrue(failure.mutationApplied());
        assertSame(sharedFailure, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(1, access.successfulWrites);
        assertEquals(
                List.of(
                        "before",
                        "compare-and-set",
                        "changed",
                        "dirty"),
                order);
    }

    @Test
    void workerThreadGuardRunsBeforeAnyWorldOrEventBoundary() throws Exception {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        DefaultWorldMutationService service = service(access, events);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            service.changeBlock(
                                                                    request(
                                                                            2,
                                                                            4,
                                                                            3)))
                                            .get());

            assertInstanceOf(
                    IllegalStateException.class, failure.getCause());
            assertEquals(0, access.boundsChecks);
            assertEquals(0, access.knownChecks);
            assertEquals(0, access.reads);
            assertEquals(0, access.writeAttempts);
            assertTrue(order.isEmpty());
            assertEquals(null, events.beforeEvent);
            assertEquals(null, events.changedEvent);
            assertEquals(null, events.dirtyEvent);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static BlockChangeRequest request(int x, int y, int z) {
        return request(x, y, z, STONE, AIR);
    }

    private static BlockChangeRequest request(
            int x,
            int y,
            int z,
            ResourceLocation expected,
            ResourceLocation replacement) {
        return new BlockChangeRequest(
                new ItemUseContext(
                        new EntityRef(1),
                        BodySlot.RIGHT_HAND,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.PRIMARY,
                        2,
                        20),
                x,
                y,
                z,
                expected,
                replacement);
    }

    private static BlockWorldMutationOutcome applied(
            ResourceLocation observed,
            List<DirtyChunkRevision> revisions) {
        return new BlockWorldMutationOutcome(
                BlockWorldMutationOutcome.Status.APPLIED,
                observed,
                revisions);
    }

    private static BlockWorldMutationOutcome outcome(
            BlockWorldMutationOutcome.Status status,
            ResourceLocation observed) {
        return new BlockWorldMutationOutcome(
                status, observed, List.of());
    }

    private static void assertNonAppliedOutcomeMaps(
            BlockWorldMutationOutcome.Status worldStatus,
            BlockChangeResult.Status resultStatus) {
        List<String> order = new ArrayList<>();
        RecordingAccess access = new RecordingAccess(order, STONE);
        access.outcome = outcome(worldStatus, STONE);
        RecordingPublisher events = new RecordingPublisher(order);
        BlockChangeRequest request = request(2, 4, 3);

        BlockChangeResult result =
                service(access, events).changeBlock(request);

        assertEquals(resultStatus, result.status());
        assertEquals(Optional.of(STONE), result.observedBlock());
        assertTrue(result.dirtiedChunks().isEmpty());
        assertEquals(1, access.writeAttempts);
        assertEquals(0, access.successfulWrites);
        assertEquals(STONE, access.block);
        assertEquals(
                List.of("before", "compare-and-set"), order);
        assertEquals(null, events.changedEvent);
        assertEquals(null, events.dirtyEvent);
    }

    private static DefaultWorldMutationService service(
            RecordingAccess access,
            RecordingPublisher events) {
        return new DefaultWorldMutationService(
                MainThreadGuard.captureCurrentThread(),
                access,
                events);
    }

    private static final class RecordingAccess
            implements BlockWorldAccess {
        private final List<String> order;
        private final Set<ResourceLocation> known =
                new HashSet<>(Set.of(AIR, STONE));
        private ResourceLocation block;
        private int boundsChecks;
        private int knownChecks;
        private int reads;
        private int writeAttempts;
        private int successfulWrites;
        private final List<ResourceLocation> writtenBlocks =
                new ArrayList<>();
        private boolean withinBounds = true;
        private BlockWorldMutationOutcome outcome;

        private RecordingAccess(
                List<String> order, ResourceLocation block) {
            this.order = order;
            this.block = block;
            outcome =
                    applied(
                            block,
                            List.of(CENTER_REVISION));
        }

        @Override
        public boolean isWithinBounds(int x, int y, int z) {
            boundsChecks++;
            return withinBounds && y >= 0 && y < 256;
        }

        @Override
        public boolean isKnownBlock(ResourceLocation candidate) {
            knownChecks++;
            return known.contains(candidate);
        }

        @Override
        public ResourceLocation blockAt(int x, int y, int z) {
            reads++;
            return block;
        }

        @Override
        public BlockWorldMutationOutcome compareAndSetBlock(
                int x,
                int y,
                int z,
                ResourceLocation expected,
                ResourceLocation replacement) {
            order.add("compare-and-set");
            writeAttempts++;
            writtenBlocks.add(replacement);
            if (outcome.status()
                    == BlockWorldMutationOutcome.Status.APPLIED) {
                block = replacement;
                successfulWrites++;
            }
            return outcome;
        }

        private void forceExternalBlockChangeForTest(
                ResourceLocation replacement) {
            block = replacement;
        }
    }

    private static final class RecordingPublisher
            implements BlockChangeEventPublisher {
        private final List<String> order;
        private BlockChangeDecision decision =
                BlockChangeDecision.ALLOW;
        private Throwable beforeFailure;
        private Throwable changedFailure;
        private Throwable dirtyFailure;
        private Runnable beforeAction;
        private BeforeBlockChangedEvent beforeEvent;
        private BlockChangedEvent changedEvent;
        private ChunkDirtyEvent dirtyEvent;

        private RecordingPublisher(List<String> order) {
            this.order = order;
        }

        @Override
        public BlockChangeDecision beforeChange(
                BeforeBlockChangedEvent event) {
            beforeEvent = event;
            order.add("before");
            if (beforeFailure != null) {
                throwUnchecked(beforeFailure);
            }
            if (beforeAction != null) {
                beforeAction.run();
            }
            return decision;
        }

        @Override
        public void blockChanged(BlockChangedEvent event) {
            changedEvent = event;
            order.add("changed");
            if (changedFailure != null) {
                throwUnchecked(changedFailure);
            }
        }

        @Override
        public void chunksDirty(ChunkDirtyEvent event) {
            dirtyEvent = event;
            order.add("dirty");
            if (dirtyFailure != null) {
                throwUnchecked(dirtyFailure);
            }
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError(
                    "test publisher accepts only unchecked failures",
                    failure);
        }
    }
}
