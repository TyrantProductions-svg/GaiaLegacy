# GAIA_GLB_HAND_TOOL_V0

Contract version: 0. Scope: small hand tools and simple props only. This document
freezes the initial profile, NOT global budgets for future characters, terrain,
buildings or generated DETAIL. A Gate A admission receipt is not conformance
approval; semantic enforcement below is explicitly deferred to Gate B.

## Security envelope — Gate A

- GLB 2.0 only; exact little-endian header and declared length; one JSON chunk
  first and at most one BIN chunk second; 4-byte chunk alignment; unknown,
  duplicated, truncated or trailing container chunks rejected.
- Maximum total file bytes: 16 MiB (16,777,216); JSON chunk: 1 MiB (1,048,576).
- Strict UTF-8 JSON object; one root value, no BOM, duplicate keys, comments,
  trailing commas, nonfinite numbers or non-JSON padding.
- JSON maximum nesting 32; tokens 100,000; string/property-name length 4,096;
  numeric literal length 64. Limits apply before JglTF, including ignored extras.
- Reject a decoded field named `uri` anywhere, including extras and escaped
  spellings. No relative, absolute, file, data, network or protocol-relative
  URI exception. This is intentionally more restrictive than general glTF.
- Supported extensions: none. `extensionsUsed` and `extensionsRequired` must be
  absent or empty arrays; `extensions` must be absent or an empty object wherever
  it appears. Wrong declaration type is rejected, including in extras.
- The root `asset.version` must be `2.0`; `asset.minVersion`, if present, must be
  `2.0`. Unknown versions never reach the semantic reader.
- Decode exactly the snapshot which passed preflight using strict Jackson binding
  to JglTF v2 DTOs and its public `GltfAssetV2` constructor. The admission tests
  disqualified JglTF 3.0.1's tolerant reader for untrusted input. No network/file
  reference resolver, image decode or arbitrary accessor expansion during Gate A.
- A JSON `null` element is invalid in every glTF core schema-defined numeric,
  integer or index array. Primitive numeric arrays fail before Jackson can replace
  the element with zero; boxed index arrays must likewise reject rather than retain
  an invalid element. Optional whole-property null behavior is unchanged. Bounded
  arbitrary JSON under `extras`, including nested null values, remains permitted.

## Semantic profile — specified now, enforced in Gate B

| Subject | HAND_TOOL_V0 rule |
|---|---|
| Scene | One default scene; one logical root named `GAIA_ASSET_ROOT`; no cycles, repeated-parent ambiguity, or unreachable model payload |
| Coordinates | Standard glTF right-handed, meters, Y-up; no fixed gameplay working axis |
| Pivot/orientation | Meaningful asset-local pivot and grip/working-axis declaration in receipt/sidecar; must be reviewed against future runtime convention |
| Transforms | Finite affine proper rigid transforms only: translation and rotation permitted; non-unit scale, negative scale, reflection, shear, singularity and matrix/TRS ambiguity rejected; tolerance `RIGID_TRANSFORM_EPSILON = 1e-4` |
| Hierarchy | At most 64 nodes, depth 16 |
| Geometry | At most 8 declared meshes and 16 declared primitives; independent unique and scene-expanded limits of 30,000 vertices and 10,000 triangles each; independent warnings above 4,000 triangles; TRIANGLES only |
| Accessors | Valid ranges/strides/component types; finite positions/normals; indices within bounds; no sparse accessors in v0 |
| Normals | Present and valid; outward orientation where meaningful; open-surface intent requires author review |
| Materials | At most 8; metallic-roughness scalar factors plus optional base-color texture; opaque, single-sided; no normal/occlusion/emissive or other advanced maps in v0 |
| Textures | At most 8 declared textures and 8 declared samplers; embedded bufferView PNG/JPEG only; at most 4 images, each at most 1024x1024; aggregate decoded RGBA at most 16 MiB; UV0 required when textured |
| Deferred | Skins, animation, morph targets, cameras/lights, compression and all extensions |
| Ownership | Project-owned or explicitly approved inputs, no Blender-only procedural dependency after export |

Counts must be checked BEFORE allocating corresponding expanded data. Image
headers and aggregate dimensions must be checked BEFORE decode. Geometry,
hierarchy, material support and image safety are not proven by Gate A preflight.

All root texture and sampler declarations count, including unused declarations.
`MAX_TEXTURES = 8` and `MAX_SAMPLERS = 8` are independent hard limits with no
warning threshold. An omitted texture sampler uses normal glTF defaults and does
not create a declared sampler entry. Count checks precede per-entry projection and
snapshot construction. `MAX_IMAGES = 4` and `MAX_MATERIALS = 8` are unchanged.

## Gate 17.75B controller contract clarification

