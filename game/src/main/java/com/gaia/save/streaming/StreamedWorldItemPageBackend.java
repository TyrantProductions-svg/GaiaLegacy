package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurabilityVerifier;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPageSource;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

/** WorldItem-specific decoder over one generic Task 4 index generation. */
public final class StreamedWorldItemPageBackend implements WorldItemPageSource {
    private final StreamedChunkStore store;
    private final InitialChunkCaptureSource initialChunkCaptureSource;
    private final AtomicLong staleChunkCaptures = new AtomicLong();
    private final AtomicLong staleWorldItemPlans = new AtomicLong();
    private final AtomicLong staleChunkRevisions = new AtomicLong();
    private final AtomicLong staleStoreTransactions = new AtomicLong();
    private final StreamedChunkStore.ProofScope proofScope;
    private final WorldItemDurabilityVerifier durabilityVerifier =
            new StoreDurabilityVerifier();

    public StreamedWorldItemPageBackend(StreamedChunkStore store) {
        this(store, (save, page, bytes) -> {
            throw invalid("A new WorldItem page requires an exact Chunk capture");
        });
    }

    public StreamedWorldItemPageBackend(
            StreamedChunkStore store,
            InitialChunkCaptureSource initialChunkCaptureSource) {
        this.store = Objects.requireNonNull(store, "store");
        this.proofScope = store.proofScope();
        this.initialChunkCaptureSource = Objects.requireNonNull(
                initialChunkCaptureSource, "initialChunkCaptureSource");
    }

    /** Publishes one exact semantic plan through Task 4's generic commit point. */
    WorldItemDurableProof persist(WorldItemPersistencePlan plan) {
        return persistAtomically(plan, ignored -> List.of()).proof();
    }

    /** Publishes the page checkpoint and its load-bearing session extension at one root. */
    AtomicPersistenceResult persistAtomically(
            WorldItemPersistencePlan plan,
            Function<AtomicCheckpointBinding, List<StreamedGlobalExtensionMutation>>
                    additionalGlobals) {
        return persistAtomically(
                plan, additionalGlobals, List.of(), Map.of(), () -> true);
    }

    /** Adds owner-prepared dirty resident Chunks to the same invisible candidate. */
    AtomicPersistenceResult persistAtomically(
            WorldItemPersistencePlan plan,
            Function<AtomicCheckpointBinding, List<StreamedGlobalExtensionMutation>>
                    additionalGlobals,
            List<StreamedChunkStore.ExactChunkCapture> chunkCaptures) {
        return persistAtomically(
                plan, additionalGlobals, chunkCaptures, Map.of(), () -> true);
    }

    /** Adds a ticket-free aggregate freshness check to final root publication. */
    AtomicPersistenceResult persistAtomically(
            WorldItemPersistencePlan plan,
            Function<AtomicCheckpointBinding, List<StreamedGlobalExtensionMutation>>
                    additionalGlobals,
            List<StreamedChunkStore.ExactChunkCapture> chunkCaptures,
            BooleanSupplier additionalStillCurrent) {
        return persistAtomically(
                plan,
                additionalGlobals,
                chunkCaptures,
                Map.of(),
                additionalStillCurrent);
    }

    public StreamedChunkUnloadResult persistUnload(
            StreamedChunkUnloadPlan plan) {
        StreamedChunkUnloadPlan checked = Objects.requireNonNull(plan, "plan");
        if (!checked.chunkCapture().stillCurrent().getAsBoolean()) {
            staleChunkCaptures.incrementAndGet();
            return StreamedChunkUnloadResult.stale();
        }
        if (checked.worldItems().map(worldItems ->
                !worldItems.stillCurrent().getAsBoolean()).orElse(false)) {
            staleWorldItemPlans.incrementAndGet();
            return StreamedChunkUnloadResult.stale();
        }
        try {
            if (checked.worldItems().isPresent()) {
                AtomicPersistenceResult result = persistAtomically(
                        checked.worldItems().orElseThrow(),
                        binding -> boundSessionGlobal(checked, binding),
                        List.of(checked.chunkCapture()),
                        Map.of(
                                checked.chunkCapture().payload().key(),
                                checked.voxelModified()),
                        () -> true);
                return checked.voxelModified()
                                && result.canonicalChunkRevisionPublished()
                        ? StreamedChunkUnloadResult.success(
                                Optional.of(result.proof()),
                                checked.chunkCapture().payload().revision())
                        : StreamedChunkUnloadResult.success(
                                Optional.of(result.proof()));
            }
            StreamedChunkStore.ExactChunkCapture capture =
                    checked.chunkCapture();
            StreamedChunkPayload payload = capture.payload();
            StreamedChunkIndex currentIndex = store.readCurrentIndex();
            StreamedChunkIndex.Entry current = currentIndex
                    .entry(payload.key()).orElse(null);
            if (current == null && payload.persistedRevision() != 0L) {
                staleChunkRevisions.incrementAndGet();
                return StreamedChunkUnloadResult.stale();
            }
            if (current != null
                    && current.revision() != payload.persistedRevision()) {
                staleChunkRevisions.incrementAndGet();
                return StreamedChunkUnloadResult.stale();
            }
            StreamedChunkPayload existing = null;
            if (current != null) {
                StreamedChunkStore.ReadResult currentPayload = store.read(
                        payload.saveGameId(),
                        payload.key(),
                        new StreamedChunkStore.ExpectedBase(
                                payload.generatorVersion(), payload.baseHash()));
                if (currentPayload.status()
                        != StreamedChunkStore.ReadResult.Status.FOUND) {
                    return StreamedChunkUnloadResult.failed();
                }
                existing = currentPayload.payload().orElseThrow();
                if (checked.voxelModified()) {
                    capture = preserveDurableNonDetailExtensions(capture, existing);
                    payload = capture.payload();
                }
                if (payload.revision() == current.revision()
                        && ChunkDetailPersistence.canonicalStateEquals(existing, payload)
                        && checked.requiredGlobals().isEmpty()) {
                    return StreamedChunkUnloadResult.success(Optional.empty());
                }
            }
            if (!checked.voxelModified()) {
                if (current == null) {
                    return StreamedChunkUnloadResult.success(Optional.empty());
                }
                if (existing.extensions().isEmpty()) {
                    StreamedChunkStore.CommitResult removed =
                            store.commitTransaction(
                                    new StreamedPersistenceTransaction(
                                            List.of(new StreamedChunkMutation.Remove(
                                                    payload.key(),
                                                    current.revision(),
                                                    current.payloadHash())),
                                            checked.requiredGlobals(),
                                            capture.stillCurrent()));
                    return unloadResult(removed);
                }
                boolean required = existing.extensions().stream()
                        .anyMatch(StreamedChunkPayload.ExtensionDescriptor::required);
                StreamedChunkPayload retained = new StreamedChunkPayload(
                        payload.saveGameId(),
                        payload.key(),
                        payload.generatorVersion(),
                        payload.baseHash(),
                        payload.revision(),
                        payload.persistedRevision(),
                        required,
                        false,
                        payload.worldHeight(),
                        payload.copyCanonicalVoxels(),
                        existing.extensions());
                capture = new StreamedChunkStore.ExactChunkCapture(
                        retained, capture.stillCurrent());
            }
            StreamedChunkStore.CommitResult result = store.commitTransaction(
                    new StreamedPersistenceTransaction(
                            List.of(new StreamedChunkMutation.Upsert(
                                    capture)),
                            checked.requiredGlobals(),
                            capture.stillCurrent()));
            return unloadResult(result, capture.payload().revision());
        } catch (RuntimeException failure) {
            return StreamedChunkUnloadResult.failed();
        }
    }

