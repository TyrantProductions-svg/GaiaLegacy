package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkRepositoryUnloadTransactionTest {
    private static final int WORLD_HEIGHT = 16;

    @Test
    void prepareKeepsExactResidentChunkWithoutAdvancingRevision() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-3, 4);
        repository.generate(key, chunk -> chunk.setBlock(2, 3, 4, (byte) 7));
        long revision = repository.revision(key);
        ChunkState state = repository.state(key);

        ChunkUnloadPreparation prepared = repository.prepareStreamingUnload(key);

        assertEquals(ChunkUnloadPreparation.Status.PREPARED, prepared.status());
        assertTrue(repository.contains(key));
        assertEquals(revision, repository.revision(key));
        assertEquals(state, repository.state(key));
        ChunkSnapshot capture = prepared.capture().orElseThrow();
        assertEquals(key, capture.key());
        assertEquals(revision, capture.revision());
        assertEquals(7, Byte.toUnsignedInt(capture.getBlock(2, 3, 4)));
        assertTrue(prepared.ticket().isPresent());
    }

    @Test
    void cancelRestoresExactStateRevisionAndFailureObservation() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(2, -5);
        repository.generate(key, chunk -> {});
        ChunkMeshInput claimed = repository.claimMeshing(key).orElseThrow();
        IllegalStateException failure = new IllegalStateException("mesh failed");
        repository.markMeshingFailure(key, claimed.center().revision(), failure);
        long revision = repository.revision(key);
        ChunkState state = repository.state(key);
        assertFalse(repository.meshingCandidates().contains(key));

        ChunkUnloadPreparation prepared = repository.prepareStreamingUnload(key);
        ChunkUnloadTicket ticket = prepared.ticket().orElseThrow();
        assertTrue(prepared.stillCurrent().getAsBoolean());
        assertEquals(
                ChunkUnloadResult.Status.CANCELED,
                repository.cancelStreamingUnload(ticket).status());
        assertFalse(prepared.stillCurrent().getAsBoolean());

        assertTrue(repository.contains(key));
        assertEquals(revision, repository.revision(key));
        assertEquals(state, repository.state(key));
        assertFalse(repository.meshingCandidates().contains(key));
    }

    @Test
    void foreignStaleAndReplayedTicketsFailClosed() {
        ChunkRepository first = repository();
        ChunkRepository second = repository();
        ChunkKey key = new ChunkKey(1, 1);
        first.generate(key, chunk -> {});
        second.generate(key, chunk -> {});
        ChunkUnloadTicket ticket = first.prepareStreamingUnload(key)
                .ticket().orElseThrow();

        assertEquals(
                ChunkUnloadResult.Status.FOREIGN,
                second.cancelStreamingUnload(ticket).status());
        assertTrue(second.contains(key));
        assertEquals(
                ChunkUnloadResult.Status.CANCELED,
                first.cancelStreamingUnload(ticket).status());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                first.cancelStreamingUnload(ticket).status());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                first.commitStreamingUnload(ticket).status());
        assertTrue(first.contains(key));
    }

    @Test
    void mutationAfterPrepareInvalidatesCaptureAndCannotRemoveNewerState() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-1, -1);
        repository.generate(key, chunk -> chunk.setBlock(1, 1, 1, (byte) 2));
        ChunkUnloadPreparation prepared = repository.prepareStreamingUnload(key);
        ChunkUnloadTicket ticket = prepared.ticket().orElseThrow();
        assertTrue(prepared.stillCurrent().getAsBoolean());

        assertTrue(repository.setBlock(
                key.worldOriginX() + 1, 1, key.worldOriginZ() + 1, (byte) 9));
        assertFalse(prepared.stillCurrent().getAsBoolean());

        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.validateStreamingUnload(ticket).status());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.commitStreamingUnload(ticket).status());
        assertTrue(repository.contains(key));
        assertEquals(9, Byte.toUnsignedInt(repository.getBlock(
                key.worldOriginX() + 1, 1, key.worldOriginZ() + 1)));
    }

    @Test
    void durableAckAdvancesPersistenceFloorWithoutEvictingNewerResidentState() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-9, 12);
        repository.generate(key, chunk -> chunk.setBlock(1, 1, 1, (byte) 2));
        ChunkUnloadPreparation prepared = repository.prepareStreamingUnload(key);
        long durablyPublishedRevision = prepared.capture().orElseThrow().revision();
        ChunkUnloadTicket ticket = prepared.ticket().orElseThrow();

        assertTrue(repository.setBlock(
                key.worldOriginX() + 1, 1, key.worldOriginZ() + 1, (byte) 9));
        assertEquals(ChunkUnloadResult.Status.VALID,
                repository.acknowledgeStreamingPersistence(
                        ticket, durablyPublishedRevision).status());
        assertEquals(ChunkUnloadResult.Status.STALE,
                repository.validateStreamingUnload(ticket).status(),
                "newer canonical state must still prevent eviction");

        ChunkUnloadPreparation retry = repository.prepareStreamingUnload(key);
        assertEquals(durablyPublishedRevision, retry.persistedRevision(),
                "the next capture must build on the last-known durable revision");
        assertEquals(9, Byte.toUnsignedInt(retry.capture().orElseThrow()
                .getBlock(1, 1, 1)));
        repository.cancelStreamingUnload(retry.ticket().orElseThrow());
    }

    @Test
    void replacementRequestInvalidatesPreparedUnload() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(6, -7);
        repository.generate(key, chunk -> {});
        ChunkUnloadTicket ticket = repository.prepareStreamingUnload(key)
                .ticket().orElseThrow();

        ChunkStreamingTicket replacement = repository.request(
                key, 10L, ChunkStreamingTicket.SourcePreference.GENERATE);

        assertNotNull(replacement);
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.validateStreamingUnload(ticket).status());
        assertTrue(repository.contains(key));
    }

    @Test
    void liveTicketRevalidatesThenCommitsDeterministicExactRemoval() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(8, 9);
        repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 4));
        ChunkUnloadTicket ticket = repository.prepareStreamingUnload(key)
                .ticket().orElseThrow();

        assertEquals(
                ChunkUnloadResult.Status.VALID,
                repository.validateStreamingUnload(ticket).status());
        assertFalse(repository.setBlock(
                key.worldOriginX(), 0, key.worldOriginZ(), (byte) 9),
                "final validation must seal the pinned capture");
        assertEquals(
                ChunkUnloadResult.Status.COMMITTED,
                repository.commitStreamingUnload(ticket).status());

        assertFalse(repository.contains(key));
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.commitStreamingUnload(ticket).status());
    }

    @Test
    void finalValidationSealsMeshingFailureAndNeighborRevisionTransitions() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(10, 10);
        repository.generate(key, chunk -> {});
        ChunkMeshInput claimed = repository.claimMeshing(key).orElseThrow();
        long revision = claimed.center().revision();
        ChunkUnloadTicket ticket = repository.prepareStreamingUnload(key)
                .ticket().orElseThrow();
        assertEquals(
                ChunkUnloadResult.Status.VALID,
                repository.validateStreamingUnload(ticket).status());

        assertFalse(repository.markReadyForUpload(key, revision));
        assertFalse(repository.markMeshingFailureIfCurrent(
                key, revision, new IllegalStateException("late failure")));
        repository.retry(key);
        assertEquals(ChunkState.MESHING, repository.state(key));
        assertEquals(revision, repository.revision(key));
        assertEquals(
                ChunkUnloadResult.Status.COMMITTED,
                repository.commitStreamingUnload(ticket).status());
    }

    @Test
    void prevalidationMeshingTransitionStalesDetachedFreshness() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(11, 11);
        repository.generate(key, chunk -> {});
        ChunkUnloadPreparation prepared = repository.prepareStreamingUnload(key);

        assertTrue(repository.claimMeshing(key).isPresent());

        assertFalse(prepared.stillCurrent().getAsBoolean());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.validateStreamingUnload(
                        prepared.ticket().orElseThrow()).status());
        assertTrue(repository.contains(key));
    }

    @Test
    void ticketOperationsRejectWrongThreadWithoutConsumingOwnerAuthority()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(3, 3);
        repository.generate(key, chunk -> {});
        ChunkUnloadTicket ticket = repository.prepareStreamingUnload(key)
                .ticket().orElseThrow();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                repository.cancelStreamingUnload(ticket);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "foreign-unload-owner");

        foreign.start();
        foreign.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(
                ChunkUnloadResult.Status.VALID,
                repository.validateStreamingUnload(ticket).status());
        assertEquals(
                ChunkUnloadResult.Status.CANCELED,
                repository.cancelStreamingUnload(ticket).status());
    }

    @Test
    void preparationItselfIsRestrictedToRepositoryOwnerThread()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(4, 4);
        repository.generate(key, chunk -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                repository.prepareStreamingUnload(key);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "foreign-unload-prepare");

        foreign.start();
        foreign.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(
                ChunkUnloadPreparation.Status.PREPARED,
                repository.prepareStreamingUnload(key).status());
    }

    @Test
    void absentOrAlreadyPreparedKeyDoesNotCreateAnotherTicket() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        assertEquals(
                ChunkUnloadPreparation.Status.NOT_RESIDENT,
                repository.prepareStreamingUnload(key).status());
        repository.generate(key, chunk -> {});
        ChunkUnloadPreparation first = repository.prepareStreamingUnload(key);
        ChunkUnloadPreparation duplicate = repository.prepareStreamingUnload(key);

        assertEquals(ChunkUnloadPreparation.Status.PREPARED, first.status());
        assertEquals(ChunkUnloadPreparation.Status.ALREADY_PREPARED, duplicate.status());
        assertEquals(Optional.empty(), duplicate.ticket());
        assertEquals(Optional.empty(), duplicate.capture());
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }
}
