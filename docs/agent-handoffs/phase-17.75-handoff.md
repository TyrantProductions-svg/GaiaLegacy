# Phase 17.75 handoff — Gates A/B/C/D approved; Gate E local closure

Current controller checkpoint: Gates A/B/C/D are CONTROLLER APPROVED / CLOSED.
Gate E is authorized only for final local end-to-end closure; Git integration and
Phase 18 remain NOT AUTHORIZED. The controller accepted Gate D after its directed
grid/GL/lifecycle correction and real Windows interaction. The viewer consumes
Gate B snapshots only; no runtime GLB support or gameplay asset replacement was
added. Gate E replays the existing exporter/validator/viewer chain without adding
a feature or changing a frozen contract.

Historical review after the original authorized 2/2 bounded correction cycles was
**0 Critical / 2 Important / 1 Minor**: diagnostic-grid float intermediate
overflow, one-read GL error handling and missing direct non-owner `close()` test.
The controller then authorized one exact additional cycle. The current code uses
checked double-to-float grid generation with optional unsafe-grid omission, fully
drains pre-existing/candidate/cleanup GL errors with a defensive bound, and has
direct exact-once owner/non-owner close coverage. Fresh permanent evidence is
viewer 62/62, Gate B 310/310 and ordinary tools 54 passed / 1 existing abort.
Three closure reviews are 0 Critical / 0 Important / 0 Minor, and the final
read-only skeptical reviewer returned `NO ADDITIONAL EVIDENCE-BACKED FINDING`.
Interactive evidence is never inferred from unit tests.

## Historical Gate C closure (preserved)

Gate C bounded ephemeral Blender export reached CLOSED / READY FOR CONTROLLER APPROVAL.
Four independent domain reviews and relevant closure re-reviews are0C/0I/0M;
the final static skeptical review returned NO ADDITIONAL EVIDENCE-BACKED FINDING.
At that checkpoint Gates D/E, viewer and Phase18 remained NOT AUTHORIZED.
Gate C evidence is in dedicated notes/provenance below.

## Gate C current record

- Windows Blender 5.1.2 / official glTF exporter 5.1.20 / Python 3.13.9.
- Explicit owned temporary scene and direct GAIA_ASSET_WORKSPACE only. No `.blend`
  saved; original unsaved Cube/Camera/Light scene remains unchanged.
- Ruler/tool exported twice each, exact same-environment bytes. Four separate
  headless Gate B CLI validations PASS; meter-scale actual bounds verified.
- Deliberately scaled ruler produces Gate B FAIL; no second semantic validator.
- Fresh Inspector 310/310; tools 54 passed /1 existing skip; pure Python 13 passed
  /1 local symlink-permission skip. Real Blender checks counted separately.
- Primary review found one Important (effective OBJECT material overrides) and
  one deduplicated Minor (link-test coverage). One bounded RED/GREEN closure now
  rejects OBJECT overrides and strengthens link/junction coverage. Final accepted
  runs are acceptance-003/rejection-002; independent closure re-review complete.
- Automatic closure cycles1/2. Final tools:check45s and Inspector13s GREEN.
  Protected files/index unchanged, staged area empty; no Git integration.
- No viewer, engine/game changes, dependency/add-on or runtime asset. No Git write.
- [Gate C plan](../superpowers/plans/2026-09-02-phase-17.75-gate-c.md),
  [Gate C notes](../superpowers/implementation-notes/2026-09-02-phase-17.75-gate-c.md),
  [authoring provenance](../model-authoring-provenance.md),
  [workflow](../../tools/blender/README.md).
- Frozen next-gate boundaries: Gate B remains sole semantic authority; no fixed
  tool working axis; source assets are ephemeral, not an approved `.blend`/LFS
  policy. Viewer/runtime use requires separate approval and must consume validated
  data without owning a second item/material/gameplay registry.

## Historical Gate B handoff (preserved)

Status: Gate 17.75A controller-approved; Gate B CLOSED / READY FOR CONTROLLER
APPROVAL. The historical 1 Critical, 3 Important and 2 Minor findings are closed.
Following the interrupted fifth review, the controller authorized one replacement
static-only skeptical review; it completed with NO ADDITIONAL EVIDENCE-BACKED
FINDING. Four primary reviews remain 0C/0I/0M. Fresh permanent tests are GREEN:
310 Inspector; ordinary tools 54 passed / 1 existing skip. Earlier blocked records
below are preserved as history, not the current Gate B decision.
The historical vertex-color stop below is resolved by controller clarification.
Phase 17.75 is NOT complete, committed, merged or released. Gates C/D/E and the
viewer are NOT authorized. No Phase 18 implementation. Gate A evidence below is
historical and is not a Gate B completion claim.

