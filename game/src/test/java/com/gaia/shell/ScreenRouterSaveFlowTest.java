package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.format.SaveGameId;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScreenRouterSaveFlowTest {
    private static final SaveGameId SAVE_A = SaveGameId.parse(
            "00000000-0000-0000-0000-00000000000a");

    @Test
    void mainMenuOpensAndClosesNewWorldAndWorldSlotsRoutes() {
        ScreenRouter router = ScreenRouter.mainMenu();

        router.openNewWorldSetup();
        assertEquals(ScreenId.NEW_WORLD_SETUP, router.snapshot().screen());
        router.back();
        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());

        router.openWorldSlots();
        assertEquals(ScreenId.WORLD_SLOTS, router.snapshot().screen());
        router.back();
        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());
    }

    @Test
    void savingRouteRejectsOrdinaryNavigationWithoutChangingState() {
        ScreenRouter router = pausedRouter();
        router.beginSaving();
        ProductShellSnapshot before = router.snapshot();

        assertThrows(IllegalStateException.class, router::back);
        assertThrows(
                IllegalStateException.class,
                () -> router.openSettings(ScreenReturnTarget.PAUSED));
        assertThrows(IllegalStateException.class, router::openWorldSlots);
        assertThrows(
                IllegalStateException.class,
                () -> router.openModal(ModalId.UNSAVED_PROGRESS_CONFIRMATION));

        assertEquals(before, router.snapshot());
    }

    @ParameterizedTest(name = "{0} x {1} legal={2}")
    @MethodSource("newModalMatrix")
    void saveModalMatrixRequiresWorldSlotsAndTargetIdentity(
            ScreenId screen,
            ModalId modal,
            boolean legal) {
        ScreenRouter router = routerAt(screen);
        ProductShellSnapshot before = router.snapshot();

        if (legal) {
            openSaveModal(router, modal, SAVE_A);
            assertEquals(modal, router.snapshot().modal().orElseThrow());
            assertEquals(Optional.of(SAVE_A), router.modalSaveGameId());
            router.dismissModal();
            assertFalse(router.modalSaveGameId().isPresent());
        } else {
            assertThrows(
                    IllegalStateException.class,
                    () -> openSaveModal(router, modal, SAVE_A));
            assertEquals(before, router.snapshot());
            assertFalse(router.modalSaveGameId().isPresent());
        }
    }

    @Test
    void genericModalOpeningCannotCreateAnIdentityFreeSaveConfirmation() {
        ScreenRouter router = ScreenRouter.mainMenu();
        router.openWorldSlots();
        ProductShellSnapshot before = router.snapshot();

        assertThrows(
                IllegalStateException.class,
                () -> router.openModal(ModalId.DELETE_WORLD_CONFIRMATION));
        assertThrows(
                IllegalStateException.class,
                () -> router.openModal(ModalId.RECOVER_BACKUP_CONFIRMATION));

        assertEquals(before, router.snapshot());
        assertFalse(router.modalSaveGameId().isPresent());
    }

    private static Stream<Arguments> newModalMatrix() {
        return Stream.of(ScreenId.values())
                .flatMap(screen -> Stream.of(
                                ModalId.DELETE_WORLD_CONFIRMATION,
                                ModalId.RECOVER_BACKUP_CONFIRMATION)
                        .map(modal -> Arguments.of(
                                screen,
                                modal,
                                screen == ScreenId.WORLD_SLOTS)));
    }

    private static void openSaveModal(
            ScreenRouter router,
            ModalId modal,
            SaveGameId id) {
        switch (modal) {
            case DELETE_WORLD_CONFIRMATION -> router.openDeleteWorldConfirmation(id);
            case RECOVER_BACKUP_CONFIRMATION -> router.openRecoverBackupConfirmation(id);
            default -> throw new AssertionError(modal);
        }
    }

    private static ScreenRouter routerAt(ScreenId screen) {
        ScreenRouter router = ScreenRouter.mainMenu();
        switch (screen) {
            case MAIN_MENU -> {
                return router;
            }
            case NEW_WORLD_SETUP -> router.openNewWorldSetup();
            case WORLD_SLOTS -> router.openWorldSlots();
            case LOADING -> router.beginLoading();
            case PLAYING -> {
                router.beginLoading();
                router.loadingSucceeded();
            }
            case PAUSED -> preparePaused(router);
            case SAVING -> {
                preparePaused(router);
                router.beginSaving();
            }
            case SETTINGS -> router.openSettings(ScreenReturnTarget.MAIN_MENU);
            case CONTROLS -> router.openControls(ScreenReturnTarget.MAIN_MENU);
        }
        return router;
    }

    private static ScreenRouter pausedRouter() {
        ScreenRouter router = ScreenRouter.mainMenu();
        preparePaused(router);
        return router;
    }

    private static void preparePaused(ScreenRouter router) {
        router.beginLoading();
        router.loadingSucceeded();
        router.pause();
    }
}
