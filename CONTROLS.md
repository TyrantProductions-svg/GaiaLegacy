# Controls

These are the production Milestone 1 gameplay and Phase 13 product-shell,
settings, and audio bindings. Input is sampled once per render frame; pressed
edges are delivered only to the first fixed step, while later catch-up steps
receive held state only.

## Product shell

| Action | Binding | Notes |
| --- | --- | --- |
| Point at an action | Mouse | Only enabled action rectangles highlight. Moving outside an enabled rectangle clears pointer highlight; disabled Load World does not highlight. |
| Activate pointed action | left mouse | Disabled actions do not activate. |
| Move focus forward | `Tab` or down arrow | Wraps through enabled actions only. |
| Move focus backward | up arrow | Wraps through enabled actions only. |
| Activate focused action | `Enter` or `Space` | Opens the selected screen or confirmation action. |
| Back, dismiss, pause, or resume | `Escape` | Dismisses the top modal first; otherwise returns from Settings or Controls, resumes from Pause, pauses Playing, or opens Quit confirmation from Main Menu. |

Main Menu provides New World, disabled `Load World - Available in Phase 14`,
Settings, Controls, and Quit. Settings uses an explicit draft with Apply and
Back actions. Back on a dirty draft opens Apply, Discard, and Cancel choices.
New World passes through Loading into a fresh gameplay session.
Return to Main Menu and Quit require confirmation where applicable.

## Settings

| Setting | Application policy |
| --- | --- |
| VSync | Applies immediately after Apply; interval is explicitly `1` when enabled and `0` when disabled. |
| FOV | Applies immediately after Apply; validated range 50-100 degrees. |
| Mouse sensitivity | Applies immediately after Apply; validated range 0.02-0.50. |
| Invert Y | Applies immediately after Apply and affects pitch only. |
| Master / Music / SFX volume | Applies immediately after Apply to the owner-thread audio device; defaults are 100% / 65% / 100%. |
| Mute when unfocused | Applies immediately after Apply; when enabled, focus loss fades music to silence and focus recovery restores it. |
| Chunk radius | Applies to the next New World session after Apply. |
| Default game mode | Applies to the next New World session after Apply. |
| Debug HUD default | Applies to the next GameSession after Apply. |

Settings are stored in the platform user configuration directory. Apply writes
atomically; invalid or corrupt input falls back safely with diagnostics. An
unapplied draft is never written during product shutdown.

## Movement and view

| Action | Binding | Notes |
| --- | --- | --- |
| Move | `W`, `A`, `S`, `D` | Camera-relative horizontal movement. |
| Look | Mouse | Default sensitivity `0.1`; raycast authority uses the canonical camera. |
| Jump | `Space` | Grounded jump. |
| Toggle noclip | double-tap `Space` | The second press must occur within 15 fixed steps. |
| Ascend in noclip | hold `Space` | Pickup is disabled while noclip is active. |
| Descend in noclip | hold left `Shift` | |
| Pause or resume active session | `F1` | Releases or recaptures the cursor and cancels active mouse interaction at the lifecycle boundary. |
| Pause | `Escape` | While Playing, enters Pause and releases the cursor. See Product shell for its other route-specific behavior. |

## Blocks and world items

| Action | Binding | Notes |
| --- | --- | --- |
| Break targeted block | hold left mouse | Survival uses timed break and creates one canonical physical drop; Creative follows the Creative break policy. |
| Place selected block | right mouse | Survival consumes the selected inventory item only after a successful transaction; Creative does not consume inventory. |
| Manual pickup | either `Shift` + right mouse | Survival only, requires an eligible targeted WorldItem, and takes priority over placement for that press. There is no automatic pickup. |
| Drop one selected item | `Q` | Creates a canonical physical WorldItem. |
| Drop selected full stack | either `Ctrl` + `Q` | Uses the same transactional drop boundary. |

## Body inventory and modes

| Action | Binding |
| --- | --- |
| Select left hand | `1` |
| Select right hand | `2` |
| Select mouth | `3` |
| Cycle active slot | mouse wheel |
| Toggle Survival / Creative | `F4` |

Changing mode cancels the current block interaction before the new mode is
published. `F4` is consumed only while Playing; product screens and Pause do not
receive the gameplay shortcut.

## Presentation

| Action | Binding | Default |
| --- | --- | --- |
| Toggle HUD | `F2` | visible |
| Toggle debug HUD | `F3` | hidden |

The debug HUD exposes player feet and representative runtime counts. It is a
presentation surface and does not mutate simulation state. `F2` and `F3` are
gameplay/session inputs and are suppressed outside Playing.

## Music behavior

Gaia fades in on Main Menu, continues through New World into exploration,
ducks while Paused, and recovers on Resume. When mute-when-unfocused is enabled,
Alt+Tab/focus loss fades music to silence without advancing gameplay; returning
focus does not automatically resume a paused session. Legacy is packaged and
registered but has no ordinary player binding in Phase 13.

## Developer-only inventory shortcuts

`F5` through `F8` are inactive in normal release-candidate runs. They are
enabled only when the development run is explicitly started with the
`gaia.inventory.debugShortcuts` property:

| Action | Binding |
| --- | --- |
| Seed debug inventory | `F5` |
| Clear debug inventory | `F6` |
| Fill debug inventory | `F7` |
| Print debug inventory | `F8` |

These shortcuts are diagnostic inputs, not part of the player-facing
Milestone 1 acceptance loop.