## Baseline / revised architecture

Primary checkout on `codex/phase-17-75-contract-admission`, based on main
`d3a219767b2a16c6f4e5f7514019c56ca9cf6cf7`. Ordinary local branch only.

All six controller revisions are incorporated:

1. Standard glTF coordinates; no universal +Y hand-tool working direction.
   Pivot/grip/working orientation is asset-local sidecar/authoring intent.
2. Explicit single logical root `GAIA_ASSET_ROOT`, to be checked in Gate B.
3. Bounded GLB/JSON security preflight before JglTF types/decoding. Reject URI,
   data/network references and unsupported extensions first. JglTF is not the
   security boundary.
4. `.blend` local authoring/staging only; no approved Git/LFS/source-asset policy.
5. `GAIA_GLB_HAND_TOOL_V0` is a hand-tool/simple-prop profile, not global budgets.
6. Gate A admission only; no full semantic validator in A and no viewer in A/B.

One isolated `tools` source set, no engine/game/LWJGL inheritance, and one isolated
test suite included by normal `check`. No new registry, renderer or game authority.
JglTF 3.0.1 and resolved Jackson artifacts are pinned by version and tested SHA-256.

## Important dependency finding

JglTF's tolerant `GltfAssetReader` logged and swallowed a malformed property despite
the throwing error callback. The failed admission assertion was retained and
fixed through strict Jackson binding to PUBLIC JglTF v2 DTOs and `GltfAssetV2`
construction after preflight. No upstream patch or reflection. No untrusted
`GltfModels.create`; only a trusted project-owned triangle compatibility test.
Admission is explicitly **not** full contract validation or asset approval.

## Original verification / review history (before scalar closure)

- Fresh `:tools:modelInspectorTest :tools:test --rerun-tasks --console=plain
  --no-daemon`: exit 0, 38s, **53/53 inspector**, **54 tools passed / 1 skipped**,
  zero failures/errors. Combined 107 passed, 1 skipped.
- Full engine/game test suites and full multi-hour wrapper matrix NOT RUN; no
  runtime changes justify them in this gate. Existing tools regressions ran.
- `:tools:check --dry-run`: new suite is included in standard verification.
- Dependency graphs: no inspector dependency in engine/game/tools.main.
- Verbatim JglTF license resource and existing Jackson embedded notices checked.
- Java `--release 17` plus dependency bytecode compatibility PASS on JDK21;
  actual JDK17, Linux, macOS: NOT RUN. No OpenGL/native application launched.
- RED/GREEN commands and the reader investigation:
  [Gate A notes](../superpowers/implementation-notes/2026-09-02-phase-17.75-gate-a.md).
- Independent scoped review: **0 Critical / 0 Important / 0 Minor** after one
  documentation qualification (source IOException versus bounded parser/decode
  errors), confirmed by the reviewer. No production correction was required.
- Final `git diff --check` and separate untracked-file whitespace checks PASS.
  Tracked `git diff --stat`: `.gitattributes` +2, `tools/build.gradle` +38;
  **2 tracked files / 40 insertions / 0 deletions**. Git diff does not include
  the 17 new files listed below; they were audited separately, not staged.
- Quarantine and dist hashes re-read and match the exact values below. Index
  empty; only the primary worktree exists. No forbidden artifact in intended
  inventory; existing dist remains untracked. New output is under ignored build/.
- Production inspector source scan found no absolute local paths, engine/game
  runtime imports or native graphics imports. No code changed after final tests.

## Historical Gate A intended file inventory

Modified tracked files:

- `.gitattributes` — preserve exact JglTF license bytes, one path only.
- `tools/build.gradle` — isolated configurations/source sets/tests and check wiring.

New source/test/resources:

- `tools/src/modelInspector/java/com/gaia/tools/model/GlbPreflight.java`
- `tools/src/modelInspector/java/com/gaia/tools/model/GlbJsonPreflight.java`
- `tools/src/modelInspector/java/com/gaia/tools/model/GlbAdmission.java`
- `tools/src/modelInspector/java/com/gaia/tools/model/PreflightException.java`
- `tools/src/modelInspector/resources/META-INF/licenses/jgltf-LICENSE.txt`
- `tools/src/modelInspector/resources/model-inspector/dependencies.properties`
- `tools/src/modelInspectorTest/java/com/gaia/tools/model/GlbFixtures.java`
- `tools/src/modelInspectorTest/java/com/gaia/tools/model/GlbPreflightTest.java`
- `tools/src/modelInspectorTest/java/com/gaia/tools/model/GlbJsonSecurityTest.java`
- `tools/src/modelInspectorTest/java/com/gaia/tools/model/GlbAdmissionTest.java`
- `tools/src/modelInspectorTest/java/com/gaia/tools/model/DependencyAdmissionTest.java`

New documentation:

- `docs/superpowers/specs/2026-09-02-phase-17.75-model-inspector-design.md`
- `docs/superpowers/plans/2026-09-02-phase-17.75-gate-a.md`
- `docs/architecture/gaia-glb-hand-tool-v0.md`
- `docs/model-inspector-dependency-provenance.md`
- `docs/superpowers/implementation-notes/2026-09-02-phase-17.75-gate-a.md`
- `docs/agent-handoffs/phase-17.75-handoff.md`

Total intended inventory: **19 files** (2 tracked modifications, 17 new files).
Tests generate tiny fixtures in memory, not production model resources. Build
outputs stay ignored. No asset export/production resource admission in this gate.

## Safety / frozen interfaces

User-owned state remains excluded; never stage/restore/delete it:

| File | Required SHA-256 |
|---|---|
| game/src/main/java/com/gaia/world/streaming/ChunkStreamingMetricsRecorder.java | EBBAAD69942CE0F8504BD37DA1B2AF7878BCC19EA38D21A27C788B4C1F7ABB51 |
| game/src/test/java/com/gaia/session/ChunkStreamingSessionIntegrationTest.java | D8C6D9DCC01707809C6414AFA9F11C4D2501CD30748A145897E8F65BA594782C |
| dist/GaiaLegacy-v0.2.0-alpha.1-windows-x64.zip | FC7D521BD7DCFBC3142E0153C82A0F63FFFC2301483FE119EBB1D372F35228D2 |

No staging, commit, push, PR or merge; no worktree creation. Blender scene and
user authoring files were not touched. No runtime Blender/MCP dependency.

All Phase16/17 mutation, inventory, persistence, one-authority and mesh resource
contracts remain frozen. Phase17.5 typography/UiRenderer and ATLAS_REGION chisel
presentation remain unchanged. See the contract's frozen runtime boundaries.

## Historical Gate A remaining work / risks

- Gate B requires separate approval: graph/accessor/geometry/material/image
  semantic validation and safe expansion, deterministic diagnostics/CLI.
- Future authoring gate needs explicit scene/save/export authority; viewer needs
  separate owner-thread GPU/lifecycle design and approval. No viewer in A/B.
- Admission does not infer meters/pivot/working direction or outward normals from
  unannotated geometry. Do not turn local .blend receipts into a tracking policy.
- Future distribution must retain all dependency licenses/notices. JglTF's reader
  callback issue and recent 3.x API require re-admission when upgrading.
- Input-stream IO errors are not sanitized by this library layer; future CLI owns
  user-safe IO diagnostics. No filesystem browsing or network input API exists.

Suggested future commit (not authorized/executed):
`feat(tools): admit isolated hand-tool GLB contract boundary`

Suggested future PR title: `feat(tools): establish Gate 17.75A GLB admission`

Suggested PR summary: tools-only isolated JglTF dependency, strict bounded
preflight and public DTO adapter, project-owned triangle/adversarial fixtures,
HAND_TOOL_V0 contract, exact licensing/hash receipt; no runtime/viewer/Blender
changes. Use this gate's actual tests, not a Phase17.75 completion claim.

## Independent parallel review and strict scalar closure

The later four-reviewer audit did NOT approve Gate A: deduplicated outcome was
**0 Critical / 1 Important / 0 Minor**. The earlier scoped review and 53-test
verification above are historical evidence, not a claim that this audit passed.
The Important finding was lossy Jackson scalar coercion after bounded preflight:
fractional integer fields and numeric/boolean String fields could receive receipts.