These decisions resolve the opening Gate B ambiguities. They are controller
clarifications, not implementation-discovered relaxations. Gate A history and
its admission-only receipt semantics are unchanged.

### Two independent geometry accounting domains

`uniqueTriangleCount` sums every declared mesh primitive exactly once, including
primitives unreachable from the default scene. For indexed TRIANGLES use
`indexAccessor.count / 3`; for non-indexed TRIANGLES use `POSITION.count / 3`,
after validating legality and divisibility. `uniqueVertexCount` sums each declared
primitive's POSITION accessor count. This is primitive-domain accounting: shared
POSITION accessors count again for each primitive; no position deduplication.

`expandedTriangleCount` and `expandedVertexCount` sum each reachable default-scene
node-to-mesh-to-primitive instance. Mesh reuse contributes again for every node,
without copying geometry arrays. Invalid references and cycles fail independently.
Unreachable declared geometry still contributes to unique accounting but not to
expanded accounting; this does not waive the existing unreachable-payload rule.

Both unique and expanded domains independently require triangles <= 10,000 and
vertices <= 30,000. Either hard-limit excess fails. Each triangle counter > 4,000
and <= 10,000 produces its own deterministic warning. Both warning facts remain
observable. A unique warning never replaces an expanded hard-limit failure.
All additions/products use checked arithmetic before allocation or expansion.

### Proper rigid transforms and one validation tolerance

`RIGID_TRANSFORM_EPSILON = 1e-4` is an absolute validation tolerance, owned by the
versioned profile. It never authorizes correction, normalization, orthogonalization,
snapping or rewriting source transforms.

TRS translation components must be finite and may be nonzero. Quaternion components
must be finite and the quaternion norm must differ from one by no more than epsilon.
Omitted scale defaults to (1,1,1); every authored scale component must satisfy
`abs(component - 1) <= epsilon`. Non-unit positive and negative scales fail.

A matrix must be finite and affine in glTF's column-major convention. Its
homogeneous row must approximate (0,0,0,1); each rotational basis vector's length
must approximate one, each pairwise dot product zero, and the 3x3 determinant +1,
all within the same absolute epsilon. Translation need only be finite; neither
translation nor rotation must be identity. Scale, reflection, shear and singularity
fail. Matrix and authored TRS are mutually exclusive under the glTF node rule.

There is no artist-intent exception for reflection and no requirement to prove
Blender applied transforms, inspect a Blender scene, supply a sidecar or declare
a fixed hand-tool working axis in Gate B. Gate C owns export-workflow evidence.

## Gate 17.75B controller contract clarification — primitive attribute allowlist

The exact v0 primitive attribute allowlist is POSITION, NORMAL and TEXCOORD_0.
POSITION and NORMAL are required. UV0 is optional without a texture and required
for a supported base-color texture; valid unused TEXCOORD_0 remains permitted.
Omitted baseColorTexture.texCoord defaults to zero; explicit zero is permitted.
Any nonzero set fails with UNSUPPORTED_TEXTURE_COORDINATE_SET, never UV0 fallback.

Every legal COLOR_n fails with UNSUPPORTED_VERTEX_COLOR and a deterministic
primitive/attribute JSON-pointer path, for example
`/meshes/0/primitives/0/attributes/COLOR_0`. TANGENT, TEXCOORD_n above zero,
JOINTS_n, WEIGHTS_n, custom attributes such as _ATTRIBUTE and all other attributes
outside the allowlist fail with UNSUPPORTED_ATTRIBUTE. Malformed core glTF
semantic syntax is INVALID_GLTF_ATTRIBUTE, not merely an unsupported feature.
Reject unsupported semantics before unnecessary accessor payload expansion.

A PASS/PASS_WITH_WARNINGS snapshot must preserve all supported rendering-relevant
attribute data. Unsupported attributes cannot be silently discarded, baked,
rewritten or downgraded to warnings; failures publish no validated snapshot.
This is a controller clarification, not a relaxation or a restart of Gate A.

## Gate B implementation tolerances and output interpretation

The profile owns NORMAL_LENGTH_EPSILON = 1e-4 (absolute Euclidean normal-length
error relative to one). TRIANGLE_AREA_EPSILON = 1e-12 square meters is the minimum
accepted length of the triangle edge cross product (twice triangle area). No
geometry/normal correction is performed. Local winding requires a positive dot
between the face cross product and the sum of its three vertex normals; this is
not proof of global outward orientation, watertightness or artistic intent.
Rigid-transform tolerance remains the separate, already frozen 1e-4 rule.

POSITION min/max must be present and metadata bounds well formed, but actual
bounds are computed from decoded positions and transforms, never copied from
declared extrema. Images are straight RGBA8, top-to-bottom rows; PNG chunk CRC,
container completion and critical-chunk recognition supplement JDK pixel decode.
Metadata for all images passes dimension/aggregate checks before pixel decoding.
No extracted images are written to disk.

