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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ExecutorService executor;
    private volatile WorldLoadState state = WorldLoadState.IDLE;
    private volatile WorldLoadFailure failure;
    private boolean loadActive;

    public WorldLoader(
            WorldGenerator generator,
            BlockRegistry blocks,
            WorldGenerationConfig config,
            SafeSpawnSelector spawnSelector,
            ExecutorService executor) {
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
        beginLoad();
        try {
            return submitCancellable(
                    cancellation ->
                            runLoad(world, cancellation),
                    this::markCancelled);
        } catch (RuntimeException | Error submissionFailure) {
            rollbackLoadReservation();
            throw submissionFailure;
        }
    }

    WorldLoadResult load(World world) {
        Objects.requireNonNull(world, "world");
        beginLoad();
        return runLoad(world, new CancellationSignal());
    }

    private WorldLoadResult runLoad(
            World world, CancellationSignal cancellation) {
        LinkedHashSet<ChunkKey> completed = new LinkedHashSet<>();
        List<ChunkGenerationData> generatedData = new ArrayList<>();
        try {
            GenerationContext context = contextFor(config);
            for (ChunkKey key : initialKeys(config.chunkRadius())) {
                checkCancelled(cancellation);
                ChunkGenerationData data =
                        generateInitial(
                                world.chunks(),
                                context,
                                key,
                                completed,
                                cancellation);
                completed.add(key);
                generatedData.add(data);
            }
            checkCancelled(cancellation);
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
            checkCancelled(cancellation);
            String configFingerprint = configFingerprint(config);
            String generationHash =
                    WorldGenerationHasher.hashRegion(config, generatedData);
            checkCancelled(cancellation);
            markSucceeded();
            return new WorldLoadResult(
                    completed,
                    spawn,
                    configFingerprint,
                    generationHash);
        } catch (CancellationException cancelled) {
            markCancelled();
            throw cancelled;
        } catch (WorldLoadException loadFailure) {
            markFailed(loadFailure.failure());
            throw loadFailure;
        } catch (RuntimeException | Error unexpected) {
            if (isCancelled(cancellation)) {
                CancellationException cancelled =
                        cancellationException();
                markCancelled();
                throw cancelled;
            }
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
        List<ChunkKey> orderedKeys = snapshotRebuildKeys(keys);
        return submitCancellable(
                cancellation ->
                        rebuildRegion(
                                world,
                                orderedKeys,
                                config,
                                cancellation),
                () -> {});
    }

    WorldRebuildResult rebuildRegion(
            World world,
            Set<ChunkKey> keys,
            WorldGenerationConfig config) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(config, "config");
        return rebuildRegion(
                world,
                snapshotRebuildKeys(keys),
                config,
                new CancellationSignal());
    }

    private WorldRebuildResult rebuildRegion(
            World world,
            List<ChunkKey> orderedKeys,
            WorldGenerationConfig config,
            CancellationSignal cancellation) {
        GenerationContext context = contextFor(config);
        LinkedHashSet<ChunkKey> committed = new LinkedHashSet<>();
        LinkedHashMap<ChunkKey, ChunkGenerationResult> outcomes =
                new LinkedHashMap<>();
        for (ChunkKey key : orderedKeys) {
            checkCancelled(cancellation);
            ChunkGenerationResult outcome =
                    rebuildOne(
                            world.chunks(),
                            context,
                            key,
                            cancellation);
            outcomes.put(key, outcome);
            if (outcome.status()
                    == ChunkGenerationResult.Status.COMMITTED) {
                committed.add(key);
            }
        }
        checkCancelled(cancellation);
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
            Set<ChunkKey> completed,
            CancellationSignal cancellation) {
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
            checkCancelled(cancellation);
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
        } catch (CancellationException cancelled) {
            failLiveTicket(chunks, ticket, cancelled);
            throw cancelled;
        } catch (WorldLoadException loadFailure) {
            throw loadFailure;
        } catch (RuntimeException | Error generationFailure) {
            if (isCancelled(cancellation)) {
                CancellationException cancelled =
                        cancellationException();
                failLiveTicket(chunks, ticket, cancelled);
                throw cancelled;
            }
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
            ChunkKey key,
            CancellationSignal cancellation) {
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
            checkCancelled(cancellation);
            ChunkGenerationData data =
                    generated.chunkData().orElseThrow();
            validateGeneratedData(chunks, key, data);
            return chunks.commitGeneration(ticket, data);
        } catch (CancellationException cancelled) {
            failLiveTicket(chunks, ticket, cancelled);
            throw cancelled;
        } catch (RuntimeException | Error generationFailure) {
            if (isCancelled(cancellation)) {
                CancellationException cancelled =
                        cancellationException();
                failLiveTicket(chunks, ticket, cancelled);
                throw cancelled;
            }
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

    private static List<ChunkKey> snapshotRebuildKeys(
            Set<ChunkKey> keys) {
        Objects.requireNonNull(keys, "keys");
        return keys.stream()
                .map(
                        key ->
                                Objects.requireNonNull(
                                        key, "rebuild key"))
                .sorted(keyOrder())
                .toList();
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
        if (loadActive) {
            throw new IllegalStateException(
                    "World loading is already running");
        }
        loadActive = true;
        failure = null;
        state = WorldLoadState.RUNNING;
    }

    private synchronized void markSucceeded() {
        if (loadActive) {
            state = WorldLoadState.SUCCEEDED;
            loadActive = false;
        }
    }

    private synchronized void markFailed(
            WorldLoadFailure loadFailure) {
        if (loadActive) {
            failure =
                    Objects.requireNonNull(
                            loadFailure, "loadFailure");
            state = WorldLoadState.FAILED;
            loadActive = false;
        }
    }

    private synchronized void markCancelled() {
        if (loadActive) {
            failure = null;
            state = WorldLoadState.CANCELLED;
            loadActive = false;
        }
    }

    private synchronized void rollbackLoadReservation() {
        if (loadActive) {
            failure = null;
            state = WorldLoadState.IDLE;
            loadActive = false;
        }
    }

    private <T> CompletableFuture<T> submitCancellable(
            CancellableOperation<T> operation,
            Runnable cancellationAction) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(
                cancellationAction, "cancellationAction");
        CancellationSignal cancellation = new CancellationSignal();
        AtomicReference<Future<?>> submittedTask = new AtomicReference<>();
        AtomicReference<TaskPhase> phase =
                new AtomicReference<>(TaskPhase.QUEUED);
        CompletableFuture<T> result =
                new CompletableFuture<>() {
                    @Override
                    public boolean cancel(
                            boolean mayInterruptIfRunning) {
                        boolean cancelled =
                                super.cancel(mayInterruptIfRunning);
                        if (cancelled) {
                            cancellation.cancel();
                            if (phase.compareAndSet(
                                    TaskPhase.QUEUED,
                                    TaskPhase.CANCELLED)) {
                                cancellationAction.run();
                            }
                            Future<?> task = submittedTask.get();
                            if (task != null) {
                                task.cancel(mayInterruptIfRunning);
                            }
                        }
                        return cancelled;
                    }
                };
        Future<?> task =
                executor.submit(
                        () -> {
                            if (!phase.compareAndSet(
                                    TaskPhase.QUEUED,
                                    TaskPhase.RUNNING)) {
                                return;
                            }
                            try {
                                result.complete(
                                        operation.run(cancellation));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            } finally {
                                phase.set(TaskPhase.FINISHED);
                            }
                        });
        submittedTask.set(task);
        if (result.isCancelled()) {
            task.cancel(true);
        }
        return result;
    }

    private static void checkCancelled(
            CancellationSignal cancellation) {
        if (isCancelled(cancellation)) {
            throw cancellationException();
        }
    }

    private static boolean isCancelled(
            CancellationSignal cancellation) {
        return cancellation.isCancelled()
                || Thread.currentThread().isInterrupted();
    }

    private static CancellationException cancellationException() {
        return new CancellationException(
                "World loading was cancelled");
    }

    @FunctionalInterface
    private interface CancellableOperation<T> {
        T run(CancellationSignal cancellation);
    }

    private enum TaskPhase {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED
    }

    private static final class CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
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
