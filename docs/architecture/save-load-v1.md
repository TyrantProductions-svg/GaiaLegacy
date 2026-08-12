# Save/Load v1 Architecture

## Status and gate boundary

This document records the Save/Load v1 behavior implemented by Gates 14A
through 14C:

- immutable save-format identities, metadata, section descriptors, and
  canonical snapshot values;
- deterministic bounded codecs for Chunks, player, inventory, and logical
  WorldItems;
- exact section sizes and SHA-256 digests;
- owner-thread session capture and checkpoint authorization;
- fresh-session canonical restore and presentation reconstruction;
- a bounded deterministic ZIP archive with closed corruption diagnostics;
- root-confined `current.glsave`/`backup.glsave` transactions;
- immutable catalog health, explicit backup recovery, and safe local delete.

Gate 14D composes these storage boundaries into the product lifecycle: Unicode
New World drafts, paged World Slots, Load/Delete/Recover, Pause Save and Save &
Quit, dirty-session confirmation, and explicit catalog refresh. Gate 14E still
owns packaged and cross-platform acceptance.

## Authority and ownership

The game-owned `com.gaia.save` model is the persistence authority. It consumes
public engine snapshots and restore results; the engine never depends on the
game module.

`SaveGameSnapshot` is the deeply immutable aggregate captured at one fixed
tick. It owns:

- one `StaticMetadata` value;
- the saved fixed tick;
- a complete `ChunkRepositorySnapshot`;
- authoritative player state;
- the canonical three-slot body inventory;
- logical WorldItems and their allocator state.

The aggregate retains no service, mutable vector, block array, reservation,
physics body, renderer, task, callback, or thread reference. Loading installs a
validated aggregate into a newly constructed session; it never patches an
already published session into another world.

## Versions and section registry

The format and every section have independent versions.

| Value | Wire ID or magic | Version | Required in v1 | Implemented Gate 14B codec |
| --- | --- | ---: | --- | --- |
| Manifest model | format version | 1 | Yes | Canonical bounded UTF-8 JSON |
| Chunks | `chunks` / `GLCH` | 1 | Yes | Binary, big-endian |
| Player | `player` | 1 | Yes | Canonical UTF-8 JSON |
| Inventory | `inventory` | 1 | Yes | Canonical UTF-8 JSON |
| WorldItems | `world-items` | 1 | Yes | Canonical UTF-8 JSON |
| Discovery/lore | `discovery-lore` | Reserved | No | Not implemented |
| Detail blocks | `detail-blocks` | Reserved | No | Not implemented |

Known core IDs cannot be marked optional, and the two reserved extension IDs
cannot be marked required. An unknown required descriptor or unsupported
required codec version fails closed. An unknown optional descriptor may be
absent. If its payload is present, its exact size and SHA-256 are validated
before the payload is skipped. A present payload without any descriptor is
always rejected.

## Manifest model

`SaveGameManifest` v1 contains these fields in its record authority:

1. `formatVersion`;
2. `gameVersion`;
3. `saveGameId`;
4. normalized `displayName`;
5. `createdAt`;
6. `modifiedAt`;
7. `worldSeed`;
8. `generatorVersion`;
9. lowercase SHA-256 `generatorConfigFingerprint`;
10. `chunkRadius`;
11. `worldHeight`;
12. `fixedTick`;
13. nullable bounded `summary`;
14. ordered section descriptors.

`SaveGameId` is a canonical lowercase UUID string. Display names are stripped,
contain 1 through 40 Unicode code points, reject controls and both path
separators, and use the documented NFC plus `Locale.ROOT` upper-then-lower
comparison key for catalog uniqueness. The optional summary is bounded to 280
Unicode code points. Chunk radius is currently 2 through 8, world height is
positive, fixed tick is nonnegative, and `modifiedAt` cannot precede
`createdAt`.

Each `SaveSectionDescriptor` contains, in order:

1. section ID;
2. positive codec version;
3. required flag;
4. nonnegative uncompressed byte size;
5. 64-character lowercase SHA-256.