    private static StreamedChunkStore.ExactChunkCapture
            preserveDurableNonDetailExtensions(
                    StreamedChunkStore.ExactChunkCapture capture,
                    StreamedChunkPayload existing) {
        StreamedChunkPayload incoming = capture.payload();
        List<StreamedChunkPayload.ExtensionDescriptor> merged = new ArrayList<>();
        for (StreamedChunkPayload.ExtensionDescriptor extension
                : existing.extensions()) {
            if (!extension.sectionId().equals(SaveSectionId.DETAIL_BLOCKS)) {
                merged.add(extension);
            }
        }
        for (StreamedChunkPayload.ExtensionDescriptor extension
                : incoming.extensions()) {
            if (extension.sectionId().equals(SaveSectionId.DETAIL_BLOCKS)) {
                merged.add(extension);
                continue;
            }
            Optional<StreamedChunkPayload.ExtensionDescriptor> durable =
                    existing.extensions().stream()
                            .filter(candidate -> candidate.sectionId().equals(
                                    extension.sectionId()))
                            .findFirst();
            if (durable.isEmpty() || !durable.orElseThrow().equals(extension)) {
                throw invalid(
                        "Chunk capture cannot replace independently owned extension "
                                + extension.sectionId().value());
            }
        }
        boolean requiredExtension = merged.stream()
                .anyMatch(StreamedChunkPayload.ExtensionDescriptor::required);
        StreamedChunkPayload preserved = new StreamedChunkPayload(
                incoming.saveGameId(),
                incoming.key(),
                incoming.generatorVersion(),
                incoming.baseHash(),
                incoming.revision(),
                incoming.persistedRevision(),
                incoming.voxelModified() || requiredExtension,
                incoming.voxelModified(),
                incoming.worldHeight(),
                incoming.copyCanonicalVoxels(),
                merged);
        return new StreamedChunkStore.ExactChunkCapture(
                preserved, capture.stillCurrent());
    }

    /** Fixed-size operational counters; no per-operation history is retained. */
    public StaleMetrics staleMetrics() {
        return new StaleMetrics(
                staleChunkCaptures.get(),
                staleWorldItemPlans.get(),
                staleChunkRevisions.get(),
                staleStoreTransactions.get());
    }

    public record StaleMetrics(
            long chunkCaptureFreshness,
            long worldItemPlanFreshness,
            long chunkRevisionMismatch,
            long storeTransaction) {}

    private StreamedChunkUnloadResult unloadResult(
            StreamedChunkStore.CommitResult result) {
        return unloadResult(result, 0L);
    }

    private StreamedChunkUnloadResult unloadResult(
            StreamedChunkStore.CommitResult result,
            long persistedChunkRevision) {
        if (result.status() == StreamedChunkStore.CommitResult.Status.STALE) {
            staleStoreTransactions.incrementAndGet();
        }
        return switch (result.status()) {
            case SUCCESS -> persistedChunkRevision > 0L
                    ? StreamedChunkUnloadResult.success(
                            Optional.empty(), persistedChunkRevision)
                    : StreamedChunkUnloadResult.success(Optional.empty());
            case STALE -> StreamedChunkUnloadResult.stale();
            case FAILED, BLOCKING_FAILURE -> StreamedChunkUnloadResult.failed();
        };
    }

    private static List<StreamedGlobalExtensionMutation> boundSessionGlobal(
            StreamedChunkUnloadPlan plan,
            AtomicCheckpointBinding binding) {
        StreamedSessionCheckpoint input = plan.sessionCheckpoint().orElseThrow();
        StreamedSessionCheckpoint bound = new StreamedSessionCheckpoint(
                input.saveGameId(),
                input.fixedTick(),
                binding.checkpointRevision(),
                binding.checkpointDigest(),
                binding.intendedIndexSequence(),
                input.modifiedTime(),
                input.player(),
                input.inventory());
        byte[] encoded = new StreamedSessionCheckpointCodec().encode(bound);
        return List.of(new StreamedGlobalExtensionMutation.Upsert(
                new StreamedGlobalExtension(
                        SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                        StreamedSessionCheckpointCodec.CODEC_VERSION,
                        true,
                        Optional.empty(),
                        encoded)));
    }

