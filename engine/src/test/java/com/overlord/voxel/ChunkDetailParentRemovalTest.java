package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.interaction.api.InteractionContext;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.ItemUseContext;
import com.overlord.interaction.api.RemoveDetailParentRequest;
import com.overlord.inventory.api.BodySlot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkDetailParentRemovalTest {
    private static final int WORLD_HEIGHT = 32;
    private static final int Y = 7;

    @Test
    void removesExactDetailParentAsFullAirInOneRevision() {
        Fixture fixture = detailFixture(4, 6, mixedDetail());

        ChunkDetailMutationOutcome result =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4,
                                Y,
                                6,
                                fixture.revision(),
                                fixture.detail()));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(fixture.revision(), result.observedChunkRevision());
        assertEquals(fixture.revision() + 1, result.resultingChunkRevision());
        assertEquals(fixture.detail(), result.oldState().orElseThrow());
        assertEquals(new FullCellState((byte) 0), result.newState().orElseThrow());
        assertEquals(
                new FullCellState((byte) 0),
                fixture.repository().observeCell(4, Y, 6)
                        .observation().orElseThrow().state());
        assertEquals(0, fixture.repository().snapshot(fixture.key())
                .orElseThrow().details().entryCount());
        assertEquals(Set.of(fixture.key()), result.dirtyChunks());
        assertTrue(fixture.repository().voxelModified(fixture.key()));
        assertTrue(fixture.repository().meshingCandidates().contains(fixture.key()));
    }

    @Test
    void staleRevisionAndExpectedStateRejectWithoutPublication() {
        Fixture fixture = detailFixture(4, 6, mixedDetail());
        DetailCellState wrong = DetailCellState.uniform((byte) 7);

        ChunkDetailMutationOutcome stale =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4, Y, 6, fixture.revision() + 1, fixture.detail()));
        ChunkDetailMutationOutcome mismatch =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4, Y, 6, fixture.revision(), wrong));

        assertEquals(
                ChunkDetailMutationOutcome.Status.STALE_CHUNK_REVISION,
                stale.status());
        assertEquals(
                ChunkDetailMutationOutcome.Status.EXPECTED_STATE_CONFLICT,
                mismatch.status());
        assertTrue(stale.dirtiedChunks().isEmpty());
        assertTrue(mismatch.dirtiedChunks().isEmpty());
        assertEquals(fixture.revision(), fixture.repository().revision(fixture.key()));
        assertEquals(fixture.detail(), fixture.repository().observeCell(4, Y, 6)
                .observation().orElseThrow().state());
    }

    @Test
    void fullRepresentationRejectsWithoutMutation() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> chunk.setBlock(4, Y, 6, (byte) 3));
        long revision = repository.revision(key);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4, Y, 6, revision, mixedDetail()));

        assertEquals(
                ChunkDetailMutationOutcome.Status.REPRESENTATION_CONFLICT,
                result.status());
        assertEquals(revision, repository.revision(key));
        assertEquals(new FullCellState((byte) 3), repository.observeCell(4, Y, 6)
                .observation().orElseThrow().state());
        assertTrue(result.dirtiedChunks().isEmpty());
    }

    @Test
    void boundaryRemovalUsesExistingCardinalAndDiagonalInvalidation() {
        assertBoundaryDirtyKeys(0, 8, List.of(new ChunkKey(0, 0), new ChunkKey(-1, 0)));
        assertBoundaryDirtyKeys(15, 8, List.of(new ChunkKey(0, 0), new ChunkKey(1, 0)));
        assertBoundaryDirtyKeys(8, 0, List.of(new ChunkKey(0, 0), new ChunkKey(0, -1)));
        assertBoundaryDirtyKeys(8, 15, List.of(new ChunkKey(0, 0), new ChunkKey(0, 1)));
        assertBoundaryDirtyKeys(
                0,
                0,
                List.of(
                        new ChunkKey(0, 0),
                        new ChunkKey(-1, 0),
                        new ChunkKey(0, -1),
                        new ChunkKey(-1, -1)));
        assertBoundaryDirtyKeys(
                -16,
                -16,
                List.of(
                        new ChunkKey(-1, -1),
                        new ChunkKey(-2, -1),
                        new ChunkKey(-1, -2),
                        new ChunkKey(-2, -2)));
    }

    @Test
    void finalizedUnloadRejectsAndActiveUnloadBecomesStale() {
        Fixture fixture = detailFixture(4, 6, mixedDetail());
        ChunkUnloadPreparation active =
                fixture.repository().prepareStreamingUnload(fixture.key());
        ChunkDetailMutationOutcome applied =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4, Y, 6, fixture.revision(), fixture.detail()));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, applied.status());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                fixture.repository().validateStreamingUnload(
                        active.ticket().orElseThrow()).status());

        Fixture finalizedFixture = detailFixture(4, 6, mixedDetail());
        ChunkUnloadPreparation finalized =
                finalizedFixture.repository().prepareStreamingUnload(
                        finalizedFixture.key());
        assertEquals(
                ChunkUnloadResult.Status.VALID,
                finalizedFixture.repository().validateStreamingUnload(
                        finalized.ticket().orElseThrow()).status());
        ChunkDetailMutationOutcome rejected =
                finalizedFixture.repository().mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                4,
                                Y,
                                6,
                                finalizedFixture.revision(),
                                finalizedFixture.detail()));
        assertEquals(
                ChunkDetailMutationOutcome.Status.UNLOAD_FINALIZED,
                rejected.status());
        assertEquals(
                finalizedFixture.revision(),
                finalizedFixture.repository().revision(finalizedFixture.key()));
        assertTrue(rejected.dirtiedChunks().isEmpty());
    }

    @Test
    void requestRequiresExactNonemptyDetailAndPositiveRevision() {
        InteractionContext context =
                new ItemUseContext(
                        new EntityRef(1),
                        BodySlot.RIGHT_HAND,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.PRIMARY,
                        1L,
                        2L);
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoveDetailParentRequest(
                        context, 1, 2, 3, 0L, mixedDetail()));
        assertThrows(
                NullPointerException.class,
                () -> new RemoveDetailParentRequest(
                        context, 1, 2, 3, 1L, null));
    }

    private static void assertBoundaryDirtyKeys(
            int worldX, int worldZ, List<ChunkKey> expected) {
        ChunkRepository repository = repository();
        ChunkKey target = ChunkKey.fromWorld(worldX, worldZ);
        for (ChunkKey key : expected) {
            if (!key.equals(target)) {
                repository.generate(key, chunk -> {});
            }
        }
        int localX = ChunkKey.localCoordinate(worldX);
        int localZ = ChunkKey.localCoordinate(worldZ);
        DetailCellState detail = mixedDetail();
        repository.generate(
                target,
                chunk -> chunk.replaceCanonicalCell(localX, Y, localZ, detail));
        long revision = repository.revision(target);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.RemoveDetailParent(
                                worldX, Y, worldZ, revision, detail));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(expected, result.dirtiedChunks().stream()
                .map(DirtyChunkRevision::key).toList());
    }

    private static Fixture detailFixture(
            int worldX, int worldZ, DetailCellState detail) {
        ChunkRepository repository = repository();
        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        repository.generate(
                key,
                chunk -> chunk.replaceCanonicalCell(
                        ChunkKey.localCoordinate(worldX),
                        Y,
                        ChunkKey.localCoordinate(worldZ),
                        detail));
        return new Fixture(repository, key, repository.revision(key), detail);
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static DetailCellState mixedDetail() {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[0] = 3;
        ids[17] = 4;
        ids[63] = 5;
        return new DetailCellState((1L << 0) | (1L << 17) | (1L << 63), ids);
    }

    private record Fixture(
            ChunkRepository repository,
            ChunkKey key,
            long revision,
            DetailCellState detail) {}
}