Gate 14B constructs this manifest in memory. Gate 14C encodes it as bounded
canonical UTF-8 JSON and publishes it as the first ZIP entry.

## Canonical section layouts

### Chunks: `GLCH` binary v1

All integral values use Java `DataOutputStream` big-endian representation.
The payload is exactly the header followed by `chunkCount` entries; trailing
bytes are rejected.

| Order | Field | Type/size | Constraint |
| ---: | --- | --- | --- |
| 1 | magic | 4 bytes | ASCII `GLCH` |
| 2 | codec version | signed 32-bit integer | Exactly 1 |
| 3 | world height | signed 32-bit integer | 1 through `GameConfig.Chunk.MAX_HEIGHT` |
| 4 | repository revision high-water | signed 64-bit integer | 0 through `Long.MAX_VALUE - 1` |
| 5 | Chunk count | signed 32-bit integer | 0 through 289, covering radius 8 |

Each entry then contains:

| Order | Field | Type/size | Constraint |
| ---: | --- | --- | --- |
| 1 | Chunk x | signed 32-bit integer | `ChunkKey.x` |
| 2 | Chunk z | signed 32-bit integer | `ChunkKey.z` |
| 3 | Chunk revision | signed 64-bit integer | Positive and no greater than the high-water |
| 4 | block-byte length | signed 32-bit integer | Exactly `16 * worldHeight * 16` |
| 5 | canonical blocks | declared byte length | Full authoritative Chunk bytes |

Entries are sorted by ascending `(x, z)`. Duplicate or noncanonical key order
is rejected. The canonical block index is
`localX + y * 16 + localZ * 16 * worldHeight`. The repository high-water is
stored independently of live Chunk revisions, so unload or deletion cannot
make a prior revision reusable.

The decoder validates height, count, checked total length, high-water, and each
fixed block length before allocating variable payload structures. It rejects
invalid magic/version, duplicates, invalid revisions, truncation, and trailing
bytes.

### Player JSON v1

The UTF-8 object has this exact emitted field order:

1. `owner`;
2. `feetPositionX`, `feetPositionY`, `feetPositionZ`;
3. `velocityX`, `velocityY`, `velocityZ`;
4. `yaw`;
5. `pitch`;
6. `gameMode`;
7. `noclip`.

Position is the authoritative feet position. Position, velocity, yaw, and
pitch must be finite. Pitch is within the camera's closed `[-89, 89]` range;
arbitrary finite yaw is preserved exactly rather than normalized. The enum is
constructed through the public `GameMode` boundary. The player payload is
bounded to 16 KiB.

### Inventory JSON v1

The root object emits fields in this order:

1. `owner`;
2. `revision`;
3. `activeSlot`;
4. `twoHandedHandsOccupied`;
5. `slots`.

`slots` always contains exactly `LEFT_HAND`, `RIGHT_HAND`, and `MOUTH`, in that
order. Each element emits `slot`, then `stack`. An empty direct slot is JSON
`null`; a stack emits `itemId`, then positive `count`.

Only direct physical slots are encoded. A two-handed item appears once at the
left-hand anchor, the right direct slot is null, and the occupancy flag is
true; the mirrored gameplay view is never serialized. Restore validates item
registration, form identity, maximum stack size, mouth eligibility, slot
acceptance, and the complete two-handed shape before one detached inventory is
published. The inventory payload is bounded to 64 KiB.

### WorldItems JSON v1

The root object emits fields in this order:

1. `fixedTick`;
2. `nextItemId`;
3. `itemIdsExhausted`;
4. `entries`.

Entries are sorted by ascending stable WorldItem ID. Each entry emits:

1. `id`;
2. `stack` with `itemId`, then `count`;
3. `positionX`, `positionY`, `positionZ`;
4. `velocityX`, `velocityY`, `velocityZ`;
5. `revision`;
6. nullable `source` entity ID;
7. `spawnTick`;
8. `pickupAvailableTick`;
9. `physicalState`.