The controller authorized a narrow centralized mapper correction plus end-to-end
regressions. `ACCEPT_FLOAT_AS_INT` is disabled and Textual Integer/Float/Boolean
coercions explicitly fail; the existing general scalar-coercion prohibition remains.
No Gate B semantics, optional-null policy, public receipt contract or dependency
version changed. A receipt still means admission/mapping only, not asset approval.

Focused RED: 34 tests, 10 intended failures, exit 1 (9s). Focused GREEN: the same
34 tests passed, exit 0 (10s). Fresh `:tools:modelInspectorTest :tools:test
--rerun-tasks --console=plain --no-daemon`: **81 inspector passed**, **54 ordinary
tools passed / 1 existing skip**, zero failures/errors, exit **0**, **38s**.
All 14 tasks executed. Details and scalar coverage are in the Gate A notes.

JDK21 Windows and release17/dependency-bytecode evidence passed; real JDK17,
Linux and macOS remain **NOT RUN**. No Blender/native viewer was launched.
The three post-fix reviews and bounded follow-up are recorded below; no Gate B
work is authorized.

### Automatic bounded follow-up: cycle 1

The first post-fix security review found one remaining Important mapping issue;
the contract and test-adequacy reviews had no findings. Quoted special doubles,
blank scalar strings and packed primitive-array strings bypassed ordinary
coercion flags. Permanent REDs reproduced all three before correction.

The same mapper now rejects blank-to-scalar coercion and privately decorates the
pinned Double/double-array deserializers with token-shape guards. It still delegates
numeric conversion, allocation and null behavior; no per-field checks, secondary
parser authority, semantic validator, new dependency or array-copy buffer was added.

Latest focused result: **48/48**, exit **0**, **9s**. Latest complete Gate A/tools
verification with `--rerun-tasks`: **95 inspector passed**, **54 tools passed / 1
existing skip**, zero failures/errors, exit **0**, **35s**, all 14 tasks executed.
The earlier 81-test result remains historical.

All three independent read-only cycle-1 reviewers (strict mapping security,
contract/regression, test adequacy) returned **0 Critical / 0 Important / 0 Minor**.
The security reviewer exercised 224 bounded probes through the production entry
point with zero unexpected results; these are separate from the 95 permanent
inspector tests. Main-agent synthesis found no remaining finding. One of the two
authorized automatic correction cycles was used; no further correction is needed.

The private primitive-array guard relies on the pinned Jackson deserializer's
`nextToken` loop. Preserve its end-to-end regression coverage and repeat dependency
admission on any future library upgrade. Valid numeric representations and existing
null behavior are retained; admission remains distinct from semantic validation.

Final closure total: **0 Critical / 0 Important / 0 Minor**. JDK21 Windows,
release17 compilation and dependency-bytecode evidence remain PASS; real JDK17,
Linux and macOS remain NOT RUN. Controller approval and Gate B remain separate.
No staging, commit, push, PR, merge, Blender operation or runtime integration occurred.

## Gate 17.75B controller contract clarification

The controller resolved both opening blockers: unique declared-primitive and
default-scene-expanded geometry have independent 10,000-triangle / 30,000-vertex
hard limits and independent >4,000-triangle warnings. Shared POSITION accessors
count per primitive; unreachable geometry counts uniquely but not as a scene
instance and does not bypass the unreachable-payload rejection.

Transforms are proper rigid only, using one absolute epsilon of 1e-4 for authored
unit scale, quaternion norm, matrix basis lengths/dots, determinant +1 and affine
constants. Translation and rotation need not be identity; no source correction,
author-intent reflection exception, working axis or sidecar requirement is added.
See the updated normative contract and Gate B plan. No Gate B PASS is claimed.

## Gate B partial execution / new contract stop

Tasks 1-5 have local TDD evidence: profile accounting/bounded diagnostics,
package-local same-path admission handoff, rigid transforms, buffer/accessor
ranges and bounded decode, and scene/root/instance traversal. Latest inspector
run: 158/158, zero failures/errors/skips, exit 0, 10s. These are partial-gate tests,
not complete semantic-validation or independent-review evidence.

Task 6 audit found `COLOR_0` is neither explicitly allowed nor forbidden by the
frozen profile. It is a core vertex attribute that changes base color, not one of
the explicitly forbidden maps/extensions. Accepting, rejecting or discarding it
cannot be chosen silently. Work stopped for controller clarification. No new
vertex-color rule was added; the two already resolved ambiguities remain resolved.

