# Phase 17.5 handoff — Cosmic presentation system

## Status and baseline

The controller accepted the typography engineering, A+ visual direction,
corrected Main Menu/emblem/ringed planet and manual resize. Final integration
is authorized on `feat/cosmic-ui-typography` from
`d4740726fe611c20b4ae1b96e5387c532c21cb1e` (opening HEAD and origin/main, divergence
0/0). This record precedes the feature commit/PR. It does not claim merge or
post-merge CI success. The final controller report supplies feature, PR and
merge identities after those events; no direct main documentation commit is
needed merely to insert a self-referential SHA.

## Completed scope

- One semantic TypographyCatalog/TextRenderer/UiRenderer. Pixelify Bold700 title,
  SemiBold600 heading; Inter400/500/600 body/functional/HUD.
- Two deterministic font pages: Pixelify256x512 NEAREST524,288bytes;
  Inter256x256 LINEAR262,144bytes. IBM Plex remains specimen-only.
- A+ WORLD-FIRST COSMIC SURVIVAL product shell, left navigation shading,
  darker contextual secondary screens, lightweight HUD and DETAIL typography.
- Project-owned smooth256x256 LINEAR emblem (262,144bytes), four-times
  supersampled analytic source, clean straight-alpha padding, one quad.
- Three packaged1280x720 Gaia hero derivatives from the tracked Gaia capture.
  Static dawn only is decoded/resident (3,686,400bytes). No pan/crossfade.
- Project-owned atmospheric ringed planet baked into heroes; back rings,
  sphere, front rings correctly occluded. No new runtime shader or geometry.
- Semantic theme/contrast and bounded motion-token foundation, not expanded
  animation. Existing project-owned functional/item icons are reused.

Architecture: `docs/architecture/cosmic-ui-presentation.md`.
Source receipts: `docs/ui-asset-provenance.md`, `ui-source/font-sources.json`,
runtime typography/hero/brand manifests. Source SHA-256 is distinct from Git
commit/blob and generated derivative SHA-256.

## Final integration corrections (tests first)

1. Packaged font notice test failed on missing Pixelify/Inter licenses.
   `game:processResources` now copies complete original notices; classpath byte
   hashes and package/install checks protect distribution. Focused4/4 GREEN.
2. Isolated Git checkout simulation reproduced2 byte differences with Windows
   autocrlf. Three source licenses now use `-text`; typography JSON uses LF.
   Both autocrlf=true/false now reproduce all4files exactly,0mismatches.
3. Production Inter ACTIVE/EMPTY labels touched at100% and overlapped at150%.
   Metric-derived state-line spacing keeps slot geometry and final row fixed.
   BodyInventoryHudTest20/20, including100/125/150/200/300% real-font cases.
4. The broader engine suite found a stale three-texture enum test. It now
   checks preservation of legacy and approved multi-page texture identities
   through the same frame command path (no production change).
5. Final Windows smoke reproduced inherited fixed-row overlap in Settings and
   a full World Archive at854x480. Two new tests first failed. Compact row
   spacing/paging now keeps controls above the footer; actions, page size,
   controller authority, saves and main-menu composition are unchanged.

## Automated verification

Final proportional wrapper evidence: engine97/97; game233/233; tools45passed,
1skipped;0failures/errors. The UI/generator/package invocation completed in51s;
after the compact-layout correction game UI/package/install verification was
rerun in26s, then28s after the breakpoint/blocked-notice regressions. These are
focused suites, not a full local repository test claim.
Normal three-platform CI is the full integration regression gate.

Process-local selector fallback only:

```powershell
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\__codex_missing_unix_socket_dir__'
.\gradlew.bat :engine:test --tests 'com.overlord.renderer.ui.*' :game:test --tests 'com.gaia.ui.*' --tests 'com.gaia.shell.ui.*' :tools:test --tests 'com.gaia.tools.ui.*' :tools:verifyGeneratedUiAssets :tools:verifyGeneratedRuntimeTypography :tools:verifyGeneratedRuntimeBrand :tools:verifyGeneratedRuntimeHeroes :tools:verifyPhase175TypographySpecimen :tools:verifyPhase175MainMenuConcepts :engine:verifyPackagedShaderResources :game:verifyPackagedResources :game:installDist :game:verifyInstalledShaderResources --console=plain --no-daemon
```

Runtime typography/brand/heroes each pass two independent generator processes
and repository byte comparisons. Baseline/A/B typography and title concepts
also pass independent generation. Packaged resources, installed and packaged
shaders, installed audio audit, and installDist are GREEN. Review images remain
ignored under tools/build, not production assets.

