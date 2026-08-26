package com.gaia.save.streaming;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.SaveFileOperations;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Bounded fixed-slot store for modified streamed Chunks.
 *
 * <p>Authority is carried only by two pre-existing forced index files.  Every
 * Chunk has two pre-existing forced payload files.  Commits rewrite an
 * unreferenced payload slot, then recovery and main index slots, without using a
 * directory-entry mutation as an authority boundary.
 */
public final class StreamedChunkStore {
    private static final ReferenceQueue<SharedWriterGate> WRITER_GATE_QUEUE =
            new ReferenceQueue<>();
    private static final ConcurrentHashMap<WriterGateKey, GateReference>
            WRITER_GATES = new ConcurrentHashMap<>();
    private static final String CHUNK_DIRECTORY_NAME = "streamed-chunks";
    private static final String INDEX_NAME = "streamed-chunks.idx";
    private static final String PRIOR_INDEX_NAME = "streamed-chunks.prev.idx";
    private static final String CHUNK_SUFFIX = ".glchunk";
    private static final int SLOT_MAGIC = 0x47495332;
    private static final int SLOT_VERSION = 2;
    private static final int SLOT_HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES;
    private static final int SLOT_HASH_BYTES = 32;
    private static final long MAX_SLOT_BYTES =
            StreamedChunkIndexCodec.MAX_FILE_BYTES + SLOT_HEADER_BYTES + SLOT_HASH_BYTES;
    private static final long MAX_HIBERNATION_BYTES = 1024L * 1024L;
    static final long MAX_STAGING_TRANSIENT_PAYLOAD_BYTES =
            256L * 1024L * 1024L;
    static final int MAX_CACHED_SHARD_IDENTITIES = 128;
    static final int MAX_CACHED_PAYLOAD_SLOTS = 256;
    static final int MAX_CACHED_INITIALIZED_POOLS = 128;

    private final SaveGameId saveGameId;
    private final StreamedChunkCodec payloadCodec;
    private final ChunkCandidateEncoder candidateEncoder;
    private final StreamedChunkIndexCodec indexCodec;
    private final SaveFileOperations files;
    private final DirectoryIdentity saveRoot;
    private final DirectoryIdentity worldDirectory;
    private final DirectoryIdentity chunkDirectory;
    private final Path indexPath;
    private final Path priorIndexPath;
    private final SharedWriterGate writerGate;
    private final Map<Integer, DirectoryIdentity> shardIdentities =
            new BoundedAccessMap<>(MAX_CACHED_SHARD_IDENTITIES);
    private final Map<Path, ManagedSlot> payloadSlots =
            new BoundedAccessMap<>(MAX_CACHED_PAYLOAD_SLOTS);
    private final Set<ChunkKey> initializedPayloadPools = Collections.newSetFromMap(
            new BoundedAccessMap<>(MAX_CACHED_INITIALIZED_POOLS));
    private ManagedSlot mainIndex;
    private ManagedSlot recoveryIndex;
    private volatile int lastValidatedModifiedChunkCount;

    public StreamedChunkStore(
            Path saveRoot,
            SaveGameId saveGameId,
            StreamedChunkCodec payloadCodec,
            StreamedChunkIndexCodec indexCodec,
            SaveFileOperations files) {
        this(saveRoot, saveGameId, payloadCodec, indexCodec, files,
                Objects.requireNonNull(payloadCodec, "payloadCodec")::encode);
    }