Remaining: actual geometry/bounds, material/images, immutable validated snapshot,
validator coordinator, CLI/reports/determinism, full tools:check and independent
reviews. No Blender, viewer, engine/game change or Git integration occurred.

## Gate 17.75B controller contract clarification — primitive attribute allowlist

Controller approved exactly POSITION/NORMAL/optional TEXCOORD_0. All COLOR_n are
unsupported errors with no snapshot; TANGENT, other UV sets, JOINTS/WEIGHTS and
custom attributes also fail. Base-color textures require UV0 and texCoord zero
(including its omitted default). Valid unused UV0 remains allowed. Malformed
semantics are invalid glTF. The snapshot cannot silently discard relevant data.
The contract/plan were updated before resuming TDD. The stop above remains history.

## Gate B resumed implementation — pre-review verification evidence

Tasks 6-10 now provide attribute fidelity, bounded actual geometry and instance
accounting, immutable PBR/texture values, metadata-first embedded PNG/JPEG decode,
owned snapshot data, deterministic reports and a thin headless one-file CLI.
No asset is granted artistic or production approval by a PASS result. No Blender
export, viewer or runtime GLB path exists.

Fresh `:tools:modelInspectorTest --rerun-tasks --console=plain --no-daemon`:
247 passed, no failures/errors/skips, exit 0, 15s. This includes two separate
headless JVM CLI processes with byte-identical canonical JSON.
Actual `:tools:check --rerun-tasks --console=plain --no-daemon`: exit 0, 48s,
19 executed tasks; 247 inspector passed, ordinary tools 54 passed / 1 existing
skip, deterministic existing UI resource checks GREEN. This is not the multi-hour
engine/game test matrix; none was claimed or required for the isolated tool.

Invocation: checked-in wrapper `:tools:modelInspector --args="--json <local.glb>"`.
Exit 0 PASS/WARN, 1 model/admission failure, 2 usage/IO failure. Only the explicit
file is opened; no reference resolution, watching, traversal or GUI. The canonical
report excludes local paths/timestamps/raw exception text. Pre-admission failures
have no trusted source hash and report null, not a fabricated hash.

JDK21 Windows (21.0.11) and release17/dependency bytecode checks PASS.
Real JDK17, Linux and macOS execution remain NOT RUN. Inspector/CLI execute with
`java.awt.headless=true`; normal tools retains its existing separate runtime graph.
This evidence predates the independent findings below and does not approve Gate B.

## Gate B independent review — BLOCKED

All four read-only seats completed. Main-agent source verification retained every
finding: **1 Critical / 3 Important / 2 Minor**. No fifth skeptical review was needed.
No automatic correction cycle was started (0/2); no production/test fixes followed
review because the controller's Critical hard-stop rule applies.

| Severity | Finding | Current implementation |
|---|---|---|
| Critical | Palette-PNG ancillary metadata can exceed the pixel-allocation budget; JDK ignoreMetadata does not prevent retention | EmbeddedImages.java:51,62,71,87 |
| Important | Nondefault scene root entries can name a parented node | SceneChecks.java:46-48 |
| Important | JPEG ICC and grayscale getRGB conversion can alter published texture colors | EmbeddedImages.java:62-68 |
| Important | Untrusted attribute diagnostic paths inject terminal controls into text reports | ValidationReportWriter.java:35 |
| Minor | Valid custom attribute names outside an ASCII suffix regex are misclassified as invalid core syntax | GeometryChecks.java:59 |
| Minor | Truncated paths collapse distinct diagnostics without marking truncation | ValidationReport.java:16,44 |

Image issues were verified against installed JDK21 source and bounded decoder-only
in-memory probes. A 1x1 palette PNG with 10,000 ancillary chunks retained 10,000
metadata entries despite ignoreMetadata=true. No large/OOM execution or completed
whole-validator image exploit probe is claimed. Reporting probes did exercise the
complete validator/writer path. Full evidence, scope and proposed minimal closure
directions are in [Gate B notes](../superpowers/implementation-notes/2026-09-02-phase-17.75-gate-b.md).

Existing 247/247 Inspector and 54 passed / 1 skipped ordinary tools results remain
truthful but do not cover these new failures. No changes to frozen attribute,
budget or rigid-transform decisions were made to accommodate implementation.
Gate A was not restarted. Gates C/D/E, runtime loading and Blender remain untouched.

