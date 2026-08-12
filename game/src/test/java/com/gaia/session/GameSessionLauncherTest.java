package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.store.SaveWriteResult;
import com.overlord.core.input.MouseDelta;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameSessionLauncherTest {
    @Test
    void newWorldWaitsForReadinessThenCommitsInitialSaveBeforePublishingReady() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        FakeSession raw = new FakeSession(GameSessionSaveLifecycleTest.snapshot());
        NewWorldRequest request = new NewWorldRequest(
                GameSessionSaveLifecycleTest.ID, "New World", 12345L);
        GameSessionLauncher launcher = launcher(
                (createdRequest, config) -> {
                    creates.incrementAndGet();
                    assertSame(request, createdRequest);
                    assertEquals(12345L, config.seed());
                    return raw;
                },
                snapshot -> { throw new AssertionError("restore not expected"); },
                id -> { throw new AssertionError("load not expected"); },
                id -> (snapshot, modified) -> {
                    writes.incrementAndGet();
                    return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
                });

        GameSession session = launcher.newWorld(request);
        assertEquals(GameSessionState.LOADING, session.state());
        session.pollLoad();
        assertEquals(GameSessionState.LOADING, session.state());
        assertEquals(0, writes.get());

        raw.complete = true;
        session.pollLoad();

        assertEquals(GameSessionState.READY, session.state());
        assertEquals(1, creates.get());
        assertEquals(1, writes.get());
        assertFalse(session.hasUnsavedChanges());
        session.close();
    }

    @Test
    void initialSaveRejectsAnyRequestIdentityMismatchBeforeChoosingAStore() {
        List<NewWorldRequest> mismatchedRequests = List.of(
                new NewWorldRequest(
                        com.gaia.save.format.SaveGameId.parse(
                                "00000000-0000-0000-0000-000000000015"),
                        "New World",
                        12345L),
                new NewWorldRequest(
                        GameSessionSaveLifecycleTest.ID,
                        "Requested World",
                        12345L),
                new NewWorldRequest(
                        GameSessionSaveLifecycleTest.ID,
                        "New World",
                        77L));

        for (NewWorldRequest request : mismatchedRequests) {
            AtomicInteger writes = new AtomicInteger();
            FakeSession raw = new FakeSession(GameSessionSaveLifecycleTest.snapshot());
            raw.complete = true;
            GameSessionLauncher launcher = launcher(
                    (createdRequest, config) -> {
                        assertSame(request, createdRequest);
                        assertEquals(request.seed(), config.seed());
                        return raw;
                    },
                    snapshot -> { throw new AssertionError("restore not expected"); },
                    id -> { throw new AssertionError("load not expected"); },
                    id -> (snapshot, modified) -> {
                        writes.incrementAndGet();
                        return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
                    });

            GameSession session = launcher.newWorld(request);
            session.pollLoad();

            assertEquals(GameSessionState.FAILED, session.state(), request.toString());
            assertEquals(1, raw.closeCalls, request.toString());
            assertEquals(0, writes.get(), request.toString());
        }
    }

    @Test
    void initialSaveFailureClosesTheFreshSessionAndNeverPublishesReady() {
        FakeSession raw = new FakeSession(GameSessionSaveLifecycleTest.snapshot());
        raw.complete = true;
        GameSessionLauncher launcher = launcher(
                (request, config) -> raw,
                snapshot -> { throw new AssertionError("restore not expected"); },
                id -> { throw new AssertionError("load not expected"); },
                id -> (snapshot, modified) -> SaveWriteResult.failed(
                        SaveDiagnostic.of("save-write.injected", "Injected initial failure")));
        GameSession session = launcher.newWorld(new NewWorldRequest(
                GameSessionSaveLifecycleTest.ID, "New World", 12345L));

        session.pollLoad();

        assertEquals(GameSessionState.FAILED, session.state());
        assertEquals(1, raw.closeCalls);
        assertThrows(IllegalStateException.class, session::captureSave);
    }

    @Test
    void loadValidatesIdUsesRestoreOnlyAndEveryLaunchOwnsAFreshSession() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        GameSessionLauncher launcher = launcher(
                (request, config) -> {
                    creates.incrementAndGet();
                    throw new AssertionError("generation path not expected");
                },
                snapshot -> {
                    restores.incrementAndGet();
                    FakeSession session = new FakeSession(snapshot);
                    session.complete = true;
                    return session;
                },
                id -> SaveArchiveReadResult.valid(
                        GameSessionSaveLifecycleTest.snapshot(), List.of()),
                id -> (snapshot, modified) -> {
                    throw new AssertionError("load must not rewrite the archive");
                });

        GameSession first = launcher.loadWorld(new LoadWorldRequest(
                GameSessionSaveLifecycleTest.ID));
        GameSession second = launcher.loadWorld(new LoadWorldRequest(
                GameSessionSaveLifecycleTest.ID));
        first.pollLoad();
        second.pollLoad();

        assertEquals(0, creates.get());
        assertEquals(2, restores.get());
        assertNotSame(first, second);
        assertEquals(GameSessionState.READY, first.state());
        assertEquals(GameSessionState.READY, second.state());
        assertFalse(first.hasUnsavedChanges());
        assertFalse(second.hasUnsavedChanges());
        first.close();
        second.close();
    }

    @Test
    void invalidOrWrongIdentityArchiveCannotCreateARestoreSession() {
        AtomicInteger restores = new AtomicInteger();
        GameSessionLauncher corrupt = launcher(
                (request, config) -> { throw new AssertionError("not expected"); },
                snapshot -> { restores.incrementAndGet(); return new FakeSession(snapshot); },
                id -> SaveArchiveReadResult.corrupt(
                        SaveDiagnostic.of("save-read.corrupt", "Corrupt save")),
                id -> (snapshot, modified) -> { throw new AssertionError("not expected"); });
        assertThrows(IllegalStateException.class, () -> corrupt.loadWorld(
                new LoadWorldRequest(GameSessionSaveLifecycleTest.ID)));
        assertEquals(0, restores.get());
    }

    private static GameSessionLauncher launcher(
            GameSessionLauncher.NewSessionFactory create,
            GameSessionLauncher.RestoreSessionFactory restore,
            GameSessionLauncher.SaveLoader load,
            SaveCoordinator.SaveTargetFactory stores) {
        return new GameSessionLauncher(
                create,
                restore,
                load,
                new SaveCoordinator(stores),
                request -> new GameSessionConfig(
                        request.seed(), 2, GameMode.SURVIVAL, false),
                () -> Instant.parse("2026-08-12T01:00:00Z"));
    }

    private static final class FakeSession implements GameSession {
        private final com.gaia.save.snapshot.SaveGameSnapshot snapshot;
        private final SessionPersistenceClock clock = SessionPersistenceClock.restored(0L, 0L);
        private GameSessionState state = GameSessionState.LOADING;
        private boolean complete;
        private boolean saved;
        private int closeCalls;

        private FakeSession(com.gaia.save.snapshot.SaveGameSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() { if (complete) state = GameSessionState.READY; }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) { throw new AssertionError(); }
        @Override public GameSessionFrame capturePaused() { throw new AssertionError(); }
        @Override public SessionSaveCaptureResult captureSave() {
            if (state != GameSessionState.READY) throw new IllegalStateException();
            return clock.captured(snapshot, 0L);
        }
        @Override public void markSaved(SessionPersistenceRevision revision) { saved = true; }
        @Override public boolean hasUnsavedChanges() {
            if (state != GameSessionState.READY) throw new IllegalStateException();
            return !saved;
        }
        @Override public void discardFixedTime() {}
        @Override public void close() { closeCalls++; state = GameSessionState.CLOSED; }
    }

}
