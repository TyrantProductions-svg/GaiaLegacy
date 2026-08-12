# Phase 14 Save/Load v1 and World Slots Design

**Status:** Approved design and implementation plans, pending implementation
**Phase:** Milestone 2 Phase 2
**Branch:** `feat/save-load-v1`
**Baseline:** `origin/main@076f9f490fa97db3ecfc0b7e44ac666c5a61df28`

## 1. Purpose and scope

Phase 14 adds local, versioned, atomic Save/Load v1 and connects it to the
Phase 13 product shell. It persists the current finite world, authoritative
player state, the canonical three-slot body inventory, logical world items,
and the minimum version metadata needed by later phases.

Save/Load is a domain and persistence boundary. Screens never mutate World,
Inventory, WorldItem, physics, or rendering internals. Loading creates a fresh
`GameSession`; no existing session is privately patched into a different
world.

Phase 14 does not add cloud sync, multiplayer locking, infinite-world region
streaming, detail blocks, moving assemblies, thumbnail rendering, background
save threads, or timed autosave.

## 2. Approved product decisions

The following decisions are part of the approved v1 contract:

1. The world section stores complete canonical Chunk snapshots. It does not
   regenerate a base world and replay mutation deltas.
2. World slots are named catalog entries with immutable `SaveGameId` identity,
   not a fixed number of numbered slots.
3. The catalog is ordered by modified time descending with `SaveGameId` as a
   stable tie-break and is presented in pages.
4. New World creates an initial valid save after the world is ready. Pause
   exposes explicit Save and Save & Quit. Phase 14 has no timed autosave and no
   background save thread.
5. Each world directory contains one sectioned `current.glsave` archive and an
   optional `backup.glsave` recovery candidate.
6. World-item pickup timing continues from the saved fixed tick. Real-world
   time spent while the process is closed does not advance pickup eligibility.
7. The save root is platform-specific application data supplied through one
   injectable `SaveRootProvider`.
8. New World adds a real Unicode character-input boundary and exposes editable
   world-name and signed 64-bit seed fields. The default seed is `12345`.

## 3. Current architecture constraints

Phase 13 established one `ProductLoop`, zero or one `GameSession`, immutable
product presentation, hard pause, focus/input invalidation, and a read-only
`SaveCatalog` placeholder. Phase 14 preserves those boundaries.

The current default world has Chunk radius 4, so the finite snapshot contains
81 Chunks. A Chunk is 16 by 256 by 16 canonical block bytes. Existing
`ChunkRepository.snapshot(ChunkKey)` supplies immutable data, but no bulk
restore boundary exists. `BodyInventoryService` and
`LogicalWorldItemService` likewise expose snapshots but no production restore
transaction. Phase 14 adds explicit restore boundaries instead of mutating
private fields.

The generator exposes an algorithm version and canonical configuration
fingerprint, but the repository does not promise that an old generator can be
replayed forever. Full Chunk snapshots therefore take precedence over smaller
delta files in v1. The seed and generator version remain in the manifest for
diagnostics and Phase 15 compatibility.

## 4. Ownership model

### 4.1 Product ownership

`ProductLoop` remains the only outer loop and owner-thread coordinator.
`ProductShellController` converts UI commands into typed lifecycle intents.
Neither class receives direct access to World, Inventory, WorldItem, Renderer,
or OpenAL internals.

A typed `GameSessionLauncher` replaces the new-world-only supplier and accepts:

- `NewWorldRequest(SaveGameId, displayName, seed, session settings)`;
- `LoadWorldRequest(SaveGameId)`.

The launcher always returns a fresh closeable session. Loading failure or
cancellation closes that entire session before the shell accepts another
launch.

### 4.2 Save ownership

The game module owns the persistence format under `com.gaia.save`:

