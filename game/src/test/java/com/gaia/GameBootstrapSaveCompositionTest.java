package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionState;
import com.gaia.session.LoadWorldRequest;
import com.gaia.session.NewWorldRequest;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.shell.ProductLoop;
import com.overlord.core.input.MouseDelta;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameBootstrapSaveCompositionTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void realCompositionCreatesLoadsRefreshesAndDeletesOneStableWorldSlot() {
        NewWorldRequest request = new NewWorldRequest(
                GameSessionSaveLifecycleTest.ID, "New World", 12345L);
        AtomicReference<SaveGameSnapshot> restored = new AtomicReference<>();
        GameBootstrap.SaveComposition composition = GameBootstrap.composeSaveLoad(
                temporaryRoot.resolve("saves"),
                (createdRequest, config) -> {
                    assertEquals(request, createdRequest);
                    assertEquals(12345L, config.seed());
                    assertEquals(2, config.chunkRadius());
                    return new PersistenceSession(GameSessionSaveLifecycleTest.snapshot());
                },
                snapshot -> {
                    restored.set(snapshot);
                    return new PersistenceSession(snapshot);
                },
                () -> new GameSessionConfig(999L, 2, GameMode.SURVIVAL, false),
                () -> Instant.parse("2026-08-12T01:00:00Z"),
                () -> request.saveGameId());
        ProductLoop.PersistenceServices services = composition.persistenceServices();

        GameSession created = services.sessions().newWorld(request);
        created.pollLoad();
        assertEquals(GameSessionState.READY, created.state());
        services.worldSlots().refresh();
        assertEquals(
                request.saveGameId(),
                services.worldSlots().snapshot().rows().get(0).id());
        created.close();

        GameSession loaded = services.sessions().loadWorld(
                new LoadWorldRequest(request.saveGameId()));
        loaded.pollLoad();
        assertEquals(GameSessionState.READY, loaded.state());
        assertEquals(request.saveGameId(),
                restored.get().metadata().saveGameId());
        loaded.close();

        SaveDeleteResult deleted = services.worldSlotOperations()
                .delete(request.saveGameId());
        assertTrue(deleted.status() == SaveDeleteResult.Status.SUCCESS
                || deleted.status()
                        == SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING);
        services.worldSlots().refresh();
        assertTrue(services.worldSlots().snapshot().rows().isEmpty());
    }

    private static final class PersistenceSession implements GameSession {
        private final SaveGameSnapshot snapshot;
        private GameSessionState state = GameSessionState.LOADING;

        private PersistenceSession(SaveGameSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() { state = GameSessionState.READY; }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            throw new AssertionError("gameplay not expected");
        }
        @Override public GameSessionFrame capturePaused() {
            throw new AssertionError("pause capture not expected");
        }
        @Override public SessionSaveCaptureResult captureSave() {
            return GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 0L);
        }
        @Override public void markSaved(SessionPersistenceRevision revision) {}
        @Override public boolean hasUnsavedChanges() { return false; }
        @Override public void discardFixedTime() {}
        @Override public void close() { state = GameSessionState.CLOSED; }
    }
}
