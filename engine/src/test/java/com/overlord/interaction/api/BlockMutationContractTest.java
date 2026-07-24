package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockMutationContractTest {
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
                    context,
                    15,
                    4,
                    3,
                    ResourceLocation.parse("gaia:stone"),
                    ResourceLocation.parse("gaia:air"));

    @Test
    void resultCopiesDirtyChunkSet() {
        Set<ChunkKey> mutable =
                new java.util.HashSet<>(Set.of(new ChunkKey(0, 0)));
        BlockChangeResult result =
                new BlockChangeResult(
                        request,
                        BlockChangeResult.Status.APPLIED,
                        Optional.of(ResourceLocation.parse("gaia:stone")),
                        mutable);
        mutable.add(new ChunkKey(1, 0));

        assertEquals(Set.of(new ChunkKey(0, 0)), result.dirtyChunks());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.dirtyChunks().add(new ChunkKey(2, 0)));
    }

    @Test
    void dirtyEventRejectsEmptyAffectedSet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkDirtyEvent(request, Set.of()));
    }

    @Test
    void dirtyEventCopiesAffectedChunkSet() {
        Set<ChunkKey> mutable =
                new java.util.HashSet<>(Set.of(new ChunkKey(0, 0)));
        ChunkDirtyEvent event = new ChunkDirtyEvent(request, mutable);
        mutable.add(new ChunkKey(1, 0));

        assertEquals(Set.of(new ChunkKey(0, 0)), event.chunks());
        assertThrows(
                UnsupportedOperationException.class,
                () -> event.chunks().add(new ChunkKey(2, 0)));
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
}