- `SaveGameId`;
- `SaveFormatVersion`;
- `SaveGameManifest`;
- `SaveSectionDescriptor`;
- `SaveSectionCodec<T>` and `SaveCodecRegistry`;
- `SaveGameSnapshot`;
- `SaveReader` and `SaveWriter`;
- `SaveRepository` for read/write/recover/delete commands;
- `SaveCatalog` and immutable catalog entries;
- `SaveCoordinator` for session capture and checkpoint publication;
- `SaveRootProvider` and its platform implementation.

The product UI reads immutable catalog and operation view models. It does not
call codecs or filesystem APIs.

### 4.3 Capture consistency

Save capture runs on the product owner thread while the session is hard
paused. It produces one deeply immutable `SaveGameSnapshot` and a session
persistence-revision token. The snapshot contains no mutable service, body,
renderer, or task reference.

Capture is rejected before writing if any of the following is observed:

- an inventory reservation is pending;
- a world-item spawn or extraction reservation is pending;
- a Chunk generation/rebuild transaction is active;
- duplicate or missing canonical IDs exist;
- the aggregate cannot be captured under one consistent session revision.

Successful persistence records the captured revision as the saved checkpoint.
A failed write never advances that checkpoint. Because fixed tick is world-save
state, every executed fixed step advances the session persistence revision.

## 5. Save root and slot identity

`SaveRootProvider` resolves:

| Platform | Save root |
| --- | --- |
| Windows | `%APPDATA%\GaiaLegacy\saves` with the established roaming fallback |
| macOS | `~/Library/Application Support/GaiaLegacy/saves` |
| Linux | `$XDG_DATA_HOME/GaiaLegacy/saves`, else `~/.local/share/GaiaLegacy/saves` |

The provider accepts injected OS name, home directory, and environment values
for tests. Production source and documentation never contain a developer's
absolute path.

`SaveGameId` is an immutable UUID-form identifier whose canonical lowercase
text is safe as a single directory name. The display name is independent of
the directory and may change in a future format without moving the save.

World display names are trimmed, contain 1 through 40 Unicode code points,
reject control characters and `/` or `\`, and are unique under Unicode
case-folding within the current catalog. Names are never interpreted as paths.

Each world uses:

```text
<save-root>/<SaveGameId>/
|-- current.glsave
|-- backup.glsave
`-- *.tmp
```

Temporary files are never catalog load candidates.

## 6. Archive and manifest format

### 6.1 Container

`current.glsave` is one JDK ZIP/Deflate archive. No new serialization or
compression dependency is added. Entries are written in this exact order:

1. `manifest.json`
2. `chunks.bin`
3. `player.json`
4. `inventory.json`
5. `world-items.json`

The writer first encodes every domain section into a bounded immutable byte
source and computes its descriptor. It can therefore write the complete
manifest as the first ZIP entry without seeking back into the archive.

ZIP entry timestamps and other non-semantic metadata are normalized. Required
domain section bytes are deterministic for an unchanged canonical snapshot.
Manifest `modifiedTime` is the intentional repeat-save difference.

The reader rejects duplicate entry names, absolute paths, `..` traversal,
unexpected directory entries, and entries outside the registered section set.

### 6.2 Manifest v1

The manifest contains at least:

- save format version;
- game/build version;
- immutable save ID and display name;
- created and modified UTC instants;
- world seed;
- generator algorithm version and configuration fingerprint;
- Chunk radius and world height;
- current fixed tick;
- optional bounded human-readable summary;
- ordered section descriptors.

Each descriptor contains section ID, codec version, required/optional flag,
uncompressed byte size, and SHA-256. ZIP CRC remains an additional container
check rather than replacing the section hash.

### 6.3 Codec registry

Every section has an independent codec version. v1 registers the five required
entries above. An unknown required section or unsupported required codec
version rejects the save. An unknown optional section is skipped with a
bounded diagnostic.

