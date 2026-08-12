# Phase 14D Mining Camera Impulse Adjustment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce committed block-break camera shake to 20% of its current peak.

**Architecture:** Preserve `CameraImpulseController` as the view-only impulse authority and change only its two break peak constants. Existing exact tests remain the contract for duration, deterministic direction, restart semantics, and canonical-state isolation.

**Tech Stack:** Java 17, JUnit 5, checked-in Gradle Wrapper.

## Global Constraints

- Break pitch peak is exactly `0.055` degrees.
- Break absolute yaw peak is exactly `0.014` degrees.
- Break duration remains exactly `0.20` seconds.
- Placement feedback and all canonical gameplay state remain unchanged.
- Do not stage, commit, push, create a PR, or merge.

---

### Task 1: Reduce committed break impulse

**Files:**
- Modify: `game/src/test/java/com/gaia/interaction/feedback/CameraImpulseControllerTest.java`
- Modify: `game/src/main/java/com/gaia/interaction/feedback/CameraImpulseController.java`
- Update: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Consumes: `CameraImpulseController.triggerBreak(long)` and `snapshot()`.
- Produces: the same `CameraImpulseVisual` contract with smaller break peaks.

- [ ] **Step 1: Write the failing test**

Change both break-peak assertions to:

```java
assertEquals(0.055f, peak.pitchDegrees(), 1.0e-6f);
assertEquals(0.014f, Math.abs(peak.yawDegrees()), 1.0e-6f);
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.interaction.feedback.CameraImpulseControllerTest --console=plain --no-daemon
```

Expected: only the new peak assertions fail against `0.275` and `0.07`.

- [ ] **Step 3: Write minimal implementation**

Set:

```java
private static final double BREAK_PITCH = 0.055;
private static final double BREAK_YAW = 0.014;
```

- [ ] **Step 4: Run focused and broader tests**

Run the exact focused class, `com.gaia.interaction.feedback.*`, and full
`:game:test`. All must pass with zero failures/errors.

- [ ] **Step 5: Record runtime result and audit**

Update the Phase 14 acceptance record only after human confirmation. Run
`git diff --check` and retain the preserved `dist/` ZIP untracked.
