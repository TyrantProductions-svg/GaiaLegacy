# Astral Membrane / Quiet Membrane Style Guide

## Name and intent

**Astral Membrane** is the overall visual language: dark spatial boundaries,
restrained iridescent rims, orbit/portal geometry, and small luminous bodies.
**Quiet Membrane** is its Phase 10 implementation profile: the same language
held to an 80% clarity and function / 20% astral atmosphere balance.

Both names describe one approved system, not competing themes. Use “Astral
Membrane / Quiet Membrane” in general documentation and “Quiet Membrane” when
referring specifically to the restrained Phase 10 token implementation.

The HUD must read as three physical body slots, not as a spell bar. World
visibility and item recognition take priority over decoration.

## Exact design tokens

The code source of truth is `GaiaUiTheme`.

| Token | Exact value | Use |
| --- | --- | --- |
| Void background | `#071019D9` | translucent slot and mode panels |
| Primary text | `#EAF6F4FF` | labels, counts, Survival text |
| Inactive rim | `#708D94FF` | quiet preserved/inactive boundaries |
| Active primary rim | `#8FDCCFFF` | first active ring |
| Active secondary halo | `#9B83CFFF` | second ring and shared-hand halo |
| Creative accent | `#E7D89DFF` | detached Creative selection and infinity |
| Failure text | `#E15C64FF` | clear failure semantics |
| Debug background | `#05090DD9` | neutral DebugHud panel |
| Debug text | `#D6E0E3FF` | neutral diagnostics |
| Spacing scale | `2, 4, 6, 8, 12, 16` logical px | all compact spacing |
| Hand slot | `46 x 46` logical px | left and right hands |
| Mouth slot | `38 x 38` logical px | upper centre mouth |
| Bottom margin | `12` logical px | safe bottom inset |
| Slot transition | `150 ms` | presentation-only active change |
| Item name | `1.5 s`, final `250 ms` fade | temporary selected name |
| Mode notice | `1.25 s`, final `250 ms` fade | F4 feedback |

Typography is the bundled, independently authored Quiet Rune 5x7 version 1
font rasterized inside 8 by 8 cells. Inventory identity, state, quantity, and
temporary-name text starts from compact logical scales, then resolves to a
pixel-grid-safe source scale: one-half scale on lower-density surfaces and an
integer scale otherwise, with a one-to-one minimum at 150% DPI and above.
Persistent mode/debug text retains its approved compact scale, while expanded
mode notices use full scale. Glyphs remain nearest-neighbour and snap to
framebuffer pixels; inventory text must never be emitted at 0.625 or 0.75
source-pixel scale.

Continuous shimmer and continuous breathing are explicitly disabled.

## Compact physical layout

The bottom-centre cluster is:

```text
             MOUTH 38 x 38
      LEFT 46 x 46  RIGHT 46 x 46
              12 px bottom margin
```

At logical centre `C`:

- left hand spans `C - 50` to `C - 4`;
- right hand spans `C + 4` to `C + 50`;
- the mouth is centred above the eight-pixel hand gap, with six logical pixels
  between mouth bottom and hand top;
- Creative selection, when present, is a detached 38 by 38 membrane above the
  mouth rather than a fourth body slot.

The mouth marker is a small three-segment organic arc. It must never become a
monster mouth, teeth, or flesh motif.

## Redundant state language

Colour is never the only state cue.

| State | Shape | Text/symbol | Colour |
| --- | --- | --- | --- |
| Active | double ring | `ACTIVE` | cyan plus soft violet |
| Empty | empty outline | `EMPTY` | cool moon-white/quiet rim |
| Two-handed companion | dashed outline | `LOCKED` | inactive rim |
| Creative | detached membrane | `CREATIVE` and `∞` | warm star-white |
| Preserved Survival slots | normal quiet outlines | `PRESERVED` | inactive rim |

Body identity is always reinforced by `1 LEFT`, `2 RIGHT`, and `3 MOUTH`.

## Shared Core two-handed truth

The approved two-handed presentation is called **Shared Core**:

- only one canonical stack anchor is drawn;
- one icon is centred between both hands;
- one quantity is drawn;
- a shared outer halo and short connector join the hand slots;
- the selected hand owns the active double ring;
- the other hand is a dashed `LOCKED` companion;
- if mouth is active while a two-handed stack occupies the hands, the left hand
  remains the deterministic storage anchor without being marked active.

Never mirror the icon or count into both hands; that falsely communicates two
items.

## Detached Creative truth

Creative uses the Phase 9 `CreativeSelection`, not a fabricated body-inventory
stack:

- selection appears in a separate warm-gold membrane above the mouth;
- quantity is `∞` and the adjacent label is `CREATIVE`;
- real left/right/mouth slots remain visible at reduced emphasis and are marked
  `PRESERVED`;
- switching to Survival removes the detached membrane in the same presented
  frame and restores the real active body slot.

Never overwrite, hide as consumed, or visually duplicate the preserved
Survival inventory.

## Crosshair and break progress

The crosshair is static pure white and uses four solid framebuffer-space quads:

- total span: 16 framebuffer pixels;
- thickness: 2 framebuffer pixels;
- centre gap: 4 framebuffer pixels;
- no texture, dynamic colour, spread, hit marker, or animation.

The approved break indicator is deliberately shorter than the original design
alternatives:

- 28 by 2 framebuffer pixels;
- top edge 15 pixels below framebuffer centre, therefore 7 pixels below the
  crosshair's lower edge;
- completed segment pure white;
- remaining track white at 22% alpha;
- visible only for active, eligible Survival breaking with progress strictly
  between zero and one.

It must not become a large centre bar or a spell-like radial effect.

## Other widgets

- Game mode is a small top-right panel. Survival is moon-white; Creative uses
  the warm accent and the infinity sign. F4 adds only a short fading notice.
- Interaction failure remains standard red below the centre interaction area;
  clarity takes priority over theme.
- DebugHud is a neutral top-left rectangle with no Astral Membrane decoration.
- Temporary item names sit above the body cluster, are capped at 144 logical
  pixels, and are truncated with ASCII `...` before they can overlap other
  slots.

## Alpha and pixel treatment

- Use straight alpha for all atlas pixels, solid fills, and tints.
- Transparent font pixels retain white RGB with zero alpha to prevent dark
  fringes.
- Use nearest-neighbour sampling, clamp-to-edge, level zero only, and no
  mipmaps.
- Convert sRGB texture and tint RGB to linear in the UI shader, multiply, and
  encode once; do not enable framebuffer sRGB in parallel.
- Position edges with `UiLayoutContext` snapping. Do not soften, blur, or
  fractionally drift bitmap glyphs and icons.

## Animation limits

Allowed:

- 150 ms active-slot presentation transition;
- 1.25 s mode notice with final 250 ms fade;
- 1.5 s item name with final 250 ms fade;
- future, separately approved low-contrast rim shimmer.

Forbidden:

- continuous breathing scale;
- sustained high-density particles;
- long writhing or organic motion;
- full-screen flash or distortion;
- animation that delays or modifies a fixed-step state;
- animation that masks active, empty, locked, or Creative truth.

## Forbidden motifs

Do not introduce:

- large eyes or eye fields;
- tentacles;
- flesh, blood, teeth, or monster mouths;
- copied depictions of Yog-Sothoth or other web artwork;
- high-saturation purple across the screen;
- heavy refraction over item icons;
- ornate spell-slot framing;
- duplicate counts, icons, or state signals that misrepresent domain state.

All future art must be original project work or have explicit provenance and
license documentation.

## Acceptance focus

Manual review must confirm that the cluster stays compact, the active slot is
immediately legible, the mouth differs from the hands, Shared Core reads as one
item, Creative reads as detached and infinite, icons remain distinguishable,
the progress bar does not obscure the crosshair, and neither DPI scaling nor
resize introduces blur, jitter, fringe, or offset.

See [UI rendering and HUD](ui-rendering-and-hud.md) and
[UI icon and font assets](ui-icon-and-font-assets.md) for the technical
contracts.