    synchronized ProofScope proofScope() {
        try {
            saveRoot.require();
            worldDirectory.require();
            return new ProofScope(
                    saveGameId,
                    worldDirectory.path.toString(),
                    worldDirectory.providerIdentity);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "The streamed save-root identity is unavailable", failure);
        }
    }

    record ProofScope(
            SaveGameId saveGameId, String worldPath, Object providerIdentity) {
        ProofScope {
            Objects.requireNonNull(saveGameId, "saveGameId");
            Objects.requireNonNull(worldPath, "worldPath");
            Objects.requireNonNull(providerIdentity, "providerIdentity");
        }
    }

    StreamedChunkStore(
            Path saveRoot,
            SaveGameId saveGameId,
            StreamedChunkCodec payloadCodec,
            StreamedChunkIndexCodec indexCodec,
            SaveFileOperations files,
            ChunkCandidateEncoder candidateEncoder) {
        this.saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.candidateEncoder = Objects.requireNonNull(
                candidateEncoder, "candidateEncoder");
        this.indexCodec = Objects.requireNonNull(indexCodec, "indexCodec");
        this.files = Objects.requireNonNull(files, "files");
        try {
            Path root = Objects.requireNonNull(saveRoot, "saveRoot")
                    .toAbsolutePath()
                    .normalize();
            this.saveRoot = captureDirectory(root, null);
            this.worldDirectory = openOrCreateDirectory(
                    directChild(root, saveGameId.value()), this.saveRoot);
            this.chunkDirectory = openOrCreateDirectory(
                    directChild(worldDirectory.path, CHUNK_DIRECTORY_NAME),
                    this.worldDirectory);
            this.indexPath = directChild(worldDirectory.path, INDEX_NAME);
            this.priorIndexPath = directChild(worldDirectory.path, PRIOR_INDEX_NAME);
            WriterGateKey writerGateKey = new WriterGateKey(
                    this.saveGameId,
                    this.worldDirectory.path.toString(),
                    this.worldDirectory.providerIdentity);
            this.writerGate = writerGateFor(writerGateKey);
            initializeIndexPool();
        } catch (IOException | RuntimeException unsafe) {
            throw new IllegalArgumentException(
                    "The streamed Chunk store path is unavailable or unsafe", unsafe);
        }
    }

    public synchronized ReadResult read(
            SaveGameId requestedSaveGameId,
            ChunkKey key,
            ExpectedBase expectedBase) {
        Objects.requireNonNull(requestedSaveGameId, "requestedSaveGameId");
        ChunkKey checkedKey;
        try {
            checkedKey = ChunkCoordinatePolicy.requireSafe(key);
            Objects.requireNonNull(expectedBase, "expectedBase");
        } catch (RuntimeException invalid) {
            return ReadResult.failed(
                    ReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.invalid-read-request",
                            "The streamed Chunk read request is invalid",
                            invalid));
        }
        if (!saveGameId.equals(requestedSaveGameId)) {
            return ReadResult.failed(
                    ReadResult.Status.IDENTITY_MISMATCH,
                    diagnostic(
                            "streamed-chunk-store.world-identity-mismatch",
                            "The streamed Chunk world identity does not match"));
        }
        try {
            StructuralSlot authority = observeStructuralAuthority();
            StreamedChunkIndex.Entry entry = authority.index.entry(checkedKey)
                    .orElse(null);
            if (entry == null) {
                return ReadResult.notFound();
            }
            if (!entry.generatorVersion().equals(expectedBase.generatorVersion())
                    || !entry.baseHash().equals(expectedBase.baseHash())) {
                return ReadResult.failed(
                        ReadResult.Status.BASE_MISMATCH,
                        diagnostic(
                                "streamed-chunk-store.base-identity-mismatch",
                                "The streamed Chunk base identity does not match"));
            }
            ResolvedPayload resolved = resolvePayload(entry);
            StreamedChunkPayload payload = resolved.payload;
            if (!payload.saveGameId().equals(saveGameId)
                    || !payload.key().equals(checkedKey)) {
                return ReadResult.failed(
                        ReadResult.Status.IDENTITY_MISMATCH,
                        diagnostic(
                                "streamed-chunk-store.chunk-identity-mismatch",
                                "The streamed Chunk payload identity does not match"));
            }
            if (!payload.generatorVersion().equals(expectedBase.generatorVersion())
                    || !payload.baseHash().equals(expectedBase.baseHash())) {
                return ReadResult.failed(
                        ReadResult.Status.BASE_MISMATCH,
                        diagnostic(
                                "streamed-chunk-store.base-identity-mismatch",
                                "The streamed Chunk base identity does not match"));
            }
            return ReadResult.found(payload);
        } catch (StoreFailure failure) {
            return ReadResult.failed(
                    failure.identityMismatch
                            ? ReadResult.Status.IDENTITY_MISMATCH
                            : ReadResult.Status.CORRUPT,
                    failure.diagnostic());
        } catch (RuntimeException failure) {
            return ReadResult.failed(
                    ReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.read-failed",
                            "The streamed Chunk could not be read safely",
                            failure));
        }
    }

    /** Returns the current immutable structural index without payload scan. */
    /** Returns one immutable validated observation of the currently published index. */
    public synchronized StreamedChunkIndex readCurrentIndex() {
        return observeStructuralAuthority().index;
    }

    /** Validates and returns one complete expected authority in one observation. */
    public synchronized BatchReadResult readModifiedBatch(
            SaveGameId requestedSaveGameId,
            StreamedChunkIndex expectedIndex) {
        Objects.requireNonNull(requestedSaveGameId, "requestedSaveGameId");
        final StreamedChunkIndex checkedIndex;
        try {
            checkedIndex = Objects.requireNonNull(expectedIndex, "expectedIndex");
            if (!checkedIndex.saveGameId().equals(requestedSaveGameId)) {
                return BatchReadResult.failed(
                        BatchReadResult.Status.IDENTITY_MISMATCH,
                        diagnostic(
                                "streamed-chunk-store.world-identity-mismatch",
                                "The streamed Chunk world identity does not match"));
            }
            indexCodec.encode(checkedIndex);
        } catch (RuntimeException invalid) {
            return BatchReadResult.failed(
                    BatchReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.invalid-batch-read-request",
                            "The streamed Chunk batch read request is invalid",
                            invalid));
        }
        if (!saveGameId.equals(requestedSaveGameId)) {
            return BatchReadResult.failed(
                    BatchReadResult.Status.IDENTITY_MISMATCH,
                    diagnostic(
                            "streamed-chunk-store.world-identity-mismatch",
                            "The streamed Chunk world identity does not match"));
        }
        try {
            Authority authority = observeAuthority();
            if (!Arrays.equals(
                    indexCodec.encode(authority.snapshot.index),
                    indexCodec.encode(checkedIndex))) {
                throw corrupt(
                        "streamed-chunk-store.index-mismatch",
                        "The streamed Chunk authority does not match the expected index");
            }
            List<StreamedChunkPayload> payloads = new ArrayList<>(
                    checkedIndex.entries().size());
            for (StreamedChunkIndex.Entry entry : checkedIndex.entries()) {
                ResolvedPayload resolved = authority.resolved.get(entry.key());
                if (resolved == null) {
                    throw corrupt(
                            "streamed-chunk-store.index-payload-mismatch",
                            "The streamed Chunk index and payload do not match");
                }
                payloads.add(resolved.payload);
            }
            return BatchReadResult.found(payloads);
        } catch (StoreFailure failure) {
            return BatchReadResult.failed(
                    failure.identityMismatch
                            ? BatchReadResult.Status.IDENTITY_MISMATCH
                            : BatchReadResult.Status.CORRUPT,
                    failure.diagnostic());
        } catch (RuntimeException failure) {
            return BatchReadResult.failed(
                    BatchReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.batch-read-failed",
                            "The streamed Chunk batch could not be read safely",
                            failure));
        }
    }

    /** Returns the latest completely validated fixed-slot authority. */
    public synchronized CurrentAuthorityReadResult readCurrentAuthority(
            SaveGameId requestedSaveGameId) {
        Objects.requireNonNull(requestedSaveGameId, "requestedSaveGameId");
        if (!saveGameId.equals(requestedSaveGameId)) {
            return CurrentAuthorityReadResult.failed(
                    CurrentAuthorityReadResult.Status.IDENTITY_MISMATCH,
                    diagnostic(
                            "streamed-chunk-store.world-identity-mismatch",
                            "The streamed Chunk world identity does not match"));
        }
        try {
            Authority authority = observeAuthority();
            List<StreamedChunkPayload> payloads = new ArrayList<>(
                    authority.snapshot.index.entries().size());
            for (StreamedChunkIndex.Entry entry : authority.snapshot.index.entries()) {
                ResolvedPayload resolved = authority.resolved.get(entry.key());
                if (resolved == null) {
                    throw corrupt(
                            "streamed-chunk-store.index-payload-mismatch",
                            "The streamed Chunk index and payload do not match");
                }
                payloads.add(resolved.payload);
            }
            return CurrentAuthorityReadResult.found(
                    authority.snapshot.index, payloads);
        } catch (StoreFailure failure) {
            return CurrentAuthorityReadResult.failed(
                    failure.identityMismatch
                            ? CurrentAuthorityReadResult.Status.IDENTITY_MISMATCH
                            : CurrentAuthorityReadResult.Status.CORRUPT,
                    failure.diagnostic());
        } catch (RuntimeException failure) {
            return CurrentAuthorityReadResult.failed(
                    CurrentAuthorityReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.current-authority-read-failed",
                            "The current streamed Chunk authority could not be read safely",
                            failure));
        }
    }

    /**
     * Reads only the crash-safe dual index envelopes to discover whether this
     * store has durably published Phase 14 migration compatibility. Payloads
     * are deliberately not resolved; callers must still validate the complete
     * authority before exposing v2 data.
     */
    public synchronized MigrationCompatibilityReadResult readMigrationCompatibility(
            SaveGameId requestedSaveGameId) {
        Objects.requireNonNull(requestedSaveGameId, "requestedSaveGameId");
        if (!saveGameId.equals(requestedSaveGameId)) {
            return MigrationCompatibilityReadResult.failed(
                    MigrationCompatibilityReadResult.Status.IDENTITY_MISMATCH,
                    diagnostic(
                            "streamed-chunk-store.world-identity-mismatch",
                            "The streamed Chunk world identity does not match"));
        }
        try {
            SlotObservation main = observeSlot(mainIndex);
            SlotObservation recovery = observeSlot(recoveryIndex);
            if (main.interference != null || recovery.interference != null) {
                Throwable cause = main.interference != null
                        ? main.interference
                        : recovery.interference;
                throw blocking(
                        "streamed-chunk-store.managed-slot-identity-changed",
                        "A streamed Chunk managed slot changed outside the store",
                        cause);
            }
            CompatibilityAuthority compatibility = requireCompatibleAuthority(
                    main, recovery, true);
            return compatibility.proof
                    .map(MigrationCompatibilityReadResult::found)
                    .orElseGet(() -> MigrationCompatibilityReadResult.notPublished(
                            !compatibility.newest.index.entries().isEmpty()));
        } catch (StoreFailure failure) {
            return MigrationCompatibilityReadResult.failed(
                    failure.identityMismatch
                            ? MigrationCompatibilityReadResult.Status.IDENTITY_MISMATCH
                            : MigrationCompatibilityReadResult.Status.CORRUPT,
                    failure.diagnostic());
        } catch (RuntimeException failure) {
            return MigrationCompatibilityReadResult.failed(
                    MigrationCompatibilityReadResult.Status.CORRUPT,
                    diagnostic(
                            "streamed-chunk-store.migration-proof-read-failed",
                            "The streamed migration compatibility proof could not be read safely",
                            failure));
        }
    }

    /** Full validation for the only two Task 4 shapes that may precede migration. */
    public synchronized boolean hasCompleteUnpublishedAuthority() {
        try {
            SlotObservation main = observeSlot(mainIndex);
            SlotObservation recovery = observeSlot(recoveryIndex);
            CompatibilityAuthority compatibility = requireCompatibleAuthority(
                    main, recovery, true);
            if (compatibility.proof.isPresent()) {
                return false;
            }
            StructuralSlot first = main.structural;
            StructuralSlot second = recovery.structural;
            boolean initializedEmpty = first.sequence == 0L
                    && second.sequence == 0L
                    && first.index.entries().isEmpty()
                    && second.index.entries().isEmpty()
                    && first.index.globalExtensions().isEmpty()
                    && second.index.globalExtensions().isEmpty()
                    && Arrays.equals(first.bytes, second.bytes);
            if (initializedEmpty) {
                return true;
            }
            return !compatibility.newest.index.entries().isEmpty()
                    && validateSlot(first) != null
                    && validateSlot(second) != null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public synchronized CommitResult commitModified(
            ExactChunkCapture capture,
            WorldItemHibernatePayload hibernationPayload) {
        ExactChunkCapture checkedCapture;
        WorldItemHibernatePayload checkedHibernation;
        try {
            checkedCapture = Objects.requireNonNull(capture, "capture");
            checkedHibernation = Objects.requireNonNull(
                    hibernationPayload, "hibernationPayload");
            validateCaptureIdentity(checkedCapture.payload());
        } catch (RuntimeException invalid) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.invalid-capture",
                    "The exact streamed Chunk capture is invalid",
                    invalid));
        }

        try (WriterLease ignored = writerGate.acquireWriter()) {
            Authority authority = observeAuthority();
            StreamedChunkPayload intended = checkedCapture.payload();
            StreamedChunkIndex.Entry previousEntry = authority.snapshot.index
                    .entry(intended.key())
                    .orElse(null);
            if (previousEntry != null
                    && (!previousEntry.generatorVersion().equals(
                                    intended.generatorVersion())
                            || !previousEntry.baseHash().equals(intended.baseHash()))) {
                return CommitResult.failed(diagnostic(
                        "streamed-chunk-store.base-identity-mismatch",
                        "The streamed Chunk base identity does not match"));
            }
            if (previousEntry != null
                    && (previousEntry.revision() != intended.persistedRevision()
                            || intended.revision() <= previousEntry.revision())) {
                return CommitResult.stale(diagnostic(
                        "streamed-chunk-store.stale-revision",
                        "The streamed Chunk capture revision is stale"));
            }
            char referenced = previousEntry == null
                    ? '\0'
                    : authority.resolved.get(intended.key()).slot;
            SequencePlan sequence = sequencePlan(authority.snapshot.sequence, referenced);

            // Encode and validate every intended byte before callbacks or mutation.
            byte[] payloadBytes = payloadCodec.encode(intended);
            StreamedChunkIndex.Entry intendedEntry = new StreamedChunkIndex.Entry(
                    intended.key(),
                    intended.generatorVersion(),
                    intended.baseHash(),
                    intended.revision(),
                    payloadBytes.length,
                    StreamedChunkCodec.sha256Hex(payloadBytes),
                    intended.persistenceRequired(),
                    intended.voxelModified());
            StreamedChunkIndex intendedIndex = authority.snapshot.index.with(intendedEntry);
            byte[] intendedEnvelope = encodeSlot(sequence.value, intendedIndex);

            // Freshness is a single fallible linearization gate.  No caller
            // callback may run after filesystem authority mutation begins.
            requireCurrent(checkedCapture, checkedHibernation);
            convergeTo(authority.snapshot);
            PayloadPair pair = ensurePayloadPool(intended.key());
            ManagedSlot target = sequence.slot == 'a' ? pair.a : pair.b;
            if (previousEntry != null && target.path.equals(
                    authority.resolved.get(intended.key()).managed.path)) {
                throw failure(
                        "streamed-chunk-store.payload-slot-still-authoritative",
                        "The next streamed Chunk payload slot is still authoritative");
            }

            writeAndValidatePayload(
                    target,
                    payloadBytes,
                    intendedEntry);
            writeAndValidateIndex(
                    recoveryIndex,
                    intendedEnvelope,
                    sequence.value,
                    intendedIndex);
            writeAndValidateIndex(
                    mainIndex,
                    intendedEnvelope,
                    sequence.value,
                    intendedIndex);
            rememberModifiedCount(intendedIndex);
            return CommitResult.success();
        } catch (Error fatal) {
            throw fatal;
        } catch (StoreFailure failure) {
            if (failure.stale) {
                return CommitResult.stale(failure.diagnostic());
            }
            return failure.blocking
                    ? CommitResult.blocking(failure.diagnostic())
                    : CommitResult.failed(failure.diagnostic());
        } catch (RuntimeException failure) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.transaction-failed",
                    "The streamed Chunk transaction failed",
                    failure));
        }
    }

    /**
     * Atomically publishes a bounded set of modified Chunk captures with one
     * index sequence. Candidate payload slots are forced and reread first; until
     * both index slots name the complete set, the previously observed index is
     * the only authority.
     */
    public synchronized CommitResult commitModifiedBatch(
            List<ExactChunkCapture> captures,
            WorldItemHibernatePayload hibernationPayload) {
        return commitLargeBatch(captures, hibernationPayload, Optional.empty());
    }

    /**
     * Bounded last-known-good count from this session graph's most recently
     * validated authority. This observation performs no filesystem read or
     * full-index scan on the owner frame.
     */
    public int modifiedChunkCount() {
        return lastValidatedModifiedChunkCount;
    }

    private static SharedWriterGate writerGateFor(WriterGateKey key) {
        Objects.requireNonNull(key, "key");
        drainWriterGateQueue();
        while (true) {
            GateReference current = WRITER_GATES.get(key);
            SharedWriterGate live = current == null ? null : current.get();
            if (live != null) {
                return live;
            }
            SharedWriterGate created = new SharedWriterGate();
            GateReference replacement = new GateReference(
                    key, created, WRITER_GATE_QUEUE);
            boolean installed = current == null
                    ? WRITER_GATES.putIfAbsent(key, replacement) == null
                    : WRITER_GATES.replace(key, current, replacement);
            if (installed) {
                return created;
            }
        }
    }

    /** Exact fresh-store shape allowed to precede the initial v1 migration floor. */
    public synchronized boolean hasPristineUnpublishedAuthority() {
        try {
            SlotObservation main = observeSlot(mainIndex);
            SlotObservation recovery = observeSlot(recoveryIndex);
            CompatibilityAuthority compatibility = requireCompatibleAuthority(
                    main, recovery, true);
            if (compatibility.proof.isPresent()) {
                return false;
            }
            StructuralSlot first = main.structural;
            StructuralSlot second = recovery.structural;
            return first.sequence == 0L
                    && second.sequence == 0L
                    && first.index.entries().isEmpty()
                    && second.index.entries().isEmpty()
                    && first.index.globalExtensions().isEmpty()
                    && second.index.globalExtensions().isEmpty()
                    && Arrays.equals(first.bytes, second.bytes);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Rebinds this already-open fresh store after the one approved Phase 14
     * migrator has published through the same fixed slot objects.
     */
    public synchronized void acknowledgePublishedMigration(
            StreamedChunkIndex.MigrationCompatibility expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            mainIndex.refreshSameObject();
            recoveryIndex.refreshSameObject();
            payloadSlots.clear();
            shardIdentities.clear();
            initializedPayloadPools.clear();
            Authority authority = observeAuthority();
            if (!authority.snapshot.index.migrationCompatibility()
                    .filter(expected::equals)
                    .isPresent()) {
                throw new IOException(
                        "Published migration compatibility does not match");
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Published migration could not be rebound to the open store",
                    failure);
        }
    }

    private static void drainWriterGateQueue() {
        GateReference expired;
        while ((expired = (GateReference) WRITER_GATE_QUEUE.poll()) != null) {
            WRITER_GATES.remove(expired.key, expired);
        }
    }

    synchronized CommitResult commitMigrationBatch(
            List<ExactChunkCapture> captures,
            StreamedGlobalExtension checkpointExtension) {
        return commitLargeBatch(
                captures,
                new WorldItemHibernatePayload(new byte[0], () -> true),
                Optional.of(Objects.requireNonNull(
                        checkpointExtension, "checkpointExtension")));
    }

    private CommitResult commitLargeBatch(
            List<ExactChunkCapture> captures,
            WorldItemHibernatePayload hibernationPayload,
            Optional<StreamedGlobalExtension> globalExtension) {
        final List<ExactChunkCapture> checkedCaptures;
        final WorldItemHibernatePayload checkedHibernation;
        try {
            checkedCaptures = List.copyOf(Objects.requireNonNull(
                    captures, "captures"));
            checkedHibernation = Objects.requireNonNull(
                    hibernationPayload, "hibernationPayload");
            if ((checkedCaptures.isEmpty() && globalExtension.isEmpty())
                    || checkedCaptures.size() > 65_536) {
                throw new IllegalArgumentException(
                        "A streamed Chunk batch must contain 1..65536 captures, "
                                + "except an empty migration checkpoint publication");
            }
            Set<ChunkKey> keys = new HashSet<>();
            for (ExactChunkCapture capture : checkedCaptures) {
                ExactChunkCapture checked = Objects.requireNonNull(
                        capture, "capture");
                validateCaptureIdentity(checked.payload());
                if (!keys.add(checked.payload().key())) {
                    throw new IllegalArgumentException(
                            "A streamed Chunk batch contains duplicate keys");
                }
            }
        } catch (RuntimeException invalid) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.invalid-batch",
                    "The exact streamed Chunk batch is invalid",
                    invalid));
        }

        try (WriterLease ignored = writerGate.acquireWriter()) {
            Authority authority = observeAuthority();
            StreamedChunkIndex intendedIndex = authority.snapshot.index;
            List<BatchWrite> writes = new ArrayList<>(checkedCaptures.size());
            Map<ChunkKey, StreamedChunkPayload> intendedPayloads = new HashMap<>();
            for (ExactChunkCapture capture : checkedCaptures) {
                StreamedChunkPayload intended = capture.payload();
                intendedPayloads.put(intended.key(), intended);
                StreamedChunkIndex.Entry previousEntry = authority.snapshot.index
                        .entry(intended.key())
                        .orElse(null);
                ResolvedPayload previous = authority.resolved.get(intended.key());
                if (previousEntry != null
                        && previous != null
                        && previous.payload.equals(intended)) {
                    continue;
                }
                if (previousEntry != null
                        && (!previousEntry.generatorVersion().equals(
                                        intended.generatorVersion())
                                || !previousEntry.baseHash().equals(
                                        intended.baseHash()))) {
                    throw failure(
                            "streamed-chunk-store.base-identity-mismatch",
                            "The streamed Chunk base identity does not match");
                }
                if (previousEntry == null && intended.persistedRevision() != 0L) {
                    throw stale(
                            "streamed-chunk-store.stale-revision",
                            "A new streamed Chunk batch capture has stale persisted state");
                }
                if (previousEntry != null
                        && (previousEntry.revision()
                                        != intended.persistedRevision()
                                || intended.revision()
                                        <= previousEntry.revision())) {
                    throw stale(
                            "streamed-chunk-store.stale-revision",
                            "The streamed Chunk batch contains a stale capture");
                }
                byte[] payloadBytes = payloadCodec.encode(intended);
                StreamedChunkIndex.Entry intendedEntry =
                        new StreamedChunkIndex.Entry(
                                intended.key(),
                                intended.generatorVersion(),
                                intended.baseHash(),
                                intended.revision(),
                                payloadBytes.length,
                                StreamedChunkCodec.sha256Hex(payloadBytes),
                                intended.persistenceRequired(),
                                intended.voxelModified());
                intendedIndex = intendedIndex.with(intendedEntry);
                writes.add(new BatchWrite(
                        capture,
                        payloadBytes,
                        intendedEntry,
                        previous == null ? '\0' : previous.slot));
            }
            boolean extensionChanged = globalExtension.isPresent()
                    && !authority.snapshot.index.globalExtension(
                                    globalExtension.orElseThrow().sectionId())
                            .filter(globalExtension.orElseThrow()::equals)
                            .isPresent();
            if (globalExtension.isPresent()) {
                intendedIndex = intendedIndex.withGlobalExtension(
                        globalExtension.orElseThrow());
                requireDependencyCounts(
                        authority.snapshot.index,
                        intendedIndex,
                        authority.resolved,
                        intendedPayloads,
                        List.of());
            }
            long sequence = writes.isEmpty() && !extensionChanged
                    ? authority.snapshot.sequence
                    : nextBatchSequence(authority.snapshot.sequence);
            byte[] intendedEnvelope = encodeSlot(sequence, intendedIndex);

            requireBatchCurrent(checkedCaptures, checkedHibernation);
            if (writes.isEmpty() && !extensionChanged) {
                return CommitResult.success();
            }
            convergeTo(authority.snapshot);
            for (BatchWrite write : writes) {
                PayloadPair pair = ensurePayloadPool(write.entry.key());
                char targetSlot = write.previousSlot == '\0'
                        ? slotFor(sequence)
                        : write.previousSlot == 'a' ? 'b' : 'a';
                ManagedSlot target = targetSlot == 'a' ? pair.a : pair.b;
                writeAndValidatePayload(
                        target, write.payloadBytes, write.entry);
            }
            writeAndValidateIndex(
                    recoveryIndex,
                    intendedEnvelope,
                    sequence,
                    intendedIndex);
            writeAndValidateIndex(
                    mainIndex,
                    intendedEnvelope,
                    sequence,
                    intendedIndex);
            rememberModifiedCount(intendedIndex);
            return CommitResult.success();
        } catch (Error fatal) {
            throw fatal;
        } catch (StoreFailure failure) {
            if (failure.stale) {
                return CommitResult.stale(failure.diagnostic());
            }
            return failure.blocking
                    ? CommitResult.blocking(failure.diagnostic())
                    : CommitResult.failed(failure.diagnostic());
        } catch (RuntimeException failure) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.batch-transaction-failed",
                    "The streamed Chunk batch could not be committed safely",
                    failure));
        }
    }

    /** Atomically publishes bounded Chunk and inline global-extension mutations. */
    public synchronized CommitResult commitTransaction(
            StreamedPersistenceTransaction transaction) {
        return commitTransaction(
                transaction, Optional.empty());
    }

    synchronized CommitResult commitMigrationTransaction(
            StreamedPersistenceTransaction transaction,
            StreamedChunkIndex.MigrationCompatibility compatibility) {
        return commitTransaction(
                transaction,
                Optional.of(Objects.requireNonNull(
                        compatibility, "compatibility")));
    }

    private CommitResult commitTransaction(
            StreamedPersistenceTransaction transaction,
            Optional<StreamedChunkIndex.MigrationCompatibility>
                    migrationCompatibility) {
        final StreamedPersistenceTransaction checked;
        try {
            checked = Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(
                    migrationCompatibility, "migrationCompatibility");
        } catch (RuntimeException invalid) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.invalid-transaction",
                    "The streamed persistence transaction is invalid",
                    invalid));
        }
        try (WriterLease ignored = writerGate.acquireWriter()) {
            Authority authority = observeAuthority();
            StreamedChunkIndex intendedIndex = authority.snapshot.index;
            List<BatchWrite> writes = new ArrayList<>(checked.chunks().size());
            List<StreamedChunkMutation.Remove> removals = new ArrayList<>();
            Map<ChunkKey, StreamedChunkPayload> intendedPayloads = new HashMap<>();
            Set<ChunkKey> keys = new HashSet<>();
            long candidateBytes = 0L;
            for (StreamedChunkMutation mutation : checked.chunks()) {
                if (mutation instanceof StreamedChunkMutation.Remove remove) {
                    StreamedChunkIndex.Entry previous = authority.snapshot.index
                            .entry(remove.key()).orElse(null);
                    if (previous == null
                            || previous.revision() != remove.expectedRevision()
                            || !previous.payloadHash().equals(remove.expectedHash())) {
                        throw stale(
                                "streamed-chunk-store.stale-remove",
                                "The streamed Chunk remove expectation is stale");
                    }
                    intendedIndex = intendedIndex.without(remove.key());
                    removals.add(remove);
                    continue;
                }
                StreamedChunkMutation.Upsert upsert =
                        (StreamedChunkMutation.Upsert) mutation;
                ExactChunkCapture exact = Objects.requireNonNull(
                        upsert.capture(), "capture");
                validateCaptureIdentity(exact.payload());
                if (!keys.add(exact.payload().key())) {
                    throw new IllegalArgumentException(
                            "A streamed transaction repeats a Chunk key");
                }
                StreamedChunkPayload intended = exact.payload();
                StreamedChunkIndex.Entry previousEntry = authority.snapshot.index
                        .entry(intended.key()).orElse(null);
                ResolvedPayload previous = authority.resolved.get(intended.key());
                if (previousEntry != null
                        && (!previousEntry.generatorVersion().equals(
                                        intended.generatorVersion())
                                || !previousEntry.baseHash().equals(
                                intended.baseHash()))) {
                    throw failure(
                            "streamed-chunk-store.base-identity-mismatch",
                            "The streamed Chunk base identity does not match");
                }
                if (previousEntry != null
                        && previousEntry.revision() == intended.revision()) {
                    if (previous != null
                            && previous.payload().equals(intended)) {
                        continue;
                    }
                    throw stale(
                            "streamed-chunk-store.stale-revision",
                            "The streamed Chunk transaction conflicts at the current revision");
                }
                if (previousEntry == null && intended.persistedRevision() != 0L) {
                    throw stale(
                            "streamed-chunk-store.stale-revision",
                            "A new streamed Chunk transaction capture is stale");
                }
                if (previousEntry != null
                        && (previousEntry.revision() != intended.persistedRevision()
                                || intended.revision() <= previousEntry.revision())) {
                    throw stale(
                            "streamed-chunk-store.stale-revision",
                            "The streamed Chunk transaction contains a stale capture");
                }
                byte[] payloadBytes = candidateEncoder.encode(intended);
                candidateBytes = Math.addExact(candidateBytes, payloadBytes.length);
                if (candidateBytes
                        > StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES) {
                    throw new IllegalArgumentException(
                            "Streamed transaction candidate bytes exceed their bound");
                }
                StreamedChunkIndex.Entry intendedEntry = new StreamedChunkIndex.Entry(
                        intended.key(),
                        intended.generatorVersion(),
                        intended.baseHash(),
                        intended.revision(),
                        payloadBytes.length,
                        StreamedChunkCodec.sha256Hex(payloadBytes),
                        intended.persistenceRequired(),
                        intended.voxelModified());
                intendedIndex = intendedIndex.with(intendedEntry);
                intendedPayloads.put(intended.key(), intended);
                writes.add(new BatchWrite(
                        exact,
                        payloadBytes,
                        intendedEntry,
                        previous == null ? '\0' : previous.slot));
            }
            for (StreamedGlobalExtensionMutation mutation
                    : checked.globalExtensionMutations()) {
                if (mutation instanceof StreamedGlobalExtensionMutation.Upsert upsert) {
                    intendedIndex = intendedIndex.withGlobalExtension(upsert.extension());
                } else {
                    intendedIndex = intendedIndex.withoutGlobalExtension(
                            ((StreamedGlobalExtensionMutation.Remove) mutation).sectionId());
                }
            }
            boolean compatibilityChanged = false;
            if (migrationCompatibility.isPresent()) {
                StreamedChunkIndex.MigrationCompatibility compatibility =
                        migrationCompatibility.orElseThrow();
                Optional<StreamedChunkIndex.MigrationCompatibility> existing =
                        intendedIndex.migrationCompatibility();
                if (existing.isPresent()
                        && !existing.orElseThrow().equals(compatibility)) {
                    throw failure(
                            "streamed-chunk-store.migration-compatibility-conflict",
                            "The migration compatibility proof conflicts");
                }
                if (existing.isEmpty()) {
                    intendedIndex = intendedIndex.withMigrationCompatibility(
                            compatibility);
                    compatibilityChanged = true;
                }
            }
            requireDependencyCounts(
                    authority.snapshot.index,
                    intendedIndex,
                    authority.resolved,
                    intendedPayloads,
                    removals);
            boolean changes = !writes.isEmpty()
                    || !removals.isEmpty()
                    || !checked.globalExtensionMutations().isEmpty()
                    || compatibilityChanged;
            long sequence = changes
                    ? nextBatchSequence(authority.snapshot.sequence)
                    : authority.snapshot.sequence;
            byte[] intendedEnvelope = encodeSlot(sequence, intendedIndex);

            requireTransactionCurrent(checked);
            if (!changes) {
                return CommitResult.success();
            }
            convergeTo(authority.snapshot);
            for (BatchWrite write : writes) {
                PayloadPair pair = ensurePayloadPool(write.entry.key());
                char targetSlot = write.previousSlot == '\0'
                        ? slotFor(sequence)
                        : write.previousSlot == 'a' ? 'b' : 'a';
                writeAndValidatePayload(
                        targetSlot == 'a' ? pair.a : pair.b,
                        write.payloadBytes,
                        write.entry);
            }
            writeAndValidateIndex(
                    recoveryIndex, intendedEnvelope, sequence, intendedIndex);
            writeAndValidateIndex(mainIndex, intendedEnvelope, sequence, intendedIndex);
            rememberModifiedCount(intendedIndex);
            return CommitResult.success();
        } catch (Error fatal) {
            throw fatal;
        } catch (StoreFailure failure) {
            if (failure.stale) {
                return CommitResult.stale(failure.diagnostic());
            }
            return failure.blocking
                    ? CommitResult.blocking(failure.diagnostic())
                    : CommitResult.failed(failure.diagnostic());
        } catch (RuntimeException failure) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.transaction-failed",
                    "The streamed transaction could not be committed safely",
                    failure));
        }
    }

    /**
     * Opens one bounded, invisible multi-batch candidate against the current
     * index generation. Payload batches may be staged independently, but only
     * {@link StagedTransaction#publish(List)} changes index authority.
     */
    synchronized StagedTransaction beginStagedTransaction(
            BooleanSupplier stillCurrent) {
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        WriterLease writer = writerGate.acquireWriter();
        try {
            StructuralSlot base = observeAuthorityBounded();
            requireBooleanCurrent(stillCurrent);
            StructuralSlot refreshed = observeAuthorityBounded();
            if (!Arrays.equals(base.bytes, refreshed.bytes)) {
                throw stale(
                        "streamed-chunk-store.staging-generation-stale",
                        "The streamed staging base generation changed");
            }
            convergeTo(base);
            requireStructuralBaseCurrent(base);
            return new StagedTransaction(
                    base,
                    nextBatchSequence(base.sequence),
                    stillCurrent,
                    writer);
        } catch (Error | RuntimeException failure) {
            writer.close();
            throw failure;
        }
    }

    final class StagedTransaction implements AutoCloseable {
        private final StructuralSlot base;
        private final long intendedSequence;
        private final BooleanSupplier stillCurrent;
        private final WriterLease writer;
        private final Set<ChunkKey> mutationKeys = new HashSet<>();
        private final Map<ChunkKey, StagedWrite> stagedWrites =
                new LinkedHashMap<>();
        private StreamedChunkIndex candidateIndex;
        private StagingState state = StagingState.OPEN;
        private int stagedMutations;
        private int maximumBatchMutations;
        private long maximumBatchBytes;
        private int maximumBatchPhysicalBlobs;
        private long maximumTransientPayloadBytesUpperBound;

        private StagedTransaction(
                StructuralSlot base,
                long intendedSequence,
                BooleanSupplier stillCurrent,
                WriterLease writer) {
            this.base = base;
            this.intendedSequence = intendedSequence;
            this.stillCurrent = stillCurrent;
            this.writer = writer;
            this.candidateIndex = base.index;
        }

        long baseSequence() {
            synchronized (StreamedChunkStore.this) {
                return base.sequence;
            }
        }

        StreamedChunkIndex baseIndex() {
            synchronized (StreamedChunkStore.this) {
                return base.index;
            }
        }

        StreamedChunkPayload basePayload(ChunkKey key) {
            synchronized (StreamedChunkStore.this) {
                requireOpen();
                StreamedChunkIndex.Entry entry = base.index.entry(
                        ChunkCoordinatePolicy.requireSafe(key)).orElse(null);
                return entry == null ? null : resolvePayload(entry).payload();
            }
        }

        CommitResult stageBatch(List<StreamedChunkMutation> mutations) {
            synchronized (StreamedChunkStore.this) {
                if (state != StagingState.OPEN) {
                    return CommitResult.stale(diagnostic(
                            "streamed-chunk-store.staging-closed",
                            "The streamed staging generation is no longer open"));
                }
                try {
                    List<StreamedChunkMutation> checked = List.copyOf(
                            Objects.requireNonNull(mutations, "mutations"));
                    if (checked.isEmpty()
                            || checked.size()
                                    > StreamedPersistenceTransaction.MAX_CHUNKS) {
                        throw new IllegalArgumentException(
                                "A staging batch must contain 1..64 mutations");
                    }
                    if (Math.addExact(stagedMutations, checked.size()) > 1_024) {
                        throw new IllegalArgumentException(
                                "A staging generation exceeds 1024 mutations");
                    }
                    long canonicalBatchBytes = 0L;
                    int physicalBlobs = 0;
                    for (StreamedChunkMutation mutation : checked) {
                        Objects.requireNonNull(mutation, "mutation");
                        if (mutation instanceof StreamedChunkMutation.Upsert upsert) {
                            ChunkKey key = upsert.capture().payload().key();
                            physicalBlobs = Math.addExact(
                                    physicalBlobs,
                                    base.index.entry(key).isPresent() ? 1 : 2);
                            if (physicalBlobs
                                    > StreamedPersistenceTransaction.MAX_CHUNKS) {
                                throw new IllegalArgumentException(
                                        "Staging batch physical blobs exceed 64");
                            }
                            canonicalBatchBytes = Math.addExact(
                                    canonicalBatchBytes,
                                    StreamedChunkCodec.canonicalEncodedSize(
                                            upsert.capture().payload()));
                            if (canonicalBatchBytes
                                    > StreamedPersistenceTransaction
                                            .MAX_CANDIDATE_BYTES) {
                                throw new IllegalArgumentException(
                                        "Staging batch candidate bytes exceed 64 MiB");
                            }
                        }
                    }

                    StreamedChunkIndex nextIndex = candidateIndex;
                    List<PreparedStage> prepared = new ArrayList<>(checked.size());
                    Set<ChunkKey> batchKeys = new HashSet<>();
                    long batchBytes = 0L;
                    for (StreamedChunkMutation mutation : checked) {
                        Objects.requireNonNull(mutation, "mutation");
                        ChunkKey key = mutation instanceof StreamedChunkMutation.Upsert upsert
                                ? upsert.capture().payload().key()
                                : ((StreamedChunkMutation.Remove) mutation).key();
                        key = ChunkCoordinatePolicy.requireSafe(key);
                        if (!batchKeys.add(key) || mutationKeys.contains(key)) {
                            throw new IllegalArgumentException(
                                    "A staging generation repeats a Chunk key");
                        }
                        StreamedChunkIndex.Entry previous = base.index.entry(key)
                                .orElse(null);
                        if (mutation instanceof StreamedChunkMutation.Remove remove) {
                            if (previous == null
                                    || previous.revision() != remove.expectedRevision()
                                    || !previous.payloadHash().equals(
                                            remove.expectedHash())) {
                                throw stale(
                                        "streamed-chunk-store.stale-remove",
                                        "The staged Chunk remove expectation is stale");
                            }
                            prepared.add(PreparedStage.remove(key));
                            nextIndex = nextIndex.without(key);
                            continue;
                        }

                        StreamedChunkStore.ExactChunkCapture exact =
                                ((StreamedChunkMutation.Upsert) mutation).capture();
                        validateCaptureIdentity(exact.payload());
                        StreamedChunkPayload payload = exact.payload();
                        if (previous != null
                                && (!previous.generatorVersion().equals(
                                                payload.generatorVersion())
                                        || !previous.baseHash().equals(
                                                payload.baseHash()))) {
                            throw failure(
                                    "streamed-chunk-store.base-identity-mismatch",
                                    "The staged Chunk base identity does not match");
                        }
                        if (previous == null && payload.persistedRevision() != 0L) {
                            throw stale(
                                    "streamed-chunk-store.stale-revision",
                                    "A new staged Chunk capture is stale");
                        }
                        if (previous != null
                                && (previous.revision()
                                                != payload.persistedRevision()
                                        || payload.revision()
                                                <= previous.revision())) {
                            throw stale(
                                    "streamed-chunk-store.stale-revision",
                                    "The staged Chunk capture revision is stale");
                        }
                        byte[] bytes = candidateEncoder.encode(payload);
                        batchBytes = Math.addExact(batchBytes, bytes.length);
                        if (batchBytes
                                > StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES) {
                            throw new IllegalArgumentException(
                                    "Staging batch candidate bytes exceed 64 MiB");
                        }
                        StreamedChunkIndex.Entry entry =
                                new StreamedChunkIndex.Entry(
                                        payload.key(),
                                        payload.generatorVersion(),
                                        payload.baseHash(),
                                        payload.revision(),
                                        bytes.length,
                                        StreamedChunkCodec.sha256Hex(bytes),
                                        payload.persistenceRequired(),
                                        payload.voxelModified());
                        char previousSlot = previous == null
                                ? '\0'
                                : resolvePayload(previous).slot;
                        char targetSlot = previousSlot == '\0'
                                ? slotFor(intendedSequence)
                                : previousSlot == 'a' ? 'b' : 'a';
                        prepared.add(PreparedStage.upsert(
                                exact.stillCurrent(), bytes, entry, targetSlot));
                        nextIndex = nextIndex.with(entry);
                    }

                    requireStructuralBaseCurrent(base);
                    requireBooleanCurrent(stillCurrent);
                    for (PreparedStage stage : prepared) {
                        if (stage.remove) {
                            continue;
                        }
                        requireBooleanCurrent(stage.stillCurrent);
                    }
                    requireStructuralBaseCurrent(base);
                    for (PreparedStage stage : prepared) {
                        if (stage.remove) {
                            continue;
                        }
                        PayloadPair pair = ensurePayloadPool(stage.entry.key());
                        ManagedSlot target = stage.targetSlot == 'a' ? pair.a : pair.b;
                        writeAndValidatePayload(target, stage.payloadBytes, stage.entry);
                        stagedWrites.put(
                                stage.entry.key(),
                                new StagedWrite(
                                        stage.entry,
                                        target,
                                        stage.targetSlot,
                                        stage.stillCurrent));
                    }
                    for (PreparedStage stage : prepared) {
                        ChunkKey key = stage.remove ? stage.key : stage.entry.key();
                        mutationKeys.add(key);
                    }
                    candidateIndex = nextIndex;
                    stagedMutations = Math.addExact(
                            stagedMutations, checked.size());
                    maximumBatchMutations = Math.max(
                            maximumBatchMutations, checked.size());
                    maximumBatchBytes = Math.max(maximumBatchBytes, batchBytes);
                    maximumBatchPhysicalBlobs = Math.max(
                            maximumBatchPhysicalBlobs, physicalBlobs);
                    long transientPayloadBytesUpperBound = Math.addExact(
                            Math.addExact(canonicalBatchBytes, batchBytes),
                            Math.multiplyExact(
                                    StreamedChunkCodec.MAX_FILE_BYTES, 6L));
                    if (transientPayloadBytesUpperBound
                            > MAX_STAGING_TRANSIENT_PAYLOAD_BYTES) {
                        throw new IllegalStateException(
                                "Staging transient payload bound is exceeded");
                    }
                    maximumTransientPayloadBytesUpperBound = Math.max(
                            maximumTransientPayloadBytesUpperBound,
                            transientPayloadBytesUpperBound);
                    return CommitResult.success();
                } catch (Error fatal) {
                    state = StagingState.FAILED;
                    writer.close();
                    throw fatal;
                } catch (StoreFailure failure) {
                    state = failure.stale ? StagingState.STALE : StagingState.FAILED;
                    writer.close();
                    return commitResult(failure);
                } catch (RuntimeException failure) {
                    state = StagingState.FAILED;
                    writer.close();
                    return CommitResult.failed(diagnostic(
                            "streamed-chunk-store.staging-batch-failed",
                            "The streamed staging batch failed",
                            failure));
                }
            }
        }

        CommitResult publish(
                List<StreamedGlobalExtensionMutation> globalMutations) {
            synchronized (StreamedChunkStore.this) {
                if (state != StagingState.OPEN) {
                    return CommitResult.stale(diagnostic(
                            "streamed-chunk-store.staging-closed",
                            "The streamed staging generation cannot publish"));
                }
                try {
                    StreamedPersistenceTransaction globals =
                            new StreamedPersistenceTransaction(
                                    List.of(),
                                    globalMutations,
                                    stillCurrent);
                    StreamedChunkIndex intended = candidateIndex;
                    for (StreamedGlobalExtensionMutation mutation
                            : globals.globalExtensionMutations()) {
                        if (mutation instanceof StreamedGlobalExtensionMutation.Upsert upsert) {
                            intended = intended.withGlobalExtension(upsert.extension());
                        } else {
                            intended = intended.withoutGlobalExtension(
                                    ((StreamedGlobalExtensionMutation.Remove) mutation)
                                            .sectionId());
                        }
                    }
                    requireStructuralBaseCurrent(base);
                    requireBooleanCurrent(stillCurrent);
                    validateStagedCandidate(base.index, intended, stagedWrites);
                    requireStructuralBaseCurrent(base);
                    requireBooleanCurrent(stillCurrent);
                    for (StagedWrite write : stagedWrites.values()) {
                        requireBooleanCurrent(write.stillCurrent);
                    }
                    requireStructuralBaseCurrent(base);
                    byte[] envelope = encodeSlot(intendedSequence, intended);
                    writeAndValidateIndex(
                            recoveryIndex, envelope, intendedSequence, intended);
                    writeAndValidateIndex(
                            mainIndex, envelope, intendedSequence, intended);
                    candidateIndex = intended;
                    rememberModifiedCount(intended);
                    state = StagingState.PUBLISHED;
                    writer.close();
                    return CommitResult.success();
                } catch (Error fatal) {
                    state = StagingState.FAILED;
                    writer.close();
                    throw fatal;
                } catch (StoreFailure failure) {
                    state = failure.stale ? StagingState.STALE : StagingState.FAILED;
                    writer.close();
                    return commitResult(failure);
                } catch (RuntimeException failure) {
                    state = StagingState.FAILED;
                    writer.close();
                    return CommitResult.failed(diagnostic(
                            "streamed-chunk-store.staging-publication-failed",
                            "The streamed staged candidate could not be published",
                            failure));
                }
            }
        }

        void cancel() {
            synchronized (StreamedChunkStore.this) {
                if (state == StagingState.OPEN) {
                    state = StagingState.CANCELED;
                    writer.close();
                }
            }
        }

        StagingMetrics metrics() {
            synchronized (StreamedChunkStore.this) {
                return new StagingMetrics(
                        stagedMutations,
                        maximumBatchMutations,
                        maximumBatchBytes,
                        maximumBatchPhysicalBlobs,
                        maximumTransientPayloadBytesUpperBound);
            }
        }

        @Override
        public void close() {
            cancel();
        }

        private void requireOpen() {
            if (state != StagingState.OPEN) {
                throw new IllegalStateException(
                        "The streamed staging generation is closed");
            }
        }
    }

    /** Captures one detached generic index generation and its resolved payloads. */
    synchronized PinnedReadView openPinnedReadView() {
        Authority authority = observeAuthority();
        Map<ChunkKey, StreamedChunkPayload> payloads = new HashMap<>();
        for (Map.Entry<ChunkKey, ResolvedPayload> entry
                : authority.resolved.entrySet()) {
            payloads.put(entry.getKey(), entry.getValue().payload());
        }
        return new PinnedReadView(
                authority.snapshot.sequence(),
                authority.snapshot.index(),
                payloads);
    }

    /**
     * Pins one structurally validated generation without retaining its payload set.
     * Payloads are decoded on demand while the shared reader capability prevents
     * a later writer from recycling slots referenced by this generation.
     */
    synchronized BoundedReadView openBoundedReadView() {
        WriterLease reader = writerGate.acquireReader();
        try {
            StructuralSlot authority = observeAuthorityBounded();
            Map<ChunkKey, PinnedPayload> payloads = new HashMap<>();
            Set<String> paths = new HashSet<>();
            for (StreamedChunkIndex.Entry entry : authority.index.entries()) {
                ResolvedPayload resolved = resolvePayload(entry);
                payloads.put(
                        entry.key(),
                        new PinnedPayload(entry, resolved.managed));
                paths.add(resolved.managed.path.toString());
            }
            reader.pinPayloadPaths(paths);
            return new BoundedReadView(authority, Map.copyOf(payloads), reader);
        } catch (Error | RuntimeException failure) {
            reader.close();
            throw failure;
        }
    }

    private static void requireTransactionCurrent(
            StreamedPersistenceTransaction transaction) {
        boolean current = true;
        RuntimeException firstFailure = null;
        for (StreamedChunkMutation mutation : transaction.chunks()) {
            if (!(mutation instanceof StreamedChunkMutation.Upsert upsert)) {
                continue;
            }
            ExactChunkCapture capture = upsert.capture();
            try {
                current &= capture.stillCurrent().getAsBoolean();
            } catch (Error fatal) {
                throw fatal;
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (failure != firstFailure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        try {
            current &= transaction.stillCurrent().getAsBoolean();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            } else if (failure != firstFailure) {
                firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) {
            throw failure(
                    "streamed-chunk-store.freshness-check-failed",
                    "The streamed transaction freshness check failed",
                    firstFailure);
        }
        if (!current) {
            throw stale(
                    "streamed-chunk-store.capture-stale",
                    "The streamed transaction is no longer current");
        }
    }

    private static void requireDependencyCounts(
            StreamedChunkIndex currentIndex,
            StreamedChunkIndex intendedIndex,
            Map<ChunkKey, ResolvedPayload> currentPayloads,
            Map<ChunkKey, StreamedChunkPayload> intendedPayloads,
            List<StreamedChunkMutation.Remove> removals) {
        Map<SaveSectionId, Integer> counts = new HashMap<>();
        Set<SaveSectionId> tracked = new HashSet<>();
        for (StreamedGlobalExtension extension : currentIndex.globalExtensions()) {
            extension.dependency().ifPresent(dependency -> {
                counts.put(
                        dependency.chunkExtensionId(),
                        dependency.referenceCount());
                tracked.add(dependency.chunkExtensionId());
            });
        }
        for (StreamedGlobalExtension extension : intendedIndex.globalExtensions()) {
            extension.dependency().ifPresent(dependency -> {
                SaveSectionId sectionId = dependency.chunkExtensionId();
                if (!tracked.contains(sectionId)) {
                    int existing = Math.toIntExact(currentPayloads.values().stream()
                            .filter(value -> hasRequiredExtension(
                                    value.payload(), sectionId))
                            .count());
                    counts.put(sectionId, existing);
                }
                tracked.add(sectionId);
            });
        }
        Set<ChunkKey> changed = new HashSet<>(intendedPayloads.keySet());
        for (StreamedChunkMutation.Remove remove : removals) {
            changed.add(remove.key());
        }
        for (ChunkKey key : changed) {
            StreamedChunkPayload before = currentPayloads.containsKey(key)
                    ? currentPayloads.get(key).payload()
                    : null;
            StreamedChunkPayload after = intendedPayloads.get(key);
            for (SaveSectionId sectionId : tracked) {
                int delta = (hasRequiredExtension(after, sectionId) ? 1 : 0)
                        - (hasRequiredExtension(before, sectionId) ? 1 : 0);
                counts.put(sectionId, Math.addExact(
                        counts.getOrDefault(sectionId, 0), delta));
            }
        }
        Map<SaveSectionId, Integer> expected = new HashMap<>();
        for (StreamedGlobalExtension extension : intendedIndex.globalExtensions()) {
            extension.dependency().ifPresent(dependency -> {
                Integer duplicate = expected.put(
                        dependency.chunkExtensionId(),
                        dependency.referenceCount());
                if (duplicate != null) {
                    throw new IllegalArgumentException(
                            "Global extensions repeat a Chunk dependency");
                }
            });
        }
        for (SaveSectionId sectionId : tracked) {
            int actual = counts.getOrDefault(sectionId, 0);
            Integer declared = expected.get(sectionId);
            if (actual < 0 || declared == null && actual != 0
                    || declared != null && declared != actual) {
                throw new IllegalArgumentException(
                        "Global extension dependency count does not match");
            }
        }
    }

    private static boolean hasRequiredExtension(
            StreamedChunkPayload payload, SaveSectionId sectionId) {
        return payload != null && payload.extensions().stream().anyMatch(
                extension -> extension.required()
                        && extension.sectionId().equals(sectionId));
    }

    /**
     * Atomically and monotonically publishes the immutable Phase 14 migration
     * compatibility proof inside the dual Task 4 index authority.
     */
    public synchronized CommitResult publishMigrationCompatibility(
            StreamedChunkIndex.MigrationCompatibility compatibility) {
        final StreamedChunkIndex.MigrationCompatibility checked;
        try {
            checked = Objects.requireNonNull(compatibility, "compatibility");
        } catch (RuntimeException invalid) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.invalid-migration-compatibility",
                    "The migration compatibility proof is invalid",
                    invalid));
        }
        try (WriterLease ignored = writerGate.acquireWriter()) {
            Authority authority = observeAuthority();
            if (authority.snapshot.index.migrationCompatibility().isPresent()) {
                return authority.snapshot.index.migrationCompatibility()
                                .orElseThrow().equals(checked)
                        ? CommitResult.success()
                        : CommitResult.blocking(diagnostic(
                                "streamed-chunk-store.migration-compatibility-conflict",
                                "The migration compatibility proof conflicts"));
            }
            long sequence = nextBatchSequence(authority.snapshot.sequence);
            StreamedChunkIndex intended = authority.snapshot.index
                    .withMigrationCompatibility(checked);
            byte[] envelope = encodeSlot(sequence, intended);
            convergeTo(authority.snapshot);
            writeAndValidateIndex(
                    recoveryIndex, envelope, sequence, intended);
            writeAndValidateIndex(mainIndex, envelope, sequence, intended);
            return CommitResult.success();
        } catch (Error fatal) {
            throw fatal;
        } catch (StoreFailure failure) {
            return failure.blocking
                    ? CommitResult.blocking(failure.diagnostic())
                    : CommitResult.failed(failure.diagnostic());
        } catch (RuntimeException failure) {
            return CommitResult.failed(diagnostic(
                    "streamed-chunk-store.migration-publication-failed",
                    "The migration compatibility proof could not be published safely",
                    failure));
        }
    }

    /** Read-only proof that every managed entry is exactly owned by this store. */
    public synchronized ManagedTreeValidationResult validateManagedTreeForDelete() {
        try {
            saveRoot.require();
            worldDirectory.require();
            chunkDirectory.require();
            SlotObservation main = observeSlot(mainIndex);
            SlotObservation recovery = observeSlot(recoveryIndex);
            if (main.structural == null
                    || recovery.structural == null
                    || validateSlot(main.structural) == null
                    || validateSlot(recovery.structural) == null) {
                throw new IOException("Both Task 4 index slots must validate");
            }
            Authority authority = observeAuthority();
            Map<Integer, Set<Integer>> expected = new TreeMap<>();
            for (StreamedChunkIndex.Entry entry : authority.snapshot.index.entries()) {
                expected.computeIfAbsent(entry.key().x(), ignored -> new HashSet<>())
                        .add(entry.key().z());
                DirectoryIdentity shard = openExistingShard(entry.key().x());
                PayloadPair pair = existingPayloadPair(shard, entry.key());
                validateDeletePayloadSlot(pair.a, entry, authority.resolved.get(
                        entry.key()));
                validateDeletePayloadSlot(pair.b, entry, authority.resolved.get(
                        entry.key()));
            }
            try (var shards = Files.list(chunkDirectory.path)) {
                List<Path> actualShards = shards.toList();
                for (Path shardPath : actualShards) {
                    int x = parseSignedKey(shardPath.getFileName().toString());
                    DirectoryIdentity shard = openExistingShard(x);
                    try (var children = Files.list(shard.path)) {
                        Map<Integer, Set<Character>> pools = new TreeMap<>();
                        for (Path child : children.toList()) {
                            PayloadFileName name = parsePayloadFileName(
                                    child.getFileName().toString());
                            if (!pools.computeIfAbsent(
                                            name.z(), ignored -> new HashSet<>())
                                    .add(name.slot())) {
                                throw new IOException(
                                        "Task 4 payload slot name is duplicated");
                            }
                        }
                        for (Map.Entry<Integer, Set<Character>> pool
                                : pools.entrySet()) {
                            if (!pool.getValue().equals(Set.of('a', 'b'))) {
                                throw new IOException(
                                        "Task 4 fixed payload pool is incomplete");
                            }
                            ChunkKey key = new ChunkKey(x, pool.getKey());
                            if (!expected.getOrDefault(x, Set.of()).contains(key.z())) {
                                validateOrphanPayloadPair(
                                        existingPayloadPair(shard, key), key);
                            }
                        }
                    }
                }
            }
            return ManagedTreeValidationResult.success();
        } catch (IOException | RuntimeException failure) {
            return ManagedTreeValidationResult.invalid(diagnostic(
                    "streamed-chunk-store.delete-tree-invalid",
                    "The streamed Chunk tree is not safe to delete",
                    failure));
        }
    }

    private PayloadPair existingPayloadPair(
            DirectoryIdentity shard, ChunkKey key) throws IOException {
        ManagedSlot a = captureExistingManaged(
                payloadPath(shard, key, 'a'), StreamedChunkCodec.MAX_FILE_BYTES);
        ManagedSlot b = captureExistingManaged(
                payloadPath(shard, key, 'b'), StreamedChunkCodec.MAX_FILE_BYTES);
        if (a == null || b == null) {
            throw new IOException("Task 4 fixed payload pool is incomplete");
        }
        return new PayloadPair(a, b);
    }

    private void validateDeletePayloadSlot(
            ManagedSlot slot,
            StreamedChunkIndex.Entry currentEntry,
            ResolvedPayload current) throws IOException {
        byte[] bytes = readExact(slot);
        if (current != null
                && slot.path.equals(current.managed.path)
                && Arrays.equals(bytes, current.bytes)) {
            return;
        }
        if (bytes.length == 0) {
            return;
        }
        StreamedChunkPayload payload;
        try {
            payload = decodePayload(bytes);
        } catch (StoreFailure invalid) {
            throw new IOException("Unused Task 4 payload slot is invalid", invalid);
        }
        if (!payload.saveGameId().equals(saveGameId)
                || !payload.key().equals(currentEntry.key())
                || !payload.generatorVersion().equals(
                        currentEntry.generatorVersion())
                || !payload.baseHash().equals(currentEntry.baseHash())
                || payload.revision() > currentEntry.revision()) {
            throw new IOException("Unused Task 4 payload slot is not owned history");
        }
    }

    private void initializeIndexPool() throws IOException {
        initializeIndexPool(false);
    }

    private void initializeIndexPool(boolean writerHeld) throws IOException {
        ManagedSlot existingMain = captureExistingManaged(indexPath, MAX_SLOT_BYTES);
        ManagedSlot existingRecovery = captureExistingManaged(
                priorIndexPath, MAX_SLOT_BYTES);
        StructuralSlot mainValue = readStructuralForInitialization(existingMain);
        StructuralSlot recoveryValue = readStructuralForInitialization(existingRecovery);

        if (existingMain != null
                && existingRecovery != null
                && mainValue == null
                && recoveryValue == null) {
            // Existing invalid dual authority is evidence, not a fresh store.
            this.mainIndex = existingMain;
            this.recoveryIndex = existingRecovery;
            return;
        }

        if (mainValue != null
                && recoveryValue != null
                && mainValue.sequence == recoveryValue.sequence
                && !Arrays.equals(mainValue.bytes, recoveryValue.bytes)) {
            // Preserve evidence; normal observation will fail closed.
            this.mainIndex = existingMain;
            this.recoveryIndex = existingRecovery;
            return;
        }

        StructuralSlot seed = chooseInitializationSeed(mainValue, recoveryValue);
        if (seed == null) {
            StreamedChunkIndex empty = new StreamedChunkIndex(saveGameId, List.of());
            byte[] bytes = encodeSlot(0L, empty);
            seed = new StructuralSlot(0L, empty, bytes, null);
        }

        boolean noValidAuthority = mainValue == null && recoveryValue == null;
        boolean writesMain = (existingMain == null && seed.sequence == 0L)
                || (mainValue == null && (noValidAuthority || seed.sequence == 0L));
        boolean writesRecovery = (existingRecovery == null && seed.sequence == 0L)
                || (recoveryValue == null
                        && (noValidAuthority || seed.sequence == 0L));
        if ((writesMain || writesRecovery) && !writerHeld) {
            try (WriterLease ignored = writerGate.acquireWriter()) {
                initializeIndexPool(true);
            }
            return;
        }
        if (existingMain == null && seed.sequence == 0L) {
            existingMain = createManaged(indexPath, seed.bytes, MAX_SLOT_BYTES, worldDirectory);
        } else if (mainValue == null
                && (noValidAuthority || seed.sequence == 0L)) {
            rewriteDuringInitialization(existingMain, seed.bytes);
        }
        if (existingRecovery == null && seed.sequence == 0L) {
            existingRecovery = createManaged(
                    priorIndexPath, seed.bytes, MAX_SLOT_BYTES, worldDirectory);
        } else if (recoveryValue == null
                && (noValidAuthority || seed.sequence == 0L)) {
            rewriteDuringInitialization(existingRecovery, seed.bytes);
        }
        this.mainIndex = existingMain;
        this.recoveryIndex = existingRecovery;
        this.lastValidatedModifiedChunkCount = countModified(seed.index);
    }

    private StructuralSlot chooseInitializationSeed(
            StructuralSlot main, StructuralSlot recovery) {
        if (main == null) {
            return recovery;
        }
        if (recovery == null || main.sequence >= recovery.sequence) {
            return main;
        }
        return recovery;
    }

    private StructuralSlot readStructuralForInitialization(ManagedSlot slot)
            throws IOException {
        if (slot == null) {
            return null;
        }
        byte[] bytes = proveExistingManagedInitialization(slot);
        try {
            SlotValue decoded = decodeSlot(bytes);
            if (!decoded.index.saveGameId().equals(saveGameId)) {
                return null;
            }
            return new StructuralSlot(decoded.sequence, decoded.index, bytes, slot);
        } catch (StoreFailure ignored) {
            return null;
        }
    }

    private void rewriteDuringInitialization(ManagedSlot slot, byte[] bytes)
            throws IOException {
        try {
            rewriteManaged(slot, bytes, slot.maximumBytes);
            if (!Arrays.equals(readExact(slot), bytes)) {
                throw new IOException("Initialized index slot did not reread exactly");
            }
        } catch (StoreFailure failure) {
            throw new IOException("Index slot initialization failed", failure);
        }
    }

    private Authority observeAuthority() {
        SlotObservation main = observeSlot(mainIndex);
        SlotObservation recovery = observeSlot(recoveryIndex);
        if (main.interference != null || recovery.interference != null) {
            Throwable cause = main.interference != null
                    ? main.interference
                    : recovery.interference;
            throw blocking(
                    "streamed-chunk-store.managed-slot-identity-changed",
                    "A streamed Chunk managed slot changed outside the store",
                    cause);
        }
        requireCompatibleAuthority(main, recovery, false);
        if (main.structural != null
                && recovery.structural != null
                && main.structural.sequence == recovery.structural.sequence
                && Arrays.equals(main.structural.bytes, recovery.structural.bytes)) {
            ValidatedSlot identical = validateSlot(main.structural);
            if (identical == null) {
                throw blocking(
                        "streamed-chunk-store.no-valid-authority",
                        "No complete streamed Chunk authority is available");
            }
            rememberModifiedCount(identical.snapshot.index);
            return new Authority(identical.snapshot, identical.resolved);
        }

        ValidatedSlot validMain = validateSlot(main.structural);
        ValidatedSlot validRecovery = validateSlot(recovery.structural);
        if (validMain == null && validRecovery == null) {
            Throwable cause = firstCause(main.failure, recovery.failure);
            throw blocking(
                    "streamed-chunk-store.no-valid-authority",
                    "No complete streamed Chunk authority is available",
                    cause);
        }
        ValidatedSlot chosen;
        if (validRecovery == null
                || (validMain != null
                        && validMain.snapshot.sequence >= validRecovery.snapshot.sequence)) {
            chosen = validMain;
        } else {
            chosen = validRecovery;
        }
        rememberModifiedCount(chosen.snapshot.index);
        return new Authority(chosen.snapshot, chosen.resolved);
    }

    /** Selects one structurally valid immutable index root without payload scan. */
    private StructuralSlot observeStructuralAuthority() {
        SlotObservation main = observeSlot(mainIndex);
        SlotObservation recovery = observeSlot(recoveryIndex);
        if (main.interference != null || recovery.interference != null) {
            Throwable cause = main.interference != null
                    ? main.interference
                    : recovery.interference;
            throw blocking(
                    "streamed-chunk-store.managed-slot-identity-changed",
                    "A streamed Chunk managed slot changed outside the store",
                    cause);
        }
        StructuralSlot chosen = requireCompatibleAuthority(
                main, recovery, false).newest;
        rememberModifiedCount(chosen.index);
        return chosen;
    }

    private void rememberModifiedCount(StreamedChunkIndex index) {
        lastValidatedModifiedChunkCount = countModified(index);
    }

    private static int countModified(StreamedChunkIndex index) {
        return Math.toIntExact(index.entries().stream()
                .filter(StreamedChunkIndex.Entry::voxelModified)
                .count());
    }

    /** Selects and validates authority while retaining at most one payload. */
    private StructuralSlot observeAuthorityBounded() {
        SlotObservation main = observeSlot(mainIndex);
        SlotObservation recovery = observeSlot(recoveryIndex);
        if (main.interference != null || recovery.interference != null) {
            Throwable cause = main.interference != null
                    ? main.interference
                    : recovery.interference;
            throw blocking(
                    "streamed-chunk-store.managed-slot-identity-changed",
                    "A streamed Chunk managed slot changed outside the store",
                    cause);
        }
        requireCompatibleAuthority(main, recovery, false);
        if (main.structural != null
                && recovery.structural != null
                && main.structural.sequence == recovery.structural.sequence
                && Arrays.equals(main.structural.bytes, recovery.structural.bytes)) {
            if (!validateSlotBounded(main.structural)) {
                throw blocking(
                        "streamed-chunk-store.no-valid-authority",
                        "No complete streamed Chunk authority is available");
            }
            return main.structural;
        }
        boolean validMain = validateSlotBounded(main.structural);
        boolean validRecovery = validateSlotBounded(recovery.structural);
        if (!validMain && !validRecovery) {
            throw blocking(
                    "streamed-chunk-store.no-valid-authority",
                    "No complete streamed Chunk authority is available",
                    firstCause(main.failure, recovery.failure));
        }
        if (!validRecovery
                || validMain && main.structural.sequence >= recovery.structural.sequence) {
            return main.structural;
        }
        return recovery.structural;
    }

    private boolean validateSlotBounded(StructuralSlot structural) {
        if (structural == null || !structural.index.saveGameId().equals(saveGameId)) {
            return false;
        }
        try {
            for (StreamedChunkIndex.Entry entry : structural.index.entries()) {
                resolvePayload(entry);
            }
            return true;
        } catch (StoreFailure invalid) {
            if (invalid.blocking) {
                throw invalid;
            }
            return false;
        }
    }

    private void validateOrphanPayloadPair(PayloadPair pair, ChunkKey key)
            throws IOException {
        validateOrphanPayloadSlot(pair.a, key);
        validateOrphanPayloadSlot(pair.b, key);
    }

    private void validateOrphanPayloadSlot(ManagedSlot slot, ChunkKey key)
            throws IOException {
        byte[] bytes = readExact(slot);
        if (bytes.length == 0) {
            return;
        }
        StreamedChunkPayload payload;
        try {
            payload = decodePayload(bytes);
        } catch (StoreFailure invalid) {
            throw new IOException("Orphan Task 4 payload slot is invalid", invalid);
        }
        if (!payload.saveGameId().equals(saveGameId)
                || !payload.key().equals(key)) {
            throw new IOException("Orphan Task 4 payload slot is not store-owned");
        }
    }

    private void requireStructuralBaseCurrent(StructuralSlot base) {
        SlotObservation main = observeSlot(mainIndex);
        SlotObservation recovery = observeSlot(recoveryIndex);
        if (main.interference != null || recovery.interference != null) {
            throw blocking(
                    "streamed-chunk-store.managed-slot-identity-changed",
                    "A streamed Chunk managed slot changed during staging",
                    main.interference != null
                            ? main.interference
                            : recovery.interference);
        }
        if (main.structural == null || recovery.structural == null) {
            throw blocking(
                    "streamed-chunk-store.degraded-index-authority",
                    "Both fixed index slots are required during staging",
                    firstCause(main.failure, recovery.failure));
        }
        if (!Arrays.equals(main.structural.bytes, base.bytes)
                || !Arrays.equals(recovery.structural.bytes, base.bytes)) {
            throw stale(
                    "streamed-chunk-store.staging-generation-stale",
                    "The streamed staging base generation changed");
        }
    }

    private void validateStagedCandidate(
            StreamedChunkIndex current,
            StreamedChunkIndex intended,
            Map<ChunkKey, StagedWrite> stagedWrites) {
        Set<SaveSectionId> tracked = new HashSet<>();
        for (StreamedGlobalExtension extension : current.globalExtensions()) {
            extension.dependency().ifPresent(
                    dependency -> tracked.add(dependency.chunkExtensionId()));
        }
        for (StreamedGlobalExtension extension : intended.globalExtensions()) {
            extension.dependency().ifPresent(
                    dependency -> tracked.add(dependency.chunkExtensionId()));
        }
        Map<SaveSectionId, Integer> expected = new HashMap<>();
        for (StreamedGlobalExtension extension : intended.globalExtensions()) {
            extension.dependency().ifPresent(dependency -> {
                Integer duplicate = expected.put(
                        dependency.chunkExtensionId(),
                        dependency.referenceCount());
                if (duplicate != null) {
                    throw new IllegalArgumentException(
                            "Global extensions repeat a Chunk dependency");
                }
            });
        }
        Map<SaveSectionId, Integer> actual = new HashMap<>();
        for (StreamedChunkIndex.Entry entry : intended.entries()) {
            StagedWrite staged = stagedWrites.get(entry.key());
            StreamedChunkPayload payload;
            if (staged == null) {
                payload = resolvePayload(entry).payload;
            } else {
                byte[] bytes = readExact(staged.target);
                payload = decodePayload(bytes);
                if (!entry.equals(staged.entry)
                        || !entryMatches(entry, payload, bytes)
                        || !payload.saveGameId().equals(saveGameId)
                        || !payload.key().equals(entry.key())) {
                    throw failure(
                            "streamed-chunk-store.staged-payload-mismatch",
                            "A staged Chunk payload no longer matches its candidate");
                }
            }
            for (SaveSectionId sectionId : tracked) {
                if (hasRequiredExtension(payload, sectionId)) {
                    actual.merge(sectionId, 1, Math::addExact);
                }
            }
        }
        for (SaveSectionId sectionId : tracked) {
            if (!Objects.equals(
                    expected.get(sectionId),
                    actual.getOrDefault(sectionId, 0))) {
                throw new IllegalArgumentException(
                        "Global extension dependency count does not match");
            }
        }
    }

    private static void requireBooleanCurrent(BooleanSupplier supplier) {
        final boolean current;
        try {
            current = Objects.requireNonNull(supplier, "stillCurrent")
                    .getAsBoolean();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failure(
                    "streamed-chunk-store.freshness-check-failed",
                    "The streamed staging freshness check failed",
                    failure);
        }
        if (!current) {
            throw stale(
                    "streamed-chunk-store.capture-stale",
                    "The streamed staging generation is no longer current");
        }
    }

    private static CommitResult commitResult(StoreFailure failure) {
        if (failure.stale) {
            return CommitResult.stale(failure.diagnostic());
        }
        return failure.blocking
                ? CommitResult.blocking(failure.diagnostic())
                : CommitResult.failed(failure.diagnostic());
    }

    private SlotObservation observeSlot(ManagedSlot slot) {
        if (slot == null) {
            StoreFailure missing = corrupt(
                    "streamed-chunk-store.index-slot-missing",
                    "A fixed streamed Chunk index slot is missing");
            return new SlotObservation(null, missing, null);
        }
        try {
            byte[] bytes = readExact(slot);
            SlotValue decoded = decodeSlot(bytes);
            StructuralSlot structural = new StructuralSlot(
                    decoded.sequence, decoded.index, bytes, slot);
            return new SlotObservation(structural, null, null);
        } catch (IdentityInterference interference) {
            return new SlotObservation(null, interference, interference);
        } catch (StoreFailure failure) {
            return new SlotObservation(null, failure, null);
        }
    }

    private CompatibilityAuthority requireCompatibleAuthority(
            SlotObservation main,
            SlotObservation recovery,
            boolean requireCompleteTree) {
        StructuralSlot first = main.structural;
        StructuralSlot second = recovery.structural;
        if (first == null && second == null) {
            throw corrupt(
                    "streamed-chunk-store.degraded-index-authority",
                    "No fixed streamed Chunk index slot is structurally valid",
                    firstCause(main.failure, recovery.failure));
        }
        if (first == null || second == null) {
            if (requireCompleteTree) {
                throw corrupt(
                        "streamed-chunk-store.degraded-index-authority",
                        "Both fixed streamed Chunk index slots must be structurally valid",
                        firstCause(main.failure, recovery.failure));
            }
            StructuralSlot surviving = first != null ? first : second;
            if (!surviving.index.saveGameId().equals(saveGameId)) {
                throw corrupt(
                        "streamed-chunk-store.index-world-identity-mismatch",
                        "A streamed Chunk index belongs to another world");
            }
            return new CompatibilityAuthority(
                    surviving, surviving.index.migrationCompatibility());
        }
        if (!first.index.saveGameId().equals(saveGameId)
                || !second.index.saveGameId().equals(saveGameId)) {
            throw corrupt(
                    "streamed-chunk-store.index-world-identity-mismatch",
                    "A streamed Chunk index belongs to another world");
        }
        if (first.sequence == second.sequence
                && !Arrays.equals(first.bytes, second.bytes)) {
            throw blocking(
                    "streamed-chunk-store.equal-sequence-conflict",
                    "Equal streamed Chunk index sequences disagree");
        }
        StructuralSlot newest = first.sequence >= second.sequence ? first : second;
        StructuralSlot older = newest == first ? second : first;
        Optional<StreamedChunkIndex.MigrationCompatibility> newestProof =
                newest.index.migrationCompatibility();
        Optional<StreamedChunkIndex.MigrationCompatibility> olderProof =
                older.index.migrationCompatibility();
        if (olderProof.isPresent()
                && (newestProof.isEmpty()
                        || !olderProof.orElseThrow().equals(
                                newestProof.orElseThrow()))) {
            throw blocking(
                    "streamed-chunk-store.migration-proof-regressed",
                    "The streamed migration compatibility proof regressed or conflicted");
        }
        return new CompatibilityAuthority(newest, newestProof);
    }

    private ValidatedSlot validateSlot(StructuralSlot structural) {
        if (structural == null || !structural.index.saveGameId().equals(saveGameId)) {
            return null;
        }
        Map<ChunkKey, ResolvedPayload> resolved = new HashMap<>();
        try {
            for (StreamedChunkIndex.Entry entry : structural.index.entries()) {
                resolved.put(entry.key(), resolvePayload(entry));
            }
            return new ValidatedSlot(structural, Map.copyOf(resolved));
        } catch (StoreFailure invalid) {
            if (invalid.blocking) {
                throw invalid;
            }
            return null;
        }
    }

    private ResolvedPayload resolvePayload(StreamedChunkIndex.Entry entry) {
        DirectoryIdentity shard = openExistingShard(entry.key().x());
        List<ResolvedPayload> matches = new ArrayList<>(2);
        for (char slotName : new char[] {'a', 'b'}) {
            Path path = payloadPath(shard, entry.key(), slotName);
            ManagedSlot slot;
            try {
                slot = captureExistingManaged(path, StreamedChunkCodec.MAX_FILE_BYTES);
            } catch (IOException unsafe) {
                throw corrupt(
                        "streamed-chunk-store.payload-identity-invalid",
                        "A streamed Chunk payload identity is unsafe",
                        unsafe);
            }
            if (slot == null) {
                continue;
            }
            try {
                byte[] bytes = readExact(slot);
                StreamedChunkPayload payload = decodePayload(bytes);
                if (entryMatches(entry, payload, bytes)
                        && payload.saveGameId().equals(saveGameId)
                        && payload.key().equals(entry.key())) {
                    matches.add(new ResolvedPayload(slotName, slot, bytes, payload));
                }
            } catch (StoreFailure ignored) {
                // A nonmatching fixed slot is expected: it may hold an older revision.
            }
        }
        if (matches.isEmpty()) {
            throw corrupt(
                    "streamed-chunk-store.index-payload-mismatch",
                    "The streamed Chunk index and payload do not match");
        }
        return matches.get(0);
    }

    private void convergeTo(StructuralSlot chosen) {
        convergeOne(mainIndex, chosen);
        convergeOne(recoveryIndex, chosen);
    }

    private void convergeOne(
            ManagedSlot destination,
            StructuralSlot chosen) {
        if (destination.path.equals(chosen.managed.path)) {
            return;
        }
        SlotObservation current = observeSlot(destination);
        if (current.interference != null) {
            throw blocking(
                    "streamed-chunk-store.managed-slot-identity-changed",
                    "A streamed Chunk managed slot changed outside the store",
                    current.interference);
        }
        if (current.structural != null
                && Arrays.equals(current.structural.bytes, chosen.bytes)) {
            return;
        }
        writeAndValidateIndex(
                destination,
                chosen.bytes,
                chosen.sequence,
                chosen.index);
    }

    private PayloadPair ensurePayloadPool(ChunkKey key) {
        DirectoryIdentity shard = openOrCreateShard(key.x());
        Path aPath = payloadPath(shard, key, 'a');
        Path bPath = payloadPath(shard, key, 'b');
        ManagedSlot a;
        ManagedSlot b;
        try {
            // Capture every pre-existing member before creating or rewriting either.
            a = captureExistingManaged(aPath, StreamedChunkCodec.MAX_FILE_BYTES);
            b = captureExistingManaged(bPath, StreamedChunkCodec.MAX_FILE_BYTES);
            boolean aExisted = a != null;
            boolean bExisted = b != null;
            if (a == null) {
                a = createManaged(
                        aPath,
                        new byte[0],
                        StreamedChunkCodec.MAX_FILE_BYTES,
                        shard,
                        false);
            }
            if (b == null) {
                b = createManaged(
                        bPath,
                        new byte[0],
                        StreamedChunkCodec.MAX_FILE_BYTES,
                        shard,
                        false);
            }
            if (!initializedPayloadPools.contains(key)) {
                if (aExisted) {
                    proveExistingManagedInitialization(a);
                }
                if (bExisted) {
                    proveExistingManagedInitialization(b);
                }
                files.forceDirectoryDurably(shard.path, shard::require);
                initializedPayloadPools.add(key);
            }
            payloadSlots.put(a.path, a);
            payloadSlots.put(b.path, b);
            return new PayloadPair(a, b);
        } catch (IOException unsafe) {
            throw failure(
                    "streamed-chunk-store.payload-pool-initialization-failed",
                    "The streamed Chunk payload pool could not be initialized",
                    unsafe);
        }
    }

    private DirectoryIdentity openOrCreateShard(int x) {
        DirectoryIdentity cached = shardIdentities.get(x);
        if (cached != null) {
            try {
                cached.require();
                return cached;
            } catch (IOException unsafe) {
                throw failure(
                        "streamed-chunk-store.shard-unavailable",
                        "The streamed Chunk shard is unavailable",
                        unsafe);
            }
        }
        Path path = directChild(chunkDirectory.path, signedKey(x));
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                files.createDirectory(path, chunkDirectory::require);
            }
            DirectoryIdentity identity = captureDirectory(path, chunkDirectory);
            files.forceDirectoryDurably(chunkDirectory.path, identity::require);
            shardIdentities.put(x, identity);
            return identity;
        } catch (IOException unsafe) {
            throw failure(
                    "streamed-chunk-store.shard-unavailable",
                    "The streamed Chunk shard is unavailable",
                    unsafe);
        }
    }

    private DirectoryIdentity openExistingShard(int x) {
        DirectoryIdentity cached = shardIdentities.get(x);
        if (cached != null) {
            try {
                cached.require();
                return cached;
            } catch (IOException unsafe) {
                throw corrupt(
                        "streamed-chunk-store.shard-unavailable",
                        "The streamed Chunk shard is unavailable",
                        unsafe);
            }
        }
        Path path = directChild(chunkDirectory.path, signedKey(x));
        try {
            DirectoryIdentity identity = captureDirectory(path, chunkDirectory);
            shardIdentities.put(x, identity);
            return identity;
        } catch (IOException unsafe) {
            throw corrupt(
                    "streamed-chunk-store.shard-unavailable",
                    "The streamed Chunk shard is unavailable",
                    unsafe);
        }
    }

    private void writeAndValidatePayload(
            ManagedSlot slot,
            byte[] bytes,
            StreamedChunkIndex.Entry expected) {
        writerGate.requireWritable(slot.path.toString());
        rewriteManaged(
                slot,
                bytes,
                StreamedChunkCodec.MAX_FILE_BYTES);
        byte[] reread = readExact(slot);
        StreamedChunkPayload decoded = decodePayload(reread);
        if (!Arrays.equals(bytes, reread) || !entryMatches(expected, decoded, reread)) {
            throw failure(
                    "streamed-chunk-store.chunk-validation-failed",
                    "The streamed Chunk payload failed exact validation");
        }
    }

    private void writeAndValidateIndex(
            ManagedSlot slot,
            byte[] bytes,
            long expectedSequence,
            StreamedChunkIndex expectedIndex) {
        rewriteManaged(slot, bytes, MAX_SLOT_BYTES);
        byte[] reread = readExact(slot);
        SlotValue decoded = decodeSlot(reread);
        if (!Arrays.equals(bytes, reread)
                || decoded.sequence != expectedSequence
                || !Arrays.equals(
                        indexCodec.encode(decoded.index),
                        indexCodec.encode(expectedIndex))) {
            throw failure(
                    "streamed-chunk-store.index-write-failed",
                    "The streamed Chunk index failed exact validation");
        }
    }

    private void rewriteManaged(
            ManagedSlot slot,
            byte[] bytes,
            long maximumBytes) {
        try {
            slot.requireExact();
            files.writeExistingBounded(slot.path, bytes, maximumBytes, () -> {
                slot.requireExact();
            });
            slot.refreshSameObject();
            files.forceFile(slot.path, () -> {
                slot.requireExact();
            });
            slot.requireExact();
        } catch (Error fatal) {
            slot.refreshAfterUncertainWrite(fatal);
            throw fatal;
        } catch (StoreFailure closed) {
            slot.refreshAfterUncertainWrite(closed);
            throw closed;
        } catch (IOException | RuntimeException failure) {
            slot.refreshAfterUncertainWrite(failure);
            throw failure(
                    slot.index ? "streamed-chunk-store.index-write-failed"
                            : "streamed-chunk-store.chunk-write-failed",
                    slot.index
                            ? "The streamed Chunk index could not be written safely"
                            : "The streamed Chunk payload could not be written safely",
                    failure);
        }
    }

    private byte[] readExact(ManagedSlot slot) {
        try {
            slot.requireExact();
            byte[] bytes = files.readBounded(
                    slot.path, slot.maximumBytes, slot::requireExact);
            slot.requireExact();
            return bytes;
        } catch (IdentityInterference interference) {
            throw interference;
        } catch (IOException | RuntimeException failure) {
            throw corrupt(
                    slot.index
                            ? "streamed-chunk-store.index-read-failed"
                            : "streamed-chunk-store.payload-read-failed",
                    slot.index
                            ? "The streamed Chunk index could not be read safely"
                            : "The streamed Chunk payload could not be read safely",
                    failure);
        }
    }

    private ManagedSlot createManaged(
            Path path, byte[] bytes, long maximumBytes, DirectoryIdentity parent)
            throws IOException {
        return createManaged(path, bytes, maximumBytes, parent, true);
    }

    private byte[] proveExistingManagedInitialization(ManagedSlot slot)
            throws IOException {
        slot.requireExact();
        files.forceFile(slot.path, slot::requireExact);
        byte[] bytes = files.readBounded(
                slot.path, slot.maximumBytes, slot::requireExact);
        slot.requireExact();
        files.forceDirectoryDurably(slot.parent.path, slot::requireExact);
        return bytes;
    }

    private ManagedSlot createManaged(
            Path path,
            byte[] bytes,
            long maximumBytes,
            DirectoryIdentity parent,
            boolean forceParentNow) throws IOException {
        files.createBounded(path, bytes, maximumBytes, parent::require);
        ManagedSlot created = null;
        try {
            created = captureManaged(path, maximumBytes, parent);
            files.forceFile(path, created::requireExact);
            if (!Arrays.equals(readExact(created), bytes)) {
                throw new IOException("Created managed slot did not reread exactly");
            }
            if (forceParentNow) {
                files.forceDirectoryDurably(parent.path, parent::require);
            }
            return created;
        } catch (RuntimeException failure) {
            throw new IOException("Managed slot initialization failed", failure);
        }
    }

    private ManagedSlot captureExistingManaged(Path path, long maximumBytes)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            payloadSlots.remove(normalized);
            return null;
        }
        ManagedSlot cached = payloadSlots.get(normalized);
        if (cached != null) {
            cached.requireExact();
            return cached;
        }
        DirectoryIdentity parent = parentIdentity(normalized);
        ManagedSlot captured = captureManaged(normalized, maximumBytes, parent);
        if (!normalized.equals(indexPath) && !normalized.equals(priorIndexPath)) {
            payloadSlots.put(normalized, captured);
        }
        return captured;
    }

    private ManagedSlot captureManaged(
            Path path, long maximumBytes, DirectoryIdentity parent) throws IOException {
        parent.require();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed slot is not a regular file");
        }
        Object identity = files.readManagedFileIdentity(path, maximumBytes, parent::require);
        if (!(identity instanceof SaveFileOperations.ManagedFileIdentity managed)) {
            throw new IOException("Managed slot identity is unavailable");
        }
        return new ManagedSlot(
                path.toAbsolutePath().normalize(),
                parent,
                maximumBytes,
                path.equals(indexPath) || path.equals(priorIndexPath),
                managed);
    }

    private DirectoryIdentity parentIdentity(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent.equals(worldDirectory.path)) {
            return worldDirectory;
        }
        if (parent.equals(chunkDirectory.path)) {
            return chunkDirectory;
        }
        if (parent.getParent() != null && parent.getParent().equals(chunkDirectory.path)) {
            int x = parseSignedKey(parent.getFileName().toString());
            return openExistingShard(x);
        }
        throw new IOException("Managed slot parent is outside the store");
    }

    private DirectoryIdentity openOrCreateDirectory(
            Path path, DirectoryIdentity parent) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            files.createDirectory(path, parent::require);
        }
        DirectoryIdentity identity = captureDirectory(path, parent);
        files.forceDirectoryDurably(parent.path, identity::require);
        return identity;
    }

    private DirectoryIdentity captureDirectory(
            Path path, DirectoryIdentity parent) throws IOException {
        Path lexical = path.toAbsolutePath().normalize();
        if (parent != null) {
            parent.require();
            if (!lexical.getParent().equals(parent.path)) {
                throw new IOException("Directory is not a direct child");
            }
        }
        requireDirectoryShape(lexical);
        lexical.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Object providerIdentity = files.readDirectoryKey(
                lexical,
                parent == null ? () -> requireDirectoryShape(lexical) : parent::require);
        if (providerIdentity == null) {
            throw new IOException("Directory provider identity is unavailable");
        }
        DirectoryIdentity identity = new DirectoryIdentity(
                lexical, parent, providerIdentity);
        identity.require();
        return identity;
    }

    private SequencePlan sequencePlan(long current, char referenced) {
        if (current == Long.MAX_VALUE) {
            throw failure(
                    "streamed-chunk-store.sequence-exhausted",
                    "The streamed Chunk index sequence is exhausted");
        }
        long next = current + 1L;
        char slot = slotFor(next);
        if (referenced != '\0' && slot == referenced) {
            if (next == Long.MAX_VALUE) {
                throw failure(
                        "streamed-chunk-store.sequence-exhausted",
                        "The streamed Chunk index sequence is exhausted");
            }
            next++;
            slot = slotFor(next);
        }
        return new SequencePlan(next, slot);
    }

    private static long nextBatchSequence(long current) {
        if (current == Long.MAX_VALUE) {
            throw failure(
                    "streamed-chunk-store.sequence-exhausted",
                    "The streamed Chunk index sequence is exhausted");
        }
        return current + 1L;
    }

    private static char slotFor(long sequence) {
        return (sequence & 1L) == 1L ? 'a' : 'b';
    }

    private byte[] encodeSlot(long sequence, StreamedChunkIndex index) {
        if (sequence < 0L
                || sequence == 0L
                        && (!index.entries().isEmpty()
                                || !index.globalExtensions().isEmpty())) {
            throw new IllegalArgumentException("Invalid streamed Chunk index sequence");
        }
        try {
            byte[] indexBytes = indexCodec.encode(index);
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(SLOT_MAGIC);
                output.writeInt(SLOT_VERSION);
                output.writeLong(sequence);
                output.writeInt(indexBytes.length);
                output.write(indexBytes);
            }
            byte[] body = bodyBytes.toByteArray();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(
                    body.length + SLOT_HASH_BYTES);
            encoded.write(body);
            encoded.write(sha256(body));
            return encoded.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory index envelope failed", impossible);
        }
    }

    private SlotValue decodeSlot(byte[] bytes) {
        try {
            if (bytes.length < SLOT_HEADER_BYTES + SLOT_HASH_BYTES
                    || bytes.length > MAX_SLOT_BYTES) {
                throw new IOException("Index slot length is invalid");
            }
            int bodyLength = bytes.length - SLOT_HASH_BYTES;
            byte[] body = Arrays.copyOf(bytes, bodyLength);
            byte[] expectedHash = Arrays.copyOfRange(bytes, bodyLength, bytes.length);
            if (!MessageDigest.isEqual(expectedHash, sha256(body))) {
                throw new IOException("Index slot hash is invalid");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(body))) {
                if (input.readInt() != SLOT_MAGIC || input.readInt() != SLOT_VERSION) {
                    throw new IOException("Index slot header is invalid");
                }
                long sequence = input.readLong();
                if (sequence < 0L) {
                    throw new IOException("Index slot sequence is invalid");
                }
                int length = input.readInt();
                if (length <= 0 || length > StreamedChunkIndexCodec.MAX_FILE_BYTES) {
                    throw new IOException("Index slot payload length is invalid");
                }
                byte[] indexBytes = new byte[length];
                input.readFully(indexBytes);
                if (input.read() != -1) {
                    throw new IOException("Index slot contains trailing bytes");
                }
                StreamedChunkIndex index = indexCodec.decode(indexBytes);
                if (sequence == 0L
                        && (!index.entries().isEmpty()
                                || !index.globalExtensions().isEmpty())) {
                    throw new IOException("Empty authority contains entries");
                }
                if (!Arrays.equals(bytes, encodeSlot(sequence, index))) {
                    throw new IOException("Index slot is not canonical");
                }
                return new SlotValue(sequence, index);
            }
        } catch (IOException | RuntimeException invalid) {
            throw corrupt(
                    "streamed-chunk-store.index-validation-failed",
                    "The streamed Chunk index failed validation",
                    invalid);
        }
    }

    private StreamedChunkPayload decodePayload(byte[] bytes) {
        StreamedChunkCodec.DecodeResult decoded = payloadCodec.decode(bytes);
        if (decoded.status() != StreamedChunkCodec.DecodeResult.Status.VALID) {
            Throwable cause = decoded.diagnostics().isEmpty()
                    ? new IllegalStateException("Payload decode failed")
                    : decoded.diagnostics().get(0).cause().orElseGet(
                            () -> new IllegalStateException("Payload decode failed"));
            throw corrupt(
                    "streamed-chunk-store.payload-validation-failed",
                    "The streamed Chunk payload failed validation",
                    cause);
        }
        return decoded.payload().orElseThrow();
    }

    private static boolean entryMatches(
            StreamedChunkIndex.Entry entry,
            StreamedChunkPayload payload,
            byte[] bytes) {
        return entry.key().equals(payload.key())
                && entry.generatorVersion().equals(payload.generatorVersion())
                && entry.baseHash().equals(payload.baseHash())
                && entry.revision() == payload.revision()
                && entry.payloadSize() == bytes.length
                && entry.payloadHash().equals(StreamedChunkCodec.sha256Hex(bytes))
                && entry.persistenceRequired() == payload.persistenceRequired()
                && entry.voxelModified() == payload.voxelModified();
    }

    private void validateCaptureIdentity(StreamedChunkPayload payload) {
        if (!payload.saveGameId().equals(saveGameId)
                || !payload.persistenceRequired()) {
            throw new IllegalArgumentException(
                    "Capture world identity or persistence state is invalid");
        }
        ChunkCoordinatePolicy.requireSafe(payload.key());
    }

    private static void requireCurrent(
            ExactChunkCapture capture,
            WorldItemHibernatePayload hibernation) {
        final boolean chunkCurrent;
        final boolean itemsCurrent;
        try {
            chunkCurrent = capture.stillCurrent().getAsBoolean();
            itemsCurrent = hibernation.stillCurrent().getAsBoolean();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException callbackFailure) {
            throw failure(
                    "streamed-chunk-store.freshness-check-failed",
                    "The streamed Chunk freshness check failed",
                    callbackFailure);
        }
        if (!chunkCurrent || !itemsCurrent) {
            throw stale(
                    "streamed-chunk-store.capture-stale",
                    "The exact streamed Chunk capture is no longer current");
        }
    }

    private static void requireBatchCurrent(
            List<ExactChunkCapture> captures,
            WorldItemHibernatePayload hibernation) {
        try {
            for (ExactChunkCapture capture : captures) {
                if (!capture.stillCurrent().getAsBoolean()) {
                    throw stale(
                            "streamed-chunk-store.capture-stale",
                            "The exact streamed Chunk batch is no longer current");
                }
            }
            if (!hibernation.stillCurrent().getAsBoolean()) {
                throw stale(
                        "streamed-chunk-store.capture-stale",
                        "The exact streamed Chunk batch is no longer current");
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (StoreFailure closed) {
            throw closed;
        } catch (RuntimeException callbackFailure) {
            throw failure(
                    "streamed-chunk-store.freshness-check-failed",
                    "The streamed Chunk batch freshness check failed",
                    callbackFailure);
        }
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    private static Throwable firstCause(Throwable first, Throwable second) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return new IllegalStateException("No valid streamed Chunk authority");
    }

    private static Path directChild(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!child.getParent().equals(parent) || !child.getFileName().toString().equals(name)) {
            throw new IllegalArgumentException("Path is not a canonical direct child");
        }
        return child;
    }

    private static Path payloadPath(
            DirectoryIdentity shard, ChunkKey key, char slot) {
        return directChild(
                shard.path,
                signedKey(key.z()) + "." + slot + CHUNK_SUFFIX);
    }

    private static String signedKey(int coordinate) {
        return (coordinate < 0 ? "n" : "p")
                + String.format(Locale.ROOT, "%08x", Math.abs((long) coordinate));
    }

    private static int parseSignedKey(String encoded) throws IOException {
        if (!encoded.matches("[np][0-9a-f]{8}")) {
            throw new IOException("Shard name is not canonical");
        }
        long magnitude = Long.parseLong(encoded.substring(1), 16);
        long signed = encoded.charAt(0) == 'n' ? -magnitude : magnitude;
        if (signed < Integer.MIN_VALUE || signed > Integer.MAX_VALUE
                || !signedKey((int) signed).equals(encoded)) {
            throw new IOException("Shard coordinate is not canonical");
        }
        return (int) signed;
    }

    private static PayloadFileName parsePayloadFileName(String encoded)
            throws IOException {
        if (encoded.length() != 11 + CHUNK_SUFFIX.length()
                || encoded.charAt(9) != '.'
                || (encoded.charAt(10) != 'a' && encoded.charAt(10) != 'b')
                || !encoded.endsWith(CHUNK_SUFFIX)) {
            throw new IOException("Payload slot name is not canonical");
        }
        int z = parseSignedKey(encoded.substring(0, 9));
        char slot = encoded.charAt(10);
        if (!encoded.equals(signedKey(z) + "." + slot + CHUNK_SUFFIX)) {
            throw new IOException("Payload slot name is not canonical");
        }
        return new PayloadFileName(z, slot);
    }

    private record PayloadFileName(int z, char slot) {}

    private static void requireDirectoryShape(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Directory path is unavailable or linked");
        }
    }

    private static SaveDiagnostic diagnostic(String code, String message) {
        return SaveDiagnostic.of(
                code, message, new StoreValidationFailure(message));
    }

    private static SaveDiagnostic diagnostic(
            String code, String message, Throwable cause) {
        return SaveDiagnostic.of(code, message, cause);
    }

    private static StoreFailure failure(String code, String message) {
        return failure(code, message, new StoreValidationFailure(message));
    }

    private static StoreFailure failure(
            String code, String message, Throwable cause) {
        return new StoreFailure(code, message, cause, false, false, false);
    }

    private static StoreFailure corrupt(String code, String message) {
        return corrupt(code, message, new StoreValidationFailure(message));
    }

    private static StoreFailure corrupt(
            String code, String message, Throwable cause) {
        return new StoreFailure(code, message, cause, false, false, false);
    }

    private static StoreFailure blocking(String code, String message) {
        return blocking(code, message, new StoreValidationFailure(message));
    }

    private static StoreFailure blocking(
            String code, String message, Throwable cause) {
        return new StoreFailure(code, message, cause, false, true, false);
    }

    private static StoreFailure stale(String code, String message) {
        return new StoreFailure(
                code,
                message,
                new StoreValidationFailure(message),
                true,
                false,
                false);
    }

    private final class DirectoryIdentity {
        private final Path path;
        private final DirectoryIdentity parent;
        private final Object providerIdentity;

        private DirectoryIdentity(
                Path path,
                DirectoryIdentity parent,
                Object providerIdentity) {
            this.path = path;
            this.parent = parent;
            this.providerIdentity = providerIdentity;
        }

        private void require() throws IOException {
            List<DirectoryIdentity> chain = new ArrayList<>();
            for (DirectoryIdentity current = this;
                    current != null;
                    current = current.parent) {
                chain.add(0, current);
            }
            for (DirectoryIdentity identity : chain) {
                identity.requireShapeOnly();
            }
            for (DirectoryIdentity identity : chain) {
                Object current = files.readDirectoryKey(
                        identity.path, this::requireShapeChain);
                if (current == null
                        || !identity.providerIdentity.equals(current)) {
                    throw new IOException("Directory identity changed");
                }
            }
        }

        private void requireShapeChain() throws IOException {
            for (DirectoryIdentity current = this;
                    current != null;
                    current = current.parent) {
                current.requireShapeOnly();
            }
        }

        private void requireShapeOnly() throws IOException {
            requireDirectoryShape(path);
        }
    }

    private final class ManagedSlot {
        private final Path path;
        private final DirectoryIdentity parent;
        private final long maximumBytes;
        private final boolean index;
        private SaveFileOperations.ManagedFileIdentity identity;

        private ManagedSlot(
                Path path,
                DirectoryIdentity parent,
                long maximumBytes,
                boolean index,
                SaveFileOperations.ManagedFileIdentity identity) {
            this.path = path;
            this.parent = parent;
            this.maximumBytes = maximumBytes;
            this.index = index;
            this.identity = identity;
        }

        private void requireExact() throws IOException {
            parent.require();
            SaveFileOperations.ManagedFileIdentity current = currentIdentity();
            if (!identity.equals(current)) {
                throw new IdentityInterference("Managed slot identity changed");
            }
        }

        private void refreshSameObject() throws IOException {
            parent.require();
            SaveFileOperations.ManagedFileIdentity refreshed = currentIdentity();
            if (!identity.providerIdentity().equals(refreshed.providerIdentity())) {
                throw new IdentityInterference("Managed slot object changed during write");
            }
            identity = refreshed;
        }

        private void refreshAfterUncertainWrite(Throwable primary) {
            try {
                parent.require();
                SaveFileOperations.ManagedFileIdentity refreshed = currentIdentity();
                if (!identity.providerIdentity().equals(refreshed.providerIdentity())) {
                    throw new IdentityInterference(
                            "Managed slot object changed during uncertain write");
                }
                identity = refreshed;
            } catch (Throwable refreshFailure) {
                if (refreshFailure != primary) {
                    primary.addSuppressed(refreshFailure);
                }
            }
        }

        private SaveFileOperations.ManagedFileIdentity currentIdentity()
                throws IOException {
            Object current = files.readManagedFileIdentity(
                    path, maximumBytes, parent::require);
            if (!(current instanceof SaveFileOperations.ManagedFileIdentity managed)) {
                throw new IOException("Managed slot identity is unavailable");
            }
            return managed;
        }
    }

    private record SlotValue(long sequence, StreamedChunkIndex index) {}

    private record StructuralSlot(
            long sequence,
            StreamedChunkIndex index,
            byte[] bytes,
            ManagedSlot managed) {
        private StructuralSlot {
            bytes = bytes.clone();
        }
    }

    private record SlotObservation(
            StructuralSlot structural,
            Throwable failure,
            IdentityInterference interference) {}

    private record CompatibilityAuthority(
            StructuralSlot newest,
            Optional<StreamedChunkIndex.MigrationCompatibility> proof) {}

    private record ValidatedSlot(
            StructuralSlot snapshot,
            Map<ChunkKey, ResolvedPayload> resolved) {}

    private record Authority(
            StructuralSlot snapshot,
            Map<ChunkKey, ResolvedPayload> resolved) {}

    private record ResolvedPayload(
            char slot,
            ManagedSlot managed,
            byte[] bytes,
            StreamedChunkPayload payload) {
        private ResolvedPayload {
            bytes = bytes.clone();
        }
    }

    private record PayloadPair(ManagedSlot a, ManagedSlot b) {}

    private record BatchWrite(
            ExactChunkCapture capture,
            byte[] payloadBytes,
            StreamedChunkIndex.Entry entry,
            char previousSlot) {
        private BatchWrite {
            payloadBytes = payloadBytes.clone();
        }
    }

    private record PreparedStage(
            ChunkKey key,
            boolean remove,
            BooleanSupplier stillCurrent,
            byte[] payloadBytes,
            StreamedChunkIndex.Entry entry,
            char targetSlot) {
        private static PreparedStage remove(ChunkKey key) {
            return new PreparedStage(
                    key, true, () -> true, new byte[0], null, '\0');
        }

        private static PreparedStage upsert(
                BooleanSupplier stillCurrent,
                byte[] payloadBytes,
                StreamedChunkIndex.Entry entry,
                char targetSlot) {
            return new PreparedStage(
                    entry.key(), false, stillCurrent, payloadBytes, entry, targetSlot);
        }
    }

    private record StagedWrite(
            StreamedChunkIndex.Entry entry,
            ManagedSlot target,
            char targetSlot,
            BooleanSupplier stillCurrent) {}

    private record PinnedPayload(
            StreamedChunkIndex.Entry entry, ManagedSlot managed) {}

    public record StagingMetrics(
            int stagedMutations,
            int maximumBatchMutations,
            long maximumBatchBytes,
            int maximumBatchPhysicalBlobs,
            long maximumTransientPayloadBytesUpperBound) {}

    private enum StagingState {
        OPEN,
        CANCELED,
        STALE,
        FAILED,
        PUBLISHED
    }

    static final class PinnedReadView implements AutoCloseable {
        private final long sequence;
        private final StreamedChunkIndex index;
        private final Map<ChunkKey, StreamedChunkPayload> payloads;
        private boolean closed;

        private PinnedReadView(
                long sequence,
                StreamedChunkIndex index,
                Map<ChunkKey, StreamedChunkPayload> payloads) {
            this.sequence = sequence;
            this.index = index;
            this.payloads = Map.copyOf(payloads);
        }

        long sequence() {
            requireOpen();
            return sequence;
        }

        StreamedChunkIndex index() {
            requireOpen();
            return index;
        }

        StreamedChunkPayload payload(ChunkKey key) {
            requireOpen();
            return payloads.get(ChunkCoordinatePolicy.requireSafe(key));
        }

        @Override
        public void close() {
            closed = true;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("The WorldItem page read view is closed");
            }
        }
    }

    final class BoundedReadView implements AutoCloseable {
        private final StructuralSlot authority;
        private final Map<ChunkKey, PinnedPayload> payloads;
        private final WriterLease reader;
        private boolean closed;

        private BoundedReadView(
                StructuralSlot authority,
                Map<ChunkKey, PinnedPayload> payloads,
                WriterLease reader) {
            this.authority = authority;
            this.payloads = payloads;
            this.reader = reader;
        }

        long sequence() {
            synchronized (StreamedChunkStore.this) {
                requireOpen();
                return authority.sequence;
            }
        }

        StreamedChunkIndex index() {
            synchronized (StreamedChunkStore.this) {
                requireOpen();
                return authority.index;
            }
        }

        StreamedChunkPayload payload(ChunkKey key) {
            synchronized (StreamedChunkStore.this) {
                requireOpen();
                PinnedPayload pinned = payloads.get(
                        ChunkCoordinatePolicy.requireSafe(key));
                if (pinned == null) {
                    return null;
                }
                byte[] bytes = readExact(pinned.managed);
                StreamedChunkPayload payload = decodePayload(bytes);
                if (!entryMatches(pinned.entry, payload, bytes)) {
                    throw corrupt(
                            "streamed-chunk-store.pinned-payload-changed",
                            "A pinned streamed payload generation changed");
                }
                return payload;
            }
        }

        int residentPayloadCount() {
            synchronized (StreamedChunkStore.this) {
                requireOpen();
                return 0;
            }
        }

        @Override
        public void close() {
            synchronized (StreamedChunkStore.this) {
                if (!closed) {
                    closed = true;
                    reader.close();
                }
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "The bounded streamed read view is closed");
            }
        }
    }

    private record WriterGateKey(
            SaveGameId saveGameId, String worldPath, Object providerIdentity) {}

    private static final class GateReference
            extends WeakReference<SharedWriterGate> {
        private final WriterGateKey key;

        private GateReference(
                WriterGateKey key,
                SharedWriterGate gate,
                ReferenceQueue<SharedWriterGate> queue) {
            super(gate, queue);
            this.key = key;
        }
    }

    private static final class SharedWriterGate {
        private boolean writer;
        private int openingReaders;
        private final Map<String, Integer> pinnedPayloadPaths = new HashMap<>();

        private synchronized WriterLease acquireWriter() {
            if (writer || openingReaders != 0) {
                throw blocking(
                        "streamed-chunk-store.writer-capability-busy",
                        "Another streamed authority operation owns the writer capability");
            }
            writer = true;
            return new WriterLease(this, true);
        }

        private synchronized WriterLease acquireReader() {
            openingReaders = Math.addExact(openingReaders, 1);
            return new WriterLease(this, false);
        }

        private synchronized void activateReader(Set<String> paths) {
            if (openingReaders <= 0) {
                throw new IllegalStateException(
                        "The streamed reader capability is not opening");
            }
            for (String path : paths) {
                pinnedPayloadPaths.merge(path, 1, Math::addExact);
            }
            openingReaders--;
        }

        private synchronized void requireWritable(String path) {
            if (pinnedPayloadPaths.containsKey(path)) {
                throw blocking(
                        "streamed-chunk-store.payload-generation-pinned",
                        "A streamed payload slot belongs to an open read generation");
            }
        }

        private synchronized void release(
                boolean writerLease,
                boolean readerActivated,
                Set<String> pinnedPaths) {
            if (writerLease) {
                if (!writer) {
                    throw new IllegalStateException(
                            "The streamed writer capability is not held");
                }
                writer = false;
            } else if (!readerActivated) {
                if (openingReaders <= 0) {
                    throw new IllegalStateException(
                            "The streamed reader capability is not opening");
                }
                openingReaders--;
            } else {
                for (String path : pinnedPaths) {
                    Integer count = pinnedPayloadPaths.get(path);
                    if (count == null || count <= 0) {
                        throw new IllegalStateException(
                                "The streamed payload slot is not pinned");
                    }
                    if (count == 1) {
                        pinnedPayloadPaths.remove(path);
                    } else {
                        pinnedPayloadPaths.put(path, count - 1);
                    }
                }
            }
        }
    }

    private static final class WriterLease implements AutoCloseable {
        private final SharedWriterGate gate;
        private final boolean writer;
        private Set<String> pinnedPaths = Set.of();
        private boolean readerActivated;
        private boolean closed;

        private WriterLease(SharedWriterGate gate, boolean writer) {
            this.gate = gate;
            this.writer = writer;
        }

        private synchronized void pinPayloadPaths(Set<String> paths) {
            if (writer || closed || readerActivated) {
                throw new IllegalStateException(
                        "The streamed reader capability cannot pin payload slots");
            }
            Set<String> checked = Set.copyOf(paths);
            gate.activateReader(checked);
            pinnedPaths = checked;
            readerActivated = true;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                gate.release(writer, readerActivated, pinnedPaths);
            }
        }
    }

    private record SequencePlan(long value, char slot) {}

    private static final class IdentityInterference extends RuntimeException {
        private IdentityInterference(String message) {
            super(message, null, false, false);
        }
    }

    private static final class StoreValidationFailure extends RuntimeException {
        private StoreValidationFailure(String message) {
            super(message, null, false, false);
        }
    }

    private static final class StoreFailure extends RuntimeException {
        private final String code;
        private final String boundedMessage;
        private final Throwable primary;
        private final boolean stale;
        private final boolean blocking;
        private final boolean identityMismatch;

        private StoreFailure(
                String code,
                String boundedMessage,
                Throwable primary,
                boolean stale,
                boolean blocking,
                boolean identityMismatch) {
            super(boundedMessage, primary, false, false);
            this.code = Objects.requireNonNull(code, "code");
            this.boundedMessage = Objects.requireNonNull(boundedMessage, "boundedMessage");
            this.primary = Objects.requireNonNull(primary, "primary");
            this.stale = stale;
            this.blocking = blocking;
            this.identityMismatch = identityMismatch;
        }

        private SaveDiagnostic diagnostic() {
            return SaveDiagnostic.of(code, boundedMessage, primary);
        }
    }

    public record ExpectedBase(String generatorVersion, String baseHash) {
        public ExpectedBase {
            generatorVersion = StreamedChunkPayload.requireBoundedText(
                    generatorVersion,
                    "generatorVersion",
                    StreamedChunkPayload.MAX_GENERATOR_VERSION_BYTES);
            baseHash = StreamedChunkPayload.requireHash(baseHash, "baseHash");
        }
    }

    public record ExactChunkCapture(
            StreamedChunkPayload payload, BooleanSupplier stillCurrent) {
        public ExactChunkCapture {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(stillCurrent, "stillCurrent");
        }
    }

    @FunctionalInterface
    interface ChunkCandidateEncoder {
        byte[] encode(StreamedChunkPayload payload);
    }

    public static final class WorldItemHibernatePayload {
        private final byte[] bytes;
        private final BooleanSupplier stillCurrent;

        public WorldItemHibernatePayload(
                byte[] bytes, BooleanSupplier stillCurrent) {
            byte[] checked = Objects.requireNonNull(bytes, "bytes");
            if (checked.length > MAX_HIBERNATION_BYTES) {
                throw new IllegalArgumentException(
                        "WorldItem hibernation payload exceeds its bound");
            }
            this.bytes = checked.clone();
            this.stillCurrent = Objects.requireNonNull(stillCurrent, "stillCurrent");
        }

        public byte[] copyBytes() {
            return bytes.clone();
        }

        public BooleanSupplier stillCurrent() {
            return stillCurrent;
        }
    }

    public static final class CommitResult {
        public enum Status {
            SUCCESS,
            STALE,
            FAILED,
            BLOCKING_FAILURE
        }

        private final Status status;
        private final List<SaveDiagnostic> diagnostics;

        private CommitResult(Status status, List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics"));
            if ((status == Status.SUCCESS) != this.diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only SUCCESS may omit streamed Chunk diagnostics");
            }
        }

        private static CommitResult success() {
            return new CommitResult(Status.SUCCESS, List.of());
        }

        private static CommitResult stale(SaveDiagnostic diagnostic) {
            return new CommitResult(Status.STALE, List.of(diagnostic));
        }

        private static CommitResult failed(SaveDiagnostic diagnostic) {
            return new CommitResult(Status.FAILED, List.of(diagnostic));
        }

        private static CommitResult blocking(SaveDiagnostic diagnostic) {
            return new CommitResult(Status.BLOCKING_FAILURE, List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public boolean unloadAuthorized() {
            return status == Status.SUCCESS;
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static final class BatchReadResult {
        public enum Status {
            FOUND,
            CORRUPT,
            IDENTITY_MISMATCH
        }

        private final Status status;
        private final List<StreamedChunkPayload> payloads;
        private final List<SaveDiagnostic> diagnostics;

        private BatchReadResult(
                Status status,
                List<StreamedChunkPayload> payloads,
                List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.payloads = List.copyOf(Objects.requireNonNull(
                    payloads, "payloads"));
            this.diagnostics = List.copyOf(Objects.requireNonNull(
                    diagnostics, "diagnostics"));
            if ((status == Status.FOUND) != this.diagnostics.isEmpty()
                    || (status == Status.FOUND) != !this.payloads.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only FOUND publishes streamed Chunk batch payloads");
            }
        }

        private static BatchReadResult found(
                List<StreamedChunkPayload> payloads) {
            return new BatchReadResult(Status.FOUND, payloads, List.of());
        }

        private static BatchReadResult failed(
                Status status, SaveDiagnostic diagnostic) {
            return new BatchReadResult(status, List.of(), List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public List<StreamedChunkPayload> payloads() {
            return payloads;
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static final class CurrentAuthorityReadResult {
        public enum Status {
            FOUND,
            CORRUPT,
            IDENTITY_MISMATCH
        }

        private final Status status;
        private final StreamedChunkIndex index;
        private final List<StreamedChunkPayload> payloads;
        private final List<SaveDiagnostic> diagnostics;

        private CurrentAuthorityReadResult(
                Status status,
                StreamedChunkIndex index,
                List<StreamedChunkPayload> payloads,
                List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.index = index;
            this.payloads = List.copyOf(Objects.requireNonNull(payloads, "payloads"));
            this.diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics"));
            boolean found = status == Status.FOUND;
            if (found != (index != null)
                    || found != this.diagnostics.isEmpty()
                    || (!found && !this.payloads.isEmpty())) {
                throw new IllegalArgumentException(
                        "Only FOUND publishes the current streamed authority");
            }
        }

        private static CurrentAuthorityReadResult found(
                StreamedChunkIndex index, List<StreamedChunkPayload> payloads) {
            return new CurrentAuthorityReadResult(
                    Status.FOUND, index, payloads, List.of());
        }

        private static CurrentAuthorityReadResult failed(
                Status status, SaveDiagnostic diagnostic) {
            return new CurrentAuthorityReadResult(
                    status, null, List.of(), List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public Optional<StreamedChunkIndex> index() {
            return Optional.ofNullable(index);
        }

        public List<StreamedChunkPayload> payloads() {
            return payloads;
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static final class MigrationCompatibilityReadResult {
        public enum Status {
            FOUND,
            NOT_PUBLISHED,
            CORRUPT,
            IDENTITY_MISMATCH
        }

        private final Status status;
        private final StreamedChunkIndex.MigrationCompatibility compatibility;
        private final boolean unpublishedEntries;
        private final List<SaveDiagnostic> diagnostics;

        private MigrationCompatibilityReadResult(
                Status status,
                StreamedChunkIndex.MigrationCompatibility compatibility,
                boolean unpublishedEntries,
                List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.compatibility = compatibility;
            this.unpublishedEntries = unpublishedEntries;
            this.diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics"));
            boolean found = status == Status.FOUND;
            boolean failed = status == Status.CORRUPT
                    || status == Status.IDENTITY_MISMATCH;
            if (found != (compatibility != null)
                    || failed != !this.diagnostics.isEmpty()
                    || (status != Status.NOT_PUBLISHED && unpublishedEntries)) {
                throw new IllegalArgumentException(
                        "Migration compatibility result has inconsistent evidence");
            }
        }

        private static MigrationCompatibilityReadResult found(
                StreamedChunkIndex.MigrationCompatibility compatibility) {
            return new MigrationCompatibilityReadResult(
                    Status.FOUND, compatibility, false, List.of());
        }

        private static MigrationCompatibilityReadResult notPublished(
                boolean unpublishedEntries) {
            return new MigrationCompatibilityReadResult(
                    Status.NOT_PUBLISHED, null, unpublishedEntries, List.of());
        }

        private static MigrationCompatibilityReadResult failed(
                Status status, SaveDiagnostic diagnostic) {
            return new MigrationCompatibilityReadResult(
                    status, null, false, List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public Optional<StreamedChunkIndex.MigrationCompatibility> compatibility() {
            return Optional.ofNullable(compatibility);
        }

        /** True only for a structural, non-authoritative pre-publication index. */
        public boolean hasUnpublishedEntries() {
            return unpublishedEntries;
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static final class ManagedTreeValidationResult {
        private final boolean valid;
        private final List<SaveDiagnostic> diagnostics;

        private ManagedTreeValidationResult(
                boolean valid, List<SaveDiagnostic> diagnostics) {
            this.valid = valid;
            this.diagnostics = List.copyOf(diagnostics);
            if (valid == !this.diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "A valid managed tree has no diagnostics");
            }
        }

        private static ManagedTreeValidationResult success() {
            return new ManagedTreeValidationResult(true, List.of());
        }

        private static ManagedTreeValidationResult invalid(
                SaveDiagnostic diagnostic) {
            return new ManagedTreeValidationResult(false, List.of(diagnostic));
        }

        public boolean valid() {
            return valid;
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static final class ReadResult {
        public enum Status {
            FOUND,
            NOT_FOUND,
            CORRUPT,
            IDENTITY_MISMATCH,
            BASE_MISMATCH
        }

        private final Status status;
        private final StreamedChunkPayload payload;
        private final List<SaveDiagnostic> diagnostics;

        private ReadResult(
                Status status,
                StreamedChunkPayload payload,
                List<SaveDiagnostic> diagnostics) {
            this.status = Objects.requireNonNull(status, "status");
            this.payload = payload;
            this.diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics"));
            if ((status == Status.FOUND) != (payload != null)) {
                throw new IllegalArgumentException("Only FOUND may publish a payload");
            }
            if ((status == Status.CORRUPT
                            || status == Status.IDENTITY_MISMATCH
                            || status == Status.BASE_MISMATCH)
                    && this.diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "A closed read failure requires a diagnostic");
            }
        }

        private static ReadResult found(StreamedChunkPayload payload) {
            return new ReadResult(Status.FOUND, payload, List.of());
        }

        private static ReadResult notFound() {
            return new ReadResult(Status.NOT_FOUND, null, List.of());
        }

        private static ReadResult failed(
                Status status, SaveDiagnostic diagnostic) {
            return new ReadResult(status, null, List.of(diagnostic));
        }

        public Status status() {
            return status;
        }

        public Optional<StreamedChunkPayload> payload() {
            return Optional.ofNullable(payload);
        }

        public List<SaveDiagnostic> diagnostics() {
            return diagnostics;
        }
    }

    /** Revalidated-on-miss access cache; eviction never removes durable authority. */
    private static final class BoundedAccessMap<K, V> extends LinkedHashMap<K, V> {
        private final int maximumSize;

        private BoundedAccessMap(int maximumSize) {
            super(16, 0.75f, true);
            this.maximumSize = maximumSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maximumSize;
        }
    }

}