Current intended Phase 17.75 file inventory is 46 files: 2 tracked modifications
(.gitattributes and tools/build.gradle), 16 inspector production Java files,
18 test/helper Java files, 2 inspector license/hash resources and 8 documentation
files. The 44 new files are untracked. Two user-owned quarantine modifications and
the pre-existing dist ZIP are separate and excluded. Nothing was staged/committed.

Suggested future commit (NOT authorized now):
`feat(tools): validate bounded hand-tool GLB semantics`

Suggested future PR title: `feat(tools): add headless HAND_TOOL_V0 validator`

Suggested PR summary, only after separately authorized finding closure: isolated
semantic validation, explicit attribute fidelity, independent geometry budgets,
proper rigid transforms, bounded image/geometry snapshot and deterministic CLI;
no viewer, runtime GLB or Blender integration. Include actual closure review and
test evidence, not a claim that the current blocked implementation is admitted.

**GATE 17.75B — BLOCKED.** Next action is controller review of the Critical and
remaining findings, not Gate C implementation.

## Subsequent controller-authorized image/review closure

The controller authorized all six findings and froze Image Profile v0. PNG is
8-bit noninterlaced RGB/RGBA with only exact optional sRGB declarations; JPEG is
3-component 8-bit baseline minimal JFIF. Arbitrary metadata, profiles and other
color types are rejected before ImageIO; work caps are 256 PNG chunks / JPEG
markers. PNG decode bytes contain only IHDR/IDAT/IEND; no disk extraction/cache.
All image definitions pass container/dimension/aggregate admission before pixels;
each images[i] decodes/charges once. Raw sRGB component samples form RGBA8 without
getRGB conversion. No image decoder dependency was added.

All scene roots are checked against one global parent relation, with default-only
expansion. Text control escaping is centralized. Custom attributes remain
unsupported but use correct core classification. Diagnostic identities are kept
bounded and distinct before display shortening; any information loss is indicated.

Fresh closure :tools:modelInspectorTest --rerun-tasks: 309/309, exit 0, 13s.
Fresh :tools:check --rerun-tasks: exit 0, 46s, 19 tasks executed; Inspector309,
ordinary tools54 passed/1 existing skip; zero failures/errors. Independent JVM
JSON, Gate A regression/isolation/license/bytecode and existing UI resources GREEN.
Windows JDK21 headless rerun; real JDK17/Linux/macOS remain NOT RUN.

Four NEW read-only reviews completed. One additional Important was independently
confirmed during synthesis: duplicate root indices in nondefault scene.nodes.
Automatic cycle1 added a full-validator RED (11 scene cases / 1 intended failure),
then a per-scene 64-bit uniqueness bitmap. Scene/geometry GREEN and independent
re-review closed it. Each of the four final seats is now **0C/0I/0M**; cycles used
**1/2**. Image review also independently ran 20 existing cases and bounded
10,000-chunk probes: rejected, decoder calls=0, no snapshot, no OOM experiment.

After the last source correction/cleanup, both complete commands were rerun:

- `.\gradlew.bat :tools:modelInspectorTest --rerun-tasks --console=plain --no-daemon`:
  **310/310**, exit 0, 13s, no failures/errors/skips.
- `.\gradlew.bat :tools:check --rerun-tasks --console=plain --no-daemon`:
  exit 0, 46s, 19 tasks executed; Inspector310, ordinary tools54 passed/1 existing
  skip, zero failures/errors. Headless independent JVM report comparison,
  release17/dependency/license/hash/admission and deterministic UI checks GREEN.

The required final skeptical reviewer was dispatched but the platform safety
check stopped its turn before a verdict. It did not identify a new verified code
finding, and its failure is not counted as a successful review. No bypass retry
was attempted. **GATE 17.75B — BLOCKED** solely on this incomplete required review;
controller direction is needed before final approval. No Gate C may begin.

Current intended inventory: 50 files (2 tracked diffs + 48 untracked files), plus
the separate unchanged two quarantined modifications and existing dist ZIP.
Tracked-only stat: 2 files / 52 insertions / 0 deletions, excluding new files.
Protected hashes and index match the opening audit; staged area is empty.
No engine/game, Gate A admission, dependency, Blender or viewer change in this
closure. Windows JDK21/headless PASS; real JDK17/Linux/macOS NOT RUN. No Git
integration. The historical review/test records above remain intact.

## Final controller resolution — static skeptical review completed

