# Phase 11 Progress Handoff

**Date:** 2026-08-07

**Branch:** `feat/physical-world-items`

**Baseline HEAD:** `819a690f85ab4b1a192bd2db3bca73ddb573ced7`

**Current status:** Phase 11 implementation and human Windows development
acceptance are complete and **PASS** in the unstaged working tree. Gates 11.1
through 11.5 and their historical automated/review evidence are preserved
below. Native macOS and Windows installed-distribution interactive acceptance
remain not run. No staging, commit, push, pull request, or merge is authorized.

## Phase 11.6 gameplay-feel and drop corrections

- Root cause: `InventoryDropController` used the complete active-slot snapshot
  as both extraction and spawn request, so plain Q dropped the whole stack and
  no Ctrl amount contract existed. `BodyInventoryInputController` now maps the
  Q press edge to one item or, with either Ctrl held on that edge, the complete
  active stack. Long holds and held-only catch-up steps cannot replay it.
- Root cause: `BlockBreakTransaction` reserved inventory insertion first and
  only spawned the insertion remainder. Normal inventory capacity therefore
  consumed count-one block loot before any world item existed. Successful
  Survival break now reserves the complete canonical world drop before voxel
  mutation and commits it exactly once after the applied mutation; Creative
  and blocks without item forms still create no drop.
- Q launch motion is copied and deterministic: 0.40 forward spawn, 4.5 forward
  speed, +1.25 vertical speed, and at most 0.15 lateral variation. Block drops
  start at the old block center, move away from the copied player position at
  a 1.25..1.75 base/outward horizontal magnitude, add +1.40 vertical speed, and
  use an independently hashed orthogonal lateral component bounded by +/-0.20.
  The total horizontal resultant is capped at approximately 1.7614 blocks/s.
- `InteractionFeedbackCoordinator` now receives committed facts after Q,
  placement, break, and pickup transactions. It owns bounded render-only held
  animation, analytic copied-view camera impulse, a 256-coordinate transient
  placement/break proxy map, and exact requests to the existing one
  `ParticleSystem`. It cannot mutate gameplay services.
- Placement collision/World state commits immediately while its render proxy
  grows 0.85 to 1.00 over 0.14 seconds. Break collision/World state disappears
  immediately while the immutable old six-face proxy shrinks/fades for 0.18
  seconds. The per-cell shader exclusion is presentation-only and changes no
  Chunk revision.
- Committed break emits 16 debris plus 4 astral particles; placement emits 6
  plus 2; pickup emits 8 inward particles. Break debris uses deterministic
  golden-angle/quadrant coverage, includes downward initial velocities, applies
  -12 gravity and light drag, shrinks, and expires in 0.28..0.52 seconds.
  Astral particles carry a translucent lilac tint and pickup particles a
  translucent aqua tint through emission, immutable snapshots, the streaming
  GPU batch, and GLSL 410 shaders.
- Phase 11 Windows development acceptance is **PASS** after live retesting of
  the corrected drop, pickup, feedback, movement-presentation, held-block, and
  exclusion-mask paths.

Phase 11.6 RED/GREEN and self-review evidence:

- Q amount/controller tests first failed because `InventoryDropAmount` and the
  exact one/full-stack transaction path did not exist; GREEN covers plain Q,
  either Ctrl+Q, held/repress behavior, lifecycle suppression, rollback, and
  committed notification failure.
- Block-break tests first failed because loot was inserted directly when
  inventory capacity existed; GREEN always reserves and commits the count-one
  canonical Survival world drop around the applied voxel mutation.
- Feedback and renderer tests first failed because the animator, copied-view
  camera impulse, transient voxel proxies/exclusion mask, and pass ordering did
  not exist; GREEN covers exact durations, bounds, committed-only triggers,
  immutable render input, resource reuse, and idempotent cleanup.
- Particle tests first failed because the required categories, exact splits,
  deterministic emission geometry, gravity/shrink behavior, and tint were
  absent. GREEN preserves the single 512-particle priority system and carries
  tint through the GPU shader path.
- Self-review RED tests additionally reproduced post-close visual
  resurrection, first-visible-frame animation time loss, and acceptance of a
  257-cell transient mask. GREEN returns an empty closed frame, advances render
  feedback only after capture/render, and validates capacity in `1..256`.
- Independent review found that exceptional spawn commits lacked an applied
  state/audit barrier. The correction adds typed reservation identity,
  read-only `PENDING`/`COMMITTED`/`ROLLED_BACK` audit, same-reservation
  completion, unresolved-operation blocking, and idempotent shutdown
  resolution. Q and block-break fault tests cover before/after-apply
  `RuntimeException` and `Error`, audit failure, exact locks/counts/calls, and
  the original stable ID.
