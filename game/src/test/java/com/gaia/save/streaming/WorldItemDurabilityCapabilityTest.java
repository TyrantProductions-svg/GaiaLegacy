package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurabilityVerifier;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemHibernatePayload;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldItemDurabilityCapabilityTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final SaveIdentity SAVE =
            new SaveIdentity(UUID.fromString(SAVE_ID.value()));

    @TempDir Path tempDirectory;

    @Test
    void productionBackendRejectsCallerProofAndKeepsProofAndVerifierPrivate()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("capability"));
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        WorldItemPageCodec pageCodec = new WorldItemPageCodec();
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(
                store,
                (save, page, pageBytes) -> new StreamedChunkStore.ExactChunkCapture(
                        new StreamedChunkPayload(
                                SAVE_ID,
                                page.chunkKey(),
                                "v15",
                                "11".repeat(32),
                                page.pageRevision(),
                                0L,
                                true,
                                false,
                                1,
                                new byte[16 * 16],
                                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                                        SaveSectionId.WORLD_ITEM_PAGE,
                                        WorldItemPageCodec.CODEC_VERSION,
                                        true,
                                        pageBytes))),
                        () -> true));
        LogicalWorldItemService service = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                2,
                0L,
                SAVE,
                new WorldItemPageCachePolicy(
                        2, 2, 1_024L * 1_024L,
                        64, 2, 1_024L * 1_024L,
                        64, 64L * 1_024L),
                backend.durabilityVerifier(),
                page -> descriptor(pageCodec, page));
        service.deliverWorldTick(100L);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemSnapshot item = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                0.5, 4.0, 0.5,
                0.0, 0.0, 0.0,
                Optional.empty(), 100L)).item().orElseThrow();
        WorldItemHibernateResult prepared = service.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        WorldItemPersistenceTicket ticket =
                prepared.persistenceTicket().orElseThrow();
        CallerProof callerProof = new CallerProof();

        WorldItemDurabilityVerifier substituted = (ignoredTicket, ignoredPlan, proof) -> {
            // Deliberately accepts everything; it is not the backend-bound verifier.
        };
        assertNotSame(substituted, backend.durabilityVerifier());
        substituted.verify(ticket, plan, callerProof);
        assertThrows(IllegalStateException.class, () ->
                service.commitPersistence(ticket, callerProof));
        assertEquals(item, service.snapshot(item.id()).orElseThrow());

        WorldItemDurableProof backendProof = backend.persist(plan);
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitPersistence(ticket, backendProof).status());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitPersistence(ticket, backendProof).status());

        for (String nestedName : List.of(
                "StoreProof", "StoreDurabilityVerifier")) {
            Class<?> nested = java.util.Arrays.stream(
                            StreamedWorldItemPageBackend.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals(nestedName))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Modifier.isPrivate(nested.getModifiers()));
            assertTrue(java.util.Arrays.stream(nested.getDeclaredConstructors())
                    .allMatch(constructor ->
                            Modifier.isPrivate(constructor.getModifiers())));
        }
        assertNoCapabilityComponent(
                LogicalWorldItemSnapshot.class,
                WorldItemRestoreEntry.class,
                WorldItemRuntimeSnapshot.class,
                WorldItemSnapshot.class,
                WorldItemPageSnapshot.class,
                WorldItemPageDescriptor.class,
                WorldItemPageMutation.Upsert.class,
                WorldItemPageMutation.Remove.class,
                WorldItemPagingCheckpoint.class,
                WorldItemPersistencePlan.class,
                WorldItemHibernatePayload.class);
    }

    private static WorldItemPageDescriptor descriptor(
            WorldItemPageCodec codec, WorldItemPageSnapshot page) {
        byte[] bytes = codec.encode(SAVE, page);
        return new WorldItemPageDescriptor(
                page.chunkKey(),
                page.pageRevision(),
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(bytes)),
                page.entries().size(),
                page.entries().size());
    }

    private static void assertNoCapabilityComponent(Class<?>... persistedTypes) {
        for (Class<?> persistedType : persistedTypes) {
            assertTrue(persistedType.isRecord(), persistedType.getName());
            for (java.lang.reflect.RecordComponent component
                    : persistedType.getRecordComponents()) {
                assertTrue(!WorldItemDurableProof.class.isAssignableFrom(
                                component.getType())
                                && !WorldItemPersistenceTicket.class.isAssignableFrom(
                                        component.getType())
                                && !component.getGenericType().getTypeName()
                                        .contains(WorldItemDurableProof.class.getName())
                                && !component.getGenericType().getTypeName()
                                        .contains(WorldItemPersistenceTicket.class.getName()),
                        persistedType.getName() + "." + component.getName());
            }
        }
    }

    private static final class CallerProof implements WorldItemDurableProof {}

    @Test
    void fullPickupFromTwoItemPageDurablyRewritesExactSurvivor() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("two-item-cleanup"));
        StreamedChunkStore store = new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        WorldItemPageCodec pageCodec = new WorldItemPageCodec();
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(
                store,
                (save, page, pageBytes) -> new StreamedChunkStore.ExactChunkCapture(
                        new StreamedChunkPayload(
                                SAVE_ID, page.chunkKey(), "v15", "11".repeat(32),
                                page.pageRevision(), 0L, true, false, 1,
                                new byte[16 * 16],
                                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                                        SaveSectionId.WORLD_ITEM_PAGE,
                                        WorldItemPageCodec.CODEC_VERSION,
                                        true, pageBytes))),
                        () -> true));
        LogicalWorldItemService service = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 4, 0L, SAVE,
                new WorldItemPageCachePolicy(
                        4, 1, 1_024L * 1_024L,
                        64, 4, 1_024L * 1_024L,
                        64, 64L * 1_024L),
                backend.durabilityVerifier(),
                page -> descriptor(pageCodec, page));
        service.deliverWorldTick(100L);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemSnapshot expired = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                0.25, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), 100L)).item().orElseThrow();
        service.deliverWorldTick(101L);
        WorldItemSnapshot removed = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), 101L)).item().orElseThrow();
        WorldItemSnapshot retained = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                1.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), 101L)).item().orElseThrow();
        WorldItemHibernateResult prepared = service.prepareHibernate(
                key, Map.of(expired.id(), expired.revision(),
                        removed.id(), removed.revision(),
                        retained.id(), retained.revision()));
        WorldItemPersistencePlan initial = prepared.persistencePlan().orElseThrow();
        service.commitPersistence(
                prepared.persistenceTicket().orElseThrow(), backend.persist(initial));
        WorldItemPageDescriptor descriptor = initial.intendedCheckpoint().pages().get(0);
        try (WorldItemPageReadView view = backend.openReadView()) {
            var activation = service.prepareActivate(view, descriptor);
            service.commitActivate(activation.ticket().orElseThrow());
        }

        ChunkKey otherKey = new ChunkKey(1, 0);
        WorldItemSnapshot other = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                otherKey.worldOriginX() + 0.5, 4.0, 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 101L)).item().orElseThrow();
        WorldItemHibernateResult initialOther = service.prepareHibernate(
                otherKey, Map.of(other.id(), other.revision()));
        WorldItemPersistencePlan initialOtherPlan =
                initialOther.persistencePlan().orElseThrow();
        service.commitPersistence(
                initialOther.persistenceTicket().orElseThrow(),
                backend.persist(initialOtherPlan));
        service.deliverWorldTick(18_100L);
        try (WorldItemPageReadView view = backend.openReadView()) {
            WorldItemPageDescriptor currentOther = view.checkpoint().pages().stream()
                    .filter(candidate -> candidate.chunkKey().equals(otherKey))
                    .findFirst().orElseThrow();
            var activation = service.prepareActivate(view, currentOther);
            service.commitActivate(activation.ticket().orElseThrow());
        }
        WorldItemHibernateResult otherPrepared = service.prepareHibernate(
                otherKey, Map.of(other.id(), other.revision()));
        WorldItemPersistencePlan otherPlan =
                otherPrepared.persistencePlan().orElseThrow();
        service.commitPersistence(
                otherPrepared.persistenceTicket().orElseThrow(),
                backend.persist(otherPlan));
        try (WorldItemPageReadView normalized = backend.openReadView()) {
            WorldItemPageDescriptor current = normalized.checkpoint().pages().stream()
                    .filter(candidate -> candidate.chunkKey().equals(key))
                    .findFirst().orElseThrow();
            assertEquals(3, current.encodedEntryCount());
            assertEquals(2, current.expectedLiveCountAtCheckpointTick());
        }

        service.commit(service.reserve(removed.id(), 1)
                .reservation().orElseThrow().id());
        assertTrue(service.prepareCleanupPersistence().isEmpty());
        WorldItemHibernateResult cleanup;
        try (WorldItemPageReadView view = backend.openReadView()) {
            WorldItemPageDescriptor current = view.checkpoint().pages().stream()
                    .filter(candidate -> candidate.chunkKey().equals(key))
                    .findFirst().orElseThrow();
            cleanup = service.prepareCleanupPersistence(view, current)
                    .orElseThrow();
        }
        WorldItemPersistencePlan cleanupPlan = cleanup.persistencePlan().orElseThrow();
        WorldItemPageMutation.Upsert rewrite = (WorldItemPageMutation.Upsert)
                cleanupPlan.pageMutations().get(0);
        assertEquals(List.of(retained.id()), rewrite.page().entries().stream()
                .map(entry -> entry.runtime().item().id()).toList());
        service.commitPersistence(
                cleanup.persistenceTicket().orElseThrow(), backend.persist(cleanupPlan));

        try (WorldItemPageReadView reopened = backend.openReadView()) {
            WorldItemPageDescriptor current = reopened.checkpoint().pages().stream()
                    .filter(candidate -> candidate.chunkKey().equals(key))
                    .findFirst().orElseThrow();
            assertEquals(List.of(retained.id()), reopened.read(current).entries().stream()
                    .map(entry -> entry.runtime().item().id()).toList());
        }
    }
}