One new independent reviewer performed only static contract/source/test/diff
inspection, with no execution, generated inputs, probes, downloads or edits.
It covered the controller's twelve requested architecture/fidelity/boundedness
areas and returned **NO ADDITIONAL EVIDENCE-BACKED FINDING**. Skeptical review:
**PASS / no additional finding**. The conditional platform-interruption waiver
was not used. The earlier interrupted review remains recorded without a verdict.

Fresh final evidence, rerun by the main agent on unchanged production/test sources:

- `.\gradlew.bat :tools:modelInspectorTest --rerun-tasks --console=plain --no-daemon`:
  exit 0, **14s**, **310/310**, 0 failures/errors/skips.
- `.\gradlew.bat :tools:check --rerun-tasks --console=plain --no-daemon`:
  exit 0, **47s**, 19 tasks executed; Inspector310, ordinary tools54 passed/1 existing
  skip, zero failures/errors; deterministic existing UI resources GREEN.
- Independent headless JVM canonical JSON byte comparison, Gate A admission,
  image boundary, snapshot immutability, dependency isolation, JAR/license hashes
  and Java17 bytecode checks GREEN; compilation remains `--release 17`.

Four primary independent reviewers: **0C/0I/0M each**. Final total: **0C/0I/0M**.
Additional automatic self-fix cycles remain **1/2**; no correction was required
by this final static review. Only these notes/handoff were updated this checkpoint.
No source/test/resource/dependency change; no new Gate A or image-profile rule.

Final expected inventory remains 50 Phase17.75 files (2 tracked diffs + 48 new
files), excluding unchanged quarantine/dist. Index unchanged, staged area empty,
`git diff --check` PASS; no forbidden generated artifacts or absolute production
paths. No Git integration, engine/game modification, Blender or viewer work.
Windows JDK21/headless PASS; real JDK17/Linux/macOS remain NOT RUN.

**GATE 17.75B — CLOSED / READY FOR CONTROLLER APPROVAL**

STOP before Gate 17.75C. Phase 17.75 as a whole is not complete or integrated.

## Gate 17.75E local end-to-end replay — BLOCKED by final review

Gate E replayed the exact approved authority chain without adding a feature:

`ephemeral Blender workspace -> bounded exporter -> exact GLB SHA-256 -> Gate A -> Gate B -> ValidatedModelSnapshot -> Gate D viewer -> human inspection`.

Blender 5.1.2 / `io_scene_gltf2` 5.1.20 exported the project-owned ruler and
simple-tool fixtures twice from identical ephemeral state. The before/after scene
fingerprint was identical and no `.blend` was saved. Ruler exports are 2,192 bytes,
SHA-256 `359c1fba2c1411ab2874f560e85569fd29a80133ff101373b7ea6edf05ccdb4f`;
simple-tool exports are 3,924 bytes, SHA-256
`902e992711fec6d895ab7e1cc0a875a12f65164d33dbd11fb4960f8e1561d752`.
Both same-state pairs are byte-identical and PASS Gate B in separate headless Java
processes. Exact report/receipt hashes are recorded in the Gate E notes and authoring
provenance; every receipt remains `asset_approval=NOT_GRANTED`.

Direct local evidence on JDK 21 is Inspector 310/310, Viewer 62/62, ordinary tools
54 passed/1 existing abort, Blender Python 13 passed/1 OS symlink skip, and fresh
`javac --release 17` for all Inspector/Viewer sources. Each required Gradle wrapper
command (`modelInspectorTest`, `modelViewerTest`, `tools:check`, `clean test build`)
was attempted and was blocked before task execution/test discovery by the known
host `Unable to establish loopback connection` condition. These commands are
INFRASTRUCTURE BLOCK, not PASS and not repository test failures.

The real Windows viewer opened the exact validated simple-tool GLB. The user
confirmed model visibility, orbit, pan, zoom, grid/bounds/wireframe toggles, resize,
Alt+Tab/focus recovery and valid reload. A known invalid GLB produced Gate B FAIL
while CURRENT remained current=1/candidate=0 with unchanged GPU resource counts.
After the exact valid tool was restored, the user confirmed successful recovery and
clean close. This is observed human evidence.

Final inventory: 2 intended tracked modifications, 100 intended untracked Phase
17.75 paths, 2 separate quarantined tracked modifications and 1 excluded dist ZIP.
The staging area is empty. Quarantine/dist/index hashes and `git diff --check` remain
unchanged/PASS. Accidental Blender `__pycache__` outputs were removed and are not an
integration input.

