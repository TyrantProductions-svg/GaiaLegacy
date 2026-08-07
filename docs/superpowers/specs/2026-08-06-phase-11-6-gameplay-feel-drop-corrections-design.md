# Phase 11.6 Gameplay Feel and Drop Corrections Design

**Date:** 2026-08-06

**Status:** Approved implementation design

**Branch:** `feat/physical-world-items`

**Baseline:** `origin/main@819a690f85ab4b1a192bd2db3bca73ddb573ced7`

## Purpose

Phase 11.6 corrects two production defects exposed by Windows interactive
testing and adds committed-only gameplay presentation. It preserves the
accepted Phase 11 canonical item, transaction, physics, rendering, threading,
and platform boundaries.

The implementation is divided into four ordered internal gates:

1. 11.6A: Q-drop semantics and block-drop production wiring;
2. 11.6B: committed gameplay feedback and transient block presentation;
3. 11.6C: mixed deterministic debris and astral particles;
4. 11.6D: integration, regression verification, documentation, and handoff.

No later gate begins while an earlier gate has failing focused tests.

## Confirmed production root causes

The Q defect is not an input-repeat defect. `BodyInventoryInputController`
correctly reacts to the Q press edge, but `InventoryDropController` converts
the complete active-slot snapshot into both the extraction reservation and the
world-item spawn request. No Ctrl distinction reaches that transaction.
Consequently every Q press drops the complete stack. The current production
kinematics also spawn at the eye position with a straight `3.0` speed and use
the existing 30-tick global pickup delay, which do not meet Phase 11.6.

The block-drop defect is in `BlockBreakTransaction`: canonical loot is offered
to inventory insertion reservations first, and only an insertion remainder is
reserved for world-item spawn. A normal inventory accepts the count-one loot,
so a successful Survival break produces no logical world item, physical
projection, or stable-ID visual.

## Authority and transaction boundaries

`LogicalWorldItemService` remains the only world-item store and implements the
single `WorldItemSpawnReservations` authority. Q and block-break paths both
reserve future spawn capacity and commit exactly one canonical spawn through
that capability. Neither path directly creates a `PhysicsBody`, renderer
entry, particle-owned identity, or alternative item entity.

`BodyInventoryService` remains the only inventory mutation authority.
Q reserves an exact extraction of one item or the complete active stack,
reserves the matching canonical spawn, then enters a synchronous audited
commit barrier. Spawn commits before extraction; an applied inventory
notification failure is recorded as a committed result and never causes a
second spawn. Pre-commit rejection rolls both reservations back.

Blind independent retry or allocation of a replacement stable ID is forbidden.
Audited completion of the SAME reservation with the SAME stable ID is allowed
and required when the reservation is proven PENDING and the commit barrier must
be completed.

Block breaking resolves the canonical item form from the pre-mutation block
identity, reserves the complete canonical spawn before world mutation, applies
the block mutation once, then commits the reserved spawn once. Creative, air,
unbreakable, and no-item-form paths do not reserve or spawn. Presentation
failure after the mutation and spawn cannot roll either back.

The conservation equations are:

```text
Q: original active-slot count
 = final active-slot count + committed world-item count

Break: canonical produced count
 = committed canonical world-item count
```

## Input and deterministic kinematics

Q is a press-edge action. Without Ctrl it requests exactly one item. Either
left or right Ctrl changes the same press edge to complete-stack mode. Held Q
does not repeat, and later fixed steps in a catch-up batch receive
`heldOnly()`. Loading, blocking UI, focus loss, F1 cursor release, and relevant
mode/lifecycle boundaries discard the destructive edge. Slot selection remains
separate from the destructive drop action.

`WorldItemDropKinematics` is a pure game-owned calculator. It accepts copied
player eye, forward/right vectors, block coordinates, and immutable event
identity; it never mutates Camera or player body state and uses no shared RNG.

Q starts 0.40 block in front of the authoritative eye, with 4.5 blocks/s
forward speed, 1.25 blocks/s added vertical speed, and deterministic lateral
variation within +/-0.15 blocks/s. Q and Ctrl+Q both use a 20-fixed-tick pickup
delay.

A block drop starts at the destroyed block center. Its horizontal direction is
away from the authoritative player position. Its deterministic base/outward
horizontal magnitude is in 1.25..1.75 blocks/s. Independently hashed lateral
variation is applied on the orthogonal horizontal axis and is bounded by
+/-0.20 blocks/s; it is not part of, and cannot redefine, the approved outward
range. Consequently the total horizontal resultant is bounded by
`sqrt(1.75^2 + 0.20^2)`, approximately 1.7614 blocks/s. Upward speed remains
1.40 blocks/s. A degenerate horizontal vector derives a stable angle from event
identity.

## Committed gameplay feedback

The existing game-owned `InteractionFeedbackCoordinator` is the single
committed-feedback coordinator. It receives immutable facts only after the
corresponding gameplay transaction has committed. It never mutates inventory,
World, logical items, physics, raycast, or stable-ID lifecycle.

Ordering is:

```text
placement commit -> placement transition -> PLACE animation
                 -> placement camera impulse -> placement particles

break mutation and spawn commit -> break proxy -> BREAK_SWING animation
                                -> break camera impulse -> break particles

Q spawn and extraction commit -> DROP animation
pickup transaction commit -> pickup particles
```

