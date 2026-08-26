package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.streaming.StreamedChunkCodec;
import com.gaia.save.streaming.StreamedChunkIndexCodec;
import com.gaia.save.streaming.StreamedChunkMutation;
import com.gaia.save.streaming.StreamedChunkPayload;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedChunkUnloadPlan;
import com.gaia.save.streaming.StreamedPersistenceTransaction;
import com.gaia.world.streaming.ChunkStreamingPipeline;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationHasher;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositoryRestoreResult;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused RED for the production unload worker's persisted base-identity binding. */
class ProductionChunkUnloadBaseIdentityTest {
    @TempDir
    Path root;

    @Test
    void deterministicCleanUnloadSkipsDurableWriteButModifiedBytesRequireIt()
            throws Exception {
        WorldGenerationConfig generationConfig = config();
        SaveGameSnapshot.StaticMetadata metadata = canonicalMetadata(generationConfig);
        ChunkKey key = new ChunkKey(-5, -10);
        byte[] generatedBlocks = filled((byte) 3);
        ChunkGenerationData generated = new ChunkGenerationData(
                key, 1, generatedBlocks);
        String baseHash = WorldGenerationHasher.hashChunk(
                generationConfig, generated);
        StreamedChunkUnloadPlan clean = new StreamedChunkUnloadPlan(
                capture(metadata, key, baseHash, 1L, 0L, generatedBlocks),
                java.util.Optional.empty(),
                List.of());
        StreamedChunkUnloadPlan modified = new StreamedChunkUnloadPlan(
                capture(metadata, key, baseHash, 2L, 0L, filled((byte) 4)),
                java.util.Optional.empty(),
                List.of());

        assertFalse(GameSessionFactory.requiresStreamingPersistence(
                clean, generated));
        assertTrue(GameSessionFactory.requiresStreamingPersistence(
                modified, generated));
    }

