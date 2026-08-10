# GaiaLegacy

![A fog-filled GaiaLegacy valley framed by rocky highlands and voxel trees](docs/images/gaialegacy-hero.png)

> **Build a world you must inhabit.**

GaiaLegacy is an experimental physical voxel survival project focused on
embodied choices, deterministic exploration, and systems with explicit
technical ownership. Milestone 1 is a pre-alpha vertical slice rather than a
finished game. The current Phase 13 working branch adds a product shell,
persistent user settings, and the first cross-platform audio foundation around
that vertical slice.

## Milestone 1 vertical slice

The current release-candidate branch integrates:

- a deterministic finite 81-Chunk world with plains, rolling terrain, rocky
  highlands, trees, surface entrances, and connected caves;
- exact 1/60-second player and world-item physics;
- Survival and Creative block breaking and placement;
- a transactional left-hand, right-hand, and mouth inventory;
- canonical physical drops, Q/Ctrl+Q dropping, and Shift+right-click manual
  pickup with no automatic pickup;
- crack, transient block, held-item, world-item, particle, and HUD
  presentation;
- first-person walk bob, step smoothing, jump/landing response, action
  impulses, and a depth-correct held-block viewmodel;
- OpenGL 4.1 / GLSL 410 rendering with VSync enabled by default.

World/save persistence, crafting, mobs, and the broader survival economy are
not implemented. The application now launches to a Main Menu with New World,
disabled `Load World - Available in Phase 14`, Settings, Controls, and Quit.
Gameplay has an explicit Pause Menu and return-to-menu confirmation.

## Product shell and audio

- Settings persist VSync, FOV, mouse sensitivity, invert-Y, Chunk radius,
  Master/Music/SFX volume, mute-when-unfocused, default game mode, and debug-HUD
  default through a versioned platform settings file.
- VSync, FOV, look settings, and audio apply after explicit Apply; Chunk radius,
  default mode, and debug-HUD default are captured by the next New World/session.
- Gaia plays as the Main Menu and exploration theme with startup fade, pause
  ducking, and focus mute/recovery. Legacy is packaged and registered for future
  explicit use rather than forced into normal exploration.
- Music credit: **Leo Deng (Leosteeeve) and David Li (Omi Hurricane)**. See
  [audio provenance](docs/audio-provenance.md).

## Requirements

- Git for obtaining the source;
- JDK 21 recommended for the Gradle runtime;
- Java 17 source and target compatibility;
- an OpenGL 4.1-capable desktop GPU and driver;
- Windows x64/arm64 or Apple Silicon macOS support through the selected LWJGL
  native artifacts.

The checked-in Gradle 8.5 Wrapper is the supported build entry point. Do not
replace it with a machine-installed Gradle distribution.

## Clean-clone build

Windows PowerShell:

```powershell
git clone https://github.com/TyrantProductions-svg/GaiaLegacy.git
Set-Location GaiaLegacy
.\gradlew.bat clean test build --console=plain --no-daemon
```

macOS:

```bash
git clone https://github.com/TyrantProductions-svg/GaiaLegacy.git
cd GaiaLegacy
./gradlew clean test build --console=plain --no-daemon
```

## Development run

Windows:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

macOS:

```bash
./gradlew :game:run --console=plain --no-daemon
```

The macOS application configuration supplies `-XstartOnFirstThread` because
GLFW owns the native window and OpenGL context on the main thread.

## installDist run

Windows:

```powershell
.\gradlew.bat :game:installDist --console=plain --no-daemon
.\game\build\install\game\bin\game.bat
```

macOS:

```bash
./gradlew :game:installDist --console=plain --no-daemon
./game/build/install/game/bin/game
```

## Release defaults

| Setting | Milestone 1 default |
| --- | ---: |
| World seed | `12345` |
| World-generation algorithm | `2` |
| Finite loaded-world radius | `4` Chunks |
| Loaded demo area | `81` Chunks |
| Vertical FOV | `70.0` degrees |
| Mouse sensitivity | `0.1` |
| VSync | enabled, swap interval `1` |
| Debug HUD | disabled |
| Initial game mode | Survival |
| Master / Music / SFX | `100%` / `65%` / `100%` |
| Mute when unfocused | enabled |

The Chunk radius is a finite demo-world loading boundary. Milestone 1 does
not implement a configurable streaming distance or a separate
distance-culling system. VSync affects presentation only; simulation remains
fixed at exactly 1/60 second.

## Demo world and route

The release candidate uses seed `12345` and algorithm `2`. The production safe
spawn observed during Windows acceptance is player feet `(0, 25, 0)`.

Deterministic inspection coordinates:

| Feature | Coordinate |
| --- | ---: |
| Plains tree | `(-33, 33)` |
| Rolling-hills tree | `(0, 12)` |
| Rocky outcrop | `(-50, 8)` |
| Surface cave entrance A | `(-22, 24, 3)` |
| Surface cave entrance B | `(-6, 23, -21)` |
| Deep reachable cave | `(67, 2, -64)` |
| Cross-Chunk tunnel air | `(48, 57, -45)` |

A representative gameplay loop is:

```text
spawn -> move and jump -> select slots 1/2/3
-> Survival break -> physical canonical drop
-> Shift+right-click manual pickup -> place
-> Q single-item drop -> Ctrl+Q full-stack drop
-> F4 Creative -> F4 Survival -> Escape
```

## Controls

See [CONTROLS.md](CONTROLS.md) for mode rules and developer-only inputs.

| Action | Binding |
| --- | --- |
| Move / look | `W A S D` / mouse |
| Jump | `Space` |
| Toggle noclip | double-tap `Space` |
| Break / place | left mouse / right mouse |
| Manual pickup | `Shift` + right mouse |
| Select body slot | `1` / `2` / `3` or mouse wheel |
| Drop one / full stack | `Q` / `Ctrl+Q` |
| HUD / debug HUD | `F2` / `F3` |
| Survival / Creative | `F4` |
| Pause/resume / route back | `F1` / `Escape` |

## Platform acceptance

Milestone 1 Phase 12 acceptance is historical:

| Platform | Automated | Development runtime | installDist runtime |
| --- | --- | --- | --- |
| Windows | fresh clean build PASS: 1,805 tests, 1 skipped, 0 failed | interactive gameplay and rapid-break correction PASS | continuous 20-minute gameplay soak PASS |
| Apple Silicon macOS | clean-clone build and packaged-resource/shader checks PASS | interactive gameplay, Retina, resize, and focus recovery PASS | continuous 26-minute gameplay soak PASS |

Apple Silicon macOS acceptance used a MacBook Air with Java 26 and the exact
RC commit `477945913cbeffbf7886b7eed0f152519a4f120b`; the macOS version was not
supplied. The F3 FPS/frame-time numeric ghosting reproduced and remains an
accepted debug-only known issue. Detailed platform evidence is recorded in
[the Phase 12 handoff](docs/agent-handoffs/phase-12-handoff.md).

Phase 13 cross-platform acceptance is complete on exact implementation
candidate `a16855c19082a09f21bd53389cd24f711bd13f0e`:

| Platform | Automated/native | Development runtime | installDist runtime |
| --- | --- | --- | --- |
| Windows | PASS: 2,248 total, 2,247 passed, 1 skipped, 0 failed/errors | PASS, 10 minutes | PASS, 7 minutes |
| Apple Silicon macOS | HUMAN-REPORTED PASS | HUMAN-REPORTED PASS | HUMAN-REPORTED PASS |

The macOS tester used an Apple Silicon MacBook Air / native arm64 with Java 26
and reported the complete requested Gate 13D checklist passing. Exact macOS
version, JUnit totals, raw logs, runtime durations, and audio-device details
were not supplied and are not claimed. See the
[Phase 13 acceptance matrix](docs/testing/phase-13-product-shell-audio-acceptance.md).

## Architecture map

- `engine/`: reusable window/context, rendering, input, audio/OpenAL, physics,
  scheduling, events, ECS primitives, resources, voxel storage, and meshing;
- `game/`: Gaia startup composition, block/resource definitions, deterministic
  generation, transactions, feedback, HUD, and gameplay orchestration;
- `tools/`: deterministic project-local asset generation tools;
- `docs/`: architecture decisions, specifications, plans, testing records,
  and phase handoffs.

OpenGL/GLFW and GPU lifecycle operations stay on the context-owning main
thread. CPU world generation and meshing may run on workers and publish only
through the established revisioned boundaries. See
[the current architecture baseline](docs/architecture/current-baseline.md) and
[the Phase 13 product-shell/settings/audio architecture](docs/architecture/product-shell-settings-audio.md).

## Known issues and deferred work

See [KNOWN_ISSUES.md](KNOWN_ISSUES.md). Phase 14 owns real save discovery and
world persistence. Crafting, mobs, expanded content, key rebinding, and broader
accessibility remain later Milestone 2 work. Gaia and Legacy are authored
source assets with verified runtime OGG derivatives; source MP3s are excluded
from runtime packaging.

## License and provenance

GaiaLegacy currently has no published project-level reuse license. Dependency
licenses and project asset provenance are recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Those dependency licenses do
not grant permission to reuse GaiaLegacy's own code, documentation,
screenshots, or original artwork.

No Terasology, Create, Minecraft, or other third-party project code or art is
copied into GaiaLegacy. See [docs/references.md](docs/references.md).