The registry reserves optional extension IDs for discovery/lore and future
detail-block data without placing either feature in Phase 14. The Chunk codec
is explicitly `ChunkKey` addressed, so Phase 15 can reuse it for modified
streamed Chunks under the same save root rather than inventing a second
persistence authority. Phase 16 can register an optional versioned detail-block
section without changing or invalidating the required v1 sections.

Because v1 contains complete Chunk snapshots, a generator-version mismatch is
reported in metadata but does not require regenerating or rejecting an
otherwise supported and valid v1 archive. Generator compatibility becomes a
load requirement only for a future format that actually depends on generator
replay.

### 6.4 Resource limits

Before allocation, the reader enforces bounded entry count, per-section size,
total uncompressed size, Chunk count, world height, inventory slots, and
world-item count. The bounds derive from the supported finite-world and domain
limits, and include checked arithmetic. A compressed archive cannot expand
beyond those limits.

Those structural bounds cover the current supported Chunk-radius range through
radius 8. The 16 MiB value in the performance section is a measurement target
for the default radius-4 profile, not the reader's hard format limit.

## 7. Canonical section contracts

### 7.1 Chunk section

`chunks.bin` is an explicit versioned binary format using a documented byte
order. It contains:

- repository revision high-water mark;
- world height;
- Chunk count;
- entries sorted by ascending `(x, z)`;
- each `ChunkKey`, exact Chunk revision, block-byte length, and canonical block
  bytes.

The high-water mark is persisted separately from live Chunk revisions so a
deleted or previously higher revision cannot be reused after restart.

### 7.2 Player section

`player.json` contains authoritative feet position, linear velocity, camera
yaw and pitch, `GameMode`, and noclip state. Values must be finite and within
the existing camera/world constraints. Interpolated render position,
ground-contact caches, walk bob, step smoothing, jump/land presentation, and
action impulses are not saved.

### 7.3 Inventory section

`inventory.json` contains inventory revision, active `BodySlot`, the canonical
stack at each physical slot, and the two-handed occupancy shape. Each stack is
encoded as canonical `ResourceLocation` plus positive count. Restore validates
registered item identity, maximum stack size, slot acceptance, and two-handed
rules as one transaction.

Reservation IDs, locks, and reservation terminal history are runtime-only and
are not serialized.

### 7.4 World-item section

`world-items.json` contains:

- fixed tick;
- next stable WorldItem ID and exhausted state;
- each live item sorted by ascending stable ID;
- canonical `ItemStack`;
- position and velocity;
- revision;
- optional source `EntityRef`;
- spawn tick and pickup-available tick;
- current `WorldItemPhysicalState`.

The allocator high-water state is required even when the current highest item
was deleted, preventing stable-ID reuse. Pickup time is measured only in saved
fixed ticks. Offline wall-clock time does not advance it.

PhysicsBody instances are never serialized. The logical physical state and
current Chunk availability drive reconstruction of exactly one projection per
admitted stable ID after load.

### 7.5 Explicit exclusions

The format never contains:

- PhysicsBody or collision object identity;
- OpenGL, GLFW, OpenAL, GPU, mesh, atlas, shader, buffer, or texture handles;
- mesh queues or renderer caches;
- transient particles, debris, block proxies, damage overlays, or animation;
- camera presentation layers;
- workers, futures, executors, callbacks, or pending reservations;
- Settings, which remain owned by `SettingsStore`.

## 8. Restore transactions

All sections are decoded and validated before any domain restore begins.
Restore targets belong to a new session that has not reached Playing or been
published as ready.

### 8.1 Chunk restore

`ChunkRepository` gains a bulk canonical restore boundary, provisionally named
`restoreCanonical(...)`, that only accepts an empty repository with no
generation attempt. It validates all keys, heights, revisions, byte lengths,
uniqueness, and the repository high-water mark before publishing any entry.
Restored Chunks become meshing candidates; no mesh or GPU state is restored.

### 8.2 Inventory restore

