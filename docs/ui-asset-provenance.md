# GaiaLegacy UI Asset Provenance

Status: final Phase 17.5 source receipts and deterministic derivatives verified.
The initial admission schema and candidate roster below remain historical
context. Only Pixelify and Inter ship as runtime font derivatives; IBM Plex is
specimen-only. Lucide/Kenney were not imported.

## Distribution notices and checkout integrity

`game:processResources` copies the complete original Pixelify `OFL.txt` and
Inter `LICENSE.txt` into `assets/gaia/ui/licenses/pixelify-OFL.txt` and
`assets/gaia/ui/licenses/inter-LICENSE.txt`. Game JAR and installDist checks
require these entries; a classpath regression verifies their exact SHA-256
against the source receipts below. No runtime tools dependency or TTF parser
is introduced. Plex remains build-only.

Vendored license files use Git `-text` to retain exact upstream bytes (Pixelify
and Plex use CRLF; Inter uses LF). Generated typography metadata uses LF.
The two verbatim CRLF notices also retain upstream trailing spaces. Only those
exact paths disable `blank-at-eol` and enable `cr-at-eol` whitespace handling;
source-code whitespace checks remain unchanged. No license bytes are normalized.
An isolated Git check-out byte experiment under `core.autocrlf=true/false`
reproduced two differences before this fix and zero after it. No primary
repository checkout, user files, or machine Git configuration was changed.

## Integrity vocabulary

Each third-party source and generated derivative records distinct identities:

| Field | Meaning |
| --- | --- |
| Upstream tag/commit | Repository version selected for review and reproducibility |
| Git blob SHA | Optional Git object identity; useful for repository archaeology, not a byte-integrity substitute |
| Source SHA-256 | SHA-256 calculated over the exact downloaded source-file bytes; the authoritative admission integrity hash |
| Derivative SHA-256 | SHA-256 calculated over each generated PNG/JSON or other committed derivative |

Git commit and blob IDs must never be copied into a SHA-256 field. Source files
are downloaded only from the documented official URL. A redirect must resolve
to the expected official owner. Any mismatch, absent file, ambiguous license,
or incomplete license inventory blocks admission.

## Required source receipt fields

Every admitted file records:

- canonical Gaia source ID;
- family/project and exact filename;
- official upstream URL;
- release/tag and commit;
- optional Git blob SHA;
- SHA-256 of source bytes;
- SPDX/license name and exact bundled license path;
- local source path under `tools/src/main/resources/ui-source/`;
- purpose and selected glyph/icon scope;
- modification/rasterization description;
- exact deterministic conversion command;
- generated atlas/metadata entry;
- SHA-256 of every generated derivative.

CC0 files still receive a complete receipt. OFL fonts retain the applicable
license text and Reserved Font Name obligations. Lucide retains the complete
1.27.0 `LICENSE`, including applicable Feather/MIT notices.

## Closed candidate roster

The approved candidates are Pixelify Sans at commit
`39df74aba80df8157546034b878e8be1eb565ced`, Inter `v4.1`, IBM Plex Sans
`@ibm/ibm-plex-sans@1.1.0` as specimen challenger only, Lucide `1.27.0`, and
Kenney Input Prompts Pixel `1.0`. Exact source-byte hashes are intentionally not
filled from Git object IDs; Gate 17.5B calculates them only after retrieving the
official bytes.

The official Pixelify tree audit corrected the preliminary design path:
approved static sources live under `fonts/ttf/`, not `fonts/static/`. The pinned
blob IDs are `45cd4e2afa3edc6fe1c2500f4e763bdbb0ec65a1` for SemiBold and
`5f8040b71be14d283010c4a646eac856a03988bf` for Bold. Their source SHA-256
values are calculated only from the downloaded raw bytes.

## Gate 17.5B font source receipts

All admitted files are build-only inputs under
`tools/src/main/resources/ui-source/fonts/`. The closed machine-readable roster
is `tools/src/main/resources/ui-source/font-sources.json`; its tests recalculate
every source and license SHA-256 from the actual bytes.

| Gaia source ID | Upstream version | Git blob SHA | Source-byte SHA-256 |
| --- | --- | --- | --- |
| `pixelify-semibold-600` | Pixelify commit `39df74aba80df8157546034b878e8be1eb565ced` | `45cd4e2afa3edc6fe1c2500f4e763bdbb0ec65a1` | `a4b54982991cd47450df317a451a7f066bf6b91aa033e105643aba2cf7bd35c3` |
| `pixelify-bold-700` | Pixelify commit `39df74aba80df8157546034b878e8be1eb565ced` | `5f8040b71be14d283010c4a646eac856a03988bf` | `3c3c203a3f3b862b944e836e4ec0fce201eddc1952cbca0d682e382d668b293c` |
| `inter-regular-400` | Inter `v4.1` (`e3a3d4c`) | release archive member | `40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82` |
| `inter-medium-500` | Inter `v4.1` (`e3a3d4c`) | release archive member | `97ad806f526e41546d46365bb3a393145f75b7b1568913db74549ad8b8dba872` |
| `inter-semibold-600` | Inter `v4.1` (`e3a3d4c`) | release archive member | `78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3` |
| `plex-regular-400` | Plex commit `1da12f02587b630c07e92692d21492d722f53614` | `bd6817d5202895da5ac4fad88de3da71e652881a` | `975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5` |
| `plex-medium-500` | Plex commit `1da12f02587b630c07e92692d21492d722f53614` | `a3826c4e9ab3f2d6e64e0c9aea7122ea3431e174` | `331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907` |
| `plex-semibold-600` | Plex commit `1da12f02587b630c07e92692d21492d722f53614` | `09ec8cdae8208c26165db0c87da409cda8c26ae4` | `a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1` |

