package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScreenRouterTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("legalTransitions")
    void legalTransitionReachesItsDestination(RouteTransition transition) {
        ScreenRouter router = ScreenRouter.mainMenu();
        transition.prepare().accept(router);

        transition.action().accept(router);

        assertEquals(transition.destination(), router.snapshot().screen());
        assertFalse(router.snapshot().modal().isPresent());
    }

    @Test
    void settingsReturnsToTheScreenThatOpenedIt() {
        ScreenRouter router = ScreenRouter.mainMenu();
        router.openSettings(ScreenReturnTarget.MAIN_MENU);
        router.back();
        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());

        router.beginLoading();
        router.loadingSucceeded();
        router.pause();
        router.openSettings(ScreenReturnTarget.PAUSED);
        router.back();
        assertEquals(ScreenId.PAUSED, router.snapshot().screen());
    }

    @Test
    void controlsReturnsToTheScreenThatOpenedIt() {
        ScreenRouter router = pausedRouter();

        router.openControls(ScreenReturnTarget.PAUSED);
        router.back();

        assertEquals(ScreenId.PAUSED, router.snapshot().screen());
        assertFalse(router.snapshot().returnTarget().isPresent());
    }

    @Test
    void modalBlocksUnderlyingTransition() {
        ScreenRouter router = ScreenRouter.mainMenu();
        router.openModal(ModalId.QUIT_CONFIRMATION);

        assertThrows(IllegalStateException.class, router::beginLoading);

        assertEquals(
                ModalId.QUIT_CONFIRMATION,
                router.snapshot().modal().orElseThrow());
        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());
    }

    @ParameterizedTest(name = "{0} x {1} legal={2}")
    @MethodSource("modalLegalityMatrix")
    void modalLegalityMatrixRejectsEveryInvalidPairWithoutChangingState(
            ScreenId screen,
            ModalId modal,
            boolean legal) {
        ScreenRouter router = routerAt(screen);
        ProductShellSnapshot before = router.snapshot();

        if (legal) {
            router.openModal(modal);
            assertEquals(screen, router.snapshot().screen());
            assertEquals(modal, router.snapshot().modal().orElseThrow());
        } else {
            assertThrows(
                    IllegalStateException.class,
                    () -> router.openModal(modal));
            assertEquals(before, router.snapshot());
        }
    }

    @Test
    void dismissModalIsIdempotent() {
        ScreenRouter router = ScreenRouter.mainMenu();
        router.openModal(ModalId.QUIT_CONFIRMATION);

        router.dismissModal();
        router.dismissModal();

        assertFalse(router.snapshot().modal().isPresent());
        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());
    }

    @Test
    void completedReturnToMainMenuIsIdempotent() {
        ScreenRouter router = pausedRouter();

        router.returnedToMainMenu();
        router.returnedToMainMenu();

        assertEquals(ScreenId.MAIN_MENU, router.snapshot().screen());
    }

    @Test
    void illegalTransitionLeavesStateUnchanged() {
        ScreenRouter router = pausedRouter();
        ProductShellSnapshot before = router.snapshot();

        assertThrows(IllegalStateException.class, router::beginLoading);

        assertEquals(before, router.snapshot());
    }

    @Test
    void mismatchedReturnTargetIsRejectedWithoutChangingState() {
        ScreenRouter router = ScreenRouter.mainMenu();
        ProductShellSnapshot before = router.snapshot();

        assertThrows(
                IllegalStateException.class,
                () -> router.openSettings(ScreenReturnTarget.PAUSED));

        assertEquals(before, router.snapshot());
    }

    private static Stream<RouteTransition> legalTransitions() {
        return Stream.of(
                new RouteTransition(
                        "main menu starts loading",
                        ignored -> {},
                        ScreenRouter::beginLoading,
                        ScreenId.LOADING),
                new RouteTransition(
                        "loading enters play after success",
                        ScreenRouter::beginLoading,
                        ScreenRouter::loadingSucceeded,
                        ScreenId.PLAYING),
                new RouteTransition(
                        "playing pauses",
                        router -> {
                            router.beginLoading();
                            router.loadingSucceeded();
                        },
                        ScreenRouter::pause,
                        ScreenId.PAUSED),
                new RouteTransition(
                        "paused resumes play",
                        ScreenRouterTest::preparePaused,
                        ScreenRouter::resume,
                        ScreenId.PLAYING));
    }

    private static Stream<Arguments> modalLegalityMatrix() {
        return Stream.of(ScreenId.values())
                .flatMap(screen -> Stream.of(ModalId.values())
                        .map(modal -> Arguments.of(
                                screen,
                                modal,
                                isLegalModalPair(screen, modal))));
    }

    private static boolean isLegalModalPair(ScreenId screen, ModalId modal) {
        return switch (modal) {
            case QUIT_CONFIRMATION -> screen == ScreenId.MAIN_MENU;
            case UNSAVED_PROGRESS_CONFIRMATION -> screen == ScreenId.PAUSED;
            case DIRTY_SETTINGS_CONFIRMATION -> screen == ScreenId.SETTINGS;
            case DELETE_WORLD_CONFIRMATION, RECOVER_BACKUP_CONFIRMATION -> false;
            case ERROR_ACKNOWLEDGEMENT ->
                    screen == ScreenId.MAIN_MENU
                            || screen == ScreenId.WORLD_SLOTS
                            || screen == ScreenId.PAUSED;
        };
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
            case SETTINGS -> router.openSettings(ScreenReturnTarget.MAIN_MENU);
            case CONTROLS -> router.openControls(ScreenReturnTarget.MAIN_MENU);
            case SAVING -> {
                preparePaused(router);
                router.beginSaving();
            }
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

    private record RouteTransition(
            String name,
            Consumer<ScreenRouter> prepare,
            Consumer<ScreenRouter> action,
            ScreenId destination) {
        @Override
        public String toString() {
            return name;
        }
    }
}
