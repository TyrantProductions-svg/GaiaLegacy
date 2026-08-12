# Phase 14D mining camera impulse adjustment design

## Goal

Reduce the committed block-break camera shake to 20% of its accepted peak
while preserving a subtle deterministic feedback cue.

## Design

`CameraImpulseController` remains the sole owner of the view-only break
impulse. Change only the break pitch peak from `0.275` to `0.055` degrees and
the absolute yaw peak from `0.07` to `0.014` degrees. Keep the `0.20` second
cubic envelope, event-identity direction, restart-without-accumulation policy,
placement impulse, canonical camera state, and all other feedback unchanged.

## Verification

Update the existing exact peak assertions first and observe RED. Then change
the two production constants, rerun `CameraImpulseControllerTest`, the broader
interaction-feedback suite, and full `:game:test`. The final Windows runtime
smoke must confirm the reduced shake subjectively before Gate 14D closes.

## Scope

No settings control, accessibility toggle, new persistence field, renderer
change, animation timing change, or Phase 15 behavior is introduced.
