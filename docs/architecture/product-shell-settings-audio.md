# Product shell, settings, and audio architecture

## Scope

Phase 13 wraps the deterministic sandbox in one product lifecycle without
creating a second game loop or moving gameplay authority into UI/audio code.
The shell owns menus, settings, focus/cursor policy, music presentation, and
zero or one `GameSession`. World, physics, inventory, block transactions,
WorldItems, and HUD remain session-owned.

```text
GameBootstrap
  -> ProductSettingsLifecycle
       -> JsonSettingsStore / SettingsController / SettingsApplier
  -> ProductLoop
       -> ScreenRouter / ProductShellController
       -> ProductScreenInputController / ProductScreenPresenter
       -> MusicManager / AudioDevice
       -> Optional<GameSession>
            -> World / physics / interaction / WorldItem / HUD
```

## Product and session ownership

`GameBootstrap` captures `MainThreadGuard`, loads assets, opens validated
settings, constructs `Engine`, opens `AudioDevice`, creates `MusicManager`, and
then enters the sole `ProductLoop`. `GameSessionFactory` is lazy: `New World`
creates one fresh `GameSession`, while Main Menu, Settings, and Controls own no
World or fixed-step services.

`ProductLoop` polls and renders on the GLFW/OpenGL owner thread. It owns at most
one session and rejects a second concurrent launch. Loading cancellation or
failure closes the partial session before returning to Main Menu. Return to
Main Menu closes the active session; a later New World creates independent
runtime state.

## Screen, modal, and input model

Primary `ScreenId` values are `MAIN_MENU`, `LOADING`, `PLAYING`, `PAUSED`,
`SETTINGS`, and `CONTROLS`. `Settings` and `Controls` retain an exact
`ScreenReturnTarget` of `MAIN_MENU` or `PAUSED`.

Legal modal ownership is closed:

| Modal | Owning screen |
| --- | --- |
| `QUIT_CONFIRMATION` | Main Menu |
| `UNSAVED_PROGRESS_CONFIRMATION` | Paused |
| `DIRTY_SETTINGS_CONFIRMATION` | Settings |
| `ERROR_ACKNOWLEDGEMENT` | Main Menu |

Modal hit regions replace underlying regions. Mouse callback coordinates are
mapped once from window space to logical UI space; paint conversion to
framebuffer space remains separate. Pointer focus exists only inside an enabled
painted rectangle. Keyboard focus uses Tab/down/up and Enter/Space.

Playing eligibility requires the `PLAYING` route, no modal, a focused window,
and captured cursor. Every transition across that boundary invalidates held
gameplay input until physical release, clears fixed input edges, discards the
session fixed-time remainder, and cancels interaction presentation. The first
newly eligible Playing frame receives zero gameplay delta. Focus loss hard
pauses; focus recovery never resumes or recaptures implicitly.

## Product-frame order

One `ProductLoop.runFrame` performs:

1. validate the bounded presentation delta;
2. poll GLFW and surface changes;
3. capture immutable `UiInputSnapshot`;
4. route one screen/modal command;
5. apply lifecycle and completed-load transitions;
6. apply focus-loss pause policy;
7. map the final route/focus into `MusicManager` and update audio once;
8. apply the gameplay eligibility boundary;
9. advance the session fixed-step batch only when eligible, otherwise capture a
   paused immutable frame;
10. compose session HUD before product UI;
11. render once and swap once.

UI and audio consume presentation delta only. Canonical simulation remains
exactly 1/60 second with the existing maximum eight-step catch-up during
continuous Playing.

## Settings ownership and persistence

`ProductSettingsLifecycle` resolves one settings file, loads schema v1 through
`JsonSettingsStore`, validates/clamps values, constructs the runtime using the
loaded VSync value, applies hot settings through `SettingsApplier`, and exposes
`SettingsController`. Writes occur on explicit Apply and final applied-only
close, never per frame. Atomic sibling-temp replacement preserves the previous
file on failure; corrupt/unsupported input falls back with bounded diagnostics.

Platform paths are:

| Platform | Location |
| --- | --- |
| Windows | `%APPDATA%\GaiaLegacy\settings.json` with user-home roaming fallback |
| macOS | `~/Library/Application Support/GaiaLegacy/settings.json` |
| Linux | `$XDG_CONFIG_HOME/GaiaLegacy/settings.json` or `~/.config/GaiaLegacy/settings.json` |

Hot-after-Apply settings are VSync, FOV, mouse sensitivity, invert-Y,
Master/Music/SFX volume, and mute-when-unfocused. Chunk radius, default game
mode, and debug-HUD default are captured only for the next New World/session.
Rollback is registered only after each successful hot boundary and runs in
reverse order on failure.

## Audio ownership and streaming

`AudioDevice` is an engine API owned by the main thread. It composes Master and
Music gains and delegates to one backend. An initialization `RuntimeException`
or native `LinkageError` emits one bounded `AudioDiagnostic` and selects
`SilentAudioBackend`; unrelated fatal `Error` values remain visible.

`OpenAlAudioBackend` owns one OpenAL device, context, and at most one music
voice. `LwjglOpenAlApi` checks AL/ALC errors around relevant calls. A creator
that obtains a native handle but fails its post-call check releases that handle
before throwing. Unproven voice cleanup marks the backend broken so replacement
voices cannot accumulate.

Runtime OGG files are decoded by `StbVorbisDecoder`. Streaming uses exactly
three OpenAL buffers of 4,096 frames each. Compressed direct buffers have an
explicit caller release boundary; decoder PCM/native allocations are released
deterministically. Ordinary end-of-track restarts the selected authored track;
the implementation does not claim gapless looping.

`MusicManager` owns one Gaia voice for Main Menu and exploration. Pause-origin
routes duck to 70%. Startup (2.0 s), route (0.35 s), and focus (0.20 s)
envelopes are independent and multiplicative. `Legacy` is registered for
future explicit routing but is not forced into ordinary exploration. Audio
failures establish coherent silence and do not mutate gameplay state.

## Shutdown

Product shutdown closes the active session, then `MusicManager`, then the final
settings close policy. `ShutdownCoordinator` continues reverse-order cleanup:
settings, music manager, audio device, then Engine/window. Repeated close is
idempotent, and later failures are suppressed under the first distinct failure
without self-suppression.

## Phase 14 seam

`SaveCatalog` is a read-only future-facing boundary. Phase 13 uses
`EmptySaveCatalog`, renders `Load World - Available in Phase 14` as disabled,
and defines no save files, serialization, autosave, restoration transaction, or
Save & Quit behavior. Phase 14 must preserve the single `ProductLoop`, lazy
`GameSession`, fixed-step authority, and UI/domain separation when it replaces
the empty adapter.