- Camera feedback now starts at the approved peaks (`+0.35` pitch and `-0.006`
  Y for PLACE; `+0.55` pitch and deterministic `+/-0.14` yaw for BREAK) and
  reaches exact zero on the first frame sample at or after `0.15`/`0.20`
  seconds. At 10/30/60/144/240 FPS the exact PLACE zero samples are
  `0.200000/0.166667/0.150000/0.152778/0.150000` seconds; BREAK is
  `0.200000/0.200000/0.200000/0.201389/0.200000` seconds.
- New behavioral tests prove non-identity PLACE/BREAK/DROP on the first visible
  frame, fixed-step catch-up isolation, held/proxy matrices at the real draw
  calls, old six-face break material, normal/exception render-state restore,
  nonzero per-Chunk exclusion masks without revision mutation, transition
  expiry, vertical and degenerate kinematics, explicit deterministic extrema,
  and no post-close resurrection.
- `nonWhiteTintReachesEveryUploadedVertexRgba` verifies `0.2/0.4/0.7/0.6` on
  every emitted cube vertex. A deliberate all-white uploader mutation made the
  test fail before the production RGBA path was restored.

Latest pre-documentation automated verification:

- `:engine:test` - 947 tests, 0 failures/errors/skips.
- `:game:test` - 825 tests, 0 failures/errors/skips.
- `:tools:test` - 27 tests, 26 passed, 1 existing skip, 0 failures/errors.
- `clean test build` - `BUILD SUCCESSFUL`, all 29 tasks executed; 1,799 total
  tests, 1,798 passed, 1 skipped, 0 failures/errors.
- Forced packaged resources, packaged shaders, installed shaders, and generated
  UI assets - `BUILD SUCCESSFUL`, all 14 tasks executed.
- `git diff --check` - exit 0 with line-ending normalization warnings only;
  staged file count 0 and tracked generated artifact count 0.

## Gate 11.2 completion

Gate 11.2 is implemented on this branch. The existing stable-ID projection now
performs fixed `1/60` gravity integration with terminal-speed clamping, static
voxel sweep/depenetration, restitution, friction, support probing, deterministic
sleep/wake, finite-world recovery, and unloaded-Chunk freeze/reload. Gate 11.3
adds manual pickup; Gate 11.4 adds six-face presentation, bounded particles,
and measurement. Body-body collision, rotation, canonical expiry, and pooling
remain omitted.

## Completed work

- Fetched and pruned `origin`.
- Confirmed the target branch was clean and at `0/0` divergence from
  `origin/main`.
- Confirmed Phase 10 is included in the public baseline.
- Confirmed no local or remote `feat/astral-environment-ambience` branch was
  present at design time.
- Audited Phase 6, 7, 8, 9A, 9B, and 10 architecture and handoffs.
- Audited the canonical world-item, inventory reservation, physics, input,
  rendering, visual tracking, and particle APIs.
- Received explicit approval for:
  - the split engine-contract/game-coordinator architecture;
  - canonical authority and stable-ID projection rules;
  - remove-body/preserve-logical-state Chunk unload behavior;
  - physical constants and a production `0.50 block` cube;
  - Shift+right input priority and independent world-item targeting;
  - pickup reservation and commit ordering;
  - bounded priority particle behavior and evidence-gated pooling;
  - Gate 11.1 through 11.5 ownership and verification structure.
- Created the Phase 11 design, architecture, and test-first implementation
  plan documents.
- Implemented the Gate 11.1 canonical runtime boundary in the engine:
  stable-ID-ordered immutable physical snapshots, extraction visibility, and
  expected-revision finite motion updates.
- Implemented the Gate 11.1 game projection foundation: one `PhysicsBody` per
  stable ID, deterministic reconciliation, capped admission with stable-ID
  capacity reporting, partial-construction rollback, stale-write rebuild,
  external-body-loss detection/rebuild, metadata refresh without body
  replacement, and idempotent main-thread shutdown.
- Corrected three independently reviewed state-integrity paths:
  - projection synchronization now validates all six float conversions before
    mutating an existing body;
  - projection construction establishes rollback ownership before registering
    a body and rolls back failures at every transaction stage;
  - world-item revision exhaustion is a closed, idempotent result that cannot
    terminalize a partial extraction or release its active lock.
- Completed the remaining Gate 11.1 review corrections:
  - identical duplicate source snapshots are de-duplicated before admission;
  - existing projections are retained, lower stable IDs are admitted first,
    capacity-limited IDs are reported, and the next eligible ID is admitted
    after a slot frees;
  - `PhysicsWorld.containsBody` checks registration by object identity and
    missing bodies are rebuilt under the same stable ID with explicit lost and
    rebuilt metrics;
  - extraction reservation and other immutable runtime metadata refresh in
    place without replacing an unchanged body;
  - `WorldItemMotionUpdateResult` remains constructor-validated for every
    status/payload combination;
  - `presentationSnapshots()` returns uninterpolated coordinates and the
    presentation boundary interpolates exactly once.
- Added Gate 11.2 behavior to the same projection: fixed `1/60` stepping,
  gravity, terminal velocity, static collision, bounce, friction, support
  snap, deterministic sleep/wake, overlap and world-bound recovery, and
  unloaded-Chunk freeze/reload.