`BodyInventoryService` gains `restoreCanonical(...)`, restricted to a fresh
inventory with no reservations. It validates the complete three-slot snapshot
against the item-form registry before replacing the initial empty state.
Restore does not synthesize ordinary gameplay insert/extract events.

### 8.3 World-item restore

`LogicalWorldItemService` gains `restoreCanonical(...)`, restricted to an empty
service with no reservations. It validates all items and allocator state before
publishing them. Runtime reservation IDs may restart because no reservation or
external reservation reference survives process shutdown; stable WorldItem IDs
may not restart.

### 8.4 Player and projection restore

Player state is applied through public controller, body, camera, and game-mode
boundaries. The restored body must pass finite/world-bound and penetration
recovery checks. A failure closes the new session and leaves the save
unchanged.

After canonical restore:

1. Chunk meshes are scheduled from restored canonical snapshots;
2. `PhysicalWorldItemSystem` reconciles once against logical items and loaded
   Chunks;
3. presentation trackers rebuild from stable IDs;
4. particle and transient visual systems start empty;
5. the session becomes ready only after required Chunk meshes are renderable.

Any restore failure discards the entire new session. No partially restored
session frame reaches the renderer.

## 9. Atomic write and recovery

### 9.1 Commit sequence

One save transaction performs:

1. create a unique sibling temp archive inside the world directory;
2. write entries in canonical order while computing sizes and SHA-256 values;
3. flush and close the archive;
4. force the temp file through `FileChannel.force(true)`;
5. reopen the temp through the production `SaveReader` and validate the whole
   archive;
6. if current exists, move it to `backup.glsave` with replacement;
7. atomically move the validated temp to `current.glsave` with replacement;
8. publish success and advance the session saved checkpoint.

Directory fsync is attempted only where the JDK/platform supports a directory
channel and is reported accurately. Phase 14 does not claim a portable
directory-fsync guarantee that Java cannot provide.

### 9.2 Atomic-move fallback

If atomic move is unsupported, the fallback first creates, forces, and
validates the new backup. It then performs the replace of current. A failure at
any point leaves at least one previously verified archive available. The exact
operation order and cleanup failures are fault injected in tests.

Initial-save failure may leave no slot, but it cannot present a half-written
slot as valid.

### 9.3 Recovery states

Catalog health is closed over:

- `VALID`;
- `RECOVERABLE_BACKUP`;
- `CORRUPT`;
- `UNSUPPORTED_VERSION`.

If current is corrupt or absent and backup is valid, the UI offers an explicit
Recover Backup action. It never silently substitutes backup content. If both
are unusable, the row remains visible with a bounded diagnostic and Load is
disabled.

Stale temp files are ignored as load candidates. Startup may report and safely
clean them only after confirming they are regular direct children of the save
directory. Catalog, recovery, and deletion do not follow symbolic links or
junction-like entries out of the configured save root.

### 9.4 Delete

Delete requires modal confirmation. The repository verifies that the target is
the expected direct child of the configured save root, then moves it under a
root-local `.trash` location before it disappears from the catalog. Bounded
trash cleanup failure produces a diagnostic. No unverified or computed broad
path is recursively removed.

## 10. Product-shell integration

### 10.1 Routes and commands

Phase 14 adds product routes:

- `NEW_WORLD_SETUP`;
- `WORLD_SLOTS`;
- `SAVING`.

It adds typed commands for creating, selecting, loading, deleting, recovering,
saving, and saving-and-quitting a specific `SaveGameId`. Dynamic catalog rows
carry their typed command and stable focus identity; the input controller never
infers a save ID from a mutable row index.

### 10.2 Unicode text input

The existing `InputManager` remains the only GLFW input owner. It installs one
owner-thread character callback and adds the frame's immutable Unicode code
points to `UiInputSnapshot`. Route, focus, and eligibility boundaries clear
pending text exactly like key/mouse edges. UI text never enters fixed gameplay
input.

