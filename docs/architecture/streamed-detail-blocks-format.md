# Streamed `detail-blocks` Format

## Extension ownership

DETAIL uses the existing streamed Chunk root and the reserved `detail-blocks` extension. It creates no second save root.

```text
extension absent                     -> valid FULL-only Chunk
extension present, required, v1      -> decode canonical DETAIL
extension present, unsupported       -> fail closed: UNSUPPORTED_VERSION
extension present but malformed      -> fail closed: CORRUPT
```

A present extension is mandatory to interpret. It is never silently skipped, downgraded to AIR, or partially published. Unrelated streamed extensions retain their existing bytes and deterministic ordering.

During an atomic Chunk plus WorldItem publication, the exact immutable Chunk capture owns the new `detail-blocks` bytes. The page backend may replace or remove only its `world-item-page` extension; it preserves other durable extension owners and must not overwrite captured DETAIL with an older durable extension. Ordinary capture, page upsert, and page removal transactions share this ownership rule.

## Codec v1 framing

The extension descriptor has section ID `detail-blocks`, codec version `1`, and `required=true`. Its payload is big-endian:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII magic `GLD1` |
| 4 | 1 | subdivisions per axis, exactly `4` |
| 5 | 1 | flags, exactly `0` |
| 6 | 2 | unsigned palette count, `1..255` |
| 8 | 4 | unsigned parent-entry count, `1..1024` |
| 12 | variable | palette entries |
| after palette | `74 * parentCount` | parent entries |

Each palette entry is:

```text
u16 UTF-8 byte length, 1..128
N bytes canonical ResourceLocation text
```

Palette entries are strictly increasing by canonical `ResourceLocation` ordering, unique, known to `BlockRegistry`, and non-AIR. Code `0` is reserved for unoccupied/AIR; palette codes are `1..paletteCount`.

Each parent entry is:

```text
u16 parent index
i64 occupancy mask
u8[64] material palette codes in x + 4*y + 16*z order
```

Parent indices are strictly increasing and in the Chunk's current `worldHeight` bounds. The canonical FULL backing byte at every listed parent must be AIR. Occupancy must be nonzero. A set occupancy bit requires a nonzero valid palette code; a clear bit requires code `0`. Duplicate parents, trailing bytes, invalid UTF-8, invalid `ResourceLocation`, unknown material, noncanonical ordering, empty DETAIL, FULL/DETAIL conflict, and truncated input fail closed before repository publication.

## Exact size bound

The fixed header is 12 bytes. The maximum palette contribution is:

```text
255 * (2 length bytes + 128 UTF-8 bytes) = 33,150 bytes
```

Each sparse parent is:

```text
2 parent-index bytes + 8 mask bytes + 64 palette-code bytes = 74 bytes
```

At the canonical cap:

```text
12 + 33,150 + 1,024 * 74 = 108,938 bytes
```

This is below the existing 1 MiB per-extension limit and 18 MiB streamed Chunk payload limit. The production encoder enforces `MAX_V1_ENCODED_BYTES = 108_938`; decode validates size before allocating bounded arrays.

## Exact capture and persistence equality

FULL bytes, ordered DETAIL data, and Chunk revision come from the same immutable `ChunkSnapshot`. Encoding never performs a later callback into a live Chunk. Persistence equality includes canonical FULL and DETAIL bytes; a DETAIL-only mutation cannot be mistaken for a flat-byte no-op.

The Phase 15 durable revision rule remains authoritative: a byte-identical/no-op result does not acknowledge a Chunk revision that was not written. Owner reconciliation still validates the exact unload ticket, revision, state, and durable proof before eviction.

On unload, the owning Chunk, DETAIL storage, collision derivation, CPU mesh state, and GPU lifecycle leave residency together. Reload maps palette `ResourceLocation`s through the current `BlockRegistry` to runtime byte IDs and owner-publishes one canonical Chunk. Phase 14 migration and Phase 15 streamed payloads without the extension remain FULL-only and are not rewritten solely because this codec exists.
