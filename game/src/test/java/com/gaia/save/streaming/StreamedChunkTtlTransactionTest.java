package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveSectionId;
import com.gaia.save.format.SaveGameId;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamedChunkTtlTransactionTest {
    private static final SaveIdentity SAVE = new SaveIdentity(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    private static final String HASH = "11".repeat(32);

    @Test
    void duplicateChunkAndConflictingGlobalMutationsFailBeforeFreshness() {
        AtomicInteger freshnessCalls = new AtomicInteger();
        StreamedChunkMutation.Remove first =
                new StreamedChunkMutation.Remove(new ChunkKey(0, 0), 1L, HASH);
        StreamedChunkMutation.Remove duplicate =
                new StreamedChunkMutation.Remove(new ChunkKey(0, 0), 1L, HASH);

        assertThrows(IllegalArgumentException.class, () ->
                new StreamedPersistenceTransaction(
                        List.of(first, duplicate),
                        List.of(),
                        () -> {
                            freshnessCalls.incrementAndGet();
                            return true;
                        }));

        StreamedGlobalExtension checkpoint = checkpointExtension(new byte[] {1});
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedPersistenceTransaction(
                        List.of(),
                        List.of(
                                new StreamedGlobalExtensionMutation.Upsert(checkpoint),
                                new StreamedGlobalExtensionMutation.Remove(
                                        SaveSectionId.WORLD_ITEM_CHECKPOINT)),
                        () -> {
                            freshnessCalls.incrementAndGet();
                            return true;
                        }));
        assertEquals(0, freshnessCalls.get());
    }

    @Test
    void globalExtensionCopiesBytesAndEnforcesTheExactOneMiBBound() {
        StreamedGlobalExtension empty = checkpointExtension(new byte[0]);
        int exactPayloadBytes = Math.toIntExact(
                StreamedGlobalExtension.MAX_CANONICAL_BYTES
                        - empty.canonicalEncodedSize());
        byte[] exact = new byte[exactPayloadBytes];
        exact[0] = 7;
        StreamedGlobalExtension extension = checkpointExtension(exact);
        exact[0] = 9;

        assertEquals(StreamedGlobalExtension.MAX_CANONICAL_BYTES,
                extension.canonicalEncodedSize());
        assertEquals(7, extension.copyPayloadBytes()[0]);
        byte[] detached = extension.copyPayloadBytes();
        detached[0] = 3;
        assertEquals(7, extension.copyPayloadBytes()[0]);

        assertThrows(IllegalArgumentException.class,
                () -> checkpointExtension(new byte[exactPayloadBytes + 1]));
        StreamedGlobalExtension firstHalf = new StreamedGlobalExtension(
                new SaveSectionId("first-half"),
                1,
                false,
                Optional.empty(),
                new byte[StreamedGlobalExtension.MAX_CANONICAL_BYTES / 2]);
        StreamedGlobalExtension secondHalf = new StreamedGlobalExtension(
                new SaveSectionId("second-half"),
                1,
                false,
                Optional.empty(),
                new byte[StreamedGlobalExtension.MAX_CANONICAL_BYTES / 2]);
        assertThrows(IllegalArgumentException.class, () -> new StreamedChunkIndex(
                SaveGameId.parse(SAVE.value().toString()),
                null,
                List.of(),
                List.of(firstHalf, secondHalf)));
        assertThrows(IllegalArgumentException.class, () ->
                new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, -1));
    }

    @Test
    void enginePlanCarriesPageIntentWithoutDependingOnGameTypes() {
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                new ChunkKey(0, 0), 1L, HASH, 1, 0);
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 7L, false, 0, List.of(descriptor));
        WorldItemPageMutation mutation = new WorldItemPageMutation.Remove(descriptor);

        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                1L,
                intended,
                List.of(mutation),
                "22".repeat(32),
                () -> true);

        assertEquals(List.of(mutation), plan.pageMutations());
        assertEquals(2L, plan.intendedCheckpoint().checkpointRevision());

        assertThrows(IllegalArgumentException.class, () ->
                new WorldItemPageMutation.Upsert(
                        new WorldItemPageSnapshot(
                                new ChunkKey(1, 1), 1L, List.of()),
                        Optional.empty()));
    }

    @Test
    void omissionIsRepresentedByNoGlobalMutationAndNeverAsImplicitRemoval() {
        StreamedPersistenceTransaction transaction =
                new StreamedPersistenceTransaction(List.of(), List.of(), () -> true);

        assertEquals(List.of(), transaction.globalExtensionMutations());
        assertEquals(List.of(), transaction.chunks());
    }

    @Test
    void fullDescriptorTableRequiresPairedZeroLiveRemovalForANewKey() {
        List<WorldItemPageDescriptor> current = new java.util.ArrayList<>();
        for (int index = 0;
                index < WorldItemPagingCheckpoint.MAX_PAGE_DESCRIPTORS;
                index++) {
            current.add(new WorldItemPageDescriptor(
                    new ChunkKey(index, 0), 1L, HASH, 1, index == 0 ? 0 : 1));
        }
        WorldItemPageDescriptor replacement = new WorldItemPageDescriptor(
                new ChunkKey(2_000, 0), 1L, "22".repeat(32), 1, 1);
        List<WorldItemPageDescriptor> intended = new java.util.ArrayList<>(
                current.subList(1, current.size()));
        intended.add(replacement);

        StreamedWorldItemPageBackend.requireFullTableAdmission(
                current, intended, Set.of(current.get(0).chunkKey()));
        assertThrows(IllegalStateException.class, () ->
                StreamedWorldItemPageBackend.requireFullTableAdmission(
                        current, intended, Set.of()));
    }

    private static StreamedGlobalExtension checkpointExtension(byte[] bytes) {
        return new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                1,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, 0)),
                bytes);
    }
}
