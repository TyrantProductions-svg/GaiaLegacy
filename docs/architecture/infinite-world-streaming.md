# Phase 15 infinite-world streaming architecture

Phase 15 replaces the finite 81-Chunk runtime boundary with deterministic,
bounded, on-demand Chunk streaming. `ChunkRepository` remains the only
resident Chunk authority and `LogicalWorldItemService` remains the only
WorldItem semantic, stable-ID, allocator, TTL, and lifecycle authority.

## Lifecycle and ownership

```text
GlobalPosition + immutable repository observation
  -> pure 2/4/5 desired-set controller (unload hysteresis 7)
  -> bounded detached load/generate or save work
  -> owner-thread ticket/revision/epoch validation
  -> resident Chunk publication
  -> bounded detached CPU mesh
  -> owner-thread GPU upload/destruction

unload:
  prepare and pin Chunk + prepare live WorldItems
  -> detached combined durable staging
  -> one semantic root publication and durable proof
  -> linked rollback-safe WorldItem hibernation
  -> exact Chunk unload-ticket commit
```

Workers never own a repository, lifecycle ticket, callback, mutable gameplay
collection, OpenGL object, or GPU resource. Repository publication, projection
callbacks, mesh upload/destruction, origin publication, and all OpenGL calls
remain on the owning main thread.

## Checked global addressing and origins

`ChunkKey` uses signed integer Chunk coordinates. `GlobalPosition` combines a
safe key with canonical local X/Z doubles in `[0,16)` and finite Y. Negative
world coordinates use `floorDiv`/`floorMod`; unsafe Chunk coordinates fail
closed through `ChunkCoordinatePolicy`.

Canonical gameplay state stays global. `SimulationOrigin` and `RenderOrigin`
are independent engine types whose coordinated publication always names the
same Chunk. Resident physics/render floats are derived from canonical global
values. Rebase prepares every participant first, commits bounded replacements,
and publishes both origins last. UNKNOWN or FAILED space is never treated as
AIR; origin-aware raycast and collision return an explicit availability result.

## Deterministic generation and modified-only persistence

World generation is a pure function of world identity, seed, generator
version/configuration, and `ChunkKey`. An untouched evicted Chunk is regenerated
and hash-compared. An exact deterministic base capture needs no durable payload;
changed bytes are persisted.

Task4 owns generic durable bytes, structural indexes, bounded invisible
prepublication staging, and the single root commit. It does not interpret
WorldItem semantics or allocate IDs. Each physical staging batch remains at
most 64 blobs and 64 MiB. Readers see the last-known-good root until a complete
candidate validates and one final root publication succeeds. Chunk payload,
WorldItem pages/checkpoint, and required session/global checkpoint state become
visible together.

Save v2 preserves the Phase14 save root and world identity. Fresh-target
restore validates identity and restores authoritative world tick, allocator
high-water, one immutable checkpoint/index view, every referenced page and all
duplicate-ID/count/hash/dependency invariants before publishing canonical state
once. v1 readers remain compatible. Lossy v1 writes fail closed when streamed
dormant state cannot be represented.

## WorldItem lifetime and paging

Ground WorldItems have canonical `WORLD_ITEM_TTL_TICKS = 18_000L` at 60 Hz.
`expiresAtWorldTick` is the sole lifetime field. Pause and process downtime do
not advance authoritative simulation tick. At
`currentWorldTick >= expiresAtWorldTick`, semantic death is immediate even if
cleanup is delayed or fails; an expired item cannot reload or resurrect and its
stable ID is never reused.

Only currently live WorldItems are paged. Durable-before-evict is mandatory:
unexpired dirty or unproved state stays resident and pinned. Activation validates
the complete page and duplicate-ID set before publish, then creates projections
transactionally. Linked persistence/hibernate commits validate both tickets and
the backend proof before callbacks; callback failure restores exact logical and
physical state and leaves capabilities retryable.

## Fixed budgets and metrics

| Boundary | Hard budget |
| --- | ---: |
| Desired simulation/render/preload radii | `2 / 4 / 5` |
| Unload hysteresis radius / maximum resident footprint | `7 / 225` |
| Load/generate accepted / active | `32 / 4` |
| Save accepted / active | `8 / 1` |
| Mesh accepted / active | `32 / 2` |
| GPU uploads / destructions per outer frame | `2 / 4` |
| Current-live WorldItems / decoded resident pages | `1,024 / 32` |

Accepted work includes queued, active, and completed-but-not-owner-drained
states. Capacity is released only at an explicit terminal owner drain/discard.
Metrics expose bounded current counters and small diagnostics; they do not keep
unbounded histories or perform ordinary-frame file/index scans.

## Known limits and later extensions

- The safe integer Chunk envelope is finite even though ordinary exploration is
  no longer bounded to a preloaded radius.
- Vertical world height and block-ID storage remain the current fixed engine
  contracts.
- There is no generic database, WAL, MVCC, region-file redesign, background GC,
  or permanent history of expired WorldItems.
- User-facing render-distance configuration, richer progress presentation, and
  additional streaming telemetry are later product work.
- Phase 16 may build gameplay/content on these public boundaries; it must not
  create another Chunk or WorldItem authority.
- Phase 19 may revisit measured storage/layout performance, but only with new
  evidence and without weakening atomic root publication or fail-closed reads.