The physical state is one of `ACTIVE`, `GROUNDED`, `SLEEPING`, or
`FROZEN_UNLOADED`. Motion must be finite, revisions and ticks are nonnegative,
pickup availability cannot precede spawn, and spawn cannot exceed the saved
fixed tick. A future pickup-available tick is valid and continues from the
saved fixed tick; offline wall-clock time is irrelevant.

Stable IDs are unique. When the allocator is not exhausted, every live ID is
strictly below `nextItemId`. Exhaustion is represented exactly by
`nextItemId == Long.MAX_VALUE` plus `itemIdsExhausted == true`. The high-water
is preserved even if its highest allocated item was deleted. The payload is
bounded to 1 MiB and 1,024 logical items.

## Section assembly, integrity, and determinism

`SaveSnapshotCodec` encodes the four required sections completely before it
constructs the manifest. Required section order is exactly:

1. `chunks`;
2. `player`;
3. `inventory`;
4. `world-items`.

For each defensively owned payload, Gate 14B records its exact byte length and
lowercase SHA-256 in the descriptor. Repeated encoding of an unchanged
snapshot produces byte-identical section payloads and equal descriptors.
Changing only `modifiedAt` changes only manifest time. Changing one canonical
block byte changes only the Chunk payload and Chunk descriptor/hash.

Decode validates descriptor order and support, every required payload's
presence, absence of undescribed payloads, exact sizes, and all hashes before
invoking any domain decoder. An absent optional payload is skipped; a present
optional payload still passes the same descriptor size and SHA-256 checks.
JSON decoders reject malformed UTF-8, excessive nesting, duplicates, missing
or unknown fields, unknown enums, trailing values, and bounded-payload
violations. They parse explicit DTO values and then invoke public domain
constructors; Gson never instantiates authoritative records as a validation
bypass.

## Gate 14C archive container and limits

Each slot archive is JDK ZIP/Deflate. The writer emits this exact order and
normalizes each entry to the fixed DOS-local ZIP epoch
(`1980-01-01 00:00`), `DEFLATED`, no comment, and no extra bytes:

1. `manifest.json`;
2. `chunks.bin`;
3. `player.json`;
4. `inventory.json`;
5. `world-items.json`.

The public writer uses the ordinary JDK file output boundary. A package-private
output factory exists only for deterministic fault injection. The writer owns
both the opened file stream and ZIP stream in one try-with-resources scope, so
an exception after partial manifest bytes or midway through `chunks.bin`
closes the partially opened output before the store cleans its owned temp.

The reader requires `manifest.json` first. It preflights the EOCD and central
directory, rejects ZIP64/multidisk or inconsistent local/central metadata,
then streams entries while enforcing unique safe names and bounded expansion.
Absolute, drive-qualified, backslash, empty-segment, `.`, `..`, directory, and
overlong entry names are rejected. CRC is enforced by the JDK ZIP layer and
the manifest's SHA-256 remains the canonical section-integrity check.

| Bound | Exact v1 value |
| --- | ---: |
| Entry count | 32 |
| `manifest.json` | 65,536 bytes |
| `chunks.bin` | 18,945,708 bytes |
| `player.json` | 16,384 bytes |
| `inventory.json` | 65,536 bytes |
| `world-items.json` | 1,048,576 bytes |
| Each optional entry | 1,048,576 bytes |
| Total uncompressed entries | 48,453,292 bytes |
| Archive file | 52,647,596 bytes |

These are hard structural limits for the supported radius-8 finite world, not
the representative radius-4 performance target. Checked arithmetic derives
the Chunk and aggregate bounds. The reader counts decompressed bytes before
publishing any snapshot and never allocates solely from an untrusted declared
length.

## Gate 14C atomic store

One direct world directory is exactly:

