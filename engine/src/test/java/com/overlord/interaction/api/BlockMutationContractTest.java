package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.BlockWorldMutationOutcome;
import com.overlord.interaction.testing.FakeBlockWorldAccess;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockMutationContractTest {
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation AIR =
            ResourceLocation.parse("gaia:air");
    private static final ChunkKey CENTER = new ChunkKey(0, 0);
    private static final ChunkKey EAST = new ChunkKey(1, 0);
    private static final DirtyChunkRevision CENTER_REVISION =
            new DirtyChunkRevision(CENTER, 7);
    private static final DirtyChunkRevision EAST_REVISION =
            new DirtyChunkRevision(EAST, 11);

    private final InteractionContext context =
            new ItemUseContext(
                    new EntityRef(1),
                    BodySlot.RIGHT_HAND,
                    Optional.empty(),
                    Optional.empty(),
                    InteractionAction.PRIMARY,
                    5,
                    50);
    private final BlockChangeRequest request =
            new BlockChangeRequest(
                    context, 15, 4, 3, STONE, AIR);

    @Test
    void worldOutcomeCopiesOrderedDirtyRevisionsAndExposesImmutableViews() {
        List<DirtyChunkRevision> mutable =
                new ArrayList<>(
                        List.of(CENTER_REVISION, EAST_REVISION));
        BlockWorldMutationOutcome outcome =
                new BlockWorldMutationOutcome(
                        BlockWorldMutationOutcome.Status.APPLIED,
                        STONE,
                        mutable);
        mutable.clear();

        assertEquals(
                List.of(CENTER_REVISION, EAST_REVISION),
                outcome.dirtiedChunks());
        assertEquals(
                orderedRevisions(CENTER_REVISION, EAST_REVISION),
                outcome.dirtyRevisions());
        assertEquals(
                List.of(CENTER, EAST),
                List.copyOf(outcome.dirtyChunks()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcome.dirtiedChunks().add(CENTER_REVISION));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcome.dirtyRevisions().put(new ChunkKey(2, 0), 13L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcome.dirtyChunks().add(new ChunkKey(2, 0)));
    }

    @Test
    void worldOutcomeValidatesStatusObservedBlockAndDirtyRevisionRules() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                null, STONE, List.of()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                BlockWorldMutationOutcome.Status.CONFLICT,
                                null,
                                List.of()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                BlockWorldMutationOutcome.Status.CONFLICT,
                                STONE,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                BlockWorldMutationOutcome.Status.APPLIED,
                                STONE,
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                BlockWorldMutationOutcome.Status.CONFLICT,
                                STONE,
                                List.of(CENTER_REVISION)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockWorldMutationOutcome(
                                BlockWorldMutationOutcome.Status.APPLIED,
                                STONE,
                                List.of(
                                        CENTER_REVISION,
                                        new DirtyChunkRevision(CENTER, 8))));
    }

    @Test
    void appliedResultCopiesExactOrderedDirtyRevisions() {
        List<DirtyChunkRevision> mutable =
                new ArrayList<>(
                        List.of(CENTER_REVISION, EAST_REVISION));
        BlockChangeResult result =
                new BlockChangeResult(
                        request,
                        BlockChangeResult.Status.APPLIED,
                        Optional.of(STONE),
                        mutable);
        mutable.clear();

        assertEquals(
                List.of(CENTER_REVISION, EAST_REVISION),
                result.dirtiedChunks());
        assertEquals(
                orderedRevisions(CENTER_REVISION, EAST_REVISION),
                result.dirtyRevisions());
        assertEquals(
                List.of(CENTER, EAST),
                List.copyOf(result.dirtyChunks()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.dirtiedChunks().add(CENTER_REVISION));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.dirtyRevisions().put(new ChunkKey(2, 0), 13L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.dirtyChunks().add(new ChunkKey(2, 0)));
    }

    @Test
    void resultRejectsDuplicateOrStatusInconsistentDirtyRevisions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockChangeResult(
                                request,
                                BlockChangeResult.Status.APPLIED,
                                Optional.of(STONE),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockChangeResult(
                                request,
                                BlockChangeResult.Status.CONFLICT,
                                Optional.of(STONE),
                                List.of(CENTER_REVISION)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockChangeResult(
                                request,
                                BlockChangeResult.Status.APPLIED,
                                Optional.of(STONE),
                                List.of(
                                        CENTER_REVISION,
                                        new DirtyChunkRevision(CENTER, 8))));
    }

    @Test
    void requestRejectsNullBlockResourceIdentities() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockChangeRequest(
                                context, 1, 2, 3, null, AIR));
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockChangeRequest(
                                context, 1, 2, 3, STONE, null));
    }

    @Test
    void resultRejectsNullObservedBlockOptionalContainer() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockChangeResult(
                                request,
                                BlockChangeResult.Status.CONFLICT,
                                null,
                                List.of()));
    }

    @Test
    void dirtyEventCopiesExactOrderedDirtyRevisionsAndRejectsInvalidLists() {
        List<DirtyChunkRevision> mutable =
                new ArrayList<>(
                        List.of(CENTER_REVISION, EAST_REVISION));
        ChunkDirtyEvent event =
                new ChunkDirtyEvent(request, mutable);
        mutable.clear();

        assertEquals(
                List.of(CENTER_REVISION, EAST_REVISION),
                event.dirtiedChunks());
        assertEquals(
                orderedRevisions(CENTER_REVISION, EAST_REVISION),
                event.dirtyRevisions());
        assertEquals(
                List.of(CENTER, EAST),
                List.copyOf(event.dirtyChunks()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> event.dirtiedChunks().add(CENTER_REVISION));
        assertThrows(
                UnsupportedOperationException.class,
                () -> event.dirtyRevisions().put(new ChunkKey(2, 0), 13L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> event.dirtyChunks().add(new ChunkKey(2, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkDirtyEvent(request, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkDirtyEvent(
                                request,
                                List.of(
                                        CENTER_REVISION,
                                        new DirtyChunkRevision(CENTER, 8))));
    }

    @Test
    void fakeWorldAccessSupportsConfiguredCompareAndSetOutcomeAndExternalChange() {
        FakeBlockWorldAccess world =
                new FakeBlockWorldAccess(
                        STONE, Set.of(STONE, AIR));
        BlockWorldMutationOutcome conflict =
                new BlockWorldMutationOutcome(
                        BlockWorldMutationOutcome.Status.CONFLICT,
                        AIR,
                        List.of());
        world.setCompareAndSetOutcome(conflict);

        assertEquals(
                conflict,
                world.compareAndSetBlock(15, 4, 3, STONE, AIR));
        assertEquals(1, world.writes());
        assertEquals(STONE, world.blockAt(15, 4, 3));

        world.forceExternalBlockChangeForTest(AIR);
        assertEquals(AIR, world.blockAt(15, 4, 3));
    }

    @Test
    void dispatchExceptionReportsCommitState() {
        BlockChangeDispatchException failure =
                new BlockChangeDispatchException(
                        "post-change delivery failed",
                        new IllegalStateException("listener"),
                        true);
        assertEquals(true, failure.mutationApplied());
    }

    private static Map<ChunkKey, Long> orderedRevisions(
            DirtyChunkRevision... revisions) {
        Map<ChunkKey, Long> result = new LinkedHashMap<>();
        for (DirtyChunkRevision revision : revisions) {
            result.put(revision.key(), revision.revision());
        }
        return result;
    }
}