- Added focused collision tests for floor/wall/corner behavior, cross-Chunk
  support, sleep/wake, lower-bound and overlap recovery, configuration
  validation, and stable-ID unload/reload.

## Remaining acceptance

- Windows development acceptance passed; installed-distribution interactive
  acceptance has not been run.
- macOS acceptance has not been run.

## Core architecture decisions

- `LogicalWorldItemService` remains the only domain store and canonical motion
  authority.
- `PhysicalWorldItemSystem` is a game-owned, main-thread projection using the
  existing engine physics APIs.
- Stable ID maps to at most one body and one reconstructable visual entry.
- Reconciliation de-duplicates identical source snapshots, admits stable IDs
  deterministically up to capacity, and reports skipped IDs immutably. Lost,
  new, missing, stale-replacement, and previously skipped IDs share the same
  ascending stable-ID candidate sequence.
- `WorldItemPhysicsMetrics` reports created/rebuilt/destroyed/lost traces, the
  latest capacity-limited stable IDs, cumulative bounded-recovery failures, and
  current recovery-blocked stable IDs; these are diagnostic snapshots, not
  domain state.
- PhysicsBody never owns a second ItemStack, lifecycle, or canonical position.
- Chunk unload writes `FROZEN_UNLOADED`, removes the body, and preserves logical
  position and velocity for reload.
- Production dropped-block edge is `0.50 block`; `1.00 block` is comparison-only.
- Pickup is Shift+right press edge in Survival, disabled in Creative and noclip.
- Pickup reserves inventory capacity before reserving the exact accepted world
  count, then enters an inventory-first guaranteed commit barrier.
- Applied notification failures do not roll back or prevent the paired commit.
- The particle cap remains 512, with 384 LOW and 128 protected HIGH capacity.
- Pooling requires measured allocation/GC evidence and excludes canonical
  domain values.

## Modified and created files

- `docs/superpowers/specs/2026-08-04-phase-11-physical-world-items-design.md`
- `docs/superpowers/plans/2026-08-04-phase-11-physical-world-items.md`
- `docs/architecture/physical-world-items.md`
- `docs/agent-handoffs/phase-11-progress.md`
- `engine/src/main/java/com/overlord/physics/PhysicsWorld.java`
- `engine/src/main/java/com/overlord/worlditem/LogicalWorldItemService.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdate.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemMotionUpdateResult.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalSnapshot.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemPhysicalState.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemReservationResult.java`
- `engine/src/main/java/com/overlord/worlditem/api/WorldItemRuntimeAccess.java`
- `engine/src/test/java/com/overlord/worlditem/WorldItemRuntimeAccessTest.java`
- `engine/src/test/java/com/overlord/worlditem/LogicalWorldItemServiceTest.java`
- `engine/src/test/java/com/overlord/worlditem/api/WorldItemContractTest.java`
- `engine/src/testFixtures/java/com/overlord/worlditem/LogicalWorldItemTestAccess.java`
- `game/src/main/java/com/gaia/worlditem/PhysicalWorldItemSystem.java`
- `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsConfig.java`
- `game/src/main/java/com/gaia/worlditem/WorldItemPhysicsMetrics.java`
- `game/src/main/java/com/gaia/worlditem/WorldItemPresentationSnapshot.java`
- `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemCollisionTest.java`
- `game/src/test/java/com/gaia/worlditem/PhysicalWorldItemSystemTest.java`

## Verification performed

Gate 11.2 focused verification:

- `:game:test --tests com.gaia.worlditem.PhysicalWorldItemCollisionTest` -
  passed; 11 tests, 0 failures, 0 errors.
- `:game:test --tests com.gaia.worlditem.PhysicalWorldItemSystemTest --tests
  com.gaia.worlditem.PhysicalWorldItemCollisionTest` - passed; 43 tests, 0
  failures, 0 errors.
- The related engine physics suite and full engine/game suites remain part of
  the final verification below.

Design-gate commands and observations:

- `git fetch origin --prune` - passed.
- `git status -sb` before documentation - clean target branch.
- `git rev-list --left-right --count origin/main...HEAD` - `0 0`.
- `git log -5 --oneline --decorate` - Phase 10 baseline confirmed.
- local and remote ambience-branch checks - no branch found.
- final documentation format scan - all four Markdown files have final newlines,
  no trailing whitespace, and no `TBD`, `TODO`, or `FIXME` markers.
- `git diff --check` - passed.
- staged-file check - empty; no file was staged.
- final `git status --short --untracked-files=all` - exactly the four Phase 11
  design/plan documents plus the Gate 11.1 Java/test files are present; no
  generated file is tracked.

Gate 11.1 TDD evidence:

- RED engine: `WorldItemRuntimeAccessTest` failed compilation with missing
  runtime contract types and `LogicalWorldItemService` runtime methods.
