package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.LoadWorldRequest;
import com.gaia.session.NewWorldRequest;
import org.junit.jupiter.api.Test;

class ProductSaveLoadIntegrationTest {
    private static final SaveGameId ID = SaveGameId.parse(
            "00000000-0000-0000-0000-000000000014");

    @Test
    void createAndLoadCommandsPublishTheirExactTypedRequests() {
        ScreenRouter newRouter = ScreenRouter.mainMenu();
        ProductShellController newController = new ProductShellController(newRouter);
        NewWorldRequest newRequest = new NewWorldRequest(ID, "世界 One", -77L);
        newController.handle(new ScreenCommand.OpenNewWorldSetup());

        ProductLifecycleIntent.StartNewWorld start = assertInstanceOf(
                ProductLifecycleIntent.StartNewWorld.class,
                newController.handle(new ScreenCommand.CreateWorld(newRequest)));

        assertEquals(newRequest, start.request());
        assertEquals(ScreenId.LOADING, newController.snapshot().screen());

        ScreenRouter loadRouter = ScreenRouter.mainMenu();
        ProductShellController loadController = new ProductShellController(loadRouter);
        loadController.handle(new ScreenCommand.OpenWorldSlots());

        ProductLifecycleIntent.LoadWorld load = assertInstanceOf(
                ProductLifecycleIntent.LoadWorld.class,
                loadController.handle(new ScreenCommand.LoadWorld(ID)));

        assertEquals(new LoadWorldRequest(ID), load.request());
        assertEquals(ScreenId.LOADING, loadController.snapshot().screen());
    }

    @Test
    void savePoliciesEnterSavingAndReturnOrCloseOnlyAfterSuccess() {
        ProductShellController stay = pausedController();

        ProductLifecycleIntent.Save save = assertInstanceOf(
                ProductLifecycleIntent.Save.class,
                stay.handle(new ScreenCommand.Save(), true));

        assertEquals(ProductLifecycleIntent.SavePolicy.SAVE_AND_STAY, save.policy());
        assertEquals(ScreenId.SAVING, stay.snapshot().screen());
        stay.savingSucceeded(save.policy());
        assertEquals(ScreenId.PAUSED, stay.snapshot().screen());

        ProductShellController quit = pausedController();
        ProductLifecycleIntent.Save saveAndQuit = assertInstanceOf(
                ProductLifecycleIntent.Save.class,
                quit.handle(new ScreenCommand.SaveAndQuit(), true));
        assertEquals(ProductLifecycleIntent.SavePolicy.SAVE_AND_QUIT, saveAndQuit.policy());
        assertEquals(ScreenId.SAVING, quit.snapshot().screen());
        assertInstanceOf(
                ProductLifecycleIntent.CloseActiveSession.class,
                quit.savingSucceeded(saveAndQuit.policy()));
        assertEquals(ScreenId.MAIN_MENU, quit.snapshot().screen());
    }

    @Test
    void returnToMenuWarnsOnlyForDirtySessions() {
        ProductShellController clean = pausedController();
        assertInstanceOf(
                ProductLifecycleIntent.CloseActiveSession.class,
                clean.handle(new ScreenCommand.ReturnToMainMenu(), false));
        assertEquals(ScreenId.MAIN_MENU, clean.snapshot().screen());

        ProductShellController dirty = pausedController();
        assertInstanceOf(
                ProductLifecycleIntent.None.class,
                dirty.handle(new ScreenCommand.ReturnToMainMenu(), true));
        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                dirty.snapshot().modal().orElseThrow());
        assertInstanceOf(
                ProductLifecycleIntent.CloseActiveSession.class,
                dirty.handle(new ScreenCommand.Confirm(), true));
        assertEquals(ScreenId.MAIN_MENU, dirty.snapshot().screen());
    }

    @Test
    void deleteAndRecoveryRequireConfirmationAndRetainStableSlotIdentity() {
        ScreenRouter deleteRouter = ScreenRouter.mainMenu();
        ProductShellController deleteController = new ProductShellController(deleteRouter);
        deleteController.handle(new ScreenCommand.OpenWorldSlots());
        assertInstanceOf(
                ProductLifecycleIntent.None.class,
                deleteController.handle(new ScreenCommand.DeleteWorld(ID)));
        assertEquals(ID, deleteRouter.modalSaveGameId().orElseThrow());
        ProductLifecycleIntent.DeleteWorld delete = assertInstanceOf(
                ProductLifecycleIntent.DeleteWorld.class,
                deleteController.handle(new ScreenCommand.Confirm()));
        assertEquals(ID, delete.saveGameId());
        assertTrue(deleteController.snapshot().modal().isEmpty());

        ScreenRouter recoveryRouter = ScreenRouter.mainMenu();
        ProductShellController recoveryController = new ProductShellController(recoveryRouter);
        recoveryController.handle(new ScreenCommand.OpenWorldSlots());
        assertInstanceOf(
                ProductLifecycleIntent.None.class,
                recoveryController.handle(new ScreenCommand.RecoverBackup(ID)));
        assertEquals(ID, recoveryRouter.modalSaveGameId().orElseThrow());
        ProductLifecycleIntent.RecoverBackup recover = assertInstanceOf(
                ProductLifecycleIntent.RecoverBackup.class,
                recoveryController.handle(new ScreenCommand.Confirm()));
        assertEquals(ID, recover.saveGameId());
        assertTrue(recoveryController.snapshot().modal().isEmpty());
    }

    private static ProductShellController pausedController() {
        ProductShellController controller = new ProductShellController(ScreenRouter.mainMenu());
        controller.handle(new ScreenCommand.OpenNewWorldSetup());
        controller.handle(new ScreenCommand.CreateWorld(
                new NewWorldRequest(ID, "New World", 12345L)));
        controller.loadingSucceeded();
        controller.togglePlaying();
        assertEquals(ScreenId.PAUSED, controller.snapshot().screen());
        return controller;
    }
}
