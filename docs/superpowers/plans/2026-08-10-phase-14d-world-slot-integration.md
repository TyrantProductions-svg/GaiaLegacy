# Phase 14D World Slot and Product Shell Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Unicode New World input, paged World Slots, load/delete/recover flows, Pause Save/Save & Quit, and exact session persistence lifecycle without changing gameplay authority.

**Architecture:** Dynamic save controls carry typed commands and stable IDs through immutable UI layouts. `ProductLoop` remains the single lifecycle coordinator and invokes one fresh-session launcher plus `GameSession` save/checkpoint operations. Saving/loading blocks fixed gameplay and invalidates input.

**Tech Stack:** Java 17, GLFW input through existing `InputManager`, immutable UI draw/hit regions, JUnit 6.1.1, production save repository from Gates 14A-C.

## Global Constraints

- All Phase 14A-C and Phase 13 product-shell constraints remain in force.
- Do not add a second loop or allow UI direct access to domain services/filesystem codecs.
- Character input belongs to the existing owner-thread `InputManager` and never reaches fixed gameplay input.
- Loading/Saving shows no fabricated percentage.
- No timed autosave or background save thread.
- Do not stage, commit, push, create a PR, or merge.

---

## File Structure

Extend the existing shell/router/presenter rather than creating a second UI framework. Add typed dynamic UI control identity under `com.gaia.shell.ui`, draft state under `com.gaia.shell.world`, and persistence-aware session launch/save contracts under `com.gaia.session`.

### Task 1: Immutable Unicode UI character input

**Files:**
- Modify: `engine/src/main/java/com/overlord/core/input/UiInputSnapshot.java`
- Modify: `engine/src/main/java/com/overlord/core/input/InputManager.java`
- Test: `engine/src/test/java/com/overlord/core/input/UiInputSnapshotTest.java`
- Test: `engine/src/test/java/com/overlord/core/input/InputManagerTest.java`
- Test: `engine/src/test/java/com/overlord/core/input/InputManagerCharacterInputTest.java`

**Interfaces:**
- Adds `List<Integer> typedCodePoints()` to immutable UI capture.
- InputManager installs exactly one GLFW char callback beside existing callbacks, bounds per-frame text, clears after capture, and clears on focus/eligibility invalidation.

- [ ] **Step 1: Write RED for Unicode, bounds, clearing, and no replay**

```java
@Test
void capturesUnicodeCodePointsOnceAndClearsOnNextSample() {
    manager.onCharacter('G');
    manager.onCharacter(0x4E16);
    assertEquals(List.of((int)'G', 0x4E16), manager.captureUiInput(1).typedCodePoints());
    assertTrue(manager.captureUiInput(2).typedCodePoints().isEmpty());
}
```

Cover invalid surrogate/code point rejection, bounded overflow diagnostic, focus loss, route invalidation, close/idempotence, and no inclusion in `InputSnapshot`/`heldOnly()`.

- [ ] **Step 2: Run strict RED**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.core.input.*Character*' --tests com.overlord.core.input.UiInputSnapshotTest --console=plain --no-daemon
```

- [ ] **Step 3: Implement callback-owned bounded character queue**

Keep the callback on the GLFW owner thread and snapshot with `List.copyOf`. Preserve an overload/test factory for existing key/mouse-only fixtures by supplying an empty character list explicitly; do not silently drop production text.

```java
void onCharacter(int codePoint) {
    ownerThread.checkCurrent();
    if (Character.isValidCodePoint(codePoint) && typedCodePoints.size() < MAX_TYPED_PER_FRAME) {
        typedCodePoints.add(codePoint);
    }
}