- GREEN engine focused: `:engine:test --tests
  com.overlord.worlditem.WorldItemRuntimeAccessTest` - passed.
- RED game: `PhysicalWorldItemSystemTest` failed compilation with missing
  `PhysicalWorldItemSystem` and `WorldItemPhysicsConfig`.
- GREEN game focused: `:game:test --tests
  com.gaia.worlditem.PhysicalWorldItemSystemTest` - 9 tests passed,
  including a failure on the second stable ID that removes the first created
  body (`created=1`, `destroyed=1`).
- Related engine: `:engine:test --tests com.overlord.worlditem.* --tests
  com.overlord.physics.*` - passed.
- Related game: `:game:test --tests com.gaia.worlditem.*` - passed.
- Full engine suite: `:engine:test` - 888 tests, 0 failures, 0 errors.
- Full game suite: `:game:test` - 621 tests, 0 failures, 0 errors.
- Full tools suite during build: 26 tests, 0 failures, 0 errors, 1 existing
  skipped test.
- `clean test build` - `BUILD SUCCESSFUL`, 29 actionable tasks.
- Total reported tests: 1,535; 1,534 passed, 1 skipped, 0 failed.

Independent-review state-integrity correction evidence:

- Finding 1 RED: `PhysicalWorldItemSystemTest.
  unrepresentableCanonicalMotionLeavesExistingProjectionUnchanged` ran six
  component cases; velocity X/Y/Z failed because body position had already
  changed (`6 tests completed, 3 failed`).
- Finding 1 GREEN: the same six cases passed after all position and velocity
  values were converted before either body mutation; the complete
  `PhysicalWorldItemSystemTest` then passed.
- Finding 5 RED: `everyConstructionFailureStageRollsBackAndCanRecover` failed
  compilation because the five construction-stage injection boundaries and
  transaction observer did not exist.
- Finding 5 GREEN: all five stages passed with zero surviving bodies and
  projections, exact unregister metrics, unchanged logical items, and a
  successful subsequent reconcile; related engine/game world-item suites
  passed.
- Finding 6 RED: focused engine tests failed compilation because
  `REVISION_EXHAUSTED` did not exist in the motion and extraction result
  contracts.
- Finding 6 GREEN: motion exhaustion, partial-extraction exhaustion, complete
  status/payload validation, and physical-projection restoration tests passed;
  related engine/game world-item suites passed.
- Fresh full engine suite: 891 tests, 0 failures, 0 errors.
- Fresh full game suite: 633 tests, 0 failures, 0 errors.
- Fresh full tools suite during build: 26 tests, 0 failures, 0 errors, 1
  existing skipped test.
- Fresh `clean test build`: `BUILD SUCCESSFUL`, 29 actionable tasks.
- Fresh total: 1,550 tests; 1,549 passed, 1 skipped, 0 failed.

Remaining-review correction evidence:

- Finding 2 RED: the new capacity-admission test first failed to compile
  because the metrics snapshot did not yet expose capacity-limited IDs or a
  skipped counter. After the API was added, the legacy fail-all assertion
  failed; it was replaced with the approved non-throwing admission contract.
- Finding 2 GREEN: capacity-one admission, lower-ID preference, skipped-ID
  reporting, slot recovery, shuffled input, and duplicate-source input pass
  with exact create/destroy traces.
- Finding 3 RED: temporarily removing the registration-identity check caused
  the external-loss test to fail with no replacement body
  (`ArrayIndexOutOfBoundsException` while reading the empty physics set).
  GREEN after restoration detects identity loss; exactly one replacement is
  created for the same stable ID, with lost/rebuilt counters updated and no
  logical deletion.
- Finding 4 RED: temporarily removing the metadata-refresh branch caused the
  reserved snapshot assertion to fail while motion/revision stayed unchanged.
  GREEN after restoration passes false -> reserved -> rolled back with the
  same body identity, unchanged revision, and zero rebuilds.
- Finding 7 RED: temporarily removing constructor payload validation allowed an
  invalid status/payload pair and failed the exact invalid-combination test
  (`Expected IllegalArgumentException, but nothing was thrown`). GREEN after
  restoration rejects every invalid pair and accepts every valid shape.
- Finding 8 RED: the no-alpha presentation test initially failed to compile
  against `presentationSnapshots(float)`. GREEN uses the single no-alpha
  collection API and one accessor-side interpolation.
- Finding 9 GREEN: projection tests cover exact duplicate/rebuild metrics,
  shuffled and duplicate source snapshots, starvation recovery, external body
  loss, negative/zero/large finite coordinates, and every projection/runtime
  main-thread public operation.

Final verification for this correction pass:

- `.\gradlew.bat :engine:test --console=plain --no-daemon` - passed;
  893 tests, 0 failures, 0 errors.
- `.\gradlew.bat :game:test --console=plain --no-daemon` - passed;
  640 tests, 0 failures, 0 errors.
