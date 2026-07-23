# GaiaLegacy Repository Guide

These rules apply to the entire repository. A more deeply nested `AGENTS.md` may add stricter local rules but must not weaken these requirements.

## Module boundaries

- `engine/` contains reusable runtime infrastructure: window/context management, rendering, input, physics, scheduling, events, ECS primitives, and voxel storage/meshing. It must not depend on `game`.
- `game/` contains Gaia-specific startup, block definitions, resources, world generation, and gameplay composition. It may depend on public `engine` APIs.
- Root Gradle files define shared build behavior. `.github/` owns automation. `docs/` records architecture, decisions, provenance, and phase handoffs.
- Keep OpenGL and GLFW ownership explicit. Every OpenGL call and every GPU resource create/upload/destroy operation must run on the main thread that owns the OpenGL context.
- New code should use constructor injection or an explicit context object. Do not expand `ServiceLocator` usage.

## Directory ownership

The two-developer split is role-based:

- Engine developer owns `engine/**`.
- Game developer owns `game/**`.
- Root build files, `gradle/**`, `.github/**`, `docs/**`, `AGENTS.md`, and `.gitignore` are shared. Changes there require awareness from both developers.
- A cross-boundary change must be reviewed by both owners. Avoid mixing engine and game changes unless the phase explicitly requires an interface change.

## Branch and scope rules

- Fetch before starting and create each phase branch from the latest `origin/main`.
- Never work directly on, commit to, or push `main`.
- Do not force-push. Do not force-merge stale branches or unresolved pull requests.
- Keep each phase limited to its explicit scope; do not bundle unrelated refactors.
- Do not merge a pull request on behalf of the developers unless explicitly instructed.

## Build and test rules

- Keep Java 17 source and target compatibility. Builds may run on JDK 21.
- Always use the checked-in Gradle Wrapper. Preserve `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`.
- Windows verification:

  ```powershell
  .\gradlew.bat clean test build
  .\gradlew.bat :game
  ```

- macOS verification:

  ```bash
  ./gradlew clean test build
  ./gradlew :game
  ```

- `:game` opens a GLFW window and is an interactive local smoke test. CI must run `clean test build` only and must not launch the game window.
- Add focused automated tests for new behavior. A successful compile is not a substitute for tests.
- Before handoff, run `git diff --check` and record each verification command and result.

## Cross-platform and graphics rules

- Never commit a personal or platform-specific absolute JDK path. Use `JAVA_HOME`, the active toolchain, or CI setup instead.
- OpenGL code must remain compatible with macOS OpenGL 4.1 and GLSL 410.
- Do not depend on OpenGL 4.3+, compute shaders, or platform-exclusive APIs.
- Do not move GLFW polling, buffer/texture/shader/VAO lifecycle operations, or renderer cleanup to worker threads.

## Repository hygiene and provenance

- Never commit generated output, including `build/`, `bin/`, `.class` files, crash dumps, IDE metadata, or local caches.
- If generated output was accidentally tracked, remove it from the index with `git rm --cached`; do not delete a developer's required local files.
- Do not copy code or assets from Terasology, Create, or any other project. Public architectural ideas may be studied and independently reimplemented.
- Any third-party code that is actually introduced must be recorded with its exact source URL, license, affected files, and source commit.

## Phase handoffs

Every phase must create `docs/agent-handoffs/phase-XX-handoff.md` containing:

- completed work;
- unfinished work;
- core architecture decisions;
- modified files;
- test commands and results;
- known risks;
- interfaces the next phase must not break.

The final phase report must also include `git diff --stat`, test results, a suggested commit message, and a suggested pull request title and description.