Runtime feedback exceptions are reported and cleanup continues. They never
retry, compensate, or roll back gameplay.

## First-person action presentation

`FirstPersonActionAnimator` owns only presentation time and a bounded immutable
transform. Its states are `IDLE`, `PLACE`, `BREAK_SWING`, and `DROP`. A new
committed action deterministically restarts the corresponding animation from
time zero. It uses accumulated render time, clamps at the exact duration, and
returns exact identity at completion.

- PLACE: 0.14 s; 35% fast attack then smooth return; maximum downward 0.10,
  backward 0.035, pitch +12 degrees, roll within +/-2 degrees.
- BREAK_SWING: 0.19 s; fast attack and slower recovery; forward 0.10,
  downward 0.05, yaw about 16 degrees, roll about 10 degrees.
- DROP: 0.12 s; short forward release and return, distinct from the others.

Because no held-item renderer exists, `FirstPersonVisualPass` draws the
canonical six-face action item as a small view-space cube. The engine receives
only immutable faces and transform values. Focus loss, F1, blocking lifecycle,
and shutdown immediately clear to identity.

## Render-only camera impulse

`CameraImpulseController` evaluates a deterministic bounded analytic/cubic
presentation envelope. Placement adds about +0.35 degrees pitch and -0.006 vertical
translation. Break adds about +0.55 degrees pitch and deterministic +/-0.14
degrees yaw. Pitch/yaw clamp to +/-1 degree, translation is strongly bounded,
and epsilon snap produces exact zero.

The immutable impulse is applied only to the final render view matrix in
`Renderer`. Canonical Camera yaw, pitch, forward/right vectors, player
orientation, raycast, targeting, and saved state are never changed. Culling
continues to use the canonical Camera view.

## Transient block presentation

`TransientBlockVisualSystem` stores at most 256 presentation transitions, one
per block coordinate. A newer event at a coordinate replaces the older event.
At capacity, the oldest event sequence is evicted deterministically. Capacity
exhaustion affects visuals only.

Placement state starts at scale 0.85 and reaches 1.00 with cubic ease-out over
0.14 s. Break state preserves the pre-mutation six-face appearance, changes
scale 1.00 to 0.72, alpha 1.00 to 0.55, and moves down 0.025 over 0.18 s.

The canonical block mutation and collision take effect immediately. An
immutable bounded coordinate mask is supplied to `WorldRenderPass`; the GLSL
410 world shader discards fragments of cells currently replaced by a transient
proxy. This is a render-only exclusion: it does not modify World, Chunk data,
revision, dirty state, meshing input, collision, raycast, or targeting. The
transition pass reuses the existing atlas, six-face shader, and shared unit
cube. Expiration or close removes every mask and proxy.

## Deterministic particles

The existing `ParticleSystem` remains the only particle system and keeps its
hard limits: 512 total active, 384 LOW occupancy, 128 protected HIGH reserve,
64 requests per fixed step, and 32 particles per request.

Committed break emits 16 HIGH block-debris particles and 4 HIGH astral sparks.
Committed placement emits 6 HIGH dust/debris particles and 2 HIGH astral
sparks. Committed pickup emits 8 HIGH converging particles. Rejected gameplay
emits none.

Debris uses a deterministic stratified golden-angle distribution rotated by
event hash. Sixteen-particle break output spans horizontal quadrants, includes
multiple non-positive vertical velocities, and has a small upward mean rather
than a world-up fountain. Speed is 1.8..2.6 blocks/s, vertical direction is
approximately -0.65..+0.85, gravity is about -12 blocks/s^2, drag is light,
size is 0.045..0.085, and lifetime is 0.28..0.52 s. Atlas face selection varies
deterministically across all canonical faces.

Placement debris uses the committed contact normal to spread primarily along
the face tangent and outward. Pickup particles start around the collection
focus and converge inward. Astral sparks use the existing atlas/palette path,
remain smaller and shorter lived, and never own an ItemStack or stable ID.

## Integration and lifecycle

Phase 11 stable-ID projection, same-ID partial pickup, full terminal pickup,
fixed 1/60 physics, unloaded-Chunk freezing, bounded recovery, ordinary
right-click placement, Shift+right-click pickup, and manual-only pickup remain
unchanged. No proximity pickup or magnetic attraction is added.

Shutdown clears animator, impulse, transient masks/proxies, visual trackers,
particles, physical projections, and GPU resources through existing owner-thread
cleanup. It never deletes canonical world items.

## Non-goals and platform limits

Phase 11.6 does not add body-body collision, rotation, pooling, persistence,
networking, merging, expiry, a second item/particle system, renderer gameplay
mutation, or worker-thread OpenGL. Java source/target remains 17. OpenGL remains
4.1 and shaders remain GLSL 410 without compute shaders or SSBOs.

## Verification

Every behavior change follows RED/GREEN. Gate 11.6A verifies production Q and
break paths plus inventory, world-item, input, and Phase 9A regressions. Gate
11.6B verifies animation, render-only camera impulse, transient presentation,
renderer masks, and placement/break integration. Gate 11.6C verifies exact
particle splits, deterministic distribution, gravity/lifetime behavior, and
existing capacity guarantees. Gate 11.6D runs integrated Phase 11 scenarios,
all module suites, a clean build, forced resource checks, hygiene scans, and a
read-only implementation-session review.

Windows Phase 11.6 interactive acceptance remains NOT RUN until the user
performs it.