- `.\gradlew.bat :tools:test --console=plain --no-daemon` - passed;
  26 tests, 0 failures, 0 errors, 1 existing skipped test.
- `.\gradlew.bat clean test build --console=plain --no-daemon` - passed;
  all 29 actionable tasks completed, including the build-integrated resource
  checks.
- Explicit `:game:verifyPackagedResources`,
  `:engine:verifyPackagedShaderResources`, and
  `:game:verifyInstalledShaderResources` with `--rerun-tasks` - all passed.
- Final reported total: 1,559 tests; 1,558 passed, 1 skipped, 0 failures,
  0 errors.
- `git diff --check` - passed. No files were staged, committed, pushed, or
  merged.

## Final two-finding correction pass

- Finding A RED:
  `lostProjectionDoesNotBypassUnifiedStableIdAdmission`,
  `lostAdmissionIsDeterministicForDuplicateShuffledSnapshots`, and
  `skippedLostProjectionIsAdmittedAfterLowerIdIsRemoved` each failed against
  the previous lost-ID-first admission. The first two observed the higher lost
  ID retaining the only capacity; the third observed that it was never
  replaced after the lower ID was removed.
- Finding A GREEN: reconciliation now builds one de-duplicated, ascending
  stable-ID candidate list containing new, skipped, missing, and lost IDs;
  capacity is finalized before body registration. A capacity-one trace admits
  lower ID `0`, reports lost higher ID `1` as skipped, records
  `lost=1, rebuilt=0, created=2, destroyed=0`, then after lower-ID terminal
  removal admits higher ID `1` with a new body and records
  `lost=1, rebuilt=1, created=3, destroyed=1`. Both logical snapshots remain
  unchanged until the explicit lower-ID extraction.
- Finding A deterministic regression: duplicate source lists `[1,0,1]` and
  `[0,1,0,1]` produce the same admitted IDs, skipped IDs, and lifecycle
  metrics.
- Finding B RED: the new
  `revisionExhaustedRequiresAStrictlyPartialReservation` test failed because
  equal-count payloads were accepted by the prior validator.
- Finding B GREEN: `REVISION_EXHAUSTED` now requires a present matching
  reservation and item, a strictly positive partial reservation, and an
  exact positive remainder equal to the canonical difference. Identity,
  missing-payload, equal-count, over-count, and inconsistent-remainder cases
  are rejected. The valid Long.MAX partial commit remains pending and
  idempotent with count, revision, and active extraction lock unchanged.
- Full extraction at `Long.MAX_VALUE` remains the approved terminal `COMMITTED`
  path and removes the logical item without a revision increment.
- Focused GREEN: Finding A three-test set and Finding B three-test set passed;
  related engine world-item/physics and game world-item packages passed.
- Final package verification: engine `896` tests passed; game `643` tests
  passed; tools `26` tests with `25` passed and `1` existing skipped; total
  `1565` tests, `1564` passed, `1` skipped, `0` failures, `0` errors.
- `clean test build` passed. Explicit packaged-resource, packaged-shader, and
  installed-shader checks all passed. `git diff --check` passed.

The worktree remains intentionally unstaged. Design, architecture, production,
test, and the test-fixture file remain tracked/untracked according to the
current feature-branch working state; none are staged.

Interactive game launch and macOS verification were not run. This gate does
not create a window or claim platform visual acceptance.

## Final focused rereview corrections

- `revisionExhaustedRejectsARevisionThatCanStillAdvance` RED failed with
  `Expected IllegalArgumentException to be thrown, but nothing was thrown`.
  `WorldItemReservationResult` now accepts `REVISION_EXHAUSTED` only when the
  authoritative current snapshot is already at `Long.MAX_VALUE`; the complete
  engine world-item package is GREEN.
- `failedAdmissionBatchPreservesLostClassificationForRetry` initially observed
  `rebuilt=1` after a later admitted candidate failed and rolled the complete
  construction batch back. Lost-ID consumption and lost/rebuilt metric
  publication now commit only after every admitted construction succeeds. A
  failed batch keeps the lost ID eligible without publishing those metrics;
  retry creates the replacement under the same stable ID.
- The admission architecture text now matches the implementation: lost, new,
  missing, stale-replacement, and previously skipped IDs share one ascending
  stable-ID candidate sequence.
- Focused and related engine/game world-item suites passed. Fresh
  `clean test build` passed with engine `897`, game `644`, and tools `26`
  tests (`25` passed, `1` existing skipped): total `1567`, `1566` passed,
  `1` skipped, `0` failures, `0` errors.
- Packaged resources, packaged shaders, and installed shaders were each
  rerun explicitly and passed.

## Final acceptance correction pass

- RED `failedAdmissionBatchDoesNotPublishLostMetricsAndRetriesDeterministically`
  observed `lost=1` after an injected later-candidate failure. Lost metric
  publication is now staged with the admission transaction. Failed batches
  publish neither lost nor rebuilt classification metrics, while the internal
  lost classification remains eligible for deterministic retry. The successful
  retry publishes exactly `lost=1` and `rebuilt=1`.