Independent read-only review found3Important issues (notices, line endings,
Inter label separation), all corrected and rechecked at0Critical/0Important/
0Minor. Final runtime-derived compact-layout correction is separately checked
in the same review closeout before integration. Archive breakpoints480/649/650/
700/711/712/720 and blocked Settings with production Inter were added RED then
GREEN; final scoped review is0Critical/0Important/0Minor, all findings closed.

## Runtime and platform evidence

Historical Gate17.5C Windows GLFW evidence covers Main Menu, Settings, New World,
World Archive, pause/confirmation, P17-Manual load, bright/dark HUD, body slots,
chisel/DETAIL state, Alt+Tab/resume and clean exit. Corrected emblem/planet
were accepted by the human; latest user explicitly reports resize PASS.
The integration smoke and packaged launcher result are appended below when
observed. No automated fixture is represented as manual acceptance.

Final observed integration smoke: the unchanged installDist launcher entered
Main Menu and compact World Archive, loaded `P17-Manual`, showed Creative HUD,
body inventory/chisel/DETAIL state, paused on Alt+Tab, resumed with no observed
input replay, displayed and dismissed the return confirmation, completed Save
& Quit, resized up/down with intact title/hit regions, and exited via Quit
with process exit0. New World entry/back, Settings and Controls were also
observed in the same integration session series. The first packaged run was
invalidated by a concurrent installDist refresh; the final immutable-install
run is the acceptance evidence (see implementation notes). No voxel edit was
made for this smoke. Local review images remain ignored under
`tools/build/phase175-integration/runtime/`.

- Windows125%interactive DPI: NOT RUN.
- Native Apple Silicon interactive: NOT RUN.
- Automated100/125/150/200/300% layout fixtures are GREEN; future macOS CI is
  build/test evidence only, not Retina or native gameplay acceptance.

## Interfaces future phases must not break

- One product/navigation controller, UiHitRegion authority and GaiaHudScreen.
- One UiRenderer/TextRenderer/TypographyCatalog; owner-thread GPU lifecycle,
  OpenGL4.1/GLSL410, no runtime TTF/SVG or OS-font/filesystem dependency.
- One ChunkRepository/World/inventory/WorldItem authority. No save/load,
  raycast, collision, worldgen or gameplay semantic changes in this phase.
- FULL/DETAIL exclusivity, no emptyDETAIL, exactly-nine snapshots/separate claim,
  typed adjacent FULL-placement safety, canonical ID+integer ItemStack,
  reserve-before-mutate/APPLIED reconciliation and no generated/player provenance.
- Hybrid output8,388,608bytes; global meshCPU134,217,728bytes; accepted32/active2;
  uploads2/frame; destruction4/frame; aggregate publication+upload2/frame.

## Deferred and known limitations

After Milestone2 core completion, a dedicated high-quality animation and
presentation pass may address slow hero pan, deterministic crossfade, parallax,
screen enter/exit, modal choreography, focus/selection micro-motion, emblem
reveal, subtle celestial movement, HUD transient tuning, optional UI SFX if a
seam exists, and final motion pacing/Reduced Motion UX. None are in this PR.

Also deferred: UI/chiselSFX, CJK/Fusion, Lucide/Kenney imports, broad mass
migration, Phase18 acquisition/crafting/full-to64 conversion/tool tiers/
progression/BlenderMCP/GLB/ModelInspector, and Phase19 generated-world content.
No next-phase work is authorized. Existing heavy save/catalog performance and
Phase16 last-known-good complexity presentation limitations remain inherited.

## Safety and modified-file inventory

All work remains in the primary checkout. No worktree creation, stash, reset,
rebase or clean. Explicit reviewed paths only may be staged. Two quarantined
tracked files and the pre-existing dist ZIP are excluded, with exact hashes:

- ChunkStreamingMetricsRecorder.java:
  `EBBAAD69942CE0F8504BD37DA1B2AF7878BCC19EA38D21A27C788B4C1F7ABB51`
- ChunkStreamingSessionIntegrationTest.java:
  `D8C6D9DCC01707809C6414AFA9F11C4D2501CD30748A145897E8F65BA594782C`
- dist ZIP:
  `FC7D521BD7DCFBC3142E0153C82A0F63FFFC2301483FE119EBB1D372F35228D2`

Final allow-list:39tracked modifications plus54new Phase17.5files,93total;
quarantine/dist excluded. The integration report records staged statistics. Modified groups:
engine UI texture/catalog/text infrastructure and tests; game bootstrap/presenter/
HUD/strict UI asset loading and tests; game resource packaging and UI PNG/JSON;
build-only font/source/brand/hero/specimen generators and tests; .gitattributes;
design/plan/implementation/provenance/architecture/handoff documentation.

Suggested commit and PR title:
`feat(ui): establish GaiaLegacy cosmic presentation system`.
PR body must distinguish focused local evidence, required exact-SHA full CI,
accepted visuals and deferred post-Milestone2 polish.