    @Test
    void modifiedResidentUnloadRetainsItsPersistedGeneratorBaseIdentity()
            throws Exception {
        SaveGameSnapshot.StaticMetadata metadata =
                canonicalMetadata(config());
        WorldGenerationConfig generationConfig = config();
        ChunkKey key = new ChunkKey(0, 0);
        byte[] generatedBaseBlocks = filled((byte) 1);
        byte[] modifiedBlocks = filled((byte) 2);
        ChunkGenerationData generatedBase =
                new ChunkGenerationData(key, 1, generatedBaseBlocks);
        String generatedBaseHash = WorldGenerationHasher.hashChunk(
                generationConfig, generatedBase);
        assertNotEquals(sha256(generatedBaseBlocks), generatedBaseHash,
                "raw voxel SHA-256 is not the canonical generated-base identity");
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                metadata.saveGameId(),
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        StreamedChunkStore.ExactChunkCapture migrated = capture(
                metadata, key, generatedBaseHash, 1L, 0L, modifiedBlocks);
        assertEquals(
                StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(migrated)),
                        List.of(),
                        () -> true)).status());

        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        assertEquals(
                ChunkRepositoryRestoreResult.Status.RESTORED,
                repository.restoreCanonical(new ChunkRepositorySnapshot(
                        1,
                        1L,
                        List.of(ChunkSnapshot.of(
                                key, 1L, 1, modifiedBlocks)))).status());
        var repositoryPreparation = repository.prepareStreamingUnload(key);
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(guard, 4, 0L);
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                new PhysicsWorld(
                        new CollisionWorld(
                                new World(),
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f()),
                guard,
                new WorldItemPhysicsConfig(0.50f, 2));
        try {
            ChunkStreamingPipeline.UnloadLifecycle lifecycle = lifecycle(
                    metadata, logical, physical);
            ChunkStreamingPipeline.PreparedUnload prepared =
                    lifecycle.prepare(repositoryPreparation);
            var original = prepared.plan();
            var rebound = GameSessionFactory.bindGeneratedBaseIdentity(
                    original,
                    generatedBase,
                    generationConfig);
            var before = original.chunkCapture().payload();
            var after = rebound.chunkCapture().payload();

            assertNotSame(original, rebound);
            assertEquals(before.saveGameId(), after.saveGameId());
            assertEquals(before.key(), after.key());
            assertEquals(before.generatorVersion(), after.generatorVersion());
            assertEquals(generatedBaseHash, after.baseHash());
            assertEquals(before.revision(), after.revision());
            assertEquals(before.persistedRevision(), after.persistedRevision());
            assertEquals(before.persistenceRequired(), after.persistenceRequired());
            assertEquals(before.voxelModified(), after.voxelModified());
            assertEquals(before.worldHeight(), after.worldHeight());
            assertArrayEquals(before.copyCanonicalVoxels(), after.copyCanonicalVoxels());
            assertEquals(before.extensions(), after.extensions());
            assertSame(
                    original.chunkCapture().stillCurrent(),
                    rebound.chunkCapture().stillCurrent());
            assertSame(original.worldItems(), rebound.worldItems());
            assertSame(original.requiredGlobals(), rebound.requiredGlobals());
            assertThrows(IllegalArgumentException.class, () ->
                    GameSessionFactory.bindGeneratedBaseIdentity(
                            original,
                            new ChunkGenerationData(
                                    new ChunkKey(1, 0), 1, generatedBaseBlocks),
                            generationConfig));
            assertThrows(IllegalArgumentException.class, () ->
                    GameSessionFactory.bindGeneratedBaseIdentity(
                            original,
                            new ChunkGenerationData(key, 2, new byte[16 * 2 * 16]),
                            generationConfig));
            StreamedChunkUnloadPlan incompatibleGenerator =
                    withGeneratorVersion(original, "gaia-v1");
            assertThrows(IllegalArgumentException.class, () ->
                    GameSessionFactory.bindGeneratedBaseIdentity(
                            incompatibleGenerator,
                            generatedBase,
                            generationConfig));
            var transaction = new StreamedPersistenceTransaction(
                    List.of(new StreamedChunkMutation.Upsert(
                            rebound.chunkCapture())),
                    rebound.requiredGlobals(),
                    rebound.chunkCapture().stillCurrent());

            StreamedChunkStore.CommitResult result =
                    store.commitTransaction(transaction);

            assertEquals(
                    StreamedChunkStore.CommitResult.Status.SUCCESS,
                    result.status(),
                    () -> "production unload must rebind the detached capture to "
                            + "the generated base identity; diagnostics="
                            + result.diagnostics().stream()
                                    .map(diagnostic -> diagnostic.code() + ": "
                                            + diagnostic.message()
                                            + diagnostic.cause()
                                                    .map(cause -> " cause=" + cause)
                                                    .orElse(""))
                                    .toList()
                            + " expectedBaseHash=" + generatedBaseHash
                            + " actualBaseHash=" + after.baseHash());
        } finally {
            physical.close();
            logical.close();
        }
    }

    @Test
    void persistedLoadCarriesExactDurableRevisionIntoItsNextModifiedUnload()
            throws Exception {
        WorldGenerationConfig generationConfig = config();
        SaveGameSnapshot.StaticMetadata metadata =
                canonicalMetadata(generationConfig);
        ChunkKey key = new ChunkKey(0, 0);
        byte[] generatedBlocks = filled((byte) 1);
        byte[] persistedBlocks = filled((byte) 2);
        ChunkGenerationData generated = new ChunkGenerationData(
                key, 1, generatedBlocks);
        String baseHash = WorldGenerationHasher.hashChunk(
                generationConfig, generated);
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                metadata.saveGameId(),
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        assertEquals(
                StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(metadata, key, baseHash, 1L, 0L,
                                        persistedBlocks))),
                        List.of(),
                        () -> true)).status());

        ChunkRepository repository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        var loadTicket = repository.request(
                key,
                1L,
                com.overlord.voxel.ChunkStreamingTicket.SourcePreference.LOAD);
        assertEquals(
                com.overlord.voxel.ChunkStreamingPublication.Status.PUBLISHED,
                repository.publish(
                        loadTicket,
                        new ChunkGenerationData(key, 1, persistedBlocks),
                        new com.overlord.voxel.ChunkStreamingTicket.BaseIdentity(
                                com.overlord.voxel.ChunkStreamingTicket
                                        .SourcePreference.LOAD,
                                0L,
                                1L)).status());
        assertTrue(repository.setBlock(0, 0, 0, (byte) 3));

        var preparation = repository.prepareStreamingUnload(key);
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        LogicalWorldItemService logical = new LogicalWorldItemService(
                guard, 4, 0L);
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                new PhysicsWorld(
                        new CollisionWorld(
                                new World(),
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f()),
                guard,
                new WorldItemPhysicsConfig(0.50f, 2));
        try {
            var prepared = lifecycle(metadata, logical, physical)
                    .prepare(preparation);
            var rebound = GameSessionFactory.bindGeneratedBaseIdentity(
                    prepared.plan(), generated, generationConfig);

            assertEquals(1L,
                    rebound.chunkCapture().payload().persistedRevision());
            assertEquals(
                    StreamedChunkStore.CommitResult.Status.SUCCESS,
                    store.commitTransaction(new StreamedPersistenceTransaction(
                            List.of(new StreamedChunkMutation.Upsert(
                                    rebound.chunkCapture())),
                            List.of(),
                            rebound.chunkCapture().stillCurrent())).status());
        } finally {
            physical.close();
            logical.close();
        }
    }

    @Test
    void productionLoadExpectedBaseUsesCanonicalGenerationIdentity() throws Exception {
        WorldGenerationConfig generationConfig = config();
        SaveGameSnapshot.StaticMetadata metadata = canonicalMetadata(generationConfig);
        ChunkGenerationData generated = new ChunkGenerationData(
                new ChunkKey(-3, 4), 1, filled((byte) 7));
        String canonical = WorldGenerationHasher.hashChunk(
                generationConfig, generated);

        StreamedChunkStore.ExpectedBase expected =
                GameSessionFactory.expectedGeneratedBase(
                        metadata, generated, generationConfig);

        assertEquals(metadata.generatorVersion(), expected.generatorVersion());
        assertEquals(canonical, expected.baseHash());
        assertNotEquals(sha256(generated.copyBlocks()), expected.baseHash(),
                "load validation must not use raw voxel SHA-256");
        assertThrows(IllegalArgumentException.class, () ->
                GameSessionFactory.expectedGeneratedBase(
                        copyMetadata(
                                metadata,
                                "gaia-v1",
                                metadata.generatorConfigFingerprint()),
                        generated,
                        generationConfig));
        assertThrows(IllegalArgumentException.class, () ->
                GameSessionFactory.expectedGeneratedBase(
                        copyMetadata(
                                metadata,
                                metadata.generatorVersion(),
                                "a".repeat(64)),
                        generated,
                        generationConfig));
    }

    private static ChunkStreamingPipeline.UnloadLifecycle lifecycle(
            SaveGameSnapshot.StaticMetadata metadata,
            LogicalWorldItemService logical,
            PhysicalWorldItemSystem physical) throws Exception {
        Class<?> type = Class.forName(
                "com.gaia.session.GameSessionFactory$ProductionUnloadLifecycle");
        Constructor<?> constructor = type.getDeclaredConstructor(
                SaveGameSnapshot.StaticMetadata.class,
                LogicalWorldItemService.class,
                PhysicalWorldItemSystem.class,
                GameSessionFactory.UnloadSessionCapture.class);
        constructor.setAccessible(true);
        return (ChunkStreamingPipeline.UnloadLifecycle) constructor.newInstance(
                metadata,
                logical,
                physical,
                (GameSessionFactory.UnloadSessionCapture) () -> {
                    throw new AssertionError("chunk-only unload must not capture session state");
                });
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            SaveGameSnapshot.StaticMetadata metadata,
            ChunkKey key,
            String baseHash,
            long revision,
            long persistedRevision,
            byte[] blocks) {
        return new StreamedChunkStore.ExactChunkCapture(
                new StreamedChunkPayload(
                        metadata.saveGameId(),
                        key,
                        metadata.generatorVersion(),
                        baseHash,
                        revision,
                        persistedRevision,
                        true,
                        true,
                        1,
                        blocks,
                        List.of()),
                () -> true);
    }

    private static StreamedChunkUnloadPlan withGeneratorVersion(
            StreamedChunkUnloadPlan source,
            String generatorVersion) {
        StreamedChunkPayload payload = source.chunkCapture().payload();
        StreamedChunkPayload changed = new StreamedChunkPayload(
                payload.saveGameId(),
                payload.key(),
                generatorVersion,
                payload.baseHash(),
                payload.revision(),
                payload.persistedRevision(),
                payload.persistenceRequired(),
                payload.voxelModified(),
                payload.worldHeight(),
                payload.copyCanonicalVoxels(),
                payload.extensions());
        return new com.gaia.save.streaming.StreamedChunkUnloadPlan(
                new StreamedChunkStore.ExactChunkCapture(
                        changed, source.chunkCapture().stillCurrent()),
                source.worldItems(),
                source.requiredGlobals());
    }

    private static byte[] filled(byte value) {
        byte[] blocks = new byte[16 * 16];
        java.util.Arrays.fill(blocks, value);
        return blocks;
    }

    private static WorldGenerationConfig config() {
        WorldGenerationConfig template =
                WorldGenerationConfig.visualRevisionCandidate();
        return new WorldGenerationConfig(
                12345L,
                template.algorithmVersion(),
                2,
                template.biome(),
                template.height(),
                template.cave(),
                template.surface(),
                template.decoration(),
                template.spawn());
    }

    private static SaveGameSnapshot.StaticMetadata canonicalMetadata(
            WorldGenerationConfig config) throws Exception {
        SaveGameSnapshot.StaticMetadata base =
                GameSessionSaveLifecycleTest.snapshot().metadata();
        return copyMetadata(
                base,
                "gaia-v" + config.algorithmVersion(),
                sha256(config.canonicalFingerprintInput()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static SaveGameSnapshot.StaticMetadata copyMetadata(
            SaveGameSnapshot.StaticMetadata base,
            String generatorVersion,
            String generatorFingerprint) {
        return new SaveGameSnapshot.StaticMetadata(
                base.formatVersion(),
                base.gameVersion(),
                base.saveGameId(),
                base.displayName(),
                base.createdAt(),
                base.worldSeed(),
                generatorVersion,
                generatorFingerprint,
                base.chunkRadius(),
                base.worldHeight(),
                base.summary());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
