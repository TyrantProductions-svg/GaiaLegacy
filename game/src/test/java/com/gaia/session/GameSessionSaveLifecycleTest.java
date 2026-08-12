package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class GameSessionSaveLifecycleTest {
    public static final SaveGameId ID = SaveGameId.parse(
            "00000000-0000-0000-0000-000000000014");

    @Test
    void dirtyStateComparesTheLivePersistenceRevisionToTheSavedCheckpoint() {
        RevisionRuntime runtime = new RevisionRuntime(snapshot(), 0L);
        GameSession session = new GameSessionFactory(
                (config, world, shutdown) -> runtime)
                .create(new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false));
        session.pollLoad();

        assertTrue(session.hasUnsavedChanges());
        SessionSaveCaptureResult initial = session.captureSave();
        session.markSaved(initial.persistenceRevision().orElseThrow());
        assertFalse(session.hasUnsavedChanges());

        runtime.revision = 1L;
        assertTrue(session.hasUnsavedChanges());
        SessionSaveCaptureResult changed = session.captureSave();
        session.markSaved(changed.persistenceRevision().orElseThrow());
        assertFalse(session.hasUnsavedChanges());

        session.close();
        assertThrows(IllegalStateException.class, session::hasUnsavedChanges);
    }

    public static SaveGameSnapshot snapshot() {
        int height = 2;
        byte[] blocks = new byte[16 * height * 16];
        EntityRef owner = new EntityRef(0);
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-test",
                        ID,
                        "New World",
                        Instant.parse("2026-08-12T00:00:00Z"),
                        12345L,
                        "v1",
                        "a".repeat(64),
                        2,
                        height,
                        Optional.empty()),
                0L,
                new ChunkRepositorySnapshot(
                        height,
                        1L,
                        List.of(ChunkSnapshot.of(
                                new ChunkKey(0, 0), 1L, height, blocks))),
                new PlayerSaveSnapshot(
                        owner, 0.0, 1.0, 0.0,
                        0.0, 0.0, 0.0,
                        -90.0, 0.0, GameMode.SURVIVAL, false),
                new InventorySaveSnapshot(
                        owner, Map.of(), BodySlot.LEFT_HAND, false, 0L),
                new WorldItemsSaveSnapshot(0L, List.of(), 0L, false));
    }

    public static SaveGameManifest manifest() {
        SaveGameSnapshot snapshot = snapshot();
        List<SaveSectionDescriptor> sections = List.of(
                descriptor(SaveSectionId.CHUNKS),
                descriptor(SaveSectionId.PLAYER),
                descriptor(SaveSectionId.INVENTORY),
                descriptor(SaveSectionId.WORLD_ITEMS));
        return new SaveGameManifest(
                snapshot.metadata().formatVersion(),
                snapshot.metadata().gameVersion(),
                snapshot.metadata().saveGameId(),
                snapshot.metadata().displayName(),
                snapshot.metadata().createdAt(),
                Instant.parse("2026-08-12T01:00:00Z"),
                snapshot.metadata().worldSeed(),
                snapshot.metadata().generatorVersion(),
                snapshot.metadata().generatorConfigFingerprint(),
                snapshot.metadata().chunkRadius(),
                snapshot.metadata().worldHeight(),
                snapshot.fixedTick(),
                "Task 4 test fixture",
                sections);
    }

    private static SaveSectionDescriptor descriptor(SaveSectionId id) {
        return new SaveSectionDescriptor(id, 1, true, 0L, "0".repeat(64));
    }

    private static final class RevisionRuntime implements GameSessionFactory.SessionRuntime {
        private final SaveGameSnapshot snapshot;
        private final SessionPersistenceClock clock = SessionPersistenceClock.restored(0L, 0L);
        private long revision;

        private RevisionRuntime(SaveGameSnapshot snapshot, long revision) {
            this.snapshot = snapshot;
            this.revision = revision;
        }

        @Override
        public boolean pollLoad() {
            return true;
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds, MouseDelta look, boolean focused) {
            throw new AssertionError("not expected");
        }

        @Override
        public GameSessionFrame capturePaused() {
            throw new AssertionError("not expected");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            return clock.captured(snapshot, revision);
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {}

        @Override
        public long persistenceRevision() {
            return revision;
        }

        @Override
        public void discardGameplayEligibility() {}

        @Override
        public void discardFixedTime() {}
    }
}
