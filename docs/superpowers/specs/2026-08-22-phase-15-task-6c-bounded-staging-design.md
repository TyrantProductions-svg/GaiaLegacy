# Phase 15 Task 6C Bounded Prepublication Staging Design

**Status:** CLOSED / READY (final independent review: 0 Critical / 0 Important / 0 Minor)

## Scope and governing invariants

Task 6C needs one semantic WorldItem checkpoint to replace as many as 1,024
current-live owner pages. The existing generic Task4 transaction deliberately
accepts at most 64 Chunk mutations and 64 MiB of encoded candidate bytes. A
legal WorldItem checkpoint can therefore be larger than one physical Task4
transaction. Raising either physical limit, lowering the current-live cap, or
reintroducing a catalog/database is not permitted.

The approved solution is a Task4 seam with two phases:

```text
bounded invisible payload staging (one or more physical batches)
-> complete candidate validation
-> one recovery/main index generation publication
```

LogicalWorldItemService remains the only WorldItem semantic, lifecycle,
stable-ID, allocator, and ItemStack authority. Task4 continues to interpret
only generic Chunk payload and global-extension bytes.

## Minimal physical mechanism

Task4 already owns exactly two fixed payload slots, `a` and `b`, for every
indexed Chunk. A published index entry identifies payload bytes by revision,
length, and hash; only a slot matching that entry is authority. Staging reuses
the slot not referenced by the captured base index. It creates no third slot,
temporary name, catalog, refcount, WAL, or directory-enumerated work queue.

A package-private `StagedTransaction` is owned by one StreamedChunkStore on the
owner thread. It captures one immutable base authority and its intended next
index sequence. It retains only:

- the base index plus bounded slot/descriptive metadata; payload bodies are
  decoded one at a time and are never retained as a whole-authority map;
- at most 1,024 lightweight staged descriptors (key, target slot, revision,
  length, hash, and expected base revision/hash);
- the current physical batch. A new owner costs two distinct physical blobs
  because both fixed A/B slots must exist before publication; an existing
  owner costs one target blob. Each batch has at most 64 distinct payload
  blobs and at most 64 MiB;
- bounded semantic accumulators supplied by the WorldItem adapter.

The adapter produces mutations incrementally. It never constructs all 1,024
encoded payloads at once. Each batch is encoded, written, forced, reread, and
validated, then its byte arrays are released before the next batch.

Remove mutations and global extensions do not create staged payload bytes.
They are retained as bounded lightweight candidate-index changes and are
validated at finalization.

## State machine

```text
OPEN(baseSequence, baseIndex)
  -> STAGING(batch 1..N)
  -> VALIDATING_COMPLETE_CANDIDATE
  -> PUBLISHING_RECOVERY_INDEX
  -> PUBLISHING_MAIN_INDEX
  -> PUBLISHED

OPEN/STAGING/VALIDATING
  -> CANCELED | FAILED | STALE
```

Only `PUBLISHED` changes visible authority. Terminal non-published states may
leave bytes in inactive fixed slots, but the old index remains authoritative.
A staging object is single-use; stage, cancel, validate, or publish after a
terminal state fails closed.

Before every batch and immediately before index publication, Task4 verifies
that the currently observed root still matches the captured base generation
and that the caller freshness predicate is true. A changed root, canceled
candidate, false capture predicate, or mismatched expected revision/hash makes
the staging generation stale and forbids publication.

Every `StreamedChunkStore` handle for the same captured save-root identity
shares one fail-fast writer capability. Ordinary commits, Phase14 publication,
and staged transactions acquire it before observing a writable base and hold
it across payload/index mutation. A second handle therefore cannot publish or
overwrite a would-be inactive slot in the check/write gap.

Constructor recovery follows the same rule. A healthy dual index is inspected
read-only and requires no writer. If either index slot needs creation or
repair, the constructor acquires the root writer capability, reinspects both
slots under that lease, and only then mutates authority. A competing cold or
recovery handle therefore cannot initialize around an active writer.

## Validation and publication order

Finalization performs the following before writing either index slot:

1. Reobserve the same save/world identity and base index sequence.
2. Revalidate every staged slot against its descriptor bytes/hash/revision.
3. Apply every upsert/remove expectation to a detached candidate index.
4. Apply checkpoint/session global mutations to that same candidate index.
5. Validate required-extension dependency counts against the complete
   candidate.
6. Validate the candidate index codec round trip and physical payload table.
7. Recheck caller freshness and the captured base generation.
8. Publish the complete candidate envelope to recovery and then main index.

WorldItem-specific validation remains above Task4 and completes before calling
finalization: identity, checkpoint revision/digest, page descriptors, raw and
survivor counts, duplicate live IDs, allocator high-water, world tick, and the
session-checkpoint binding all belong to one candidate index sequence.