- RED `revisionExhaustedRequiresAStrictlyPartialReservation` accepted a missing
  positive remainder, and
  `partialExtractionRevisionExhaustionKeepsReservationPendingAndStateUnchanged`
  observed `Optional.empty()`. `REVISION_EXHAUSTED` now requires and returns the
  exact positive canonical remainder.
- The production terminal-state regression confirms that rolling back the
  pending exhausted reservation releases its active lock and makes a later
  commit return `TERMINAL_CONFLICT`; no terminal reservation can reach the
  operational exhaustion branch.
- RED `snapshotUpdateKeepsProjectionIdentityAndUpdatesPresentation` observed
  `rebuilt=1` when a retained body merely synchronized to a newer canonical
  revision. The retained body now keeps its exact identity and updates in place
  with `rebuilt=0`; rebuilt metrics are exclusive to successfully admitted
  externally lost IDs.
- Focused RED failures were reproduced before the production changes. Focused
  GREEN and related engine world-item/physics and game world-item suites passed.
- Fresh `clean test build` passed with all 29 tasks executed: engine `897`, game
  `644`, and tools `26` tests (`25` passed, `1` existing skipped), for `1567`
  total tests with `1566` passed and `0` failures or errors. Packaged resources,
  packaged shaders, and installed shaders passed within the build.

## Gate 11.3 through 11.5 completion

Canonical world-item automatic expiry is deferred. Phase 11 defines physical projection, pickup, unloaded-Chunk freezing and runtime cleanup, but does not define despawn duration or timeout-based stable-ID termination.

- Added read-only inventory/world reservation audit contracts and typed
  applied-state world commit failures for exceptional diagnosis only.
- Added active-slot-first multi-slot insertion planning, reverse rollback,
  closed pickup receipts/results, and an inventory-first/world-second commit
  barrier with exact conservation verification and fatal guarantee handling.
- Added deterministic 0.50-cube eye-ray targeting with independent block
  occlusion, stable-ID tie breaking, delay/lock/frozen exclusion, and immutable
  input candidates.
- Added stateless Shift+right routing, one-edge pickup coordination, explicit
  `GameContext` ownership, and the approved post-physics/pre-block fixed-step
  order. F4, Creative, noclip, disabled interaction, held-only catch-up steps,
  and failed targeting produce no pickup transaction.
- Migrated world-item presentation to six immutable block-face regions and one
  0.50 cube draw while retaining one shared cube mesh and GLSL 410.
- Added LOW/HIGH particle priority, 384/512 occupancy caps, 64 requests per
  fixed-step window, 32 particles per request, deterministic priority-safe
  eviction, immutable allocation metrics, and committed-only eight-particle
  pickup feedback.
- Added a deterministic headless profiling fixture and
  `:tools:profileWorldItems`. The full run reported 3,679,206,752 allocated
  bytes over 60 sampled seconds (about 58.5 MiB/s), 12 GC collections, 27 ms
  aggregate collection time, and a 3 ms maximum observed pause.
- Pooling remains disabled. The allocation rate and collection count exceed
  the approved follow-up thresholds, so later work must first attribute churn
  between existing physics, immutable snapshots, projection processing, and
  particles. Canonical stacks, stable IDs, reservations, and public immutable
  snapshots are never pooling candidates.
- Shutdown now rejects new pickup work idempotently, removes physical bodies,
  clears stable-ID presentation and particle caches, and preserves every
  canonical logical item. There is no deletion for sleep, unloaded duration,
  world-bound recovery failure, or shutdown.

Focused verification completed during implementation:

- Gate 11.3/11.4 combined engine and game regression suites passed.
- The 120-step deterministic short profiler test passed.
- The full 600-step warm-up plus 3,600-step sample profiler passed.
- Full/partial pickup integration retains/removes the correct stable ID, body,
  logical item, and visual entry.

## Current risks and historical implementation cautions

- Gate 11.1's runtime extension is intentionally narrow; later physics gates
  must not widen it into a second world-item store or bypass revision checks.
- PhysicsBody integration uses temporary vector allocations; profiling must
  distinguish existing PhysicsWorld churn from Phase 11-specific churn before
  any optimization proposal.
- The pre-Gate 11.4 global oldest-first particle overflow was replaced by the
  single priority-aware bounded system without changing Phase 9B committed-only
  effects.
- Inventory notification errors can carry fatal `Error` causes after state has
  applied; the pickup barrier must finish conservation before rethrowing.
- A future ambience branch must use the single priority-aware ParticleSystem
  instead of introducing a parallel API.
- macOS/Retina verification remains `NOT RUN` because a Mac is not available.

## Interfaces implementation must not break

- canonical `ResourceLocation` and `ItemStack`;
- Phase 7 `InventoryService`, `InventoryReservation`, `WorldItemService`, and
  world-item reservation signatures;
