# Controls

These are the production Milestone 1 bindings. Input is sampled once per
render frame; pressed edges are delivered only to the first fixed step, while
later catch-up steps receive held state only.

## Movement and view

| Action | Binding | Notes |
| --- | --- | --- |
| Move | `W`, `A`, `S`, `D` | Camera-relative horizontal movement. |
| Look | Mouse | Default sensitivity `0.1`; raycast authority uses the canonical camera. |
| Jump | `Space` | Grounded jump. |
| Toggle noclip | double-tap `Space` | The second press must occur within 15 fixed steps. |
| Ascend in noclip | hold `Space` | Pickup is disabled while noclip is active. |
| Descend in noclip | hold left `Shift` | |
| Release or recapture cursor | `F1` | Cancels active mouse interaction at the lifecycle boundary. |
| Exit | `Escape` | Leaves the loop through the normal shutdown path. |

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
published.

## Presentation

| Action | Binding | Default |
| --- | --- | --- |
| Toggle HUD | `F2` | visible |
| Toggle debug HUD | `F3` | hidden |

The debug HUD exposes player feet and representative runtime counts. It is a
presentation surface and does not mutate simulation state.

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
