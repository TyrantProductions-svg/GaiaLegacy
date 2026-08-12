# Phase 14 Save/Load v1 and World Slots handoff

## Status

- Branch: `feat/save-load-v1`.
- Baseline: `origin/main@076f9f490fa97db3ecfc0b7e44ac666c5a61df28`.
- Committed HEAD remains the baseline; Phase 14 is an uncommitted working-tree
  candidate and has not been staged, committed, pushed, or published.
- Gate 14A schema/ownership: **PASS**.
- Gate 14B core persistence/restore: **PASS**.
- Gate 14C atomicity/recovery: **PASS**.
- Gate 14D product integration and Windows development runtime: **PASS**.
- Gate 14E Windows automation/package checks: **PASS**.
- Gate 14E Windows installDist human cycle: **HUMAN-REPORTED PASS**.
- Gate 14E Apple Silicon macOS: **HUMAN-REPORTED PASS**.

Phase 14 cross-platform acceptance is complete. The implementation is ready
for final human review and a separately authorized candidate commit; no Git
mutation is implied by this handoff.

## Completed work

- Versioned format vocabulary, manifest, codec registry, immutable catalog
  summaries, deterministic cross-platform save root, and strict name/ID rules.
- Fresh-target canonical snapshot/restore for Chunks, player, three-slot body
  inventory, and stable-ID logical WorldItems without serializing runtime,
  physics, mesh, GPU, thread, reservation, or presentation identity.
- Deterministic Chunk binary and bounded JSON codecs, section hashes/sizes,
  sectioned aggregate codec, bounded canonical ZIP writer/reader, future-format
  closure, optional-section skip, and path-safe diagnostics.
- Atomic current/backup store with validated temp/copy/move/reread, explicit
  unsupported-atomic fallback, last-known-good preservation, expected slot ID,
  root/world identity guards, remediation, and blocking uncertainty result.
- Immutable catalog discovery, health classification, explicit recovery, and
  root-confined non-recursive delete with junction/link rejection.
- Unicode New World name/seed editor, stable-ID dynamic controls, four-row
  World Slots paging, Load/Recover/Delete confirmations, and corruption states.
- Owner-thread product lifecycle for initial save, fresh load, Pause Save,
  Save & Quit, dirty Return, explicit catalog refresh, no-input Saving frame,
  exact checkpoint publication, and second-session cleanup.
- Windows acceptance defect correction: LOADING sessions no longer call
  `capturePaused()` before READY.
- Human-requested feedback adjustment: committed mining camera shake is 20% of
  the prior peak (`0.055` pitch / `0.014` yaw degrees), duration unchanged.

## Core architecture decisions

- Full finite-world Chunk snapshots are v1 authority. Delta saves are deferred
  because generator compatibility is not yet a robust persistence contract.
- `SaveGameId` is immutable slot identity. Every archive/store/recovery/load
  validation checks manifest and snapshot IDs against the directory ID.
- `ProductLoop` is the sole product lifecycle coordinator. Filesystem/archive
  work remains behind repository/store/catalog boundaries; UI consumes typed
  commands and immutable snapshots.
- Capture is READY-only and owner-thread-only. The O(1) checkpoint ledger uses
  opaque session-provenanced tokens and retains no historical snapshots.
- Restore always uses fresh owners, validates before publication, bypasses
  generation for complete snapshots, reconstructs projections/presentation,
  and commits shared camera state only at READY.
- Save writes are synchronous and explicit for the finite world. There is no
  autosave timer or background save thread.
- Loading and Saving expose truthful state only; no percentage is fabricated.

## File inventory

The Phase 14 working tree contains 43 tracked modifications and 118 untracked
paths at final inventory time. Intended source/test/document roots are:

- `engine/src/main/java/com/overlord/core/input/`: Unicode UI input capture.
- `engine/src/main/java/com/overlord/physics/PlayerController.java`: bounded
  canonical player restore.
- `engine/src/main/java/com/overlord/voxel/`: Chunk snapshot/restore authority.
- `engine/src/main/java/com/overlord/worlditem/` and `api/`: stable-ID logical
  WorldItem snapshot/restore authority.
- `engine/src/test/` and `engine/src/testFixtures/`: persistence/input REDs and
  test access without public runtime authority expansion.
- `game/src/main/java/com/gaia/save/`: format, path, snapshot, codec, archive,
  session, store, catalog, recovery, and delete boundaries.
- `game/src/main/java/com/gaia/session/`: persistence clock/token/result,
  launcher, request/result types, and fresh production restore composition.
- `game/src/main/java/com/gaia/shell/` and `shell/world/`: typed lifecycle
  intents, routes/modals, dynamic stable IDs, New World, World Slots, and save
  orchestration.
