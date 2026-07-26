# GaiaLegacy

![A fog-filled GaiaLegacy valley framed by rocky highlands and voxel trees](docs/images/gaialegacy-hero.png)

> **Build a world you must inhabit.**

GaiaLegacy is an experimental physical voxel survival project focused on embodied choices, deterministic exploration, and systems that meet at clear technical boundaries.

The public repository is currently a **pre-alpha Milestone 1 foundation**, not a finished game. The source build offers a finite generated world to explore with fixed-step movement and a modernized voxel render pipeline. Inventory, block interaction, world items, persistence, and the survival loop remain under development.

## Current public build

| Area | Status |
| --- | --- |
| Deterministic 81-Chunk world | **Available** |
| Plains, hills, rocky highlands, trees, entrances, connected caves | **Available** |
| Fixed 1/60-second player physics, collision, stepping, ground snap, noclip | **Available** |
| GLSL 410 lighting, AO, sky, fog, frustum culling, render metrics | **Available** |
| Data-driven block/material/atlas resources | **Available** |
| Interaction, inventory, and world-item API contracts | **In development** |
| Player-facing breaking, placement, inventory, HUD, saves, survival | **Planned / not playable** |

Documentation baseline: [`main@1ae90ef`](https://github.com/TyrantProductions-svg/GaiaLegacy/commit/1ae90efdf7fe512bf1101d62557ec878862bab13), checked 2026-07-26.

## Build and run

Requirements:

- Git
- JDK 21 recommended for running Gradle
- Java 17 source/target compatibility
- OpenGL 4.1-capable desktop graphics

Windows:

```powershell
.\gradlew.bat clean test build --console=plain --no-daemon
.\gradlew.bat :game --console=plain --no-daemon
```

macOS:

```bash
./gradlew clean test build --console=plain --no-daemon
./gradlew :game --console=plain --no-daemon
```

The latest Windows baseline passed 893 automated tests and development/installDist interactive acceptance. Latest native macOS/Retina acceptance is **NOT RUN**; the OpenGL and build architecture intentionally target macOS compatibility, but this is not a substitute for native evidence.

## Controls

| Action | Binding |
| --- | --- |
| Move | `W A S D` |
| Look | Mouse |
| Jump | `Space` |
| Toggle noclip | Double-tap `Space` |
| Descend in noclip | `Left Shift` |
| Release/recapture cursor | `F1` |
| Exit | `Escape` |

## Project map

- [Wiki home](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki)
- [Current status](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki/Current-Status)
- [Getting started](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki/Getting-Started)
- [Architecture overview](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki/Architecture-Overview)
- [Roadmap](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki/Roadmap)
- [Contributing and testing](https://github.com/TyrantProductions-svg/GaiaLegacy/wiki/Contributing-and-Testing)

The Wiki is the detailed source for project state, player guidance, architecture, limitations, provenance, and future direction.

## Repository boundaries

- `engine/`: reusable runtime, rendering, physics, input, events, scheduling, ECS primitives, resources, voxel storage and meshing
- `game/`: Gaia startup composition, resources, world generation, and future gameplay integration
- `docs/`: architecture decisions, plans, references, and phase handoffs

OpenGL/GLFW and GPU lifecycle operations stay on the context-owning main thread. CPU world generation and meshing may run on workers and publish results through explicit revisioned boundaries.

## License and provenance

GaiaLegacy currently has **no published project-level reuse license**. The repository's third-party dependencies retain their own licenses, documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), but those licenses do not grant permission to reuse GaiaLegacy's own code, documentation, screenshots, or original artwork.

No Terasology, Create, Minecraft, or other third-party project code or art is copied into GaiaLegacy. See [docs/references.md](docs/references.md) for the provenance statement.