List<Integer> drainTypedCodePoints() {
    List<Integer> snapshot = List.copyOf(typedCodePoints);
    typedCodePoints.clear();
    return snapshot;
}
```

- [ ] **Step 4: Run full engine input GREEN**

```powershell
.\gradlew.bat :engine:test --tests 'com.overlord.core.input.*' --console=plain --no-daemon
```

- [ ] **Step 5: Mandatory immediate Windows runtime smoke**

Run:

```powershell
.\gradlew.bat :game:run --console=plain --no-daemon
```

At this intermediate point verify launch, existing Main Menu mouse/keyboard navigation, focus loss/recovery, New World, pause/resume, and clean exit. If the agent cannot enter text yet because Task 3 is not wired, verify the callback does not break existing input and repeat the full text smoke after Task 3. Stop on any GLFW/input regression.

- [ ] **Step 6: Review checkpoint**

Record actual runtime evidence; do not infer it from tests. Run `git diff --check`. Do not commit.

### Task 2: Typed dynamic UI controls and closed save routes

**Files:**
- Create: `game/src/main/java/com/gaia/shell/ui/UiControlId.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/UiActionId.java`
- Create: `game/src/main/java/com/gaia/shell/ui/WorldSlotControlId.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/UiHitRegion.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductUiLayout.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`
- Modify: `game/src/main/java/com/gaia/shell/ScreenId.java`
- Modify: `game/src/main/java/com/gaia/shell/ModalId.java`
- Modify: `game/src/main/java/com/gaia/shell/ScreenCommand.java`
- Modify: `game/src/main/java/com/gaia/shell/ScreenRouter.java`
- Test: `game/src/test/java/com/gaia/shell/ScreenRouterSaveFlowTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/DynamicSaveControlInputTest.java`

**Interfaces:**
- `UiControlId` is a closed identity implemented by static `UiActionId` and dynamic `WorldSlotControlId(SaveGameId, WorldSlotAction)`.
- `UiHitRegion` carries both immutable control identity and typed `ScreenCommand`; activation returns that command directly.
- Adds routes `NEW_WORLD_SETUP`, `WORLD_SLOTS`, `SAVING` and modals `DELETE_WORLD_CONFIRMATION`, `RECOVER_BACKUP_CONFIRMATION`.

- [ ] **Step 1: Write route/modal matrix RED**

Test every new route against every modal. Delete confirmation is legal only on World Slots with selected ID; recovery only for a recoverable ID; Saving accepts no ordinary input. Illegal pairs leave router state byte-for-byte unchanged.

- [ ] **Step 2: Write dynamic identity RED**

```java
assertEquals(new ScreenCommand.LoadWorld(SAVE_B), click(layoutFor(SAVE_A, SAVE_B), rowB));
assertEquals(new WorldSlotControlId(SAVE_B, LOAD), highlightedControl());
```

Shuffle and repaginate catalog rows between frames; focus must follow the stable control ID or clear, never activate the old row index.

- [ ] **Step 3: Run RED, implement typed regions, run GREEN**

```powershell
.\gradlew.bat :game:test --tests com.gaia.shell.ScreenRouterSaveFlowTest --tests com.gaia.shell.ui.DynamicSaveControlInputTest --console=plain --no-daemon
```

```java
public record UiHitRegion(UiControlId id, UiRect bounds, ScreenCommand command,
                          boolean enabled) {
    ScreenCommand activate(double x, double y) {
        if (!enabled || !bounds.contains(x, y)) {
            return ScreenCommand.none();
        }
        return command;
    }
}
```

- [ ] **Step 4: Run Phase 13 UI regression matrix**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.shell.ui.*' --tests com.gaia.shell.ScreenRouterTest --tests com.gaia.shell.ProductShellControllerTest --console=plain --no-daemon
```

- [ ] **Step 5: Review checkpoint**

Confirm static Phase 13 controls still map exactly once and disabled controls cannot focus/click. Run `git diff --check`. Do not commit.

### Task 3: New World draft and paged World Slots presentation

**Files:**
- Create: `game/src/main/java/com/gaia/shell/world/NewWorldDraftController.java`
- Create: `game/src/main/java/com/gaia/shell/world/NewWorldDraftSnapshot.java`
- Create: `game/src/main/java/com/gaia/shell/world/WorldSlotsController.java`
- Create: `game/src/main/java/com/gaia/shell/world/WorldSlotsSnapshot.java`
- Create: `game/src/main/java/com/gaia/session/NewWorldRequest.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductScreenPresenter.java`
- Modify: `game/src/main/java/com/gaia/shell/ui/ProductScreenInputController.java`
- Test: `game/src/test/java/com/gaia/shell/world/NewWorldDraftControllerTest.java`
- Test: `game/src/test/java/com/gaia/shell/world/WorldSlotsControllerTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/WorldSlotsPresenterTest.java`
- Test: `game/src/test/java/com/gaia/shell/ui/NewWorldPresenterTest.java`

**Interfaces:**
- `NewWorldDraftController.acceptCodePoints`, `.backspace`, `.selectField`, and `.createRequest(Supplier<SaveGameId>)` return a validated `NewWorldRequest(SaveGameId, displayName, seed)` or diagnostics.
- `WorldSlotsController` consumes immutable catalog snapshots, exposes fixed-size pages, selected stable ID, and typed commands.

- [ ] **Step 1: Write New World editing/validation RED**