- `game/src/main/java/com/gaia/GameBootstrap.java`: real save composition.
- `game/src/main/java/com/gaia/inventory/`, `worlditem/`, and
  `interaction/feedback/`: canonical restore boundaries and accepted camera
  feedback adjustment.
- `game/src/test/java/com/gaia/save/`, `session/`, `shell/`, `inventory/`, and
  `worlditem/`: schema, codec, corruption, atomicity, recovery, lifecycle,
  composition, round-trip, security, and performance coverage.
- `README.md`, `CONTROLS.md`, `KNOWN_ISSUES.md`, `CHANGELOG.md`,
  `docs/architecture/`, `docs/testing/`, `docs/superpowers/`, and this handoff.

The local Milestone 1 artifact
`dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip` remains intentionally
untracked and excluded. Build output, local settings/saves, logs, screenshots,
crash dumps, IDE files, and temporary corruption fixtures are not intended
source.

## Verification

- Forced engine Gate 14 focus: `BUILD SUCCESSFUL in 16s`, all tasks executed.
- Forced game Gate 14 focus: `BUILD SUCCESSFUL in 36s`, all tasks executed.
- Full engine: 1,141/1,141 passed.
- Full game: 1,496/1,496 passed.
- Full tools: 27 total, 26 passed, one pre-existing skip.
- `./gradlew clean test build` Windows equivalent: `BUILD SUCCESSFUL in 1m
  55s`, 30/30 tasks executed; repository total 2,664, passed 2,663, skipped 1,
  failures/errors 0.
- Forced packaged shader/game resources, installed shaders, generated UI, and
  installed audio runtime: `BUILD SUCCESSFUL in 18s`, 15/15 tasks; OpenAL
  3.3.3 API and Windows native jars present.
- Representative 81-Chunk archive: 7,819 bytes. Three-run medians: capture
  119.667 ms, encode 44.502 ms, write 56.770 ms, read/decode 43.335 ms,
  restore-to-ready 422.213 ms. No time value is a CI assertion.
- Human Windows development: `Test` create after RED/GREEN fix, position/item
  restore, slot delete, reduced mining shake, and clean process exit PASS.
- Human Windows installDist: installed create/load, mutation, Save & Quit,
  relaunch, restored-state verification, and normal exit cycle PASS. Exact
  duration and raw runtime logs were not supplied and are not claimed.
- Human Apple Silicon macOS: the complete requested Gate 14E test is
  HUMAN-REPORTED PASS. Exact Mac model, macOS version, architecture command
  output, Java version, automated totals, raw logs, runtime durations, audio
  device details, and macOS performance measurements were not supplied and are
  not claimed.
- `git diff --check`: PASS with only pre-existing line-ending warnings.

## Unfinished work and risks

- Apple Silicon macOS detailed environment, numeric, timing, and raw-log
  evidence was not supplied even though the requested Gate 14E test is
  human-reported passing.
- The Windows installDist cycle is human-reported passing without an exact soak
  duration or retained raw runtime log. Corruption/recovery behavior has broad
  automated fault/security coverage; no separate Windows corruption-copy raw
  log is claimed.
- Directory durability is limited by Java/provider capabilities; Windows
  directory fsync is not claimed.
- Loading percentage remains deferred until a truthful progress contract.
- F3 rapidly changing numeric ghosting remains the accepted debug-HUD issue.

## Interfaces Phase 15 and Phase 16 must not break

- Preserve the single save root, manifest identity, world seed, and
  `generatorVersion` authority.
- Extend Chunk persistence through the existing `ChunkKey`-addressed codec and
  registry boundary; do not invent a second streamed-world save authority.
- Preserve stable WorldItem IDs, allocator high-water, pickup tick policy, and
  projection reconstruction semantics.
- Use reserved optional `detail-blocks` for Phase 16 data and preserve unknown
  optional-section integrity validation/skip behavior.
- Do not serialize mesh/GPU/physics-body/thread/reservation/presentation state.
- Do not bypass public fresh-target restore boundaries with private-field
  mutation.

## Final diff summary

The final tracked-only diff stat is 43 files, 4,469 insertions, and 532
deletions. The complete candidate also contains 118 intended untracked paths,
including the deliberately excluded M1 `dist/` ZIP. Re-run `git diff --stat`
and the full untracked inventory before any later authorized staging because
the candidate remains uncommitted.

## Suggested Git metadata

- Commit: `feat(save): add versioned local world save and load`
- PR title: `feat(save): implement GaiaLegacy local world slots and persistence`
- PR summary: Adds versioned finite-world persistence, atomic recovery-safe
  storage, world-slot product flows, canonical fresh-session restore, and
  complete Windows and human-reported Apple Silicon macOS Gate 14E acceptance
  evidence.

No Git mutation is authorized by this handoff.