The headless CLI accepts `[--json] <local.glb>`. Exit 0 means PASS/WARN, 1 means
admission/model FAIL and 2 means usage/operational IO failure. Canonical JSON has
profile/version, admitted source SHA-256, counts, bounds, diagnostics and truncation.
Pre-admission failures report a null hash, not a hash of a partial or reread file.
No failed model publishes a validated snapshot. CLI reports are not human approval.

## Gate B controller-authorized Image Profile v0 clarification

This replaces the earlier generic PNG/JPEG admission description. It is a strict
Gaia subset; unsupported profiles are not described as invalid PNG/JPEG.
Canonical base-color pixels are straight RGBA8 sRGB, top-to-bottom. No host or
arbitrary embedded color-management transform participates in snapshot creation.

PNG_V0: 8-bit RGB/RGBA, standard compression/filter methods, non-interlaced,
bounded dimensions. Exactly IHDR, consecutive IDAT and IEND carry decoded data.
Optional sRGB (intent 0-3), gAMA=45455 and cHRM=(31270,32900,64000,33000,30000,
60000,15000,6000) may declare sRGB before IDAT; each may appear once and is removed
from the memory-only decode stream. No other chunks are admitted (including
optional palettes, tRNS, ICC, cICP, Exif and text). CRC, ranges and ordering are
checked without per-metadata-entry objects. MAX_PNG_CHUNKS=256, including IHDR/IEND;
256 legal chunks may pass, 257 fails.

JPEG_V0: baseline sequential DCT, 8-bit, three JFIF Y/Cb/Cr components, valid
SOI/EOI and bounded dimensions. Minimal JFIF APP0 is first, version 1.00-1.02,
valid density fields and no thumbnail/extension. Only baseline frame, quantization,
Huffman, restart-interval, scan and entropy/restart markers are otherwise allowed.
Progressive, grayscale, CMYK/YCCK, ICC APP2, Exif APP1, Adobe APP14, COM and other
APP data fail before JDK decode. MAX_JPEG_MARKERS=256 (including SOI/EOI/restarts).
Encoded-byte bounds also bound entropy/fill scanning. No new decoder dependency.

Each images[i] reserves width*height*4 once, decodes at most once and contributes
one snapshot image. Repeated texture/material references do not re-decode, copy or
recharge it. Distinct image declarations count separately even for identical bytes.
All container/profile/dimension checks and aggregate reservations precede any
generic decoder invocation or pixel allocation. Only validated metadata-neutral
memory input reaches standard JDK readers; no ImageIO disk cache. Accepted PNG
samples are copied exactly (RGB alpha=255); accepted JPEG yields sRGB RGB samples
without ICC/Adobe transforms. No generic color-managed getRGB extraction.

References: [PNG specification](https://www.w3.org/TR/png-3/#11sRGB) and
[JFIF specification](https://www.w3.org/Graphics/JPEG/jfif3.pdf).

All declared scene roots must be parentless, though only the default scene is
expanded. Custom attributes retain the underscore-prefix core syntax distinction
and are still unsupported. Diagnostic identities are deduplicated before display
shortening; any information loss sets truncated=true. Text output visibly escapes
C0/C1/DEL/U+2028/U+2029 controls without altering canonical diagnostics.

## Receipt / sidecar semantics (design, not a new game save format)

Record contract ID/version; exported GLB SHA-256; optional local `.blend` source
SHA-256; Blender/exporter/script versions; authored unit statement; asset-local
pivot position and working/grip frames; ownership/provenance; validation version
and result; human approval. Do not encode generated/player provenance for drops.
Working direction must be an explicit asset-local vector/frame, not an implicit
global +Y tool policy. Sidecar schema/runtime held transform binding is deferred.

Authoring files are NOT declared tracked production assets. No `.blend` enters
Git or LFS by this contract. Export staging is not Git staging. Temporary GLB
fixtures and reports must not be mistaken for approved game resources.

## Frozen runtime boundaries

One ChunkRepository, FULL/DETAIL exclusivity, no empty DETAIL, nine snapshots per
ChunkMeshInput and separate ChunkMeshingClaim; one raycast, collision, mesh/GPU,
item identity, WorldItem and BlockInteractionController authority. Preserve
reserve-before-mutate, APPLIED reconciliation and typed adjacent placement.
No generated/player provenance. Hybrid mesh 8,388,608 bytes, global CPU mesh
134,217,728 bytes, accepted <=32, active <=2, upload <=2/frame, destruction
<=4/frame, aggregate publication/upload <=2/frame. This tooling changes none.