Cover default `New World`/`12345`, Unicode code points, 1/40/41 boundaries, trim, control/path separators, case-fold duplicate, signed long min/max/overflow, backspace by code point, field focus, Escape/Back, and stale text clearing.

- [ ] **Step 2: Write World Slots paging RED**

Cover empty catalog, page boundaries, catalog shrink/grow, modified-desc/ID tie ordering, VALID/RECOVERABLE/CORRUPT/UNSUPPORTED actions, mouse/keyboard focus, DPI 1.0/1.25/1.5/2.0/asymmetric, and modal exclusivity.

- [ ] **Step 3: Run RED, implement, run GREEN**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.shell.world.*' --tests com.gaia.shell.ui.WorldSlotsPresenterTest --tests com.gaia.shell.ui.NewWorldPresenterTest --console=plain --no-daemon
```

```java
Optional<NewWorldRequest> createRequest(Supplier<SaveGameId> ids) {
    String validatedName = validator.requireWorldName(name.codePoints().toArray());
    long validatedSeed = validator.requireSignedLong(seedText);
    if (catalog.containsCaseFolded(validatedName)) {
        return Optional.empty();
    }
    return Optional.of(new NewWorldRequest(ids.get(), validatedName, validatedSeed));
}
```

- [ ] **Step 4: Real Windows Unicode and pointer smoke**

Launch the actual game. Type ASCII plus at least one non-ASCII character into the name field, edit seed, move pointer across exact button/field boundaries, create/back, resize, Alt+Tab, and verify no input replay or click offset. Record actual observations and exit cleanly.

- [ ] **Step 5: Review checkpoint**

Confirm no UI class imports World, inventory, worlditem, codec, or filesystem service. Run `git diff --check`. Do not commit.

### Task 4: Persistence-aware GameSession launcher and save lifecycle

**Files:**
- Create: `game/src/main/java/com/gaia/session/GameSessionLauncher.java`
- Create: `game/src/main/java/com/gaia/session/LoadWorldRequest.java`
- Create: `game/src/main/java/com/gaia/session/GameSessionSaveResult.java`
- Modify: `game/src/main/java/com/gaia/session/GameSession.java`
- Modify: `game/src/main/java/com/gaia/session/GameSessionFactory.java`
- Create: `game/src/main/java/com/gaia/save/session/SaveCoordinator.java`
- Test: `game/src/test/java/com/gaia/session/GameSessionLauncherTest.java`
- Test: `game/src/test/java/com/gaia/save/session/SaveCoordinatorTest.java`
- Test: `game/src/test/java/com/gaia/session/GameSessionSaveLifecycleTest.java`

**Interfaces:**
- `GameSessionLauncher.newWorld(NewWorldRequest)` and `.loadWorld(LoadWorldRequest)` always create fresh sessions.
- `GameSession.save()` returns a typed result and marks checkpoint only after `AtomicSaveStore` success.
- `GameSession.hasUnsavedChanges()` compares current persistence revision to saved checkpoint.

- [ ] **Step 1: Write initial-save and load RED**

New World must generate, reach canonical readiness, commit initial save, then become READY. Initial-save failure closes the session and publishes no loadable catalog row. Load reads/validates immutable snapshot off the owner thread if desired, but applies restore on owner thread and bypasses world generation.

- [ ] **Step 2: Write manual save RED**

Assert Save from paused captures once, writes once, marks only the exact revision, and remains READY. Write failure preserves old checkpoint and live session. Save & Quit is represented as save success followed by close, never close-before-save.

- [ ] **Step 3: Run RED, implement, run GREEN**

```powershell
.\gradlew.bat :game:test --tests com.gaia.session.GameSessionLauncherTest --tests com.gaia.save.session.SaveCoordinatorTest --tests com.gaia.session.GameSessionSaveLifecycleTest --console=plain --no-daemon
```

```java
CompletionStage<OwnedGameSession> loadWorld(LoadWorldRequest request) {
    return decoder.readValidated(request.id())
            .thenCompose(snapshot -> ownerThread.call(() ->
                    sessionFactory.restoreFresh(snapshot)));
}

GameSessionSaveResult save(OwnedGameSession session, Instant now) {
    SessionSaveCaptureResult capture = session.captureForSave();
    SaveWriteResult write = store.save(capture.snapshot(), now);
    return write.isSuccess()
            ? session.markSaved(capture.persistenceRevision(), write.manifest())
            : GameSessionSaveResult.failed(write.diagnostic());
}
```

- [ ] **Step 4: Run lifecycle/WorldItem regressions**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.session.*' --tests 'com.gaia.worlditem.*' --tests 'com.gaia.inventory.*' --console=plain --no-daemon
```

