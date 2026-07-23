# Repository Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a clean, reproducible, cross-platform Phase 0 baseline without adding gameplay features or merging PR #4.

**Architecture:** Keep the existing `engine` and `game` module source trees unchanged. Limit implementation to repository policy, build configuration, CI, architecture reports, and the required handoff. Base all PR salvage conclusions on the fetched `origin/main`, `origin/build/add-windows-wrapper`, and GitHub PR #4 head.

**Tech Stack:** Java 17 source compatibility, Gradle Wrapper 8.5, GitHub Actions, JDK 21, LWJGL 3.3.3, Markdown.

## Global Constraints

- Work only on `chore/repository-baseline`, created from the latest `origin/main`; never modify or push `main`.
- Do not merge PR #4, force-push, or copy code/resources from Terasology, Create, or other projects.
- Keep Java 17 source compatibility and use the Gradle Wrapper; do not add a platform-specific JDK path.
- Preserve macOS OpenGL 4.1 / GLSL 410 compatibility and keep OpenGL/GPU lifecycle calls on the context-owning main thread.
- Do not add gameplay functionality or unrelated refactors.
- Do not expand `ServiceLocator`; document current use and prefer constructor injection or explicit contexts in later phases.
- Do not commit or push in this phase execution; leave a suggested commit and PR description for the developer.

---

### Task 1: Clean repository configuration

**Files:**
- Modify: `.gitignore`
- Modify: `gradle.properties`

**Interfaces:**
- Consumes: Existing Gradle Wrapper and module build files.
- Produces: Platform-neutral Gradle configuration and ignore rules for generated binaries/crash logs.

- [x] **Step 1: Remove the repository-local JDK path**

Delete only:

```properties
org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

Keep the four existing proxy properties unchanged.

- [x] **Step 2: Add required generated-artifact ignores**

Add these patterns without removing existing ignores:

```gitignore
bin/
**/bin/*
*.class*
*hs_err_pid*
*replay_pid*
```

- [x] **Step 3: Confirm no tracked generated binaries remain**

Run:

```powershell
git ls-files | Select-String -Pattern '(^|/)bin(/|$)|\.class($|[^/]*$)'
```

Expected: no matches. If matches exist, remove them only from the index with `git rm --cached`; do not delete local user files.

### Task 2: Add headless cross-platform CI

**Files:**
- Create: `.github/workflows/build.yml`

**Interfaces:**
- Consumes: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, Java 17-compatible sources.
- Produces: Matrix builds on Ubuntu, Windows, and macOS using JDK 21.

- [x] **Step 1: Define the workflow**

Create a `Build` workflow triggered by pushes and pull requests. Use:

```yaml
strategy:
  fail-fast: false
  matrix:
    os: [ubuntu-latest, windows-latest, macos-latest]
```

Check out the repository, install Temurin JDK 21 with Gradle caching, validate the wrapper, and run `clean test build`. Use `gradlew.bat` on Windows and `./gradlew` elsewhere. Do not run `:game` or open GLFW in CI.

- [x] **Step 2: Validate workflow invariants**

Confirm the YAML contains all three runners, JDK 21, wrapper validation, and only the headless build command.

### Task 3: Document repository governance

**Files:**
- Create: `AGENTS.md`

**Interfaces:**
- Consumes: Current `engine` and `game` module boundaries.
- Produces: Repository-local rules for developers and later agents.

- [x] **Step 1: Record module and directory ownership**

Document `engine/` as reusable runtime infrastructure owned by the engine developer and `game/` as Gaia-specific content/bootstrap owned by the game developer. Treat root Gradle, `.github/`, and `docs/` changes as shared and review-sensitive.

- [x] **Step 2: Record branch, test, artifact, and handoff rules**

Include the exact Phase 0 constraints: feature branches from latest `origin/main`, no direct main work, no force-push or forced old-branch merges, Gradle Wrapper commands for Windows/macOS, Java 17 source compatibility, JDK 21 build allowance, no generated binaries, no platform absolute paths, OpenGL 4.1/GLSL 410 and main-thread ownership, dependency provenance, scoped phases, and `docs/agent-handoffs/phase-XX-handoff.md`.

### Task 4: Capture architecture and PR #4 salvage evidence

**Files:**
- Create: `docs/architecture/current-baseline.md`
- Create: `docs/architecture/pr4-salvage-report.md`

**Interfaces:**
- Consumes: Latest `origin/main`, PR #4 metadata/head, and current Java sources.
- Produces: Phase 1 planning baseline without importing PR #4 code.

- [x] **Step 1: Document the current main architecture**

Cover Engine, ECS, EventBus, TaskScheduler, World, Renderer, and Physics. For each, state implemented responsibilities, present limitations, thread/context assumptions, and interfaces later phases must preserve unless deliberately migrated.

- [x] **Step 2: Classify PR #4 contents**

State that PR #4 is open/unmerged, diverged from current main, and has unresolved conflict markers. Separate:

- Source concepts already present on main: configuration constants, module/service/task/event infrastructure, and cross-platform wrapper/runtime support.
- PR-only source files: `GameBootstrap.java` and `GameLoop.java`.
- Main-only source package: current ECS files.
- Reimplementable ideas: bootstrap/loop separation and CPU mesh-data handoff, subject to explicit context injection and main-thread OpenGL ownership.
- Rejectable content: tracked `.class`/`bin` outputs, conflict markers, hard-coded macOS natives/JVM flags, placeholder scheduler behavior, and direct singleton expansion.

- [x] **Step 3: Decide Phase 1 treatment of GameBootstrap and GameLoop**

Recommend a clean reimplementation of the separation, not cherry-picking: immutable/explicit game context, interrupt-aware startup, propagated worker failures, time-based delta, controlled shutdown, and all GLFW/OpenGL resource actions on the context-owning main thread.

### Task 5: Verify and hand off

**Files:**
- Create: `docs/agent-handoffs/phase-00-handoff.md`

**Interfaces:**
- Consumes: All Phase 0 changes and fresh verification output.
- Produces: The required Phase 0 handoff and developer-ready commit/PR guidance.

- [x] **Step 1: Run Windows verification**

Run:

```powershell
.\gradlew.bat clean test build
```

Expected: exit code 0. Do not run `:game` as an automated success criterion because it opens a GLFW window and is interactive.

- [x] **Step 2: Run repository policy checks**

Verify:

- `git status --short --branch` shows only intended Phase 0 changes.
- `git ls-files` finds no tracked `bin` or `.class` artifacts.
- `gradle.properties` has no `org.gradle.java.home` or platform absolute path.
- Workflow YAML contains Ubuntu, Windows, macOS, and JDK 21.
- `git diff --check` reports no whitespace errors.

- [x] **Step 3: Write the handoff**

Record completed work, unfinished work (including macOS verification not run locally), core architecture decisions, changed files, exact commands/results, known risks, and interfaces Phase 1 must not break.

- [x] **Step 4: Produce final evidence**

Run:

```powershell
git diff --stat
git status --short --branch
```

Report test results, suggested commit `chore: establish clean cross-platform repository baseline`, and a concise PR title/body. Do not merge, commit, push, or create a PR.
