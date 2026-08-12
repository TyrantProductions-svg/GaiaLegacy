package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.NewWorldRequest;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenRouter;
import com.gaia.shell.ProductShellController;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.NewWorldDraftSnapshot;
import com.gaia.shell.world.WorldSlotsController;
import com.overlord.core.input.UiInputSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NewWorldPresenterTest {
    private static final SaveGameId ID = SaveGameId.parse(
            "00000000-0000-0000-0000-000000000014");

    @Test
    void mainMenuNewWorldOpensTheSetupRouteInsteadOfStartingAHiddenDefaultSession() {
        ProductScreenPresenter presenter = presenter(
                new NewWorldDraftController(new EmptySaveCatalog()));
        ProductUiLayout layout = presenter.present(
                ProductScreenPresenterTest.snapshot(ScreenId.MAIN_MENU),
                ProductScreenPresenterTest.context());

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                layout.region(UiActionId.NEW_WORLD).command());
    }

    @Test
    void backReturnsBothSaveSetupScreensToMainMenu() {
        ScreenRouter newWorldRouter = ScreenRouter.mainMenu();
        ProductShellController newWorldShell = new ProductShellController(newWorldRouter);
        newWorldShell.handle(new ScreenCommand.OpenNewWorldSetup());
        newWorldShell.handle(new ScreenCommand.Back());
        assertEquals(ScreenId.MAIN_MENU, newWorldShell.snapshot().screen());

        ScreenRouter slotsRouter = ScreenRouter.mainMenu();
        ProductShellController slotsShell = new ProductShellController(slotsRouter);
        slotsShell.handle(new ScreenCommand.OpenWorldSlots());
        slotsShell.handle(new ScreenCommand.Back());
        assertEquals(ScreenId.MAIN_MENU, slotsShell.snapshot().screen());
    }

    @Test
    void setupPresentsNameSeedCreateAndBackAndRoutesUnicodeEditing() {
        NewWorldDraftController draft = new NewWorldDraftController(new EmptySaveCatalog());
        ProductScreenPresenter presenter = presenter(draft);
        ProductUiLayout layout = presenter.present(
                ProductScreenPresenterTest.snapshot(ScreenId.NEW_WORLD_SETUP),
                ProductScreenPresenterTest.context());

        assertEquals(
                List.of(
                        UiActionId.NEW_WORLD_NAME,
                        UiActionId.NEW_WORLD_SEED,
                        UiActionId.CREATE_WORLD,
                        UiActionId.BACK),
                layout.hitRegions().stream().map(UiHitRegion::id).toList());

        ProductScreenInputController input = new ProductScreenInputController();
        UiHitRegion name = layout.region(UiActionId.NEW_WORLD_NAME);
        input.routeNewWorld(click(name, 1L), layout, draft, () -> ID);
        for (int index = 0; index < "New World".length(); index++) {
            input.routeNewWorld(key(GLFW_KEY_BACKSPACE, 2L + index), layout, draft, () -> ID);
        }
        input.routeNewWorld(typed("Gaia 🌍", 20L), layout, draft, () -> ID);

        assertEquals("Gaia 🌍", draft.snapshot().name());
        assertEquals(NewWorldDraftSnapshot.Field.NAME, draft.snapshot().focusedField());
    }

    @Test
    void createProducesValidatedTypedRequestAndEscapeResetsTheDraftBeforeBack() {
        NewWorldDraftController draft = new NewWorldDraftController(new EmptySaveCatalog());
        ProductScreenPresenter presenter = presenter(draft);
        ProductUiLayout layout = presenter.present(
                ProductScreenPresenterTest.snapshot(ScreenId.NEW_WORLD_SETUP),
                ProductScreenPresenterTest.context());
        ProductScreenInputController input = new ProductScreenInputController();
        UiHitRegion create = layout.region(UiActionId.CREATE_WORLD);

        assertEquals(
                new ScreenCommand.CreateWorld(new NewWorldRequest(ID, "New World", 12345L)),
                input.routeNewWorld(click(create, 1L), layout, draft, () -> ID)
                        .orElseThrow());

        draft.acceptCodePoints(List.of((int) 'X'));
        assertEquals(
                new ScreenCommand.Back(),
                input.routeNewWorld(key(GLFW_KEY_ESCAPE, 2L), layout, draft, () -> ID)
                        .orElseThrow());
        assertEquals("New World", draft.snapshot().name());
        assertEquals("12345", draft.snapshot().seedText());
        assertTrue(draft.snapshot().diagnostic().isEmpty());
    }

    private static ProductScreenPresenter presenter(NewWorldDraftController draft) {
        var defaults = SettingsDefaults.schemaV1();
        return new ProductScreenPresenter(
                new EmptySaveCatalog(),
                ProductScreenPresenterTest.textRenderer(),
                () -> new SettingsDraftSnapshot(
                        defaults, defaults, false, Optional.empty()),
                draft,
                new WorldSlotsController(new EmptySaveCatalog(), 4));
    }

    private static UiInputSnapshot click(UiHitRegion region, long sampleId) {
        return input(
                Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of(),
                region.centerX(),
                region.centerY(),
                sampleId);
    }

    private static UiInputSnapshot key(int key, long sampleId) {
        return input(Set.of(key), Set.of(), List.of(), -1.0d, -1.0d, sampleId);
    }

    private static UiInputSnapshot typed(String value, long sampleId) {
        return input(
                Set.of(), Set.of(), value.codePoints().boxed().toList(),
                -1.0d, -1.0d, sampleId);
    }

    private static UiInputSnapshot input(
            Set<Integer> keys,
            Set<Integer> mouse,
            List<Integer> typed,
            double x,
            double y,
            long sampleId) {
        return new UiInputSnapshot(
                keys, keys, mouse, mouse, List.of(), typed, x, y, true, sampleId);
    }
}
