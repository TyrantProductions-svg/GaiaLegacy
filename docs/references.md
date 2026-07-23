# References and Provenance

## Architectural references

GaiaLegacy consulted the public
[Terasology project](https://github.com/MovingBlocks/Terasology) only for
high-level modular and data-driven architecture ideas. Phase 2 independently
implements its namespaced identifiers, explicit classpath indexes, immutable
registries, validation, diagnostics, and engine/game boundary. No Terasology
source code or assets were copied.

Create was not used as an implementation source. No Create source code,
models, textures, or other art were copied into GaiaLegacy.

The approved project-local Phase 2 design and execution plan are:

- [Resource, Material, and Data-Driven Asset System Design](superpowers/specs/2026-07-23-resource-material-system-design.md)
- [Resource, Material, and Data-Driven Asset System Plan](superpowers/plans/2026-07-23-resource-material-system.md)

## Software dependencies

The software libraries actually declared or resolved by the Gradle build,
including their declared or resolved versions, purpose, licenses, and official
sources, are enumerated in
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

Phase 2 introduced no copied third-party source code or third-party art. The
purple-and-black missing-texture tile is original GaiaLegacy project work.
