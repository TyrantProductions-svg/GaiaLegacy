# First-Person Movement Presentation and Held-Item Polish Design

## Scope and authority

This pass adds view-only first-person movement presentation and corrects the shared presentation-cube UV convention. Canonical player position, collision resolution, grounded authority, velocity, raycast origin/direction, fixed-step gameplay, block transactions, inventory transactions, world-item transactions, and world mutation remain unchanged.

The existing `PlayerController` signals are sufficient. After each 1/60 fixed movement update, presentation observes an immutable sample containing feet Y, horizontal speed, vertical speed, grounded, and noclip. No physics event or traversal-rule change is required.

## Components and data flow

`FirstPersonMovementState` is the immutable game-side authoritative observation. `FirstPersonMovementPresentation` owns deterministic presentation state and keeps immutable previous/current `FirstPersonMovementVisual` snapshots. Fixed updates advance bob phase and traversal envelopes; render capture linearly interpolates the snapshots using the existing fixed-step render alpha.

`InteractionFeedbackCoordinator` owns and closes the movement presentation component. `GameLoop` submits one movement sample immediately after each `PlayerManager.fixedUpdate`. The coordinator places the interpolated movement visual in `InteractionFeedbackFrame`, separately from the existing `CameraImpulseVisual`.

The renderer composes the view in this order:

1. authoritative camera view;
2. interpolated walk bob, step smoothing, and jump/landing motion;
3. existing break/place action impulse.

Raycasts continue to read the authoritative player body and canonical camera look direction. Presentation matrices never write back to either object.

The held-item pass composes its model in this order:

1. local anchor and approved base orientation;
2. damped movement-presentation response;
3. existing held-item action animation;
4. scale and cube centering.

## Movement behavior and tuning

Walking bob is active only while grounded with meaningful horizontal speed. Its target strength scales with speed and uses a 0.10-second attack and 0.14-second release. The deterministic phase advances at 1.8 Hz at full walking speed. Full-strength limits are 0.025 blocks vertical, 0.012 blocks lateral, and 0.18 degrees roll. Airborne and noclip states target zero. Once idle decay reaches zero, phase and output reset exactly, preventing drift.

Traversal classification is explicit:

- `STEP_UP` and `STEP_DOWN` require previous and current samples both grounded plus a bounded nontrivial vertical feet delta no larger than the approved player step height.
- `JUMP` requires grounded to airborne with positive vertical velocity.
- `LAND` requires airborne to grounded and cannot also be classified as a step.

Step smoothing initially cancels the authoritative grounded vertical delta in presentation space, then eases exactly to zero: 0.13 seconds up and 0.11 seconds down. Repeated steps restart from the current bounded offset and clamp to the player step-height limit, so they cannot accumulate drift.

Jump takeoff uses a bounded 0.018-block response. Landing compression scales monotonically with downward impact speed and is capped at 0.035 blocks; zero impact produces zero compression. Landing recovery lasts 0.16 seconds. All envelopes finish at exact zero.

## Held-item orientation and shared cube UVs

The root defect is in `OpenGlUnitCubeMesh`: its SOUTH, DOWN, WEST, and EAST local V orientation differs from `ChunkMeshBuilder`, while NORTH and UP already match. The held camera prominently sees SOUTH and side faces, making asymmetric textures appear vertically mirrored. Face ordinals and atlas-region resolution are otherwise correct.

The shared unit-cube vertex data will make all six faces match the canonical `ChunkMeshBuilder` UV convention. Because held items, dropped world-item visuals, and transient block proxies share this mesh, the correction applies uniformly without a special texture set or any world-mesh change. Tests use six distinct asymmetric regions and literal per-face UV expectations, then verify all three render-pass consumers upload the canonical ordinal-to-region mapping.

The held block keeps the existing 0.35 scale and uses the approved tuning baseline of approximately -15 degrees pitch, -28 degrees yaw, and -3 degrees roll at the existing anchor. Constants remain local to the pass for visual tuning.

## Lifecycle and tests

Movement presentation resets on transient lifecycle clearing, full clearing, and idempotent close. Closed components ignore updates and always snapshot identity; no post-close trigger can resurrect motion. Action impulses remain separately owned and are tested in composition with nonzero movement presentation.

Focused tests cover zero/grounded/airborne/idle/deterministic bob, grounded-to-grounded step up/down, repeated-step bounds, jump/landing classification, impact-scaled landing cap, render-alpha interpolation, canonical-position immutability, composition with action impulses, close/reset behavior, literal six-face UV orientation, and all shared-cube presentation consumers.