- `BodyInventoryService` as the sole inventory mutation boundary;
- `LogicalWorldItemService` as the sole world-item store;
- Phase 6 fixed 1/60 physics and static voxel collision ownership;
- Phase 9A block raycast, mutation, event, dirty, and conservation contracts;
- Phase 9B immutable feedback, committed-only particles, and stable-ID visuals;
- Phase 10 immutable UI/render presentation and single crosshair authority;
- OpenGL context-thread, OpenGL 4.1, and GLSL 410 rules.

## Historical Gate 11.2 handoff note (superseded)

At the Gate 11.2 checkpoint, its implementation and focused tests were complete,
interactive game launch and macOS verification were unrun, and no Gate 11.3 work
was included in that checkpoint. Gate 11.3 through 11.5 completion is recorded
above.

Final Gate 11.2 verification:

- `./gradlew.bat :game:test --tests
  com.gaia.worlditem.PhysicalWorldItemCollisionTest --offline` - passed; 11
  tests, 0 failures, 0 errors.
- `./gradlew.bat :engine:test --tests com.overlord.physics.* --tests
  com.overlord.worlditem.* :game:test --tests com.gaia.worlditem.* --offline` -
  passed; 43 tests, 0 failures, 0 errors.
- `./gradlew.bat clean test build --offline` - `BUILD SUCCESSFUL`; 29 tasks,
  1,578 tests, 1,577 passed, 1 existing skipped, 0 failures, 0 errors.
- `git diff --check` - passed (only Git's existing LF/CRLF normalization
  warnings); no files were staged or committed.

## Historical Gate 11.1 stop note (superseded)

At the Gate 11.1 review checkpoint, the pass stopped after independent-review
findings 1 through 9 and did not yet authorize Gate 11.2. Grounding, collision,
sleep, bounds, pickup, and Chunk-freeze work were deferred pending separate
design approval.

That historical restriction was superseded by explicit Gate 11.2 approval and
the subsequent Gate 11.3 through 11.5 completion recorded above.

## Gate 11.2 acceptance-review correction pass

The seven Important acceptance findings were reproduced with focused RED tests
and corrected without adding Gate 11.3 pickup or inventory work:

- production now owns one `PhysicalWorldItemSystem` and advances it exactly
  around the existing `PhysicsWorld` step for each fixed `1/60` update;
- per-body terminal fall speed is clamped after gravity/impulses and before
  displacement, while upward velocity remains unclamped;
- restitution remains contact-normal behavior, and world-item horizontal
  friction is applied only on positive-Y support;
- support classification, 30-step sleep, and deterministic counter reset are
  based on post-contact motion rather than any floor contact at any speed;
- Chunk availability covers the complete current and swept collider, freezes
  new or retained projections before unavailable integration, and recreates
  the same stable ID after reload;
- initial-overlap and world-bound recovery fail closed when no finite in-world
  non-overlapping position exists, leaving canonical logical state unchanged;
- collision, integration, preparation, and writeback failures discard physical
  scratch state, roll back new admissions and staged metrics, and allow a
  deterministic retry from canonical state.

Focused regressions cover render rates 10/60/144/240, zero-step render frames,
terminal speed, floor/wall/ceiling contacts, negative coordinates, one-block
high-speed obstacles, bounce energy loss, exact supported friction, sleep/wake,
current/swept unloaded Chunk boundaries, unload/reload loops, immutable Chunk
revision, blocked recovery at the world top and in a full-height enclosure,
non-finite coordinates, revision-exhausted freezing, later-candidate rollback,
writeback rollback, exact body identity/metrics, and successful retry.

Final verification for this correction pass:

- focused engine physics/world-item and game world-item/production-loop suites
  passed: engine `179` tests and game `72` tests, `0` failures or errors;
- `clean test build --offline` passed with all 29 tasks executed: engine `898`,
  game `678`, and tools `26` tests (`25` passed, `1` existing skipped), for
  `1602` total tests with `1601` passed and `0` failures or errors;
- packaged resources, packaged shaders, installed shaders, and generated UI
  asset verification all passed inside the clean build;
- `git diff --check` passed with only existing LF/CRLF normalization warnings.

No files were staged, committed, pushed, merged, or included in a pull request.

## Branch-wide acceptance-review correction pass

The final branch-wide review findings were reproduced and corrected:

- partial pickup at canonical revision `Long.MAX_VALUE` now detects an
  impossible partial world commit before reserving the world item, reverse
  rolls back every inventory reservation, preserves exact counts, and leaves
  both domains unlocked; full extraction at the same revision still follows
  the terminal removal path;
- frozen admission now checks the same current-plus-next-step swept collider as
  registered bodies, so a missing swept Chunk cannot cause activation/freeze
  revision churn or body construction churn;
- bounded recovery failure now records cumulative failure and current blocked-ID
  diagnostics, suppresses unchanged retries, retries after canonical or
  intersecting Chunk revision changes, and rolls diagnostic changes back when
  a later candidate aborts the preparation batch;
