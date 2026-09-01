package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.InteractionContext;
import com.overlord.interaction.api.ItemUseContext;
import com.overlord.interaction.api.SculptParentSubVoxelRequest;
import com.overlord.inventory.api.BodySlot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChunkDetailSculptTest {
    private static final int WORLD_HEIGHT = 64;
    private static final int X = 4;
    private static final int Y = 7;
    private static final int Z = 6;
    private static final LocalSubVoxelPosition TARGET =
            new LocalSubVoxelPosition(3, 2, 1);

    @Test
    void fullSolidRemovalPublishesFinalSixtyThreeCellDetailInOneRevision() {
        ChunkRepository repository = repositoryWithFull(X, Z, (byte) 7);
        ChunkKey key = ChunkKey.fromWorld(X, Z);
        long revision = repository.revision(key);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X,
                                Y,
                                Z,
                                revision,
                                new FullCellState((byte) 7),
                                TARGET,
                                (byte) 0));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(revision, result.observedChunkRevision());
        assertEquals(revision + 1, result.resultingChunkRevision());
        assertEquals(new FullCellState((byte) 7), result.oldState().orElseThrow());
        DetailCellState detail =
                assertInstanceOf(DetailCellState.class, result.newState().orElseThrow());
        assertEquals(-1L & ~(1L << TARGET.index()), detail.occupancyMask());
        assertEquals(0, Byte.toUnsignedInt(detail.blockId(TARGET)));
        assertEquals(7, Byte.toUnsignedInt(detail.blockId(LocalSubVoxelPosition.fromIndex(0))));
        assertEquals(
                detail,
                repository.observeCell(X, Y, Z).observation().orElseThrow().state());
        assertEquals(revision + 1, repository.revision(key));
    }

    @Test
    void fullAirPlacementPublishesOneCellDetail() {
        ChunkRepository repository = repositoryWithFull(X, Z, (byte) 0);
        ChunkKey key = ChunkKey.fromWorld(X, Z);
        long revision = repository.revision(key);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X,
                                Y,
                                Z,
                                revision,
                                new FullCellState((byte) 0),
                                TARGET,
                                (byte) 9));

        DetailCellState detail =
                assertInstanceOf(DetailCellState.class, result.newState().orElseThrow());
        assertEquals(1L << TARGET.index(), detail.occupancyMask());
        assertEquals(9, Byte.toUnsignedInt(detail.blockId(TARGET)));
        assertEquals(revision + 1, repository.revision(key));
    }

    @Test
    void detailSupportsExactReplaceRemoveAndFinalClearWithoutAutoCompaction() {
        DetailCellState initial = detailAt(TARGET, (byte) 4, LocalSubVoxelPosition.fromIndex(2), (byte) 6);
        Fixture fixture = detailFixture(X, Z, initial);

        ChunkDetailMutationOutcome replaced =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X, Y, Z, fixture.revision(), initial, TARGET, (byte) 8));
        DetailCellState afterReplace =
                assertInstanceOf(DetailCellState.class, replaced.newState().orElseThrow());
        assertEquals(8, Byte.toUnsignedInt(afterReplace.blockId(TARGET)));
        assertEquals(initial.occupancyMask(), afterReplace.occupancyMask());

        ChunkDetailMutationOutcome removed =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X,
                                Y,
                                Z,
                                replaced.resultingChunkRevision(),
                                afterReplace,
                                TARGET,
                                (byte) 0));
        DetailCellState oneLeft =
                assertInstanceOf(DetailCellState.class, removed.newState().orElseThrow());
        assertEquals(1L << 2, oneLeft.occupancyMask());

        ChunkDetailMutationOutcome finalClear =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X,
                                Y,
                                Z,
                                removed.resultingChunkRevision(),
                                oneLeft,
                                LocalSubVoxelPosition.fromIndex(2),
                                (byte) 0));
        assertEquals(new FullCellState((byte) 0), finalClear.newState().orElseThrow());
        assertEquals(0, fixture.repository().snapshot(fixture.key())
                .orElseThrow().details().entryCount());

        Fixture uniformFixture = detailFixture(X, Z, DetailCellState.uniform((byte) 5));
        ChunkDetailMutationOutcome noChange =
                uniformFixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X,
                                Y,
                                Z,
                                uniformFixture.revision(),
                                DetailCellState.uniform((byte) 5),
                                TARGET,
                                (byte) 5));
        assertEquals(ChunkDetailMutationOutcome.Status.NO_CHANGE, noChange.status());
        assertInstanceOf(
                DetailCellState.class,
                uniformFixture.repository().observeCell(X, Y, Z)
                        .observation().orElseThrow().state());
    }

    @Test
    void staleRevisionAndExactStateRejectWithoutDirtyPublication() {
        DetailCellState initial = detailAt(TARGET, (byte) 4);
        Fixture fixture = detailFixture(X, Z, initial);
        DetailCellState wrong = detailAt(TARGET, (byte) 5);

        ChunkDetailMutationOutcome stale =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X, Y, Z, fixture.revision() + 1, initial, TARGET, (byte) 0));
        ChunkDetailMutationOutcome mismatch =
                fixture.repository().mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X, Y, Z, fixture.revision(), wrong, TARGET, (byte) 0));

        assertEquals(ChunkDetailMutationOutcome.Status.STALE_CHUNK_REVISION, stale.status());
        assertEquals(ChunkDetailMutationOutcome.Status.EXPECTED_STATE_CONFLICT, mismatch.status());
        assertEquals(fixture.revision(), fixture.repository().revision(fixture.key()));
        assertTrue(stale.dirtiedChunks().isEmpty());
        assertTrue(mismatch.dirtiedChunks().isEmpty());
    }

    @Test
    void fullSolidRejectsNonRemovalAndFullAirRejectsEmptyNoOp() {
        ChunkRepository solid = repositoryWithFull(X, Z, (byte) 7);
        long solidRevision = solid.revision(new ChunkKey(0, 0));
        ChunkDetailMutationOutcome nonRemoval =
                solid.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X, Y, Z, solidRevision, new FullCellState((byte) 7), TARGET, (byte) 8));
        assertEquals(ChunkDetailMutationOutcome.Status.REPRESENTATION_CONFLICT, nonRemoval.status());

        ChunkRepository air = repositoryWithFull(X, Z, (byte) 0);
        long airRevision = air.revision(new ChunkKey(0, 0));
        ChunkDetailMutationOutcome empty =
                air.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                X, Y, Z, airRevision, new FullCellState((byte) 0), TARGET, (byte) 0));
        assertEquals(ChunkDetailMutationOutcome.Status.NO_CHANGE, empty.status());
    }

    @Test
    void newDetailParentHonorsCapacityAndBoundaryInvalidation() {
        ChunkRepository capped = repository();
        ChunkKey key = new ChunkKey(0, 0);
        capped.generate(
                key,
                chunk -> {
                    for (int index = 0; index < Chunk.MAX_DETAIL_PARENTS_PER_CHUNK; index++) {
                        int localZ = index / (16 * WORLD_HEIGHT);
                        int remainder = index % (16 * WORLD_HEIGHT);
                        int y = remainder / 16;
                        int localX = remainder % 16;
                        chunk.replaceCanonicalCell(localX, y, localZ, detailAt(TARGET, (byte) 2));
                    }
                });
        int next = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
        int worldZ = next / (16 * WORLD_HEIGHT);
        int remainder = next % (16 * WORLD_HEIGHT);
        int y = remainder / 16;
        int worldX = remainder % 16;
        long revision = capped.revision(key);
        ChunkDetailMutationOutcome capacity =
                capped.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                worldX,
                                y,
                                worldZ,
                                revision,
                                new FullCellState((byte) 0),
                                TARGET,
                                (byte) 3));
        assertEquals(ChunkDetailMutationOutcome.Status.CAPACITY_EXCEEDED, capacity.status());

        assertBoundaryDirtyKeys(-16, -16);
        assertBoundaryDirtyKeys(15, 15);
    }

    @Test
    void requestRequiresPositiveRevisionExactStatePositionAndOptionalReplacement() {
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
                () -> new SculptParentSubVoxelRequest(
                        context,
                        X,
                        Y,
                        Z,
                        0L,
                        new FullCellState((byte) 7),
                        TARGET,
                        Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new SculptParentSubVoxelRequest(
                        context,
                        X,
                        Y,
                        Z,
                        1L,
                        new FullCellState((byte) 7),
                        TARGET,
                        null));
        new SculptParentSubVoxelRequest(
                context,
                X,
                Y,
                Z,
                1L,
                new FullCellState((byte) 7),
                TARGET,
                Optional.of(ResourceLocation.parse("gaia:stone")));
    }

    private static void assertBoundaryDirtyKeys(int worldX, int worldZ) {
        ChunkRepository repository = repository();
        ChunkKey target = ChunkKey.fromWorld(worldX, worldZ);
        ChunkKey xNeighbor = ChunkKey.localCoordinate(worldX) == 0
                ? target.west()
                : target.east();
        ChunkKey zNeighbor = ChunkKey.localCoordinate(worldZ) == 0
                ? target.north()
                : target.south();
        ChunkKey diagonal = new ChunkKey(xNeighbor.x(), zNeighbor.z());
        List<ChunkKey> expected = List.of(
                target,
                xNeighbor,
                zNeighbor,
                diagonal);
        for (ChunkKey candidate : expected) {
            repository.generate(candidate, chunk -> {});
        }
        long revision = repository.revision(target);
        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.SculptParentSubVoxel(
                                worldX,
                                Y,
                                worldZ,
                                revision,
                                new FullCellState((byte) 0),
                                TARGET,
                                (byte) 3));
        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(expected, result.dirtiedChunks().stream()
                .map(DirtyChunkRevision::key).toList());
    }

    private static ChunkRepository repositoryWithFull(int worldX, int worldZ, byte id) {
        ChunkRepository repository = repository();
        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        repository.generate(
                key,
                chunk -> chunk.setBlock(
                        ChunkKey.localCoordinate(worldX),
                        Y,
                        ChunkKey.localCoordinate(worldZ),
                        id));
        return repository;
    }

    private static Fixture detailFixture(int worldX, int worldZ, DetailCellState detail) {
        ChunkRepository repository = repository();
        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        repository.generate(
                key,
                chunk -> chunk.replaceCanonicalCell(
                        ChunkKey.localCoordinate(worldX),
                        Y,
                        ChunkKey.localCoordinate(worldZ),
                        detail));
        return new Fixture(repository, key, repository.revision(key));
    }

    private static DetailCellState detailAt(
            LocalSubVoxelPosition first,
            byte firstId,
            Object... rest) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        long mask = 1L << first.index();
        ids[first.index()] = firstId;
        for (int index = 0; index < rest.length; index += 2) {
            LocalSubVoxelPosition position = (LocalSubVoxelPosition) rest[index];
            byte id = (byte) rest[index + 1];
            mask |= 1L << position.index();
            ids[position.index()] = id;
        }
        return new DetailCellState(mask, ids);
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private record Fixture(ChunkRepository repository, ChunkKey key, long revision) {}
}