The New World screen provides:

- world name, default `New World`;
- signed decimal 64-bit seed, default `12345`;
- Create and Back.

Backspace/Delete editing and deterministic keyboard/mouse focus are in scope.
Complex clipboard, key rebinding, or a new IME framework is not.

Because this changes GLFW input, focused GREEN is followed immediately by a
real Windows runtime smoke before later gates continue.

### 10.3 World Slots

The World Slots screen displays immutable catalog rows in fixed-size pages.
Rows show name, modified time, seed, format/health status, and selected action
availability. Sorting is modified time descending and stable ID ascending as a
tie-break.

Valid rows support Load and Delete. Recoverable rows support Recover Backup and
Delete. Corrupt or unsupported rows expose diagnostics and Delete but not
Load. Recovery and deletion require dedicated confirmation modals.

Load World on Main Menu is enabled whenever the catalog has a displayable row;
an empty catalog presents a clear empty-state World Slots screen rather than a
fake enabled load.

### 10.4 Save lifecycle

Pause Menu adds Save and Save & Quit. Phase 14 performs synchronous owner-thread
save for the finite world:

1. command enters static `SAVING`;
2. one frame presents `SAVING...` with no percentage claim;
3. the next owner-thread frame captures, validates, and writes;
4. Save returns to Paused on success;
5. Save & Quit closes the session and returns to Main Menu only on success.

No gameplay fixed step runs in `SAVING`. Input is invalidated on entry and exit.
A write failure returns to Paused with a diagnostic and retains both the live
session and previous last-known-good save.

Return to Main Menu prompts about unsaved progress only when the session
persistence revision differs from its saved checkpoint. A clean session may
return directly. Repeated Save, Save & Quit, cancel, and close operations are
idempotent.

New World writes its initial archive after generation and readiness checks and
before Playing is published. If that initial save fails, the new session is
closed and no invalid slot becomes loadable.

## 11. Diagnostics and closed results

Filesystem, format, validation, and recovery operations return typed status and
bounded diagnostic codes. UI-facing diagnostics do not expose arbitrary stack
traces or unbounded filesystem content. Operational code retains the underlying
Throwable for logs and tests.

At minimum diagnostics distinguish:

- missing current/backup;
- malformed or future manifest;
- missing/unknown required section;
- checksum or size mismatch;
- truncated or duplicate archive entry;
- invalid domain value or identity;
- capture blocked by pending transaction;
- temp write/force/backup/replace/cleanup failure;
- recovery or delete failure.

Corruption is never silently discarded and never crashes the product loop.
Cleanup failure that makes ownership or last-known-good status uncertain
remains fatal rather than being masked as a recoverable UI error.

## 12. RED/GREEN gate strategy

### 12.1 Gate 14A - schema and authority

RED tests establish invalid manifest, ID, name, path, registry, section, and
restore shapes. Restore-boundary REDs prove the current domain APIs cannot
safely install canonical state. GREEN adds immutable schema, path policy,
registry, capture models, and fresh-instance bulk restore boundaries.

Architecture tests retain `engine -> game` independence and prove product UI
cannot call domain persistence internals.

### 12.2 Gate 14B - core persistence

Focused round-trip tests cover:

- complete canonical save/load equality;
- deterministic section bytes under shuffled source order;
- all 81 default Chunks and exact block bytes;
- Chunk revision/high-water restoration and dirty mesh scheduling;
- player position, velocity, yaw, pitch, mode, and noclip;
- all inventory slots, active slot, two-handed rules, revision, and count
  conservation;
- partial-pickup remainder under the same stable WorldItem ID;
- stable-ID allocator high-water after deletion;
- grounded, sleeping, and unloaded projection reconstruction without a saved
  PhysicsBody;
- fixed-tick pickup timing with no offline-time advance;
- empty transient presentation after restore.