- obsolete Gate 11.1/Gate 11.2 stop text is explicitly historical and no longer
  contradicts the completed Gate 11.3 through Gate 11.5 implementation.

Focused RED/GREEN regressions cover partial and full pickup at maximum revision,
inventory/world lock release, unchanged swept-Chunk freezing, one stable-ID
reload, unchanged fully enclosed recovery, collision-revision retry, exact
recovery diagnostics, and later-candidate diagnostic rollback.

Fresh verification for this correction pass:

- focused `WorldItemPickupTransactionTest`, `PhysicalWorldItemCollisionTest`,
  and `PhysicalWorldItemSystemTest` suites passed with zero failures;
- `:engine:test`, `:game:test`, and `:tools:test` passed;
- `clean test build` executed all 29 tasks: engine `905/905`, game `724/724`,
  and tools `26` passed with `1` existing skip, for `1,655` passed,
  `1` skipped, and zero failures or errors;
- packaged resources, packaged shaders, installed shaders, and generated UI
  assets passed a separate `--rerun-tasks` verification.

No files were staged, committed, pushed, merged, or included in a pull request.

## Phase 11.6 transaction/API closure candidate

- The legacy public Q-drop overload delegates to the single amount-aware
  `COMPLETE_STACK` transaction path, so close state, unresolved barriers, and
  audited spawn reservations cannot be bypassed.
- Inventory cleanup now has a typed rollback resolution. Only exact
  `ROLLED_BACK`/`ALREADY_ROLLED_BACK` outcomes release the active extraction
  lock or clear the owner barrier; conflict, unknown, mismatched identity,
  exception, and `Error` outcomes retain the same reservation for shutdown.
- A rejected block mutation returns ordinary `MUTATION_REJECTED` only after the
  exact reserved spawn is proven rolled back. Every other rollback outcome
  retains a same-stable-ID fatal/unresolved barrier and never allocates a
  second drop.
- `WorldItemSpawnIdentity` is the one public validator for request, reservation,
  committed runtime, audit, resolver, and `APPLIED` construction. The immutable
  reservation stores the resolved pickup-availability tick, and validation
  covers stable ID, stack/count, exact position/velocity, initial revision,
  source, spawn tick, and pickup timing.
- The real `WorldRenderPass` boundary now has a consecutive-frame behavioral
  regression: Frame A masks only selected cell A while same-Chunk B remains;
  Frame B clears the mask and restores A without mutating World/Chunk state.

Blind independent retry or allocation of a replacement stable ID is forbidden.
Audited completion of the SAME reservation with the SAME stable ID is allowed
and required when the reservation is proven PENDING and the commit barrier must
be completed.

Historical Phase 11.6 transaction/API closure verification passed:

- focused spawn/drop/block transaction, public contract, and consecutive-frame
  render suites passed;
- `:engine:test` passed 931 tests;
- `:game:test` passed 810 tests;
- `:tools:test` passed 26 of 27 tests with one existing skip;
- forced packaged-resource, packaged-shader, installed-shader, and generated-UI
  checks executed all 14 tasks successfully;
- `clean test build` executed all 29 tasks: 1,768 tests, 1,767 passed, one
  existing skip, and zero failures or errors.

No files were staged, committed, pushed, merged, or included in a pull request.

## Final Phase 11 documentation closure

Human Windows development acceptance is **PASS** for Q single-item drop,
Ctrl+Q full-stack drop, canonical Survival block drops, physical drop behavior,
no automatic pickup, Shift+right-click manual pickup, break/place transient
feedback, mixed debris/astral particles, walking bob, step-up/down smoothing,
jump/landing presentation, held-block orientation, convex held-block geometry,
and the live exclusion-mask shader/uniform path.

First-person movement presentation advances at fixed 1/60 and interpolates
immutable previous/current snapshots at render alpha. Its 1.8 Hz walk bob is
bounded to `0.025` vertical, `0.012` lateral, and `0.18` degree roll.
Grounded-to-grounded bounded vertical traversal drives step smoothing, while
grounded/airborne transitions drive jump takeoff and impact-speed-scaled
landing. Movement presentation composes before action impulse and never changes
canonical Camera, collision, player-body, or raycast authority.

The held-block runtime defect was geometric visibility state, not UV mapping or
cube geometry. The first-person viewmodel pass had disabled depth testing,
depth writes, and face culling; back/interior triangles could overwrite visible
faces in draw order and make the cube appear concave/open. The final correction
clears only depth before viewmodel rendering, enables depth testing/writes and
canonical back-face culling, then restores the prior GL state. Geometry, UVs,
world rendering, and gameplay are unchanged.

The latest pre-documentation clean verification contains engine `947`, game
`825`, and tools `27` tests: `1,799` total, `1,798` passed, `1` skipped, and
zero failures or errors. The final working-tree inventory is recorded in the
Phase 11 handoff after this documentation-only edit.