```text
<save-root>/<SaveGameId>/
|-- current.glsave
|-- backup.glsave        (optional)
`-- *.tmp                (transaction-owned or stale)
```

The public store boundary accepts only the configured save root plus a
canonical `SaveGameId`; it derives the direct world directory. Root and world
real paths and provider file keys are retained and revalidated inside every
filesystem mutation boundary. Direct slot and temp entries must remain regular
non-link files under that retained world identity. The store also retains the
target `SaveGameId`: a snapshot carrying another ID is rejected before section
encoding or save filesystem mutation. Every temp, current, backup, rotation,
exact-copy, installed-current, and last-known-good validation requires a
reader-valid archive carrying that expected target identity. A structurally
valid wrong-ID archive can never replace the expected-ID backup or satisfy
last-known-good ownership.

The commit call order is:

1. validate retained root/world identities and, for a newly created world,
   attempt to force the configured save root;
2. encode every required section and the manifest in memory;
3. create a sibling current temp, write/close the archive, call
   `FileChannel.force(true)`, production-reread it, and capture its exact raw
   manifest, size, and SHA-256 identity;
4. when current exists, create a sibling backup candidate, copy current,
   force/reread/byte-compare it, atomically move it over backup, reread and
   byte-compare the installed backup, then attempt to force the world
   directory;
5. atomically move the validated current temp over current, reread it as the
   intended canonical snapshot and exact archive identity, then attempt to
   force the world directory;
6. publish `SUCCESS` with the exact committed manifest only after every
   required validation succeeds.

If an atomic move reports `AtomicMoveNotSupportedException`, the same already
forced and validated candidate is installed with deterministic
`REPLACE_EXISTING`. No fallback is taken for other move errors. A failure after
current installation removes that installed current and attempts to force the
directory; when a prior current existed, its verified copy remains the backup
recovery candidate. Failure to establish known-good ownership during temp
cleanup or installed-current
remediation returns `BLOCKING_FAILURE`; ordinary failures return `FAILED` and
never publish a committed manifest.

The durability claim is deliberately bounded to JDK capabilities. Regular
archives are flushed and forced with `FileChannel.force(true)`. Directory
force is attempted through a readable directory channel on providers that
support it. The Windows JDK provider is treated as unsupported for directory
channels; no portable Windows directory-fsync guarantee is claimed. Real
permission or I/O failures are not mislabeled as unsupported.

## Gate 14C catalog, recovery, and delete

Catalog discovery scans only direct canonical UUID directories. It ignores
`.trash`, unknown directories, temp-only worlds, linked/junction-like worlds,
and stale temps. Current and backup are independently production-reread and
must carry the directory's expected `SaveGameId` in both manifest and decoded
snapshot. Discovery never writes or silently promotes backup.

| Catalog health | Current | Backup | Load/recover meaning at Gate 14C |
| --- | --- | --- | --- |
| `VALID` | Valid expected ID | Any | Load action is available |
| `RECOVERABLE_BACKUP` | Missing/corrupt/wrong ID | Valid expected ID | Explicit recovery is available |
| `UNSUPPORTED_VERSION` | Future format and no valid archive wins | No valid expected-ID archive | Visible, not loadable |
| `CORRUPT` | Unusable | Unusable | Visible with bounded diagnostics, not loadable |

Snapshots are immutable and sorted by modified time descending, then
`SaveGameId` ascending. `WorldSlotsController` owns the explicit refresh and
four-row paging boundary; no render/input frame performs filesystem discovery.
The product lifecycle refreshes after initial save, load failure, save-and-quit,
delete, recovery, and session close.

Explicit recovery performs this deterministic sequence:

1. require unusable current plus valid expected-ID backup;
2. copy backup to an owned sibling temp;
3. force, production-reread, and byte-compare the temp;
4. guarded atomic move (or only the explicit unsupported-atomic fallback) over
   current while revalidating temp and backup identity;
5. production-reread and byte-compare installed current, attempt directory
   force, and reread again before returning `SUCCESS`.

Backup bytes remain unchanged. A stale temp is neither loaded nor implicitly
owned. Any failure returns a closed result; success requires the immediately
following catalog observation to be `VALID` for the expected ID.

Delete first rejects links, nested directories, and unexpected direct entries.
It then moves only the direct world directory to a unique child of the
root-local `.trash` directory before the catalog row disappears. Cleanup uses
only guarded deletes of prevalidated direct regular files and the empty trash
child; there is no recursive walk. A cleanup failure yields
`DELETED_WITH_CLEANUP_WARNING` and retains the confined trash entry. Move
failure leaves the original row and bytes unchanged.

## Gate 14C diagnostics

All published codes and messages are bounded; operational causes remain
available for logs/tests but path, entry-name, exception-message, stack, and
payload text are never concatenated into UI messages.

| Boundary | Stable diagnostic codes (shown without repeated prefix) |
| --- | --- |
| `save-archive.` | `truncated`, `manifest-first`, `malformed-manifest`, `unsupported-version`, `entry-count-limit`, `invalid-entry-name`, `duplicate-entry`, `unexpected-entry`, `unknown-required-section`, `unknown-optional-section`, `declared-size-limit`, `expansion-limit`, `size-mismatch`, `checksum-mismatch`, `missing-required-entry`, `invalid-section` |
| `save-write.` | `snapshot-identity-mismatch`, `section-encode-failed`, `temp-create-failed`, `temp-write-failed`, `temp-force-failed`, `temp-validation-failed`, `backup-temp-create-failed`, `backup-copy-failed`, `backup-force-failed`, `backup-validation-failed`, `backup-move-failed`, `backup-replace-failed`, `current-move-failed`, `current-replace-failed`, `current-validation-failed`, `current-manifest-mismatch`, `directory-force-failed`, `root-directory-force-failed`, `unsafe-temp-path`, `unsafe-world-path`, `temp-cleanup-ownership-uncertain`, `current-remediation-failed` |
| `save-catalog.` | `current-missing`, `archive-unreadable`, `identity-mismatch`, `corrupt`, plus bounded archive diagnostics |
| `save-recovery.` | `not-found`, `not-recoverable`, `unsafe-target`, `failed` |
| `save-delete.` | `not-found`, `unsafe-target`, `move-failed`, `cleanup-failed` |

## Capture and checkpoint authority

Capture is an owner-thread, READY-only session operation. It reads the session
persistence revision and fixed tick before and after collecting detached
Chunk, inventory, logical WorldItem, player, camera, and game-mode state.

Capture returns one of three closed outcomes:

- `CAPTURED`, containing one immutable snapshot, its numeric revision, and an
  opaque session-provenanced checkpoint token;
- `PENDING_TRANSACTION`, with no snapshot or token;
- `INCONSISTENT_REVISION`, with no snapshot or token.

Pending inventory reservations, WorldItem spawn reservations, or WorldItem
extraction reservations block capture. A generation-active or mixed revision
Chunk capture becomes an inconsistent result. Fixed-step and other canonical
mutations reserve their next checked revision before mutation and commit it
only after the mutation succeeds.

`markSaved` accepts only the latest genuine token captured by that same
session, while the exact last-saved token remains idempotent. Stale, foreign,
future, decreasing, or publicly fabricated tokens are rejected. The owned
session retains an O(1) ledger consisting only of the latest outstanding token
and last-saved token (or their overlap); it never retains captured snapshots
in the checkpoint ledger. A failed disk write therefore has no reason to
advance the checkpoint. Session-to-store checkpoint publication remains Gate
14D product-lifecycle wiring.

## Fresh-session restore

Every section is decoded and validated before domain restoration begins. The
production load factory builds a new World at the saved height and fresh
inventory, logical WorldItem, physics, projection, and presentation owners. A
complete-snapshot load bypasses world generation and the world-loader
executor.

Before any target mutation, the coordinator validates all saved Chunk keys
against the saved radius and converts/checks the player's collider position,
velocity, yaw, and pitch. Restore then runs in this order:

1. Chunks;
2. inventory;
3. logical WorldItems;
4. player body/noclip, game mode, camera orientation staging, and fixed tick;
5. physical WorldItem projection reconciliation;
6. Chunk mesh-readiness publication, followed by camera publication at the
   production READY boundary.

Chunk, inventory, and logical WorldItem restores accept only virgin targets.
Prior use remains irreversible even if the target later becomes empty. Each
boundary validates and builds detached state before a single authoritative
publication. Restored Chunks retain exact bytes, revisions, and repository
high-water; they start `DIRTY` as meshing candidates and contain no restored
mesh or GPU state.

Inventory restore publishes no ordinary gameplay insert/extract or active-slot
events. Logical WorldItem restore recreates no reservation records. Projection
reconciliation reads canonical logical state without modifying it, admits
loaded/capacity-eligible stable IDs in deterministic order, creates at most one
body per admitted ID, and leaves unloaded items unprojected. Repeating
reconciliation retains the same valid bodies and creates no duplicate stable
ID.

A failure at any production restore, mesh-pump, initial-frame, or READY-frame
boundary publishes no READY session. The entire half-built fresh session is
closed exactly once in reverse owner order; there is no generation fallback.
Shared camera orientation is not published until the READY frame succeeds.
Transient feedback and presentation trackers begin empty and are rebuilt only
from canonical stable IDs.

## Exact exclusions

Gate 14A/14B state never contains:

- `PhysicsBody`, collision-object, or physical-projection identity;
- OpenGL, GLFW, OpenAL, GPU, mesh, atlas, shader, buffer, texture, or renderer
  cache handles;
- Chunk mesh queues or uploaded render objects;
- inventory or WorldItem reservation IDs, locks, pending records, or terminal
  audit history;
- particles, debris, block proxies, damage overlays, animations, presentation
  interpolation, camera bob/smoothing, or action impulses;
- workers, executors, futures, callbacks, services, mutable vectors, or mutable
  block arrays;
- Settings, which remain under `SettingsStore` ownership.

## Gate 14D product lifecycle

`ProductLoop` is the single owner-thread coordinator. Dynamic controls carry a
stable `SaveGameId`, so catalog reordering or pagination cannot retarget a
click. New World carries one validated `NewWorldRequest` through generation,
canonical capture, the initial atomic write, and session publication. Load
production-rereads the selected current archive, restores a fresh session, and
bypasses world generation.

Pause Save uses this visible order:

1. route to `SAVING` and render/swap one no-input frame;
2. on the next frame capture the READY session exactly once;
3. atomically write the selected slot;
4. mark only the returned opaque checkpoint token saved;
5. return to Pause, or close only after success for Save & Quit.

A failed write never advances the checkpoint or closes the live session. A
clean Return to Main Menu closes directly; a dirty return requires discard
confirmation. Delete and recovery use the selected stable ID and refresh the
catalog only after the closed operation result. There is no timer, worker, or
per-frame save path.

The New World draft accepts Unicode scalar input, removes one code point per
Backspace, validates a trimmed 1-40-code-point name, rejects separators/control
characters and case-folded duplicates, and parses the seed as a signed 64-bit
integer. World Slots presents four rows per page. `VALID` rows load,
`RECOVERABLE_BACKUP` rows offer explicit recovery, and corrupt or unsupported
rows remain visible but non-loadable; every row may be deleted after explicit
confirmation. Diagnostics remain bounded and never expose filesystem paths or
raw exception messages.

## Future compatibility and deferred behavior

The Chunk wire format is explicitly `ChunkKey` addressed. Phase 15 may reuse
the same versioned Chunk persistence authority for modified streamed Chunks;
it must not introduce a second incompatible Chunk save authority. The reserved
optional `detail-blocks` ID allows Phase 16 to add an independently versioned
detail-block payload without changing required v1 sections. The reserved
optional `discovery-lore` ID likewise remains vocabulary only.

The following are explicitly not implemented through Gate 14D:

- Gate 14E: packaged acceptance, Windows/macOS runtime evidence, archive size
  and latency measurements, corruption UI smoke, and cross-platform release
  claims.

No timed autosave, background save thread, cloud sync, multiplayer locking,
infinite-world region streaming, or detail-block payload is part of the
implemented v1 boundary through Gate 14D.