The Pixelify OFL bytes hash to
`b66ba46f511a851ab09998b5a5a9fdbb102545a3864cb993095e1745996873a7`.
The Inter license hashes to
`262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a`;
the downloaded `Inter-4.1.zip` source archive hashed to
`9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`.
The IBM Plex license hashes to
`7e6b2818edbd8f6a01ae80641cc8f16a51080d08fb4e532be3a0b6f74adb07da`.
IBM Plex remains a specimen challenger and is not selected for production by
source admission alone.

## Deterministic typography specimen derivatives

The deterministic conversion command is:

```powershell
.\gradlew.bat :tools:generatePhase175TypographySpecimen --console=plain --no-daemon
```

`verifyPhase175TypographySpecimen` launches two independent Java generator
processes and byte-compares every output. Review derivatives remain under
`tools/build/phase-17.5-specimen/`; they are not runtime assets and are not
eligible for staging without separate approval.

| Derivative | SHA-256 |
| --- | --- |
| `quiet-rune-baseline.png` | `cab8b6ba7c6f9769a578dcc4ec9667f6ecfdeec952306878739a3d84e47018f6` |
| `pixelify-inter.png` | `91fcca1a41e506242ca316b18ffab115cd57475053de1f28bb740b6d9d64368d` |
| `pixelify-plex.png` | `5c4ee193c8519c11dec665b60a1e3ff9659d64363dde765b020c823e2b66a7e7` |
| `atlas-composite.png` | `12397bef6b588c07216956816d9b841dcb79c100882753a37a936e6e14caa0db` |
| `atlas-composite.json` | `4d43d764ea14fef09f7b57423a468be3bc351c7af10eec139f82c24f53cc736d` |
| `atlas-split-display.png` | `eeb73d7f348a04f076d6186e2d084ee38c8be68f82be91c61bc63ffc45d4927b` |
| `atlas-split-display.json` | `5f3bf54d55a44740106f79043ee47c57ad274c1d6703462a69744d21bf48bc77` |
| `atlas-split-body.png` | `e102986182248288ecdb7b1b5d4e08eded0d96202715f54707061b659b8648ad` |
| `atlas-split-body.json` | `d6587bcd65db60e3b2261aa644967889756a55057d8da9e88d95cb1349b71eda` |

The measured production candidate uses Pixelify Bold 42 px, Pixelify SemiBold
28 px, Inter Regular 18 px, Inter Medium 16 px, and Inter SemiBold 18 px, all
with deterministic 3x source oversampling. The single-page option is
256x512 RGBA8 (524,288 bytes, LINEAR, one estimated bind/draw run). The
page-specific option is a 256x512 display page (524,288 bytes, NEAREST) plus a
256x256 body page (262,144 bytes, LINEAR), totaling 786,432 bytes and four
estimated bind/draw runs for the closed specimen ordering. Both remain below
the 2 MiB design-review target. The approved production direction uses the two
physical pages: Pixelify NEAREST and Inter LINEAR, under one logical
typography/rendering authority.

## Project-owned Gaia main-menu concept source

Gate 17.5B.5 uses the tracked GaiaLegacy runtime capture
`docs/images/gaialegacy-hero.png`. It is a project-owned repository asset, not a
downloaded background.

| Source | Repository commit | Git blob SHA | Source-byte SHA-256 | Dimensions |
| --- | --- | --- | --- | ---: |
| GaiaLegacy terrain capture | `d13d8fe4d0ac59e2a1a94b84cc0ed698fa6aca33` | `ff1da87408d26db9fd17d3e429f88407ce75c3e6` | `66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262` | 2560x1345 |

The deterministic review conversion command is:

```powershell
.\gradlew.bat :tools:generatePhase175MainMenuConcepts --console=plain --no-daemon
```

`verifyPhase175MainMenuConcepts` runs two independent JVM processes and
byte-compares every output. These review derivatives remain under
`tools/build/phase-17.5-main-menu-concepts/`; they are not runtime assets and are
not eligible for staging without separate approval.

| Derivative | SHA-256 |
| --- | --- |
| `concept-a-gaia-panorama.png` | `d0db5a6c06157ad489cd7176682e78acc274b0156252a084d926e81c952b104b` |
| `concept-b-orbital-legacy.png` | `2183378e07513be40dba21fffd5d7c9f0a9951df02f13ef35c2656f19be7a494` |
| `concept-c-dark-signal.png` | `9330a586e8054b80e04995f9fe4c71b8eabc97ab3947a56ba9a18606b95d3aea` |
| `measurement.json` | `928447a7100a77661fbdd0a663382e65bf6600c9d752909cb7332d8994b099c5` |

