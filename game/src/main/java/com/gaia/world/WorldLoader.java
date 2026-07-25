package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationStageResult;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationHasher;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkGenerationResult;
import com.overlord.voxel.ChunkGenerationStatus;
import com.overlord.voxel.ChunkGenerationTicket;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.World;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.joml.Vector3f;

public final class WorldLoader {
    private static final ResourceLocation GENERATION_FAILED =
            ResourceLocation.parse("gaia:generation_failed");
    private static final ResourceLocation GENERATION_COMMIT_FAILED =
            ResourceLocation.parse("gaia:generation_commit_failed");
    private static final ResourceLocation NO_SAFE_SPAWN =
            ResourceLocation.parse("gaia:no_safe_spawn");

    private final WorldGenerator generator;
    private final BlockRegistry blocks;
    private final WorldGenerationConfig config;
    private final SafeSpawnSelector spawnSelector;
    private final Executor executor;
    private volatile WorldLoadState state = WorldLoadState.IDLE;
    private volatile WorldLoadFailure failure;

    public WorldLoader(
            WorldGenerator generator,
            BlockRegistry blocks,
            WorldGenerationConfig config,
            SafeSpawnSelector spawnSelector,
            Executor executor) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.config = Objects.requireNonNull(config, "config");
        this.spawnSelector =
                Objects.requireNonNull(spawnSelector, "spawnSelector");
        this.executor = Objects.requireNonNull(executor, "executor");
        validateInclusiveArea(config.chunkRadius());
    }

    public CompletableFuture<WorldLoadResult> loadAsync(World world) {
        Objects.requireNonNull(world, "world");
        return CompletableFuture.supplyAsync(() -> load(world), executor);
    }

    WorldLoadResult load(World world) {
        Objects.requireNonNull(world, "world");
        beginLoad();
        LinkedHashSet<ChunkKey> completed = new LinkedHashSet<>();
        List<ChunkGenerationData> generatedData = new ArrayList<>();
        try {
            GenerationContext context = contextFor(config);
            for (ChunkKey key : initialKeys(config.chunkRadius())) {
                checkCancelled();
                ChunkGenerationData data =
                        generateInitial(world.chunks(), context, key, completed);
                completed.add(key);
                generatedData.add(data);
            }
            checkCancelled();
            Vector3f spawn =
                    spawnSelector
                            .find(world, completed, config)
                            .orElseThrow(
                                    () ->
                                            loadException(
                                                    completed,
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    NO_SAFE_SPAWN,
                                                    new IllegalStateException(
                                                            "No safe spawn "
                                                                    + "exists in the committed "
                                                                    + "initial region")));
            checkCancelled();
            String configFingerprint = configFingerprint(config);
            String generationHash =
                    WorldGenerationHasher.hashRegion(config, generatedData);
            checkCancelled();
            markSucceeded();
            return new WorldLoadResult(
                    completed,
                    spawn,
                    configFingerprint,
                    generationHash);
        } catch (CancellationException cancellation) {
            markCancelled();
            throw cancellation;
        } catch (WorldLoadException loadFailure) {
            markFailed(loadFailure.failure());
            throw loadFailure;
        } catch (RuntimeException | Error unexpected) {
            WorldLoadException loadFailure =
                    loadException(
                            completed,
                            Optional.empty(),
                            Optional.empty(),
                            GENERATION_FAILED,
                            unexpected);
            markFailed(loadFailure.failure());
            throw loadFailure;
        }
    }

    public CompletableFuture<WorldRebuildResult> rebuildRegionAsync(
            World world,
            Set<ChunkKey> keys,
            WorldGenerationConfig config) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(config, "config");
        return CompletableFuture.supplyAsync(
                () -> rebuildRegion(world, keys, config),
                executor);
    }

    WorldRebuildResult rebuildRegion(
            World world,
            Set<ChunkKey> keys,
            WorldGenerationConfig config) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(config, "config");
        GenerationContext context = contextFor(config);
        List<ChunkKey> orderedKeys =
                keys.stream()
                        .map(
                                key ->
                                        Objects.requireNonNull(
                                                key, "rebuild key"))
                        .sorted(keyOrder())
                        .toList();
        LinkedHashSet<ChunkKey> committed = new LinkedHashSet<>();
        LinkedHashMap<ChunkKey, ChunkGenerationResult> outcomes =
                new LinkedHashMap<>();
        for (ChunkKey key : orderedKeys) {
            checkCancelled();
            ChunkGenerationResult outcome =
                    rebuildOne(world.chunks(), context, key);
            outcomes.put(key, outcome);
            if (outcome.status()
                    == ChunkGenerationResult.Status.COMMITTED) {
                committed.add(key);
            }
        }
        checkCancelled();
        return new WorldRebuildResult(committed, outcomes);
    }

    public WorldLoadState state() {
        return state;
    }

    public Optional<WorldLoadFailure> failure() {
        return Optional.ofNullable(failure);
    }

    private ChunkGenerationData generateInitial(
            ChunkRepository chunks,
            GenerationContext context,
            ChunkKey key,
            Set<ChunkKey> completed) {
        ChunkGenerationTicket ticket;
        try {
            ticket =
                    chunks.beginGeneration(
                            key, ChunkGenerationMode.INITIAL);
        } catch (RuntimeException | Error beginFailure) {
            throw loadException(
                    completed,
                    Optional.of(key),
                    Optional.empty(),
                    GENERATION_FAILED,
                    beginFailure);
        }

        try {
            WorldGenerationResult generated =
                    Objects.requireNonNull(
                            generator.generate(context, key),
                            "generator result");
            if (!generated.succeeded()) {
                GenerationStageResult failedStage =
                        generated.failedStage().orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "World generator failed "
                                                        + "without a failed stage"));
                Throwable cause =
                        failedStage.failure().orElseThrow();
                chunks.failGeneration(ticket, cause);
                throw loadException(
                        completed,
                        Optional.of(key),
                        Optional.of(failedStage.stageId()),
                        GENERATION_FAILED,
                        cause);
            }
            checkCancelled();
            ChunkGenerationData data =
                    generated.chunkData().orElseThrow();
            validateGeneratedData(chunks, key, data);
            ChunkGenerationResult committed =
                    chunks.commitGeneration(ticket, data);
            if (committed.status()
                    != ChunkGenerationResult.Status.COMMITTED) {
                IllegalStateException cause =
                        new IllegalStateException(
                                "Generation commit "
                                        + committed.status()
                                        + " for "
                                        + key);
                throw loadException(
                        completed,
                        Optional.of(key),
                        Optional.empty(),
                        GENERATION_COMMIT_FAILED,
                        cause);
            }
            return data;
        } catch (CancellationException cancellation) {
            failLiveTicket(chunks, ticket, cancellation);
            throw cancellation;
        } catch (WorldLoadException loadFailure) {
            throw loadFailure;
        } catch (RuntimeException | Error generationFailure) {
            failLiveTicket(chunks, ticket, generationFailure);
            throw loadException(
                    completed,
                    Optional.of(key),
                    Optional.empty(),
                    GENERATION_FAILED,
                    generationFailure);
        }
    }

    private ChunkGenerationResult rebuildOne(
            ChunkRepository chunks,
            GenerationContext context,
            ChunkKey key) {
        ChunkGenerationTicket ticket;
        try {
            ticket =
                    chunks.beginGeneration(
                            key, ChunkGenerationMode.REBUILD);
        } catch (RuntimeException | Error beginFailure) {
            return failedOutcome(chunks, key, beginFailure);
        }

        try {
            WorldGenerationResult generated =
                    Objects.requireNonNull(
                            generator.generate(context, key),
                            "generator result");
            if (!generated.succeeded()) {
                Throwable cause =
                        generated.failedStage()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "World generator failed "
                                                                + "without a failed stage"))
                                .failure()
                                .orElseThrow();
                return chunks.failGeneration(ticket, cause);
            }
            checkCancelled();
            ChunkGenerationData data =
                    generated.chunkData().orElseThrow();
            validateGeneratedData(chunks, key, data);
            return chunks.commitGeneration(ticket, data);
        } catch (CancellationException cancellation) {
            failLiveTicket(chunks, ticket, cancellation);
            throw cancellation;
        } catch (RuntimeException | Error generationFailure) {
            ChunkGenerationResult failed =
                    failLiveTicket(chunks, ticket, generationFailure);
            if (failed.status()
                    == ChunkGenerationResult.Status.FAILED) {
                return failed;
            }
            return failedOutcome(chunks, key, generationFailure);
        }
    }

    private static ChunkGenerationResult failLiveTicket(
            ChunkRepository chunks,
            ChunkGenerationTicket ticket,
            Throwable failure) {
        if (chunks.generationStatus(ticket.key())
                == ChunkGenerationStatus.GENERATING) {
            return chunks.failGeneration(ticket, failure);
        }
        return new ChunkGenerationResult(
                ChunkGenerationResult.Status.CONFLICT,
                ticket.key(),
                chunks.revision(ticket.key()),
                Optional.empty());
    }

    private static ChunkGenerationResult failedOutcome(
            ChunkRepository chunks,
            ChunkKey key,
            Throwable failure) {
        return new ChunkGenerationResult(
                ChunkGenerationResult.Status.FAILED,
                key,
                chunks.revision(key),
                Optional.of(failure));
    }

    private GenerationContext contextFor(
            WorldGenerationConfig generationConfig) {
        return new GenerationContext(
                generationConfig,
                GenerationBlockPalette.from(blocks),
                new DeterministicCoordinateSampler(
                        generationConfig.seed(),
                        generationConfig.algorithmVersion()));
    }

    private static void validateGeneratedData(
            ChunkRepository chunks,
            ChunkKey requestedKey,
            ChunkGenerationData data) {
        if (!requestedKey.equals(data.key())) {
            throw new IllegalStateException(
                    "World generator returned key "
                            + data.key()
                            + " for requested key "
                            + requestedKey);
        }
        int repositoryHeight = chunks.worldHeight();
        if (data.worldHeight() != repositoryHeight) {
            throw new IllegalStateException(
                    "World generator returned world height "
                            + data.worldHeight()
                            + " for repository world height "
                            + repositoryHeight);
        }
    }

    private static List<ChunkKey> initialKeys(int radius) {
        validateInclusiveArea(radius);
        List<ChunkKey> keys =
                new ArrayList<>(
                        Math.multiplyExact(
                                Math.addExact(
                                        Math.multiplyExact(radius, 2),
                                        1),
                                Math.addExact(
                                        Math.multiplyExact(radius, 2),
                                        1)));
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                keys.add(new ChunkKey(x, z));
            }
        }
        return Collections.unmodifiableList(keys);
    }

    private static void validateInclusiveArea(int radius) {
        try {
            int diameter =
                    Math.addExact(Math.multiplyExact(radius, 2), 1);
            Math.multiplyExact(diameter, diameter);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Inclusive chunk radius area overflows", overflow);
        }
    }

    private static Comparator<ChunkKey> keyOrder() {
        return Comparator.comparingInt(ChunkKey::x)
                .thenComparingInt(ChunkKey::z);
    }

    private static WorldLoadException loadException(
            Set<ChunkKey> completed,
            Optional<ChunkKey> failedChunk,
            Optional<ResourceLocation> failedStage,
            ResourceLocation code,
            Throwable cause) {
        return new WorldLoadException(
                new WorldLoadFailure(
                        completed,
                        failedChunk,
                        failedStage,
                        code,
                        cause));
    }

    private synchronized void beginLoad() {
        if (state == WorldLoadState.RUNNING) {
            throw new IllegalStateException(
                    "World loading is already running");
        }
        failure = null;
        state = WorldLoadState.RUNNING;
    }

    private synchronized void markSucceeded() {
        state = WorldLoadState.SUCCEEDED;
    }

    private synchronized void markFailed(
            WorldLoadFailure loadFailure) {
        failure = Objects.requireNonNull(loadFailure, "loadFailure");
        state = WorldLoadState.FAILED;
    }

    private synchronized void markCancelled() {
        failure = null;
        state = WorldLoadState.CANCELLED;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "World loading was cancelled");
        }
    }

    private static String configFingerprint(
            WorldGenerationConfig config) {
        MessageDigest digest = sha256();
        digest.update(
                config.canonicalFingerprintInput()
                        .getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(
                    Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
