# Phase 11 World-Item Acceptance

Date: 2026-08-07

Branch: `feat/physical-world-items`

Baseline: `origin/main@819a690f85ab4b1a192bd2db3bca73ddb573ced7`

## Scope

Automated acceptance covers canonical runtime access, stable-ID projection,
fixed-step physics, unloaded-Chunk freezing, bounded world recovery, manual
pickup, visuals, particles, measurement, and runtime cleanup.

Canonical world-item automatic expiry is deferred. Phase 11 defines physical projection, pickup, unloaded-Chunk freezing and runtime cleanup, but does not define despawn duration or timeout-based stable-ID termination.

No test expects sleeping time, frozen time, failed recovery, or shutdown to
delete or decrement a canonical item.

Blind independent retry or allocation of a replacement stable ID is forbidden.
Audited completion of the SAME reservation with the SAME stable ID is allowed
and required when the reservation is proven PENDING and the commit barrier must
be completed.

## Automated matrix

| Area | Required evidence | Status |
|---|---|---|
| Stable-ID runtime and `REVISION_EXHAUSTED` | Closed contracts, pending lock, full terminal extraction | PASS |
| Projection admission and rollback | Unified stable-ID order, exact bodies and metrics, deterministic retry | PASS |
| Physics | Fixed 1/60, gravity, terminal speed, static collision, bounce, friction, sleep/wake | PASS |
| World availability | Initial-overlap recovery, bounds fail closed, unload freeze/reload same ID | PASS |
| Input and targeting | Shift+right edge, lifecycle/mode gates, ray/AABB, tie, reach, occlusion | PASS |
| Q drop | One item, either-Ctrl complete stack, no held replay, exact rollback | PASS |
| Block drop | Normal-capacity canonical spawn, pre-mutation identity, deterministic motion | PASS |
| Transaction/API closure | Proven rollback barriers, exact spawn runtime identity, same-ID audited completion | PASS |
| Pickup transaction | Full/partial conservation, exact trace, applied failures, fatal guarantee handling | PASS |
| Presentation | Six faces, 0.50 cube, one interpolation, full removal/partial retention | PASS |
| Committed feedback | Exact commit boundary, identity reset, copied-view impulse, 256 transient cap | PASS |
| First-person movement | Fixed-step/render-alpha bob, step smoothing, jump takeoff, impact landing, action composition | PASS |
| Held viewmodel | Convex cube, outward winding, safe near plane, depth/cull state and restoration | PASS |
| Exclusion-mask shader | Production manifest, indexed array bounds, zero/next-frame clearing | PASS |
| Particles | 16/4 break, 6/2 place, 8 pickup, deterministic directions, gravity/lifetime and caps | PASS |
| Shutdown | Stop new work, remove bodies/caches, idempotence, preserve logical items | PASS |
| Profiling | Short deterministic test and full 600/3,600-step fixture | PASS |

Latest pre-documentation automated result: engine `947/947`, game `825/825`,
and tools `26/27` passed with one existing tools skip. Total: `1,799` tests,
`1,798` passed, `1` skipped, `0` failures, and `0` errors. `clean test build` executed all 29
tasks successfully, and the forced packaged-resource, packaged-shader,
installed-shader, and generated-UI verification executed all 14 tasks
successfully.

## Profiling evidence

Command:

```powershell
.\gradlew.bat :tools:profileWorldItems --console=plain --no-daemon
```

Result:

```text
worldItems=1024 particles=512 warmupSteps=600 sampleSteps=3600 peakWorldItems=1024 peakParticles=216 allocationSupported=true allocatedBytes=3679206752 gcCollections=12 gcTimeMillis=27 maxGcPauseMillis=3 simulationHash=-6638820482655353883
```

This is approximately 58.5 MiB/s over the 60-second sample. Allocation rate
and collection count cross the approved review thresholds; the observed maximum
pause does not. Phase 11 does not add pooling. A later approved task must first
attribute churn and keep canonical/public immutable values out of any reuse.

## Manual Windows matrix

Status: **PASS** for Windows development runtime acceptance.

Human-confirmed behaviors:

- Q single-item drop and Ctrl+Q full-stack drop;
- canonical Survival block drops and physical drop behavior;
- no automatic pickup and Shift+right-click manual pickup;
- break/place transient feedback and mixed debris/astral particles;
- walking bob, natural step-up/down smoothing, and jump/landing presentation;
- correct held-block orientation and a convex solid held cube;
- a live exclusion-mask shader/uniform path with no crash.

The movement presentation runs at fixed 1/60 with render-alpha interpolation
of immutable previous/current snapshots. Walk bob is 1.8 Hz with maximum
`0.025` vertical, `0.012` lateral, and `0.18` degree roll. Step smoothing is
grounded-to-grounded; jump takeoff and impact-scaled landing are separate.
Movement presentation composes before action impulse and does not change
canonical Camera/raycast authority.

The held cube previously looked concave/open because the first-person
viewmodel pass disabled depth testing, depth writes, and face culling. That
allowed back/interior triangles to overwrite visible faces in draw order. The
pass now clears only depth before the viewmodel, enables depth testing/writes
and canonical back-face culling, and restores prior GL state afterward.
Geometry, UVs, world rendering, and gameplay remain unchanged.

Windows installed-distribution acceptance is still not run.

## macOS matrix

Status: **NOT RUN**. Native Apple Silicon, Retina, OpenGL 4.1/GLSL 410,
focus/resize, item physics, pickup, particles, and shutdown remain human
acceptance work.

## Explicit exclusions

- canonical world-item automatic expiry or despawn timers;
- body-body collision, rotation, joints, or pooling;
- pickup UI or inventory UI redesign;
- automatic pickup or Shift+right behavior outside the approved Survival path.