## Gate 17.5C runtime derivatives (initial visual review, superseded below)

The approved A+ vertical slice derives three packaged 1280x720 hero images from
the same hash-locked project-owned source. The generator command is:

```powershell
.\gradlew.bat :tools:generateRuntimeHeroAssets --console=plain --no-daemon
```

`verifyGeneratedRuntimeHeroes` runs two independent JVM processes and compares
all generated bytes. Runtime currently binds only `dawn`; the other two images
form the deterministic future pan/crossfade roster.

| Runtime derivative | PNG bytes | SHA-256 | RGBA8 bytes |
| --- | ---: | --- | ---: |
| `gaia-hero-dawn.png` | 972,165 | `2d2695d1cfde944cf3f72df1efc3bf469b749e2ba069e3f8e265dd18fbd03ed2` | 3,686,400 |
| `gaia-hero-highlands.png` | 976,195 | `a02515488bc4c3d1e54cbbded274946faf6f74ee284810c022e8fb9fe5ae1da0` | 3,686,400 |
| `gaia-hero-twilight.png` | 842,649 | `fa85b933de01962fd239da67809131c4481fc6314a87a3874aa325789ae9cb75` | 3,686,400 |

The runtime typography generator preserves the approved specimen pages:

| Runtime derivative | Dimensions | Sampling | PNG SHA-256 | RGBA8 bytes |
| --- | ---: | --- | --- | ---: |
| `ui_font_display.png` | 256x512 | NEAREST | `eeb73d7f348a04f076d6186e2d084ee38c8be68f82be91c61bc63ffc45d4927b` | 524,288 |
| `ui_font_body.png` | 256x256 | LINEAR | `e102986182248288ecdb7b1b5d4e08eded0d96202715f54707061b659b8648ad` | 262,144 |

For Lucide, the generator manifest is a closed mapping:

```text
Gaia semantic ID
    -> exact icons/<name>.svg
    -> Lucide tag 1.27.0 / commit 4aec3f8
    -> downloaded source SHA-256
    -> generated Gaia atlas entry and derivative SHA-256
```

The recovery-action candidate is `icons/archive-restore.svg`, subject to visual
confirmation. No nonexistent shorthand such as `recover.svg` is permitted.

## Gate 17.5C human-review visual correction

No external art was used. The brand vector/path and celestial parametric source
are project-owned Java build-tool source, not runtime procedures. Actual source
file-byte SHA-256 (not Git blob hashes):

| Source | SHA-256 |
| --- | --- |
| `tools/src/main/java/com/gaia/tools/ui/RuntimeBrandAssetGenerator.java` | `4f2679b429c24068b1db4a624580c5227cc07ff8fa1d5276480d07908a38b465` |
| `tools/src/main/java/com/gaia/tools/ui/RingedPlanetLayer.java` | `186a9c378adddf6aac2377d32ef8a36ac5705534e6a7febd8148e36530d09790` |

The landscape capture remains the same project-owned source with SHA-256
`66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262`;
its Git commit/blob references above remain historical source identifiers.
The following replace the initial runtime derivative hashes, not the historical
A/B/C specimen hashes:

| Derivative | PNG bytes | SHA-256 |
| --- | ---: | --- |
| `ui/brand/gaia-emblem.png` | 5,343 | `701ca3f2aced239a49d405aa6e68e5f26bcf4641c1ab1135ba66590ff5cf781c` |
| `ui/hero/gaia-hero-dawn.png` | 988,795 | `182637e8093698cde5203749f6ab7de9f900fa217165639ab7049453a942c4d0` |
| `ui/hero/gaia-hero-highlands.png` | 992,848 | `7b55199fcb878d30b74f305cc2f75abce748f4a4ad9fd4a54753931467d4295b` |
| `ui/hero/gaia-hero-twilight.png` | 859,761 | `a313b7e67b3b3a03ac6dab00875a6590eed9da8ea5c79e395dde0ce5ce466607` |

`generateRuntimeBrandAssets` creates a 256x256 LINEAR straight-alpha page from
4x supersampled geometry. Its transparent texels retain ink RGB to prevent
LINEAR filtering fringes. `generateRuntimeHeroAssets` bakes a 200px filled sphere
and tilted 430x112 ring system into each existing hero. No new celestial texture
or runtime draw is introduced. Source/receipt LF is pinned in `.gitattributes`.
`verifyGeneratedRuntimeBrand` and `verifyGeneratedRuntimeHeroes` compare two
independent generator JVMs with repository outputs, byte for byte.

## Deferred or excluded sources

- Kenney UI Pack: Sci-Fi is reference-only and is not imported in Gate 17.5B.
- Fusion Pixel Font and CJK runtime packaging are deferred.
- UI and gameplay chisel SFX are deferred because no bounded playback seam is
  available.
- Gaia/Legacy/DETAIL/chisel identities remain project-owned art.
- Entire upstream repositories, release archives, downloaded ZIPs, and unused
  source files are not eligible for staging.