No reader follows a staged slot because no published index names its candidate
revision/hash. During all staging batches readers continue to resolve the
last-known-good base index. The final recovery/main write protocol preserves
the existing old-or-complete-new crash recovery rule.

WorldItem proof, restart, and session reopen use a lazy bounded generation
view. It stores index/slot metadata only, resolves at most one payload body per
call, and pins the exact referenced slot paths. One later publication may use
the opposite slots while the old view remains valid; a further writer that
would recycle a pinned old-generation slot fails closed. This preserves the
accepted pinned-generation contract without materializing every payload body.

## Crash, cancellation, and cleanup semantics

- Crash/failure before final publication: the old root remains authoritative.
- Crash between recovery and main index publication: existing Task4 authority
  selection exposes a complete old or complete new generation.
- Crash after successful publication: the complete new root is authoritative.
- Late candidate validation failure, cancellation, or stale generation never
  writes an index naming staged bytes.
- Inactive-slot remnants are unreachable fixed-slot artifacts. Restart ignores
  them without scanning any directory; a later mutation for that exact key
  overwrites the inactive slot.
- Cleanup is lazy exact-slot overwrite: the next staged mutation for the same
  key replaces its inactive remnant as part of one ordinary bounded batch.
  There is no eager delete, automatic cleanup retry, or cleanup scan. A caller
  may issue one fresh semantic retry after a failed save; that retry must again
  obey the ordinary batch bounds. Otherwise the remnant remains unreachable.
  Cleanup has no correctness role.

Because staging reuses the existing two-slot namespace, orphan name count does
not grow with failed attempts. Cleanup failure can waste the inactive copy for
that key until its next rewrite, but cannot affect restart or semantic
authority.

## Boundedness contract

| Quantity | Hard bound |
|---|---:|
| Current-live WorldItems / semantic owner mutations | 1,024 |
| Staged descriptors | 1,024 |
| Distinct payload blobs per physical staging batch | 64 |
| Encoded bytes per physical staging batch | 64 MiB |
| Simultaneously retained staging batches | 1 |
| Staging payload byte-array residency upper bound | <=256 MiB |
| Bounded read-view resident payload bodies | 0 between reads; <=1 during a read |
| Bounded read-view slot metadata | one entry per captured index entry |
| Cleanup paths touched per lazy overwrite step | <=64 |
| Automatic cleanup retries | 0 |
| Fresh semantic retry after one failed save request | at most 1 |
| Index publications per semantic candidate | 1 recovery/main generation |

The total candidate may exceed 64 MiB because encoded payload bytes are
released batch by batch. The candidate index and 1,024 descriptors remain
bounded by existing index/checkpoint codec limits.

The 256 MiB payload-array bound is conservative and executable, rather than a
heap-working-set claim. `StagingMetrics` measures the submitted canonical
source and actual encoded batch bytes and reports their sum plus six
maximum-size (18 MiB) payload buffers. The two batch terms cover decoded source
plus retained encoded candidate bytes. The six-buffer
allowance covers a pending adapter replacement/page scratch, codec clone and
output buffers, durable reread, and decoded validation copy that can overlap a
batch boundary. At the maximum legal batch this is 236 MiB, below the enforced
256 MiB ceiling. JVM object headers and the index/descriptor structures are
separately bounded by 64 batch entries and 1,024 candidate descriptors; they
do not scale with historical owners.

## Compatibility

Existing `StreamedPersistenceTransaction` retains its 64-Chunk/64-MiB contract
and existing single-batch behavior. WorldItem save/restart integration uses the
new staging seam when publishing its page/checkpoint/session semantic root.
Legacy v1 reads, v1 lossy-write rejection, Phase14 migration authority, and
Task 6A/6B runtime contracts are unchanged.

The process-wide writer/slot-pin registry uses the save identity, normalized
world path, and provider identity as its key and a reference-queued weak gate
value. Live stores are the gate's only strong owners; expired keys are removed
by exact queued reference, without a scan. It is neither durable nor enumerable
and cannot become a second authority or historical catalog.

## Acceptance evidence

Tests must prove multi-batch atomicity at 65 writes, legal 1,024-owner bounded
staging, total candidate size above 64 MiB, first/middle/final batch failures,
pre/post-publication crashes, stale/cancel rejection, late revision/hash
mismatch, checkpoint/session single-root publication, restart with inactive
remnants, reader invisibility, single-batch compatibility, and frozen 6A/6B
plus current 6C regression health. Final evidence includes 16 staging
adversarial cases, a real 1,024-owner WorldItem adapter run with explicit peak
metrics, Task4 TTL/paging, Task6C restart/v1/v2, Phase14 migration faults, and
the frozen Task6A/6B boundaries. Physical I/O evidence independently counts
the actual unique payload paths and byte lengths observed through the protocol
file-operations seam; it does not trust the production 1/2-slot metric.