Tests reject unknown items, duplicate keys/IDs, invalid counts, invalid timing,
non-finite values, and inconsistent allocator state.

### 12.3 Gate 14C - atomicity and recovery

Fault-injection tests cover failure during manifest, Chunk, Inventory, and
WorldItem encoding; flush/force; backup; atomic move; fallback replace; temp
cleanup; and recovery publication.

Reader tests cover truncation, bad CRC/hash/size, duplicate entries, traversal,
ZIP expansion limits, unknown future version, missing optional section, and
unknown required section. Current/backup combinations and crash-like temp files
are tested as catalog behavior, not only low-level exceptions.

Every fault test asserts the exact last-known-good bytes and checkpoint state.

### 12.4 Gate 14D - product integration

Focused shell/session tests cover:

- Unicode character capture and clearing;
- name and seed validation;
- pagination and dynamic stable focus;
- New, Load, Delete, Recover, Save, and Save & Quit routes;
- modal exclusivity;
- hard pause during save/load;
- no held-input or text replay;
- initial-save failure;
- save failure retaining the session;
- load failure/cancel followed by a fresh second session;
- no duplicate music voice or settings contamination;
- clean and dirty Return to Main Menu behavior;
- repeated close and command idempotence.

The first GLFW character-input GREEN receives an immediate real Windows smoke.

### 12.5 Gate 14E - acceptance

Automated verification runs focused save/catalog/codec/fault/UI/session tests,
all module suites, clean build, packaged resources, shader/UI/audio/installDist
checks, `git diff --check`, and a complete repository inventory.

Windows and Apple Silicon macOS each execute:

1. create a named world;
2. break and place blocks;
3. alter inventory;
4. create/drop world items;
5. Save & Quit;
6. relaunch;
7. load the same world;
8. verify player, world, inventory, and WorldItems;
9. save again;
10. corrupt a copied test save and verify diagnostic UI without a crash.

A platform not actually run is reported `NOT RUN / PENDING`.

## 13. Performance and measurement

The representative profile is the default radius-4, 81-Chunk finite world with
player mutations, inventory, and world items.

Documented v1 targets are:

- default-profile archive size target no greater than 16 MiB;
- owner-thread capture, validate, and commit target no greater than 1.0 second;
- archive decode plus canonical restore, excluding GPU mesh rebuild, target no
  greater than 1.0 second;
- time-to-play and mesh rebuild recorded separately.

Elapsed-time measurements are reported evidence, not flaky CI timing
assertions. Structural size/allocation bounds remain automated. If synchronous
save materially exceeds the target, Phase 14 stops for attribution and design
review rather than introducing an unapproved background writer, pooling, or
streaming architecture.

## 14. Documentation deliverables

Phase 14 produces or updates:

- this approved design;
- a Gate 14A through 14E RED/GREEN implementation plan;
- `docs/architecture/save-load-v1.md`;
- a save-format version and recovery table;
- `docs/testing/phase-14-save-load-acceptance.md`;
- `docs/agent-handoffs/phase-14-handoff.md`;
- README, CONTROLS, KNOWN_ISSUES, CHANGELOG, and current baseline only where the
  implemented behavior requires factual updates.

No Phase 14 file is staged, committed, pushed, opened as a PR, or merged without
separate user authorization.

## 15. Stop conditions

Implementation stops for user direction if:

- restore would require unvalidated private-field mutation;
- stable WorldItem IDs or allocator high-water cannot be preserved;
- a pending reservation would have to be serialized;
- atomic save cannot protect a last-known-good archive;
- the full snapshot exceeds the approved finite-world bounds or synchronous
  latency target without an attributable fix;
- loading requires a second product/game loop;
- save work expands into infinite streaming, detail blocks, cloud sync, or
  multiplayer locking;
- a GLFW/input/lifecycle change fails real runtime smoke;
- a protection must be disabled to make a test or runtime path pass.
