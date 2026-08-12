package com.gaia.save.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.GameSessionConfig;
import com.gaia.interaction.GameMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SaveCoordinatorTest {
    private static final Instant MODIFIED = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void successCapturesAndWritesOnceThenMarksOnlyTheExactCapturedRevision() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L)));
        harness.makeReady();
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Instant> writtenAt = new AtomicReference<>();
        SaveGameManifest manifest = GameSessionSaveLifecycleTest.manifest();
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) -> {
            writes.incrementAndGet();
            writtenAt.set(modified);
            assertEquals(snapshot, captured);
            return SaveWriteResult.success(manifest);
        });

        GameSessionSaveResult result = coordinator.save(harness.session(), MODIFIED);

        assertEquals(GameSessionSaveResult.Status.SUCCESS, result.status());
        assertEquals(manifest, result.committedManifest().orElseThrow());
        assertEquals(1, writes.get());
        assertEquals(MODIFIED, writtenAt.get());
        assertEquals(List.of(5L), harness.runtime().marked().stream()
                .map(revision -> revision.value()).toList());
        harness.session().close();
    }

    @Test
    void writeFailurePreservesTheCheckpointAndLiveReadySessionForRetry() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(
                        GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L),
                        GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 6L)));
        harness.makeReady();
        AtomicInteger attempt = new AtomicInteger();
        SaveDiagnostic diagnostic = SaveDiagnostic.of(
                "save-write.injected", "Injected save failure");
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) ->
                attempt.getAndIncrement() == 0
                        ? SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest())
                        : SaveWriteResult.failed(diagnostic));

        assertEquals(GameSessionSaveResult.Status.SUCCESS,
                coordinator.save(harness.session(), MODIFIED).status());
        GameSessionSaveResult failed = coordinator.save(harness.session(), MODIFIED.plusSeconds(1));

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, failed.status());
        assertEquals(List.of(diagnostic), failed.diagnostics());
        assertEquals(List.of(5L), harness.runtime().marked().stream()
                .map(revision -> revision.value()).toList());
        assertFalse(harness.session().state().name().equals("CLOSED"));
        harness.session().close();
    }

    @Test
    void rejectedCaptureNeverCallsTheWriterOrPublishesAManifest() {
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(com.gaia.session.SessionSaveCaptureResult.pendingTransaction()));
        harness.makeReady();
        AtomicInteger writes = new AtomicInteger();
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) -> {
            writes.incrementAndGet();
            return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
        });

        GameSessionSaveResult result = coordinator.save(harness.session(), MODIFIED);

        assertEquals(GameSessionSaveResult.Status.CAPTURE_REJECTED, result.status());
        assertTrue(result.committedManifest().isEmpty());
        assertEquals(0, writes.get());
        harness.session().close();
    }

}