- [ ] **Step 5: Review checkpoint**

Confirm settings are absent from archives, session restore is fresh, no pending reservation is persisted, and no save runs per frame. Run `git diff --check`. Do not commit.

### Task 5: ProductLoop and shell Save/Load orchestration

**Files:**
- Create: `game/src/main/java/com/gaia/shell/ProductLifecycleIntent.java`
- Modify: `game/src/main/java/com/gaia/shell/ProductShellController.java`
- Modify: `game/src/main/java/com/gaia/shell/ProductLoop.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Test: `game/src/test/java/com/gaia/shell/ProductSaveLoadIntegrationTest.java`
- Test: `game/src/test/java/com/gaia/shell/ProductLoopSaveOrderingTest.java`
- Test: `game/src/test/java/com/gaia/GameBootstrapSaveCompositionTest.java`

**Interfaces:**
- Replace the payload-free lifecycle enum with a sealed intent carrying `NewWorldRequest`, `LoadWorldRequest`, nested `SavePolicy { SAVE_AND_STAY, SAVE_AND_QUIT }`, `SaveGameId`, or close/exit.
- ProductLoop renders one static SAVING frame before executing synchronous save on the next owner-thread frame.
- Save policy is `SAVE_AND_STAY` or `SAVE_AND_QUIT`.

- [ ] **Step 1: Write orchestration RED**

Cover New, Load, Save, Save & Quit, clean Return, dirty Return confirmation, delete, recovery, cancel/failure, second fresh session, catalog refresh, and repeated commands. Assert exact order:

```text
route SAVING -> render/swap -> next frame capture -> write -> checkpoint
-> (PAUSED | close session -> refresh catalog -> MAIN_MENU)
```

- [ ] **Step 2: Write no-input/no-time RED**

Saving/loading frames run zero fixed steps, discard remainder, clear text/key/mouse edges, preserve music route without duplicate Gaia, and require physical release before Playing resumes.

- [ ] **Step 3: Run RED, implement, run GREEN**

```powershell
.\gradlew.bat :game:test --tests com.gaia.shell.ProductSaveLoadIntegrationTest --tests com.gaia.shell.ProductLoopSaveOrderingTest --tests com.gaia.GameBootstrapSaveCompositionTest --console=plain --no-daemon
```

```java
if (pendingIntent instanceof ProductLifecycleIntent.Save save) {
    router.route(ScreenId.SAVING);
    renderAndSwapOnce();
    input.invalidateUiAndGameplayEdges();
    GameSessionSaveResult result = activeSession.save();
    routeAfterSave(save.policy(), result);
    pendingIntent = ProductLifecycleIntent.none();
}
```

- [ ] **Step 4: Run complete shell/session/settings/audio regression**

```powershell
.\gradlew.bat :game:test --tests 'com.gaia.shell.*' --tests 'com.gaia.session.*' --tests 'com.gaia.settings.*' --tests 'com.gaia.audio.*' --console=plain --no-daemon
```

- [ ] **Step 5: Mandatory integrated Windows runtime smoke**

Launch development runtime and verify name/seed entry, New World initial save, mutate, pause Save, Save & Quit, relaunch, Load, exact state, Return, delete/recovery on a copied test slot, resize/Alt+Tab, audio continuity, and clean exit. Never corrupt the only human save.

- [ ] **Step 6: Review checkpoint**

Run `git diff --check` and full status. Confirm no Loading percentage, autosave timer, background writer, or Phase 15 behavior was introduced. Do not commit.

### Task 6: Gate 14D documentation checkpoint

**Files:**
- Update: `CONTROLS.md`
- Update: `KNOWN_ISSUES.md`
- Update: `docs/architecture/save-load-v1.md`
- Create: `docs/testing/phase-14-save-load-acceptance.md`

**Interfaces:**
- Records implemented controls/routes and actual Windows status without claiming Gate 14E completion.

- [ ] **Step 1: Update factual UI/control documentation**

Document New World fields, World Slots paging/actions, Pause Save/Save & Quit, corruption diagnostics, save root policy, and explicit absence of autosave/loading percentage.

- [ ] **Step 2: Record actual focused/runtime evidence**

Include exact commands/results and human-observed runtime actions. Mark macOS `NOT RUN / PENDING` until actually tested.

- [ ] **Step 3: Gate 14D audit**

```powershell
git diff --check
git status --short --untracked-files=all
git diff --name-status
```

Do not stage or commit.