Four read-only Gate E reviews completed. Main-agent verification retained two new
Important findings outside Gate E's correction authority:

1. nested JSON `null` values in glTF primitive-backed numeric arrays can reach the
   DTO binding without strict token rejection and become zero-valued elements;
2. HAND_TOOL_V0 lacks texture/sampler count budgets while all declared textures are
   retained and the viewer allocates one GPU sampler per snapshot texture.

The build/dependency/license review was clean. Documentation inventory/handoff gaps
were corrected without production changes. The end-to-end authority reviewer also
raised receipt metadata/approval wording; controller-required synthetic fixture
receipts intentionally remain NOT_GRANTED, while unit/pivot/orientation evidence is
recorded in authoring provenance and the sidecar/runtime held-transform schema is
explicitly deferred, so this is not counted as a Gate E defect. With two verified
Important findings, no conditional skeptical review ran and no Gate E self-fix was
attempted.

Final review counts: end-to-end authority 0C/0I/0M after re-evaluation;
security/resource boundary 0C/2I/0M; build/dependency/license 0C/0I/0M;
scope/evidence/documentation 0C/0I/0M after documentation recheck. Deduplicated
Gate E total: 0 Critical / 2 Important / 0 Minor.

**PHASE 17.75 — BLOCKED.** Controller resolution is required for strict nested-null
token handling and HAND_TOOL_V0 texture/sampler resource limits. Do not integrate or
begin Phase 18.

## Gate E controller-authorized contract closure

The controller authorized one narrow closure cycle for the two final-review
Important findings above. Gate A now rejects null elements in primitive numeric
arrays through centralized Jackson primitive-null enforcement; existing core boxed
index-array null rejection remains intact. Valid zeroes, integer lexical values for
double targets, optional whole-property nulls and bounded `extras` nulls remain
accepted.

HAND_TOOL_V0 now has independent hard declaration limits
`MAX_TEXTURES = 8` and `MAX_SAMPLERS = 8`, alongside unchanged
`MAX_MATERIALS = 8` and `MAX_IMAGES = 4`. All declarations count, even unused
ones, and limit checks complete before texture snapshot projection. Omitted samplers
retain glTF defaults. Viewer production/lifecycle architecture is unchanged.

Permanent full-path REDs were observed before production edits. The initial direct
GREEN is Inspector 324/324 and Viewer 63/63. Final proportional verification,
review totals, end-to-end replay and closure decision are recorded only after they
actually complete; no Git integration or Phase 18 work is authorized.

## Gate E controller contract closure — final evidence

Fresh all-source `--release 17` compilation and direct headless execution completed:
Inspector 324/324, Viewer 63/63, ordinary tools 54 passed/1 pre-existing abort.
Two independent Inspector processes produced byte-identical canonical PASS JSON for
the unchanged simple-tool SHA; the unchanged ruler also PASSed. Runtime JAR/license
hashes and dependency isolation remain intact.

Standard Gradle tasks `modelInspectorTest`, `modelViewerTest`, `tools:check` and
`clean test build` were each attempted after correction. All four were blocked
before task execution/test discovery by the same host loopback failure. They are
HOST LOOPBACK INFRASTRUCTURE BLOCK, not PASS; successful execution in a normal
environment/PR CI is required before merge.

The exact simple-tool GLB launched through Gate A -> Gate B -> snapshot -> Viewer
on Windows OpenGL 4.1. It published one current model, zero candidates and its
expected six GPU handles. Standard WM_CLOSE exercised normal cleanup and the launch
process exited 0. No Blender replay/save was needed because C/D architecture did not
change.

Final independent reviews: strict admission 0C/0I/0M; resource profile 0C/0I/0M;
integration/evidence 0C/0I/1M before this final documentation-only evidence update.
The final static skeptical reviewer returned
`NO ADDITIONAL EVIDENCE-BACKED FINDING`. The documentation Minor is closed without
production/test/resource change. Final deduplicated total: **0C/0I/0M**.

No stage, commit, push, PR, merge, Blender scene mutation, engine/game production
change, Gate D architecture change, new dependency or Phase 18 work occurred.

**LOCAL PHASE 17.75 CLOSURE READY — GIT INTEGRATION AUTHORIZATION REQUIRED**
