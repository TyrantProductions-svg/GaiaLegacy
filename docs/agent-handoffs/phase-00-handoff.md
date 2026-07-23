# Phase 00 Handoff: Repository Baseline

## Completed work

- Fetched `origin` and created `chore/repository-baseline` from the refreshed `origin/main` commit `1f260e1`.
- Confirmed the starting worktree had no uncommitted changes.
- Read the latest 15 commits. No pre-existing `AGENTS.md`, README, or `docs/architecture` files existed on current main.
- Compared current main with `origin/build/add-windows-wrapper` and GitHub PR #4 without merging or cherry-picking it.
- Removed the hard-coded macOS `org.gradle.java.home` from `gradle.properties`.
- Added ignore rules for `bin/`, nested bin output, `.class` files, JVM crash logs, and replay logs.
- Confirmed current main tracks no `bin` or `.class` artifacts; no `git rm --cached` action was necessary.
- Preserved the complete Gradle Wrapper.
- Added a headless GitHub Actions build matrix for Ubuntu, Windows, and macOS using Temurin JDK 21 and `clean test build`.
- Added repository-wide module boundaries, two-role directory ownership, branch/test/platform rules, artifact policy, dependency provenance, and handoff requirements in `AGENTS.md`.
- Added the current architecture baseline and PR #4 salvage report.
- Added the required Phase 0 execution plan and this handoff.
- Made no Java source or gameplay changes and introduced no third-party code or assets.

## Unfinished work

- The branch has not been committed, pushed, or opened as a pull request.
- GitHub Actions has not run because this branch has not been pushed.
- A native macOS clean-clone build was not available in this Windows session; the new macOS CI job is the pending verification.
- `.\gradlew.bat :game` was not run because it opens an interactive GLFW window. The headless build compiles and packages the game module, but a developer should still perform the local window smoke test.
- The repository currently contains no `src/test` sources. Phase 0 changes only policy/configuration/documentation, so no runtime test was added; later behavior changes require focused tests.
- PR #4 remains open and unmerged.

## Core architecture decisions

- `origin/main` at `1f260e1` is the only source baseline for subsequent work. PR #4 is evidence, not an integration branch.
- `engine` remains reusable infrastructure and must not depend on `game`; `game` composes and depends on engine APIs.
- Phase 1 should independently reimplement the useful `GameBootstrap`/`GameLoop` separation rather than cherry-pick the conflicted PR files.
- Worker threads may generate world and CPU mesh data. GLFW calls and all GPU resource create/upload/destroy/render operations remain on the main thread that owns the OpenGL context.
- New composition should use constructor injection or a tightly encapsulated explicit context and must not expand `ServiceLocator`.
- CI is headless and runs `clean test build` only. Interactive `:game` remains a developer smoke test.
- Java sources stay Java 17-compatible while local/CI builds may use JDK 21.

## Modified files

- `.gitignore`
- `gradle.properties`
- `.github/workflows/build.yml`
- `AGENTS.md`
- `docs/architecture/current-baseline.md`
- `docs/architecture/pr4-salvage-report.md`
- `docs/superpowers/plans/2026-07-23-repository-baseline.md`
- `docs/agent-handoffs/phase-00-handoff.md`

No Java source file was modified.

## Test commands and results

### Starting baseline

```powershell
.\gradlew.bat clean test build
```

Result before configuration cleanup: failed because the repository supplied `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` as an invalid Windows Java home.

### Final Windows verification

```powershell
.\gradlew.bat clean test build
```

Result: exit code 0.

Fresh verbose verification:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
```

Result: `BUILD SUCCESSFUL in 9s`; 11 actionable tasks executed. Both `:engine:test` and `:game:test` reported `NO-SOURCE`, and both modules compiled and packaged successfully. Gradle selected `natives-windows`. The runtime was Microsoft OpenJDK 21.0.11 LTS.

### Repository policy checks

```powershell
git ls-files
Select-String -Path gradle.properties -Pattern 'org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\'
git diff --check
git status --short --branch
```

Results:

- no tracked `bin` or `.class` artifacts;
- no platform-specific Java home in `gradle.properties`;
- workflow contains Ubuntu, Windows, macOS, JDK 21, and `clean test build`, with no `:game` invocation;
- no Java source changes;
- `git diff --check` passed;
- current branch is `chore/repository-baseline`.

### Not run in this environment

```powershell
.\gradlew.bat :game
```

```bash
./gradlew clean test build
./gradlew :game
```

The first is an interactive Windows window smoke test. The latter two require macOS; the headless build will be covered by the macOS CI matrix job after push.

## Known risks

- There are no automated tests in the current baseline, so the successful build proves compilation and packaging but no behavioral coverage.
- The GitHub Actions YAML has not yet been exercised by GitHub-hosted runners.
- Current runtime risks documented in `current-baseline.md` remain unchanged, notably scheduler target routing, mutable world threading, global service coupling, and renderer mesh ownership/double-cleanup.
- PR #4 contains unresolved conflict markers, 25 tracked `.class` artifacts, cross-platform build regressions, and incomplete scheduler logic. It must not be merged as-is.
- The raw unstaged `git diff --stat` excludes new untracked files until they are staged; use `git status --short` with the modified-file list above when reviewing the complete scope.

## Interfaces Phase 1 must not break

- Java 17 source/target compatibility and use of the checked-in Gradle Wrapper.
- Cross-platform LWJGL native selection and macOS-only `-XstartOnFirstThread`.
- OpenGL 4.1 / GLSL 410 compatibility.
- Main/context-owning thread control of GLFW and all GPU resource lifecycle operations.
- `engine` must remain independent from `game`.
- Current `Engine.init()`, `shutdown()`, subsystem getters, and `submitToCore(...)` call surfaces unless replaced through an explicit, tested migration.
- EventBus queued delivery through an explicit `processAll()` pump unless deliberately migrated.
- World coordinate mapping, 16-by-16 chunk footprint, sparse subchunks, and byte block IDs.
- `PhysicsManager(Camera, World)` constructor-injection boundary.
- No expansion of `ServiceLocator`; new startup/loop code should receive explicit dependencies.

## Suggested commit and pull request

Suggested commit message:

```text
chore: establish clean cross-platform repository baseline
```

Suggested PR title:

```text
chore: establish clean cross-platform repository baseline
```

Suggested PR description:

```markdown
## Summary

- remove the repository-local macOS JDK path and ignore generated binaries/crash logs
- add JDK 21 headless builds for Ubuntu, Windows, and macOS
- document module ownership, repository rules, the current architecture, and PR #4 salvage decisions

## Verification

- `.\gradlew.bat clean test build`
- `.\gradlew.bat clean test build --console=plain --no-daemon`
- no tracked `bin` or `.class` artifacts
- `git diff --check`

## Notes

- PR #4 was analyzed but not merged
- no Java source or gameplay behavior changed
- macOS and Ubuntu verification will run in GitHub Actions
```

Do not merge the pull request automatically.