    private AtomicPersistenceResult persistAtomically(
            WorldItemPersistencePlan plan,
            Function<AtomicCheckpointBinding, List<StreamedGlobalExtensionMutation>>
                    additionalGlobals,
            List<StreamedChunkStore.ExactChunkCapture> exactChunkCaptures,
            Map<ChunkKey, Boolean> exactCaptureVoxelModified,
            BooleanSupplier additionalStillCurrent) {
        WorldItemPersistencePlan checked = Objects.requireNonNull(plan, "plan");
        Function<AtomicCheckpointBinding, List<StreamedGlobalExtensionMutation>> globals =
                Objects.requireNonNull(additionalGlobals, "additionalGlobals");
        List<StreamedChunkStore.ExactChunkCapture> captures = List.copyOf(
                Objects.requireNonNull(exactChunkCaptures, "exactChunkCaptures"));
        Map<ChunkKey, Boolean> captureVoxelModified = Map.copyOf(
                Objects.requireNonNull(
                        exactCaptureVoxelModified, "exactCaptureVoxelModified"));
        BooleanSupplier extraCurrent = Objects.requireNonNull(
                additionalStillCurrent, "additionalStillCurrent");
        Map<ChunkKey, StreamedChunkStore.ExactChunkCapture> capturesByKey =
                new HashMap<>();
        for (StreamedChunkStore.ExactChunkCapture capture : captures) {
            StreamedChunkStore.ExactChunkCapture duplicate = capturesByKey.put(
                    capture.payload().key(), Objects.requireNonNull(capture, "capture"));
            if (duplicate != null) {
                throw invalid("Dirty Chunk captures repeat a key");
            }
        }
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(() ->
                        checked.stillCurrent().getAsBoolean()
                                && extraCurrent.getAsBoolean()
                                && captures.stream().allMatch(capture ->
                                        capture.stillCurrent().getAsBoolean()))) {
            StreamedChunkIndex baseIndex = staged.baseIndex();
            long currentSequence = staged.baseSequence();
            CurrentCheckpointState currentState = currentCheckpointState(
                    baseIndex,
                    currentSequence,
                    staged::basePayload,
                    checked.intendedCheckpoint().saveIdentity());
            WorldItemPagingCheckpoint currentCheckpoint = currentState.checkpoint();
            boolean pristineMigrationAlreadyPublishedIntendedCheckpoint =
                    currentCheckpoint != null
                            && checked.expectedCheckpointRevision() == 0L
                            && checked.pageMutations().isEmpty()
                            && currentCheckpoint.equals(
                                    checked.intendedCheckpoint());
            if (!pristineMigrationAlreadyPublishedIntendedCheckpoint
                    && (currentCheckpoint == null
                            ? checked.expectedCheckpointRevision() != 0L
                            : currentCheckpoint.checkpointRevision()
                                            != checked.expectedCheckpointRevision()
                                    || !currentCheckpoint.saveIdentity().equals(
                                            checked.intendedCheckpoint().saveIdentity())
                                    || checked.intendedCheckpoint().worldTick()
                                            < currentCheckpoint.worldTick()
                                    || checked.intendedCheckpoint().nextItemId()
                                            < currentCheckpoint.nextItemId()
                                    || currentCheckpoint.itemIdsExhausted()
                                            && !checked.intendedCheckpoint()
                                                    .itemIdsExhausted())) {
                throw invalid("WorldItem persistence plan is stale");
            }

            Map<ChunkKey, WorldItemPageDescriptor> currentDescriptors =
                    new HashMap<>();
            if (currentCheckpoint != null) {
                for (WorldItemPageDescriptor descriptor : currentCheckpoint.pages()) {
                    currentDescriptors.put(descriptor.chunkKey(), descriptor);
                }
            }
            Map<ChunkKey, WorldItemPageDescriptor> transformed = new HashMap<>();
            Map<ChunkKey, WorldItemPageDescriptor> intended = new HashMap<>();
            for (WorldItemPageDescriptor descriptor
                    : checked.intendedCheckpoint().pages()) {
                intended.put(descriptor.chunkKey(), descriptor);
            }
            Set<ChunkKey> mutationKeys = new HashSet<>();
            for (WorldItemPageMutation mutation : checked.pageMutations()) {
                mutationKeys.add(mutation instanceof WorldItemPageMutation.Upsert upsert
                        ? upsert.page().chunkKey()
                        : ((WorldItemPageMutation.Remove) mutation)
                                .expected().chunkKey());
            }
            requirePhysicalPageTable(
                    baseIndex, staged::basePayload, currentDescriptors.keySet());

            Set<ChunkKey> provenZeroLiveRemovals = new HashSet<>();
            SemanticAccumulator semantic = new SemanticAccumulator(
                    checked.intendedCheckpoint());
            StagingBatcher batcher = new StagingBatcher(staged, baseIndex);
            WorldItemPageCodec pageCodec = new WorldItemPageCodec();
            Set<ChunkKey> exactCapturesUsed = new HashSet<>();
            boolean canonicalChunkRevisionPublished = false;
            for (WorldItemPageDescriptor descriptor : currentDescriptors.values()) {
                if (mutationKeys.contains(descriptor.chunkKey())) {
                    continue;
                }
                WorldItemPageSnapshot page = validatePage(
                        baseIndex,
                        staged::basePayload,
                        pageCodec,
                        checked.intendedCheckpoint().saveIdentity(),
                        descriptor).page();
                WorldItemPageDescriptor normalized = withSurvivorCount(
                        descriptor,
                        survivorCount(page, checked.intendedCheckpoint().worldTick()));
                transformed.put(normalized.chunkKey(), normalized);
                semantic.accept(page, normalized);
            }
            for (WorldItemPageMutation mutation : checked.pageMutations()) {
                if (mutation instanceof WorldItemPageMutation.Upsert upsert) {
                    WorldItemPageSnapshot page = upsert.page();
                    WorldItemPageDescriptor previous = currentDescriptors.get(
                            page.chunkKey());
                    if (!upsert.expectedPrevious().equals(Optional.ofNullable(previous))) {
                        throw invalid("WorldItem page replacement expectation is stale");
                    }
                    byte[] pageBytes = pageCodec.encode(
                            checked.intendedCheckpoint().saveIdentity(), page);
                    int survivors = Math.toIntExact(page.entries().stream()
                            .filter(entry -> entry.runtime().expiresAtWorldTick()
                                    > checked.intendedCheckpoint().worldTick())
                            .count());
                    WorldItemPageDescriptor replacement = new WorldItemPageDescriptor(
                            page.chunkKey(),
                            page.pageRevision(),
                            HexFormat.of().formatHex(StreamedChunkCodec.sha256(pageBytes)),
                            page.entries().size(),
                            survivors);
                    if (!replacement.equals(intended.get(page.chunkKey()))) {
                        throw invalid("WorldItem page does not match the intended checkpoint");
                    }
                    semantic.accept(page, replacement);
                    StreamedChunkPayload currentPayload = staged.basePayload(page.chunkKey());
                    StreamedChunkIndex.Entry currentEntry = baseIndex
                            .entry(page.chunkKey()).orElse(null);
                    if (previous != null && currentEntry != null) {
                        validatePage(
                                baseIndex,
                                staged::basePayload,
                                pageCodec,
                                checked.intendedCheckpoint().saveIdentity(),
                                previous);
                    }
                    StreamedChunkStore.ExactChunkCapture matchingCapture =
                            capturesByKey.get(page.chunkKey());
                    if (matchingCapture != null) {
                        boolean canonicalWrite = exactCaptureNeedsCanonicalWrite(
                                matchingCapture, currentEntry, currentPayload);
                        StreamedChunkPayload replacementPayload =
                                replacePageExtensionOnCapture(
                                        matchingCapture.payload(),
                                        currentPayload,
                                        pageBytes,
                                        captureVoxelModified.getOrDefault(
                                                page.chunkKey(),
                                                matchingCapture.payload()
                                                        .voxelModified()),
                                        canonicalWrite
                                                ? matchingCapture.payload().revision()
                                                : Math.addExact(
                                                        currentEntry.revision(), 1L));
                        batcher.add(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        replacementPayload,
                                        matchingCapture.stillCurrent())));
                        exactCapturesUsed.add(page.chunkKey());
                        canonicalChunkRevisionPublished |= canonicalWrite;
                    } else if (currentEntry == null) {
                        StreamedChunkStore.ExactChunkCapture initial =
                                initialChunkCaptureSource.capture(
                                        checked.intendedCheckpoint().saveIdentity(),
                                        page,
                                        pageBytes.clone());
                        validateInitialCapture(
                                initial,
                                checked.intendedCheckpoint().saveIdentity(),
                                page,
                                pageBytes);
                        batcher.add(new StreamedChunkMutation.Upsert(initial));
                    } else {
                        if (currentPayload == null) {
                            throw invalid("WorldItem page Chunk payload is missing");
                        }
                        StreamedChunkPayload replacementPayload = replacePageExtension(
                                currentPayload, currentEntry.revision(), pageBytes);
                        batcher.add(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        replacementPayload, () -> true)));
                    }
                    transformed.put(page.chunkKey(), replacement);
                } else {
                    WorldItemPageDescriptor expected =
                            ((WorldItemPageMutation.Remove) mutation).expected();
                    if (!expected.equals(currentDescriptors.get(expected.chunkKey()))) {
                        throw invalid("WorldItem page removal expectation is stale");
                    }
                    WorldItemPageSnapshot removedPage = validatePage(
                            baseIndex,
                            staged::basePayload,
                            pageCodec,
                            checked.intendedCheckpoint().saveIdentity(),
                            expected).page();
                    if (survivorCount(
                                    removedPage,
                                    checked.intendedCheckpoint().worldTick()) == 0) {
                        provenZeroLiveRemovals.add(expected.chunkKey());
                    }
                    StreamedChunkPayload currentPayload = staged.basePayload(
                            expected.chunkKey());
                    StreamedChunkIndex.Entry currentEntry = baseIndex
                            .entry(expected.chunkKey()).orElseThrow();
                    List<StreamedChunkPayload.ExtensionDescriptor> retained =
                            currentPayload.extensions().stream()
                                    .filter(extension -> !extension.sectionId().equals(
                                            SaveSectionId.WORLD_ITEM_PAGE))
                                    .toList();
                    boolean hasRequired = retained.stream()
                            .anyMatch(StreamedChunkPayload.ExtensionDescriptor::required);
                    StreamedChunkStore.ExactChunkCapture matchingCapture =
                            capturesByKey.get(expected.chunkKey());
                    if (matchingCapture != null) {
                        boolean canonicalWrite = exactCaptureNeedsCanonicalWrite(
                                matchingCapture, currentEntry, currentPayload);
                        matchingCapture = preserveDurableNonDetailExtensions(
                                matchingCapture, currentPayload);
                        retained = matchingCapture.payload().extensions().stream()
                                .filter(extension -> !extension.sectionId().equals(
                                        SaveSectionId.WORLD_ITEM_PAGE))
                                .toList();
                        hasRequired = retained.stream().anyMatch(
                                StreamedChunkPayload.ExtensionDescriptor::required);
                        boolean modified = captureVoxelModified.getOrDefault(
                                expected.chunkKey(),
                                matchingCapture.payload().voxelModified());
                        if (!modified && !hasRequired) {
                            batcher.add(new StreamedChunkMutation.Remove(
                                    expected.chunkKey(),
                                    currentEntry.revision(),
                                    currentEntry.payloadHash()));
                        } else {
                            StreamedChunkPayload replacementPayload = copyPayload(
                                    matchingCapture.payload(),
                                    canonicalWrite
                                            ? matchingCapture.payload().revision()
                                            : Math.addExact(currentEntry.revision(), 1L),
                                    matchingCapture.payload().persistedRevision(),
                                    retained,
                                    modified);
                            batcher.add(new StreamedChunkMutation.Upsert(
                                    new StreamedChunkStore.ExactChunkCapture(
                                            replacementPayload,
                                            matchingCapture.stillCurrent())));
                        }
                        exactCapturesUsed.add(expected.chunkKey());
                        canonicalChunkRevisionPublished |= canonicalWrite;
                    } else if (!currentPayload.voxelModified() && !hasRequired) {
                        batcher.add(new StreamedChunkMutation.Remove(
                                expected.chunkKey(),
                                currentEntry.revision(),
                                currentEntry.payloadHash()));
                    } else {
                        StreamedChunkPayload replacementPayload = copyPayload(
                                currentPayload,
                                Math.addExact(currentEntry.revision(), 1L),
                                currentEntry.revision(),
                                retained);
                        batcher.add(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        replacementPayload, () -> true)));
                    }
                    transformed.remove(expected.chunkKey());
                }
            }
            for (StreamedChunkStore.ExactChunkCapture capture : captures) {
                if (exactCapturesUsed.contains(capture.payload().key())) {
                    continue;
                }
                StreamedChunkIndex.Entry currentEntry = baseIndex
                        .entry(capture.payload().key()).orElse(null);
                StreamedChunkPayload payload = capture.payload();
                StreamedChunkPayload currentPayload = staged.basePayload(
                        payload.key());
                boolean canonicalWrite = exactCaptureNeedsCanonicalWrite(
                        capture, currentEntry, currentPayload);
                if (!canonicalWrite) {
                    exactCapturesUsed.add(capture.payload().key());
                } else if (currentPayload != null
                        && !currentPayload.extensions().isEmpty()) {
                    capture = preserveDurableNonDetailExtensions(
                            capture, currentPayload);
                    payload = capture.payload();
                }
                if (canonicalWrite) {
                    batcher.add(new StreamedChunkMutation.Upsert(
                            new StreamedChunkStore.ExactChunkCapture(
                                    payload, capture.stillCurrent())));
                    canonicalChunkRevisionPublished = true;
                    exactCapturesUsed.add(capture.payload().key());
                }
            }
            batcher.finish();
            semantic.finish();
            requireFullTableAdmission(
                    currentCheckpoint == null ? List.of() : currentCheckpoint.pages(),
                    checked.intendedCheckpoint().pages(),
                    provenZeroLiveRemovals);
            if (!transformed.equals(intended)) {
                throw invalid(
                        "WorldItem page mutations do not produce the intended checkpoint");
            }

            byte[] checkpointBytes = new WorldItemPagingCheckpointCodec()
                    .encode(checked.intendedCheckpoint());
            StreamedGlobalExtension checkpointExtension = new StreamedGlobalExtension(
                    SaveSectionId.WORLD_ITEM_CHECKPOINT,
                    WorldItemPagingCheckpointCodec.CODEC_VERSION,
                    true,
                    Optional.of(new RequiredChunkExtensionDependency(
                            SaveSectionId.WORLD_ITEM_PAGE,
                            checked.intendedCheckpoint().pages().size())),
                    checkpointBytes);
            long intendedSequence = Math.addExact(currentSequence, 1L);
            AtomicCheckpointBinding atomicBinding = new AtomicCheckpointBinding(
                    checked.intendedCheckpoint().checkpointRevision(),
                    HexFormat.of().formatHex(StreamedChunkCodec.sha256(checkpointBytes)),
                    intendedSequence);
            List<StreamedGlobalExtensionMutation> globalMutations = new ArrayList<>();
            globalMutations.add(new StreamedGlobalExtensionMutation.Upsert(
                    checkpointExtension));
            globalMutations.addAll(List.copyOf(globals.apply(atomicBinding)));
            StreamedChunkStore.CommitResult result = staged.publish(globalMutations);
            if (result.status() != StreamedChunkStore.CommitResult.Status.SUCCESS) {
                throw invalid(
                        "WorldItem persistence plan did not publish: " + result.status());
            }
            StoreProof proof = new StoreProof(
                    proofScope,
                    checked.intendedCheckpoint().checkpointRevision(),
                    checked.transactionDigest(),
                    atomicBinding.checkpointDigest(),
                    intendedSequence);
            return new AtomicPersistenceResult(
                    proof, atomicBinding, staged.metrics(),
                    canonicalChunkRevisionPublished);
        }
    }

    public record AtomicCheckpointBinding(
            long checkpointRevision,
            String checkpointDigest,
            long intendedIndexSequence) {}

    public record AtomicPersistenceResult(
            WorldItemDurableProof proof,
            AtomicCheckpointBinding binding,
            StreamedChunkStore.StagingMetrics stagingMetrics,
            boolean canonicalChunkRevisionPublished) {
        public AtomicPersistenceResult {
            proof = Objects.requireNonNull(proof, "proof");
            binding = Objects.requireNonNull(binding, "binding");
            stagingMetrics = Objects.requireNonNull(
                    stagingMetrics, "stagingMetrics");
        }
    }

    public WorldItemDurabilityVerifier durabilityVerifier() {
        return durabilityVerifier;
    }

    public WorldItemRestoreResult restoreFresh(
            LogicalWorldItemService target,
            SaveIdentity expectedIdentity,
            long manifestWorldTick) {
        return restoreFresh(
                target,
                expectedIdentity,
                manifestWorldTick,
                ChunkCoordinatePolicy.canonicalComparator(),
                ignored -> {});
    }

    public WorldItemRestoreResult restoreFresh(
            LogicalWorldItemService target,
            SaveIdentity expectedIdentity,
            long manifestWorldTick,
            Comparator<ChunkKey> traversalOrder,
            Consumer<RestartStage> stageObserver) {
        LogicalWorldItemService service = Objects.requireNonNull(target, "target");
        SaveIdentity identity = Objects.requireNonNull(
                expectedIdentity, "expectedIdentity");
        Comparator<ChunkKey> order = Objects.requireNonNull(
                traversalOrder, "traversalOrder");
        Consumer<RestartStage> observer = Objects.requireNonNull(
                stageObserver, "stageObserver");
        if (!service.canonicalSnapshot().entries().isEmpty()
                || service.canonicalSnapshot().completeness()
                        != com.overlord.worlditem.api.LogicalWorldItemSnapshot
                                .Completeness.LEGACY_COMPLETE) {
            return new WorldItemRestoreResult(
                    WorldItemRestoreResult.Status.TARGET_NOT_FRESH, 0);
        }
        try (WorldItemPageReadView view = openReadView()) {
            WorldItemPagingCheckpoint checkpoint = view.checkpoint();
            if (!checkpoint.saveIdentity().equals(identity)) {
                throw invalid("WorldItem checkpoint belongs to another save");
            }
            observer.accept(RestartStage.IDENTITY_VALIDATED);
            if (manifestWorldTick < 0L
                    || checkpoint.worldTick() != manifestWorldTick) {
                throw invalid("WorldItem checkpoint tick does not match the manifest");
            }
            observer.accept(RestartStage.WORLD_TICK_VALIDATED);
            if (checkpoint.nextItemId() < 0L
                    || (checkpoint.itemIdsExhausted()
                            && checkpoint.nextItemId() != Long.MAX_VALUE)) {
                throw invalid("WorldItem allocator checkpoint is invalid");
            }
            observer.accept(RestartStage.ALLOCATOR_VALIDATED);

            List<WorldItemPageDescriptor> descriptors =
                    new ArrayList<>(checkpoint.pages());
            descriptors.sort(Comparator.comparing(
                    WorldItemPageDescriptor::chunkKey, order));
            List<WorldItemPageSnapshot> pages = new ArrayList<>(descriptors.size());
            List<WorldItemLiveMetadata> metadata = new ArrayList<>();
            Set<Long> liveIds = new HashSet<>();
            for (WorldItemPageDescriptor descriptor : descriptors) {
                WorldItemPageSnapshot encodedPage = view.read(descriptor);
                List<com.overlord.worlditem.api.WorldItemRestoreEntry> liveEntries =
                        encodedPage.entries().stream()
                                .filter(entry -> entry.runtime().expiresAtWorldTick()
                                        > manifestWorldTick)
                                .toList();
                WorldItemPageSnapshot page = liveEntries.size()
                                == encodedPage.entries().size()
                        ? encodedPage
                        : new WorldItemPageSnapshot(
                                encodedPage.chunkKey(),
                                encodedPage.pageRevision(),
                                liveEntries);
                pages.add(page);
                WorldItemDurablePageProof pageProof = new WorldItemDurablePageProof(
                        descriptor.chunkKey(),
                        descriptor.pageRevision(),
                        descriptor.pageHash());
                for (var entry : page.entries()) {
                    long id = entry.runtime().item().id().value();
                    if (!liveIds.add(id)) {
                        throw invalid("WorldItem paging repeats a live item ID");
                    }
                    metadata.add(new WorldItemLiveMetadata(
                            entry.runtime().item().id(),
                            descriptor.chunkKey(),
                            descriptor.pageRevision(),
                            entry.runtime().expiresAtWorldTick(),
                            WorldItemLiveState.EVICTED_UNEXPIRED,
                            Optional.of(pageProof)));
                }
            }
            if (metadata.size() != checkpoint.totalLiveItemCount()) {
                throw invalid("WorldItem paging live count does not match its checkpoint");
            }
            observer.accept(RestartStage.PAGES_VALIDATED);
            if (!service.restorePagingState(checkpoint, metadata, pages)) {
                return new WorldItemRestoreResult(
                        WorldItemRestoreResult.Status.TARGET_NOT_FRESH, 0);
            }
            observer.accept(RestartStage.PUBLISHED);
            return new WorldItemRestoreResult(
                    WorldItemRestoreResult.Status.RESTORED, metadata.size());
        }
    }

    public enum RestartStage {
        IDENTITY_VALIDATED,
        WORLD_TICK_VALIDATED,
        ALLOCATOR_VALIDATED,
        PAGES_VALIDATED,
        PUBLISHED
    }

    static void requireFullTableAdmission(
            List<WorldItemPageDescriptor> current,
            List<WorldItemPageDescriptor> intended,
            Set<ChunkKey> provenZeroLiveRemovals) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(intended, "intended");
        Objects.requireNonNull(provenZeroLiveRemovals, "provenZeroLiveRemovals");
        if (current.size() < WorldItemPagingCheckpoint.MAX_PAGE_DESCRIPTORS) {
            return;
        }
        Set<ChunkKey> currentKeys = new HashSet<>();
        current.forEach(descriptor -> currentKeys.add(descriptor.chunkKey()));
        long additions = intended.stream()
                .map(WorldItemPageDescriptor::chunkKey)
                .filter(key -> !currentKeys.contains(key))
                .count();
        if (additions == 0L) {
            return;
        }
        Set<ChunkKey> intendedKeys = new HashSet<>();
        intended.forEach(descriptor -> intendedKeys.add(descriptor.chunkKey()));
        long eligibleRemovals = provenZeroLiveRemovals.stream()
                .filter(currentKeys::contains)
                .filter(key -> !intendedKeys.contains(key))
                .count();
        if (eligibleRemovals < additions) {
            throw invalid(
                    "A full WorldItem page table requires paired zero-live removal");
        }
    }

    @FunctionalInterface
    public interface InitialChunkCaptureSource {
        StreamedChunkStore.ExactChunkCapture capture(
                SaveIdentity saveIdentity,
                WorldItemPageSnapshot page,
                byte[] canonicalPageBytes);
    }

    @Override
    public WorldItemPageReadView openReadView() {
        StreamedChunkStore.BoundedReadView bounded = store.openBoundedReadView();
        return openReadView(
                bounded.index(),
                bounded.sequence(),
                bounded::payload,
                bounded::close);
    }

    WorldItemPageReadView openReadView(
            StreamedChunkStore.BoundedReadView bounded) {
        Objects.requireNonNull(bounded, "bounded");
        return openReadView(
                bounded.index(),
                bounded.sequence(),
                bounded::payload,
                bounded::close);
    }

    private WorldItemPageReadView openReadView(
            StreamedChunkIndex index,
            long sequence,
            Function<ChunkKey, StreamedChunkPayload> payloads,
            Runnable close) {
        try {
            requireSupportedGlobalExtensions(index);
            StreamedGlobalExtension checkpointExtension = index
                    .globalExtension(SaveSectionId.WORLD_ITEM_CHECKPOINT)
                    .orElseThrow(() -> invalid("WorldItem paging checkpoint is missing"));
            if (!checkpointExtension.required()
                    || checkpointExtension.codecVersion()
                            != WorldItemPagingCheckpointCodec.CODEC_VERSION) {
                throw invalid("WorldItem paging checkpoint is unsupported");
            }
            RequiredChunkExtensionDependency dependency = checkpointExtension
                    .dependency()
                    .filter(value -> value.chunkExtensionId().equals(
                            SaveSectionId.WORLD_ITEM_PAGE))
                    .orElseThrow(() -> invalid(
                            "WorldItem paging dependency is missing"));
            SaveIdentity identity = new SaveIdentity(UUID.fromString(
                    index.saveGameId().value()));
            byte[] checkpointBytes = checkpointExtension.copyPayloadBytes();
            WorldItemPagingCheckpoint checkpoint =
                    new WorldItemPagingCheckpointCodec().decode(
                            identity, checkpointBytes);
            if (dependency.referenceCount() != checkpoint.pages().size()) {
                throw invalid("WorldItem paging dependency count does not match");
            }
            Set<ChunkKey> checkpointKeys = new HashSet<>();
            for (WorldItemPageDescriptor descriptor : checkpoint.pages()) {
                checkpointKeys.add(descriptor.chunkKey());
            }
            requirePhysicalPageTable(index, payloads, checkpointKeys);
            WorldItemPageCodec pageCodec = new WorldItemPageCodec();
            Set<Long> survivingIds = new HashSet<>();
            int survivingCount = 0;
            for (WorldItemPageDescriptor descriptor : checkpoint.pages()) {
                PageValidation validation = validatePage(
                        index, payloads, pageCodec, identity, descriptor);
                int pageSurvivors = 0;
                for (var entry : validation.page().entries()) {
                    long id = entry.runtime().item().id().value();
                    if ((!checkpoint.itemIdsExhausted() && id >= checkpoint.nextItemId())
                            || (checkpoint.itemIdsExhausted()
                                    && checkpoint.nextItemId() != Long.MAX_VALUE)) {
                        throw invalid("WorldItem paging allocator does not own an encoded ID");
                    }
                    if (entry.runtime().expiresAtWorldTick() > checkpoint.worldTick()) {
                        if (!survivingIds.add(id)) {
                            throw invalid("WorldItem paging repeats a live item ID");
                        }
                        if (survivingIds.size()
                                > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS) {
                            throw invalid("WorldItem paging live count exceeds its bound");
                        }
                        pageSurvivors = Math.addExact(pageSurvivors, 1);
                    }
                }
                if (pageSurvivors != descriptor.expectedLiveCountAtCheckpointTick()) {
                    throw invalid("WorldItem page survivor count does not match its descriptor");
                }
                survivingCount = Math.addExact(survivingCount, pageSurvivors);
            }
            if (survivingCount != checkpoint.totalLiveItemCount()) {
                throw invalid("WorldItem paging live count does not match its checkpoint");
            }
            return new ReadView(
                    index,
                    sequence,
                    payloads,
                    close,
                    HexFormat.of().formatHex(
                            StreamedChunkCodec.sha256(checkpointBytes)),
                    identity,
                    checkpoint,
                    pageCodec);
        } catch (Error | RuntimeException failure) {
            close.run();
            throw failure;
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    private CurrentCheckpointState currentCheckpointState(
            StreamedChunkIndex index,
            long sequence,
            Function<ChunkKey, StreamedChunkPayload> payloads,
            SaveIdentity identity) {
        requireSupportedGlobalExtensions(index);
        requireSaveIdentity(index, identity);
        StreamedGlobalExtension extension = index.globalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT).orElse(null);
        if (extension == null) {
            requirePhysicalPageTable(index, payloads, Set.of());
            return new CurrentCheckpointState(sequence, null);
        }
        if (!extension.required()
                || extension.codecVersion()
                        != WorldItemPagingCheckpointCodec.CODEC_VERSION) {
            throw invalid("WorldItem paging checkpoint is unsupported");
        }
        RequiredChunkExtensionDependency dependency = extension.dependency()
                .filter(value -> value.chunkExtensionId().equals(
                        SaveSectionId.WORLD_ITEM_PAGE))
                .orElseThrow(() -> invalid(
                        "WorldItem paging dependency is missing"));
        WorldItemPagingCheckpoint checkpoint =
                new WorldItemPagingCheckpointCodec().decode(
                        identity, extension.copyPayloadBytes());
        if (dependency.referenceCount() != checkpoint.pages().size()) {
            throw invalid("WorldItem paging dependency count does not match");
        }
        return new CurrentCheckpointState(sequence, checkpoint);
    }

    private static void requireSupportedGlobalExtensions(StreamedChunkIndex index) {
        for (StreamedGlobalExtension extension : index.globalExtensions()) {
            if (extension.required()
                    && !extension.sectionId().equals(
                            SaveSectionId.WORLD_ITEM_CHECKPOINT)
                    && !extension.sectionId().equals(
                            SaveSectionId.STREAMED_SESSION_CHECKPOINT)) {
                throw invalid("A required streamed global extension is unsupported");
            }
        }
    }

    private static void requirePhysicalPageTable(
            StreamedChunkIndex index,
            Function<ChunkKey, StreamedChunkPayload> payloads,
            Set<ChunkKey> expectedKeys) {
        Set<ChunkKey> physicalKeys = new HashSet<>();
        for (StreamedChunkIndex.Entry entry : index.entries()) {
            StreamedChunkPayload payload = payloads.apply(entry.key());
            if (payload != null && payload.extensions().stream().anyMatch(
                    extension -> extension.sectionId().equals(
                            SaveSectionId.WORLD_ITEM_PAGE))) {
                physicalKeys.add(entry.key());
            }
        }
        if (!physicalKeys.equals(expectedKeys)) {
            throw invalid("WorldItem physical page table does not match its checkpoint");
        }
    }

    private static void validateInitialCapture(
            StreamedChunkStore.ExactChunkCapture capture,
            SaveIdentity identity,
            WorldItemPageSnapshot page,
            byte[] pageBytes) {
        StreamedChunkStore.ExactChunkCapture checked = Objects.requireNonNull(
                capture, "initial Chunk capture");
        StreamedChunkPayload payload = checked.payload();
        if (!payload.saveGameId().value().equals(identity.value().toString())
                || !payload.key().equals(page.chunkKey())
                || payload.persistedRevision() != 0L) {
            throw invalid("Initial WorldItem Chunk capture has the wrong identity");
        }
        StreamedChunkPayload.ExtensionDescriptor extension = payload.extensions().stream()
                .filter(value -> value.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE))
                .findFirst()
                .orElseThrow(() -> invalid(
                        "Initial WorldItem Chunk capture is missing its page"));
        if (!extension.required()
                || extension.codecVersion() != WorldItemPageCodec.CODEC_VERSION
                || !java.util.Arrays.equals(extension.copyBytes(), pageBytes)) {
            throw invalid("Initial WorldItem Chunk capture has different page bytes");
        }
    }

    private static void requireSaveIdentity(
            StreamedChunkIndex index, SaveIdentity identity) {
        if (!index.saveGameId().value().equals(identity.value().toString())) {
            throw invalid("WorldItem checkpoint belongs to another save");
        }
    }

    private static WorldItemPageDescriptor withSurvivorCount(
            WorldItemPageDescriptor descriptor, int survivors) {
        return new WorldItemPageDescriptor(
                descriptor.chunkKey(),
                descriptor.pageRevision(),
                descriptor.pageHash(),
                descriptor.encodedEntryCount(),
                survivors);
    }

    private static int survivorCount(WorldItemPageSnapshot page, long worldTick) {
        return Math.toIntExact(page.entries().stream()
                .filter(entry -> entry.runtime().expiresAtWorldTick() > worldTick)
                .count());
    }

    private static StreamedChunkPayload replacePageExtension(
            StreamedChunkPayload current, long persistedRevision, byte[] pageBytes) {
        List<StreamedChunkPayload.ExtensionDescriptor> retained = new ArrayList<>();
        for (StreamedChunkPayload.ExtensionDescriptor extension : current.extensions()) {
            if (!extension.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE)) {
                retained.add(extension);
            }
        }
        retained.add(new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.WORLD_ITEM_PAGE,
                WorldItemPageCodec.CODEC_VERSION,
                true,
                pageBytes));
        return copyPayload(
                current,
                Math.addExact(persistedRevision, 1L),
                persistedRevision,
                retained);
    }

    private static StreamedChunkPayload replacePageExtensionOnCapture(
            StreamedChunkPayload capture,
            StreamedChunkPayload current,
            byte[] pageBytes,
            boolean voxelModified,
            long physicalRevision) {
        StreamedChunkStore.ExactChunkCapture merged = current == null
                ? new StreamedChunkStore.ExactChunkCapture(capture, () -> true)
                : preserveDurableNonDetailExtensions(
                        new StreamedChunkStore.ExactChunkCapture(capture, () -> true),
                        current);
        List<StreamedChunkPayload.ExtensionDescriptor> extensions =
                new ArrayList<>();
        for (StreamedChunkPayload.ExtensionDescriptor extension
                : merged.payload().extensions()) {
            if (!extension.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE)) {
                extensions.add(extension);
            }
        }
        extensions.add(new StreamedChunkPayload.ExtensionDescriptor(
                SaveSectionId.WORLD_ITEM_PAGE,
                WorldItemPageCodec.CODEC_VERSION,
                true,
                pageBytes));
        return copyPayload(
                capture,
                physicalRevision,
                capture.persistedRevision(),
                extensions,
                voxelModified);
    }

    private static boolean exactCaptureNeedsCanonicalWrite(
            StreamedChunkStore.ExactChunkCapture capture,
            StreamedChunkIndex.Entry currentEntry,
            StreamedChunkPayload currentPayload) {
        StreamedChunkPayload payload = capture.payload();
        if (currentEntry == null) {
            if (payload.persistedRevision() != 0L) {
                throw invalid("Initial detached Chunk capture has a stale base");
            }
            return true;
        }
        if (payload.persistedRevision() != currentEntry.revision()) {
            throw invalid("Detached Chunk capture has a stale base");
        }
        if (payload.revision() > currentEntry.revision()) {
            return true;
        }
        if (payload.revision() == currentEntry.revision()
                && currentPayload != null
                && ChunkDetailPersistence.canonicalStateEquals(
                        payload, currentPayload)) {
            return false;
        }
        throw invalid("Detached Chunk capture has a stale base");
    }

    private static StreamedChunkPayload copyPayload(
            StreamedChunkPayload current,
            long revision,
            long persistedRevision,
            List<StreamedChunkPayload.ExtensionDescriptor> extensions) {
        boolean hasRequired = extensions.stream()
                .anyMatch(StreamedChunkPayload.ExtensionDescriptor::required);
        return new StreamedChunkPayload(
                current.saveGameId(),
                current.key(),
                current.generatorVersion(),
                current.baseHash(),
                revision,
                persistedRevision,
                current.voxelModified() || hasRequired,
                current.voxelModified(),
                current.worldHeight(),
                current.copyCanonicalVoxels(),
                extensions);
    }

    private static StreamedChunkPayload copyPayload(
            StreamedChunkPayload current,
            long revision,
            long persistedRevision,
            List<StreamedChunkPayload.ExtensionDescriptor> extensions,
            boolean voxelModified) {
        boolean hasRequired = extensions.stream()
                .anyMatch(StreamedChunkPayload.ExtensionDescriptor::required);
        return new StreamedChunkPayload(
                current.saveGameId(),
                current.key(),
                current.generatorVersion(),
                current.baseHash(),
                revision,
                persistedRevision,
                voxelModified || hasRequired,
                voxelModified,
                current.worldHeight(),
                current.copyCanonicalVoxels(),
                extensions);
    }

    private static PageValidation validatePage(
            StreamedChunkIndex index,
            Function<ChunkKey, StreamedChunkPayload> payloads,
            WorldItemPageCodec pageCodec,
            SaveIdentity identity,
            WorldItemPageDescriptor descriptor) {
        index.entry(descriptor.chunkKey())
                .orElseThrow(() -> invalid("WorldItem page Chunk is missing"));
        StreamedChunkPayload payload = payloads.apply(descriptor.chunkKey());
        if (payload == null) {
            throw invalid("WorldItem page payload is missing");
        }
        StreamedChunkPayload.ExtensionDescriptor pageExtension =
                payload.extensions().stream()
                        .filter(extension -> extension.sectionId().equals(
                                SaveSectionId.WORLD_ITEM_PAGE))
                        .findFirst()
                        .orElseThrow(() -> invalid(
                                "WorldItem page extension is missing"));
        byte[] pageBytes = pageExtension.copyBytes();
        String pageHash = HexFormat.of().formatHex(
                StreamedChunkCodec.sha256(pageBytes));
        if (!pageExtension.required()
                || pageExtension.codecVersion() != WorldItemPageCodec.CODEC_VERSION
                || !descriptor.pageHash().equals(pageHash)) {
            throw invalid("WorldItem page does not match its descriptor");
        }
        WorldItemPageSnapshot page = pageCodec.decode(
                identity, descriptor.chunkKey(), pageBytes);
        if (page.pageRevision() != descriptor.pageRevision()
                || page.entries().size() != descriptor.encodedEntryCount()) {
            throw invalid("WorldItem page does not match its descriptor");
        }
        return new PageValidation(page);
    }

    private record PageValidation(WorldItemPageSnapshot page) {}

    private static final class StagingBatcher {
        private final StreamedChunkStore.StagedTransaction staged;
        private final StreamedChunkIndex baseIndex;
        private final List<StreamedChunkMutation> batch = new ArrayList<>(
                StreamedPersistenceTransaction.MAX_CHUNKS);
        private long batchBytes;
        private int batchPhysicalBlobs;

        private StagingBatcher(
                StreamedChunkStore.StagedTransaction staged,
                StreamedChunkIndex baseIndex) {
            this.staged = staged;
            this.baseIndex = baseIndex;
        }

        private void add(StreamedChunkMutation mutation) {
            long bytes = mutation instanceof StreamedChunkMutation.Upsert upsert
                    ? StreamedChunkCodec.canonicalEncodedSize(
                            upsert.capture().payload())
                    : 0L;
            int physicalBlobs = mutation instanceof StreamedChunkMutation.Upsert upsert
                    ? baseIndex.entry(upsert.capture().payload().key()).isPresent()
                            ? 1
                            : 2
                    : 0;
            if (!batch.isEmpty()
                    && (batch.size() == StreamedPersistenceTransaction.MAX_CHUNKS
                            || batchPhysicalBlobs + physicalBlobs
                                    > StreamedPersistenceTransaction.MAX_CHUNKS
                            || Math.addExact(batchBytes, bytes)
                                    > StreamedPersistenceTransaction
                                            .MAX_CANDIDATE_BYTES)) {
                flush();
            }
            if (bytes > StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES) {
                throw invalid("A WorldItem staged payload exceeds 64 MiB");
            }
            batch.add(Objects.requireNonNull(mutation, "mutation"));
            batchBytes = Math.addExact(batchBytes, bytes);
            batchPhysicalBlobs = Math.addExact(
                    batchPhysicalBlobs, physicalBlobs);
        }

        private void finish() {
            if (!batch.isEmpty()) {
                flush();
            }
        }

        private void flush() {
            StreamedChunkStore.CommitResult result = staged.stageBatch(batch);
            if (result.status() != StreamedChunkStore.CommitResult.Status.SUCCESS) {
                throw invalid(
                        "WorldItem staging batch failed: " + result.status());
            }
            batch.clear();
            batchBytes = 0L;
            batchPhysicalBlobs = 0;
        }
    }

    private static final class SemanticAccumulator {
        private final WorldItemPagingCheckpoint checkpoint;
        private final Set<Long> liveIds = new HashSet<>();
        private int liveCount;

        private SemanticAccumulator(WorldItemPagingCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        private void accept(
                WorldItemPageSnapshot page, WorldItemPageDescriptor descriptor) {
            int pageSurvivors = 0;
            for (var entry : page.entries()) {
                long id = entry.runtime().item().id().value();
                if (!checkpoint.itemIdsExhausted() && id >= checkpoint.nextItemId()) {
                    throw invalid("WorldItem paging allocator does not own an encoded ID");
                }
                if (entry.runtime().expiresAtWorldTick() > checkpoint.worldTick()) {
                    if (!liveIds.add(id)) {
                        throw invalid("WorldItem paging repeats a live item ID");
                    }
                    if (liveIds.size()
                            > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS) {
                        throw invalid("WorldItem paging live count exceeds its bound");
                    }
                    pageSurvivors = Math.addExact(pageSurvivors, 1);
                }
            }
            if (pageSurvivors != descriptor.expectedLiveCountAtCheckpointTick()) {
                throw invalid("WorldItem page survivor count does not match its descriptor");
            }
            liveCount = Math.addExact(liveCount, pageSurvivors);
        }

        private void finish() {
            if (liveCount != checkpoint.totalLiveItemCount()) {
                throw invalid("WorldItem paging live count does not match its checkpoint");
            }
        }
    }

    private record CurrentCheckpointState(
            long sequence, WorldItemPagingCheckpoint checkpoint) {}

    private final class StoreDurabilityVerifier implements WorldItemDurabilityVerifier {
        @Override
        public void verify(
                WorldItemPersistenceTicket ticket,
                WorldItemPersistencePlan plan,
                WorldItemDurableProof proof) {
            Objects.requireNonNull(ticket, "ticket");
            WorldItemPersistencePlan checkedPlan = Objects.requireNonNull(plan, "plan");
            if (!(proof instanceof StoreProof checkedProof)
                    || !checkedProof.proofScope.equals(proofScope)
                    || checkedProof.checkpointRevision
                            != checkedPlan.intendedCheckpoint().checkpointRevision()
                    || !checkedProof.transactionDigest.equals(
                            checkedPlan.transactionDigest())) {
                throw invalid("WorldItem durable proof is foreign or stale");
            }
            checkedProof.verifyCurrent(checkedPlan);
        }
    }

    private final class StoreProof implements WorldItemDurableProof {
        private final StreamedChunkStore.ProofScope proofScope;
        private final long checkpointRevision;
        private final String transactionDigest;
        private final String checkpointDigest;
        private final long indexSequence;

        private StoreProof(
                StreamedChunkStore.ProofScope proofScope,
                long checkpointRevision,
                String transactionDigest,
                String checkpointDigest,
                long indexSequence) {
            this.proofScope = proofScope;
            this.checkpointRevision = checkpointRevision;
            this.transactionDigest = transactionDigest;
            this.checkpointDigest = checkpointDigest;
            this.indexSequence = indexSequence;
        }

        private void verifyCurrent(WorldItemPersistencePlan checkedPlan) {
            try (WorldItemPageReadView current = openReadView()) {
                if (current.indexSequence() != indexSequence
                        || !current.checkpointDigest().equals(checkpointDigest)
                        || !current.checkpoint().equals(
                                checkedPlan.intendedCheckpoint())) {
                    throw invalid(
                            "WorldItem durable proof no longer names current state");
                }
            }
        }
    }

    private static final class ReadView implements WorldItemPageReadView {
        private final StreamedChunkIndex index;
        private final long sequence;
        private final Function<ChunkKey, StreamedChunkPayload> payloads;
        private final Runnable close;
        private final String checkpointDigest;
        private final SaveIdentity identity;
        private final WorldItemPagingCheckpoint checkpoint;
        private final WorldItemPageCodec pageCodec;
        private boolean closed;

        private ReadView(
                StreamedChunkIndex index,
                long sequence,
                Function<ChunkKey, StreamedChunkPayload> payloads,
                Runnable close,
                String checkpointDigest,
                SaveIdentity identity,
                WorldItemPagingCheckpoint checkpoint,
                WorldItemPageCodec pageCodec) {
            this.index = index;
            this.sequence = sequence;
            this.payloads = payloads;
            this.close = close;
            this.checkpointDigest = checkpointDigest;
            this.identity = identity;
            this.checkpoint = checkpoint;
            this.pageCodec = pageCodec;
        }

        @Override
        public long indexSequence() {
            requireOpen();
            return sequence;
        }

        @Override
        public String checkpointDigest() {
            requireOpen();
            return checkpointDigest;
        }

        @Override
        public WorldItemPagingCheckpoint checkpoint() {
            requireOpen();
            return checkpoint;
        }

        @Override
        public WorldItemPageSnapshot read(WorldItemPageDescriptor descriptor) {
            requireOpen();
            WorldItemPageDescriptor checked = Objects.requireNonNull(
                    descriptor, "descriptor");
            if (!checkpoint.pages().contains(checked)) {
                throw new IllegalArgumentException(
                        "The descriptor is not part of this read view");
            }
            return validatePage(
                    index, payloads, pageCodec, identity, checked).page();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                close.run();
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("The WorldItem page read view is closed");
            }
        }
    }
}
