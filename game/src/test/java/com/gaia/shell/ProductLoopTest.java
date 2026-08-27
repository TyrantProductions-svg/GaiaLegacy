package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionState;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.gaia.shell.ui.UiHitRegion;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductLoopTest {
    private static final double FIXED_STEP_SECONDS = 1.0d / 60.0d;

    @Test
    void callbackPollingPrecedesRoutingAndLifecycleBeforeImmutablePresentationAndSwap() {
        Fixture fixture = new Fixture();
        fixture.host().onNextPoll(() -> fixture.pressKey(GLFW_KEY_ENTER));

        fixture.loop().runFrame(FIXED_STEP_SECONDS);

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(
                List.of(
                        "poll-events",
                        "create-session",
                        "poll-load",
                        "discard-fixed-time",
                        "cursor-captured:true",
                        "advance-playing",
                        "render-session",
                        "render-product",
                        "swap-buffers"),
                fixture.events());
        assertNotNull(fixture.host().lastSessionFrame());
        assertNotNull(fixture.host().lastProductLayout());
        assertEquals(
                List.of(),
                fixture.host().lastProductLayout().frame().commands());
    }

    @Test
    void ordinaryPlayingAndMainMenuFramesNeverPublishOperationProgress() {
        Fixture mainMenu = new Fixture();
        assertDoesNotThrow(mainMenu::frame);
        assertEquals(ScreenId.MAIN_MENU, mainMenu.shell().snapshot().screen());
        assertTrue(mainMenu.shell().snapshot().operationProgress().isEmpty());

        Fixture playing = new Fixture();
        playing.enterPlaying();
        assertDoesNotThrow(playing::frame);
        assertEquals(ScreenId.PLAYING, playing.shell().snapshot().screen());
        assertTrue(playing.shell().snapshot().operationProgress().isEmpty());
    }

    @Test
    void repeatedMainMenuFramesDoNotRediscoverSavesWhileLoadIsDisabled() {
        AtomicInteger discoveries = new AtomicInteger();
        SaveCatalog catalog = () -> {
            discoveries.incrementAndGet();
            return List.of();
        };
        Fixture fixture = new Fixture(catalog);
        discoveries.set(0);

        fixture.frame();
        fixture.frame();
        fixture.frame();

        assertEquals(0, discoveries.get(),
                "the frame loop must consume a cached immutable catalog snapshot");
    }

    @Test
    void focusLossHardPausesAndResumeCannotReplayHeldQOrRightClick() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();
        fixture.pressKey(GLFW_KEY_Q);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_RIGHT);
        fixture.loseFocus();

        fixture.frame();

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(1, fixture.session().capturePausedCalls());
        assertEquals(1, fixture.session().discardFixedTimeCalls());
        assertEquals(List.of(false), fixture.host().cursorTransitions());

        fixture.restoreFocus();
        fixture.frame();
        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(List.of(false), fixture.host().cursorTransitions());

        fixture.tapShortcut(GLFW_KEY_ESCAPE);

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertFalse(fixture.session().lastInput().isKeyDown(GLFW_KEY_Q));
        assertFalse(fixture.session().lastInput()
                .isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals(2, fixture.session().discardFixedTimeCalls());
        assertEquals(List.of(false, true), fixture.host().cursorTransitions());
    }

    @Test
    void loadCompletionWhileUnfocusedSettlesAtPausedWithoutAdvancingGameplay() {
        Fixture fixture = new Fixture();
        fixture.session().completeLoadOnPoll(false);
        fixture.pressKey(GLFW_KEY_ENTER);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ENTER);
        assertEquals(ScreenId.LOADING, fixture.shell().snapshot().screen());

        fixture.resetRecordings();
        fixture.loseFocus();
        fixture.session().completeLoadOnPoll(true);
        fixture.frame();

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(1, fixture.session().capturePausedCalls());
        assertEquals(List.of(), fixture.host().cursorTransitions());
    }

    @Test
    void oneEnterOpensReturnWarningButOnlyAFreshEnterCanConfirmIt() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.pointAt(UiActionId.RETURN_TO_MAIN_MENU);
        fixture.pressKey(GLFW_KEY_ENTER);

        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ENTER);
        fixture.pointOutside();

        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                fixture.shell().snapshot().modal().orElseThrow());
        assertFalse(fixture.session().closed());

        fixture.frame();
        fixture.frame();

        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                fixture.shell().snapshot().modal().orElseThrow());
        assertFalse(fixture.session().closed());

        fixture.pressKey(GLFW_KEY_ENTER);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ENTER);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
        assertTrue(fixture.session().closed());
    }

    @Test
    void oneTabEdgeMovesMainMenuFocusExactlyOnceAcrossLaterFrames() {
        Fixture fixture = new Fixture();

        fixture.pressKey(GLFW_KEY_TAB);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_TAB);
        fixture.frame();
        fixture.frame();

        fixture.pressKey(GLFW_KEY_ENTER);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ENTER);

        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
    }

    @Test
    void oneMousePressCannotReplayAgainstTheNewConfirmationLayout() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.pointAt(UiActionId.RETURN_TO_MAIN_MENU);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_LEFT);

        fixture.frame();
        fixture.releaseMouse(GLFW_MOUSE_BUTTON_LEFT);

        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                fixture.shell().snapshot().modal().orElseThrow());
        assertFalse(fixture.session().closed());

        fixture.frame();

        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                fixture.shell().snapshot().modal().orElseThrow());
        assertFalse(fixture.session().closed());

        fixture.pointAt(UiActionId.CONFIRM);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_LEFT);
        fixture.frame();
        fixture.releaseMouse(GLFW_MOUSE_BUTTON_LEFT);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.session().closed());
    }

    @Test
    void physicallyCapturedHostIsReleasedBeforeTheFirstMainMenuPoll() {
        Fixture fixture = new Fixture(true, 1.0f, 1.0f);

        assertFalse(fixture.host().cursorCaptured());
        assertEquals(List.of(false), fixture.host().cursorTransitions());
        assertEquals(0, fixture.host().pollCount());
    }

    @Test
    void loadReadyWhileUnfocusedCannotPreserveTheStartupCursorCapture() {
        Fixture fixture = new Fixture(true, 1.0f, 1.0f);
        fixture.session().completeLoadOnPoll(false);
        fixture.pressKey(GLFW_KEY_ENTER);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ENTER);
        fixture.loseFocus();
        fixture.session().completeLoadOnPoll(true);

        fixture.frame();

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertFalse(fixture.host().cursorCaptured());
    }

    @ParameterizedTest
    @CsvSource({"2.0, 2.0", "1.5, 2.0"})
    void logicalGlfwPointerAtPaintedButtonCenterRoutesNewWorldAtHighDpi(
            float scaleX,
            float scaleY) {
        Fixture fixture = new Fixture(false, scaleX, scaleY);
        fixture.session().completeLoadOnPoll(false);
        fixture.pointAt(UiActionId.NEW_WORLD);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_LEFT);

        fixture.frame();

        assertEquals(ScreenId.LOADING, fixture.shell().snapshot().screen());
    }

    @Test
    void windowsWindowCoordinateAtPaintedNewWorldCenterRoutesNewWorld() {
        Fixture fixture = new Fixture(
                false,
                new RenderSurfaceMetrics(1920, 1080, 1920, 1080, 1.5f, 1.5f));
        fixture.session().completeLoadOnPoll(false);
        fixture.pointAtPaintedCenterInWindowCoordinates(UiActionId.NEW_WORLD);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_LEFT);

        fixture.frame();

        assertEquals(ScreenId.LOADING, fixture.shell().snapshot().screen());
    }

    @Test
    void initialMainMenuWithPointerOutsideHasNoSelectedTint() {
        Fixture fixture = new Fixture();

        fixture.frame();

        ProductUiLayout layout = fixture.host().lastProductLayout();
        assertNoEnabledSelection(fixture, layout);
        assertNotEquals(
                buttonCommand(fixture, layout, UiActionId.NEW_WORLD).tint(),
                buttonCommand(fixture, layout, UiActionId.LOAD_WORLD).tint());
    }

    @ParameterizedTest(name = "pointer highlights {0}")
    @MethodSource("enabledMainMenuActions")
    void pointerEnteringEachEnabledButtonHighlightsExactlyThatRectangle(UiActionId action) {
        Fixture fixture = new Fixture();

        fixture.pointAtPaintedCenterInWindowCoordinates(action);
        fixture.frame();

        assertOnlySelected(fixture, fixture.host().lastProductLayout(), action);
    }

    @ParameterizedTest(name = "Windows pointer exits {0} edge")
    @MethodSource("outsideEdges")
    void pointerMovingJustOutsideEveryButtonEdgeClearsHighlightOnNextWindowsFrame(
            OutsideEdge edge) {
        Fixture fixture = new Fixture(
                false,
                new RenderSurfaceMetrics(1920, 1080, 1920, 1080, 1.5f, 1.5f));
        fixture.pointAtPaintedCenterInWindowCoordinates(UiActionId.SETTINGS);
        fixture.frame();
        assertOnlySelected(
                fixture, fixture.host().lastProductLayout(), UiActionId.SETTINGS);

        ProductUiLayout layout = fixture.host().lastProductLayout();
        UiRect bounds = layout.region(UiActionId.SETTINGS).logicalBounds();
        double epsilon = 0.001d;
        double logicalX = switch (edge) {
            case LEFT -> bounds.left() - epsilon;
            case RIGHT -> bounds.right() + epsilon;
            case TOP, BOTTOM -> bounds.left() + (bounds.right() - bounds.left()) / 2.0d;
        };
        double logicalY = switch (edge) {
            case TOP -> bounds.top() - epsilon;
            case BOTTOM -> bounds.bottom() + epsilon;
            case LEFT, RIGHT -> bounds.top() + (bounds.bottom() - bounds.top()) / 2.0d;
        };
        fixture.pointAtLogicalInWindowCoordinates(logicalX, logicalY);

        fixture.frame();

        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
    }

    @Test
    void repeatedStationaryOutsideFramesRemainUnhighlightedWithoutFlicker() {
        Fixture fixture = new Fixture();
        fixture.pointOutside();

        fixture.frame();
        UiColor normal = buttonCommand(
                fixture, fixture.host().lastProductLayout(), UiActionId.NEW_WORLD).tint();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
        fixture.frame();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
        fixture.frame();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
        assertEquals(
                normal,
                buttonCommand(
                        fixture,
                        fixture.host().lastProductLayout(),
                        UiActionId.NEW_WORLD).tint());
    }

    @Test
    void keyboardSelectionPersistsUntilActualPointerMovementChoosesPointerMode() {
        Fixture fixture = new Fixture();
        fixture.pointOutside();
        fixture.frame();

        fixture.pressKey(GLFW_KEY_DOWN);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_DOWN);
        fixture.frame();
        assertOnlySelected(
                fixture, fixture.host().lastProductLayout(), UiActionId.SETTINGS);

        fixture.pointAtLogicalInWindowCoordinates(-2.0d, -2.0d);
        fixture.frame();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());

        fixture.pointAtPaintedCenterInWindowCoordinates(UiActionId.CONTROLS);
        fixture.frame();
        assertOnlySelected(
                fixture, fixture.host().lastProductLayout(), UiActionId.CONTROLS);
    }

    @Test
    void disabledLoadHoverNeverHighlightsOrRoutesACommand() {
        Fixture fixture = new Fixture();
        fixture.frame();
        UiColor disabled = buttonCommand(
                fixture, fixture.host().lastProductLayout(), UiActionId.LOAD_WORLD).tint();

        fixture.pointAtPaintedCenterInWindowCoordinates(UiActionId.LOAD_WORLD);
        fixture.frame();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
        assertEquals(
                disabled,
                buttonCommand(
                        fixture,
                        fixture.host().lastProductLayout(),
                        UiActionId.LOAD_WORLD).tint());

        fixture.pressMouse(GLFW_MOUSE_BUTTON_LEFT);
        fixture.frame();
        fixture.releaseMouse(GLFW_MOUSE_BUTTON_LEFT);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());
    }

    @ParameterizedTest
    @ValueSource(ints = {GLFW_KEY_DOWN, GLFW_KEY_TAB})
    void oneKeyboardNavigationEdgeMovesThePaintedFocusExactlyOnce(int key) {
        Fixture fixture = new Fixture();
        fixture.pointOutside();
        fixture.frame();
        assertNoEnabledSelection(fixture, fixture.host().lastProductLayout());

        fixture.pressKey(key);
        fixture.frame();
        fixture.releaseKey(key);
        fixture.frame();
        fixture.frame();

        ProductUiLayout layout = fixture.host().lastProductLayout();
        assertOnlySelected(fixture, layout, UiActionId.SETTINGS);
    }

    @Test
    void modalPaintedFocusIsExclusiveToItsOwnEnabledActions() {
        Fixture fixture = new Fixture();
        fixture.shell().handle(new ScreenCommand.Quit());
        fixture.pointOutside();

        fixture.frame();

        ProductUiLayout layout = fixture.host().lastProductLayout();
        assertEquals(
                Set.of(UiActionId.CONFIRM, UiActionId.DISMISS),
                Set.copyOf(layout.hitRegions().stream().map(UiHitRegion::action).toList()));
        assertNoEnabledSelection(fixture, layout);

        fixture.pointAtPaintedCenterInWindowCoordinates(UiActionId.DISMISS);
        fixture.frame();

        assertOnlySelected(
                fixture, fixture.host().lastProductLayout(), UiActionId.DISMISS);
        assertFalse(fixture.host().lastProductLayout().hitRegions().stream()
                .anyMatch(region -> region.action() == UiActionId.NEW_WORLD));
    }

    @Test
    void focusLossAndRecoveryPulseInOnePollStillHardPausesExactlyOnce() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();
        fixture.host().onNextPoll(() -> {
            fixture.loseFocus();
            fixture.restoreFocus();
        });

        fixture.frame();

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(1, fixture.session().capturePausedCalls());
        assertEquals(1, fixture.session().discardFixedTimeCalls());
        assertEquals(List.of(false), fixture.host().cursorTransitions());
        assertFalse(fixture.input().consumeMouseInteractionInvalidation());
    }

    @Test
    void heldGameplayInputRequiresPhysicalReleaseAcrossPauseAndResume() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();
        fixture.pressKey(GLFW_KEY_Q);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_RIGHT);

        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.tapShortcut(GLFW_KEY_F1);

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertFalse(fixture.session().lastInput().isKeyDown(GLFW_KEY_Q));
        assertFalse(fixture.session().lastInput()
                .isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));

        fixture.releaseKey(GLFW_KEY_Q);
        fixture.releaseMouse(GLFW_MOUSE_BUTTON_RIGHT);
        fixture.pressKey(GLFW_KEY_Q);
        fixture.pressMouse(GLFW_MOUSE_BUTTON_RIGHT);
        fixture.frame();

        assertTrue(fixture.session().lastInput().isKeyPressed(GLFW_KEY_Q));
        assertTrue(fixture.session().lastInput()
                .isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void pausedFramesCaptureTheFrozenSessionButNeverAdvanceGameplay() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.resetSessionCalls();

        fixture.loop().runFrame(0.25d);
        fixture.loop().runFrame(0.25d);

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(2, fixture.session().capturePausedCalls());
        assertEquals(2, fixture.host().sessionRenderCount());
    }

    @Test
    void resumeTransitionFrameUsesZeroDeltaAndCannotAdvanceCanonicalRevision() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        long revisionBeforeResume = fixture.session().canonicalRevision();
        fixture.resetRecordings();

        fixture.pressKey(GLFW_KEY_F1);
        fixture.loop().runFrame(0.25d);
        fixture.releaseKey(GLFW_KEY_F1);

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(List.of(0.0d), fixture.session().advancedDeltas());
        assertEquals(0, fixture.session().fixedSteps());
        assertEquals(revisionBeforeResume, fixture.session().canonicalRevision());
        assertEquals(
                0.0d,
                fixture.host().lastSessionFrame().renderInput().frameDeltaSeconds());
    }

    @Test
    void loadCompletionTransitionFrameUsesZeroDeltaAndCannotAdvanceCanonicalRevision() {
        Fixture fixture = new Fixture();
        fixture.session().completeLoadOnPoll(false);
        fixture.pressKey(GLFW_KEY_ENTER);
        fixture.loop().runFrame(0.25d);
        fixture.releaseKey(GLFW_KEY_ENTER);
        assertEquals(ScreenId.LOADING, fixture.shell().snapshot().screen());
        long revisionBeforeReady = fixture.session().canonicalRevision();
        fixture.resetRecordings();

        fixture.session().completeLoadOnPoll(true);
        fixture.loop().runFrame(0.25d);

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(List.of(0.0d), fixture.session().advancedDeltas());
        assertEquals(0, fixture.session().fixedSteps());
        assertEquals(revisionBeforeReady, fixture.session().canonicalRevision());
        assertEquals(
                0.0d,
                fixture.host().lastSessionFrame().renderInput().frameDeltaSeconds());
    }

    @Test
    void throwingLoadFailureClosesSessionShowsErrorAndContinuesLaterFrames() {
        Fixture fixture = new Fixture();
        fixture.enterLoading();
        fixture.resetRecordings();
        fixture.session().throwOnNextLoadPoll(
                new IllegalStateException("recorded load failure"));

        assertDoesNotThrow(() -> fixture.loop().runFrame(0.25d));

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertEquals(
                ModalId.ERROR_ACKNOWLEDGEMENT,
                fixture.shell().snapshot().modal().orElseThrow());
        assertTrue(fixture.session().closed());
        assertEquals(1, fixture.session().closeCalls());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(0, fixture.session().capturePausedCalls());
        assertNull(fixture.host().lastSessionFrame());
        assertEquals(1, fixture.host().swapCount());

        fixture.pressKey(GLFW_KEY_ESCAPE);
        assertDoesNotThrow(fixture::frame);
        fixture.releaseKey(GLFW_KEY_ESCAPE);
        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
        assertEquals(2, fixture.host().swapCount());
        assertEquals(1, fixture.session().closeCalls());
    }

    @Test
    void loadFailureWithSuppressedCleanupFailureRemainsFatalWhenFollowupCloseSucceeds() {
        Fixture fixture = new Fixture();
        fixture.enterLoading();
        fixture.resetRecordings();
        RuntimeException cleanupFailure =
                new IllegalStateException("recorded cleanup failure");
        RuntimeException loadFailure =
                new IllegalStateException("recorded load failure");
        loadFailure.addSuppressed(cleanupFailure);
        fixture.session().throwOnNextLoadPoll(loadFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> fixture.loop().runFrame(0.25d));

        assertSame(loadFailure, thrown);
        assertEquals(
                List.of(cleanupFailure),
                List.of(thrown.getSuppressed()));
        assertTrue(fixture.session().closed());
        assertEquals(1, fixture.session().closeCalls());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(0, fixture.session().capturePausedCalls());
        assertNull(fixture.host().lastSessionFrame());
        assertNull(fixture.host().lastProductLayout());
        assertEquals(0, fixture.host().sessionRenderCount());
        assertEquals(0, fixture.host().swapCount());
    }

    @Test
    void failedLoadStateClosesSessionShowsErrorAndContinuesLaterFrames() {
        Fixture fixture = new Fixture();
        fixture.enterLoading();
        fixture.resetRecordings();
        fixture.session().failOnNextLoadPoll();

        assertDoesNotThrow(() -> fixture.loop().runFrame(0.25d));

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertEquals(
                ModalId.ERROR_ACKNOWLEDGEMENT,
                fixture.shell().snapshot().modal().orElseThrow());
        assertTrue(fixture.session().closed());
        assertEquals(1, fixture.session().closeCalls());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(0, fixture.session().capturePausedCalls());
        assertNull(fixture.host().lastSessionFrame());
        assertDoesNotThrow(fixture::frame);
        assertEquals(2, fixture.host().swapCount());
    }

    @Test
    void escapeCancelsLoadingClosesSessionAndContinuesWithoutSessionPresentation() {
        Fixture fixture = new Fixture();
        fixture.enterLoading();
        fixture.resetRecordings();

        fixture.pressKey(GLFW_KEY_ESCAPE);
        assertDoesNotThrow(() -> fixture.loop().runFrame(0.25d));
        fixture.releaseKey(GLFW_KEY_ESCAPE);

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
        assertTrue(fixture.session().closed());
        assertEquals(1, fixture.session().closeCalls());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(0, fixture.session().capturePausedCalls());
        assertNull(fixture.host().lastSessionFrame());
        assertEquals(1, fixture.host().swapCount());

        assertDoesNotThrow(fixture::frame);
        assertEquals(2, fixture.host().swapCount());
        assertEquals(1, fixture.session().closeCalls());
    }

    @Test
    void repeatedPauseResumeDiscardsSessionFixedTimeAtEveryPlayingBoundary() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();

        for (int cycle = 0; cycle < 3; cycle++) {
            fixture.tapShortcut(GLFW_KEY_F1);
            fixture.tapShortcut(GLFW_KEY_F1);
        }

        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(6, fixture.session().discardFixedTimeCalls());
        assertEquals(
                List.of(false, true, false, true, false, true),
                fixture.host().cursorTransitions());
    }

    @ParameterizedTest
    @ValueSource(ints = {GLFW_KEY_ESCAPE, GLFW_KEY_F1})
    void escapeAndF1ShareTheSamePlayingPausedTransition(int shortcut) {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();

        fixture.tapShortcut(shortcut);
        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());

        fixture.tapShortcut(shortcut);
        assertEquals(ScreenId.PLAYING, fixture.shell().snapshot().screen());
        assertEquals(2, fixture.session().discardFixedTimeCalls());
        assertEquals(List.of(false, true), fixture.host().cursorTransitions());
    }

    @ParameterizedTest
    @ValueSource(ints = {GLFW_KEY_F2, GLFW_KEY_F3, GLFW_KEY_F4})
    void gameplayDebugShortcutsNeverActivateAScreenOrReplayOutsidePlaying(int shortcut) {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.resetRecordings();

        fixture.pressKey(shortcut);
        fixture.frame();

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(1, fixture.session().capturePausedCalls());

        fixture.releaseKey(shortcut);
        fixture.tapShortcut(GLFW_KEY_F1);
        assertFalse(fixture.session().lastInput().isKeyDown(shortcut));

        fixture.pressKey(shortcut);
        fixture.frame();
        assertTrue(fixture.session().lastInput().isKeyPressed(shortcut));
    }

    @Test
    void modalCommandHasPriorityOverPauseShortcutFromTheSameUiSample() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.tapShortcut(GLFW_KEY_F1);
        fixture.shell().handle(new ScreenCommand.ReturnToMainMenu());
        fixture.resetRecordings();

        fixture.pressKey(GLFW_KEY_ESCAPE);
        fixture.pressKey(GLFW_KEY_F1);
        fixture.frame();
        fixture.releaseKey(GLFW_KEY_ESCAPE);
        fixture.releaseKey(GLFW_KEY_F1);

        assertEquals(ScreenId.PAUSED, fixture.shell().snapshot().screen());
        assertTrue(fixture.shell().snapshot().modal().isEmpty());
        assertEquals(0, fixture.session().advanceCalls());
        assertEquals(1, fixture.session().capturePausedCalls());
        assertFalse(fixture.session().closed());
    }

    @Test
    void productLoopDelegatesOneWholeFrameDeltaAndDoesNotOwnASecondFixedLoop() {
        Fixture fixture = new Fixture();
        fixture.enterPlaying();
        fixture.resetRecordings();
        fixture.pressKey(GLFW_KEY_W);

        fixture.loop().runFrame(3.0d * FIXED_STEP_SECONDS);

        assertEquals(List.of(3.0d * FIXED_STEP_SECONDS),
                fixture.session().advancedDeltas());
        assertEquals(1, fixture.session().advanceCalls());
        assertTrue(fixture.session().lastInput().isKeyPressed(GLFW_KEY_W));
    }

    @Test
    void runOwnsOnePollingAndSwapLoop() {
        Fixture fixture = new Fixture();
        fixture.host().closeAfterPolls(2);

        fixture.loop().run();

        assertEquals(2, fixture.host().pollCount());
        assertEquals(2, fixture.host().swapCount());
        assertEquals(2, fixture.deltaSampleCount());
    }

    @Test
    void runFrameRejectsAnotherThreadBeforePollingTheGlfwOwnerBoundary()
            throws InterruptedException {
        Fixture fixture = new Fixture();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        fixture.loop().runFrame(FIXED_STEP_SECONDS);
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "not-the-product-owner");

        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("ProductLoop"));
        assertEquals(0, fixture.host().pollCount());
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static UiDrawCommand buttonCommand(
            Fixture fixture,
            ProductUiLayout layout,
            UiActionId action) {
        var expectedBounds = fixture.host().layoutContext()
                .toFramebuffer(layout.region(action).logicalBounds());
        return layout.frame().commands().stream()
                .filter(command -> command.texture() == UiTextureId.SOLID)
                .filter(command -> command.framebufferBounds().equals(expectedBounds))
                .findFirst()
                .orElseThrow();
    }

    private static void assertNoEnabledSelection(
            Fixture fixture,
            ProductUiLayout layout) {
        List<UiColor> enabledTints = layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .map(region -> buttonCommand(fixture, layout, region.action()).tint())
                .distinct()
                .toList();
        assertEquals(1, enabledTints.size());
    }

    private static void assertOnlySelected(
            Fixture fixture,
            ProductUiLayout layout,
            UiActionId selectedAction) {
        UiDrawCommand selected = buttonCommand(fixture, layout, selectedAction);
        assertEquals(
                fixture.host().layoutContext().toFramebuffer(
                        layout.region(selectedAction).logicalBounds()),
                selected.framebufferBounds());
        List<UiColor> otherEnabledTints = layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .filter(region -> region.action() != selectedAction)
                .map(region -> buttonCommand(fixture, layout, region.action()).tint())
                .distinct()
                .toList();
        assertEquals(1, otherEnabledTints.size());
        assertNotEquals(otherEnabledTints.get(0), selected.tint());
    }

    private static Stream<Arguments> enabledMainMenuActions() {
        return Stream.of(
                Arguments.of(UiActionId.NEW_WORLD),
                Arguments.of(UiActionId.SETTINGS),
                Arguments.of(UiActionId.CONTROLS),
                Arguments.of(UiActionId.QUIT));
    }

    private static Stream<Arguments> outsideEdges() {
        return Stream.of(OutsideEdge.values()).map(Arguments::of);
    }

    private enum OutsideEdge {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final InputManager input = new InputManager();
        private final ScreenRouter router = ScreenRouter.mainMenu();
        private final ProductShellController shell =
                new ProductShellController(router);
        private final RecordingGameSession session;
        private final RecordingHost host;
        private final ProductScreenPresenter presenter;
        private int deltaSampleCount;
        private final ProductLoop loop;

        private Fixture() {
            this(new EmptySaveCatalog());
        }

        private Fixture(SaveCatalog saveCatalog) {
            this(false, 1.0f, 1.0f, saveCatalog);
        }

        private Fixture(
                boolean cursorCaptured,
                float scaleX,
                float scaleY) {
            this(cursorCaptured, scaleX, scaleY, new EmptySaveCatalog());
        }

        private Fixture(
                boolean cursorCaptured,
                float scaleX,
                float scaleY,
                SaveCatalog saveCatalog) {
            this(
                    cursorCaptured,
                    new RenderSurfaceMetrics(
                            1280,
                            720,
                            Math.round(1280 * scaleX),
                            Math.round(720 * scaleY),
                            scaleX,
                            scaleY),
                    saveCatalog);
        }

        private Fixture(
                boolean cursorCaptured,
                RenderSurfaceMetrics surface) {
            this(cursorCaptured, surface, new EmptySaveCatalog());
        }

        private Fixture(
                boolean cursorCaptured,
                RenderSurfaceMetrics surface,
                SaveCatalog saveCatalog) {
            session = new RecordingGameSession(input, events);
            host = new RecordingHost(
                    events,
                    cursorCaptured,
                    new UiLayoutContext(surface));
            presenter = new ProductScreenPresenter(
                    saveCatalog, textRenderer());
            loop = new ProductLoop(
                    input,
                    shell,
                    new ProductScreenInputController(),
                    presenter,
                    () -> {
                        events.add("create-session");
                        return session;
                    },
                    () -> {
                        deltaSampleCount++;
                        return FIXED_STEP_SECONDS;
                    },
                    host);
        }

        ProductLoop loop() {
            return loop;
        }

        ProductShellController shell() {
            return shell;
        }

        InputManager input() {
            return input;
        }

        RecordingGameSession session() {
            return session;
        }

        RecordingHost host() {
            return host;
        }

        List<String> events() {
            return List.copyOf(events);
        }

        int deltaSampleCount() {
            return deltaSampleCount;
        }

        void enterPlaying() {
            pressKey(GLFW_KEY_ENTER);
            frame();
            releaseKey(GLFW_KEY_ENTER);
            assertEquals(ScreenId.PLAYING, shell.snapshot().screen());
        }

        void enterLoading() {
            session.completeLoadOnPoll(false);
            pressKey(GLFW_KEY_ENTER);
            frame();
            releaseKey(GLFW_KEY_ENTER);
            assertEquals(ScreenId.LOADING, shell.snapshot().screen());
        }

        void frame() {
            loop.runFrame(FIXED_STEP_SECONDS);
        }

        void tapShortcut(int key) {
            pressKey(key);
            frame();
            releaseKey(key);
        }

        void pressKey(int key) {
            InputManagerTestDriver.key(input, key, GLFW_PRESS);
        }

        void releaseKey(int key) {
            InputManagerTestDriver.key(input, key, GLFW_RELEASE);
        }

        void pressMouse(int button) {
            InputManagerTestDriver.mouseButton(input, button, GLFW_PRESS);
        }

        void releaseMouse(int button) {
            InputManagerTestDriver.mouseButton(input, button, GLFW_RELEASE);
        }

        void loseFocus() {
            InputManagerTestDriver.windowFocus(input, false);
        }

        void restoreFocus() {
            InputManagerTestDriver.windowFocus(input, true);
        }

        void pointAt(UiActionId action) {
            ProductUiLayout layout = presenter.present(
                    shell.snapshot(), host.layoutContext());
            UiHitRegion region = layout.region(action);
            InputManagerTestDriver.cursor(
                    input, region.centerX(), region.centerY());
        }

        void pointAtPaintedCenterInWindowCoordinates(UiActionId action) {
            UiLayoutContext context = host.layoutContext();
            ProductUiLayout layout = presenter.present(shell.snapshot(), context);
            UiHitRegion region = layout.region(action);
            InputManagerTestDriver.cursor(
                    input,
                    region.centerX() * context.logicalWindowWidth() / context.logicalWidth(),
                    region.centerY() * context.logicalWindowHeight() / context.logicalHeight());
        }

        void pointAtLogicalInWindowCoordinates(double logicalX, double logicalY) {
            UiLayoutContext context = host.layoutContext();
            InputManagerTestDriver.cursor(
                    input,
                    logicalX * context.logicalWindowWidth() / context.logicalWidth(),
                    logicalY * context.logicalWindowHeight() / context.logicalHeight());
        }

        void pointOutside() {
            InputManagerTestDriver.cursor(input, -1.0d, -1.0d);
        }

        void resetRecordings() {
            events.clear();
            session.resetCalls();
            host.resetRecordings();
        }

        void resetSessionCalls() {
            session.resetCalls();
            host.resetRecordings();
            events.clear();
        }
    }

    private static final class RecordingGameSession implements GameSession {
        private final InputManager input;
        private final List<String> events;
        private GameSessionState state = GameSessionState.LOADING;
        private InputSnapshot lastInput = new InputSnapshot(Set.of(), Set.of());
        private GameSessionFrame lastFrame = frame(0.0d);
        private final List<Double> advancedDeltas = new ArrayList<>();
        private int advanceCalls;
        private int capturePausedCalls;
        private int discardFixedTimeCalls;
        private int fixedSteps;
        private long canonicalRevision;
        private int closeCalls;
        private boolean completeLoadOnPoll = true;
        private boolean failOnNextLoadPoll;
        private RuntimeException nextLoadFailure;
        private boolean closed;

        private RecordingGameSession(InputManager input, List<String> events) {
            this.input = input;
            this.events = events;
        }

        @Override
        public GameSessionState state() {
            return state;
        }

        @Override
        public void pollLoad() {
            events.add("poll-load");
            if (nextLoadFailure != null) {
                RuntimeException failure = nextLoadFailure;
                nextLoadFailure = null;
                throw failure;
            }
            if (failOnNextLoadPoll) {
                failOnNextLoadPoll = false;
                state = GameSessionState.FAILED;
                return;
            }
            if (completeLoadOnPoll) {
                state = GameSessionState.READY;
            }
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            events.add("advance-playing");
            advanceCalls++;
            advancedDeltas.add(frameDeltaSeconds);
            int steps = Math.min(
                    8,
                    (int) Math.floor(
                            frameDeltaSeconds / FIXED_STEP_SECONDS + 1.0e-9d));
            fixedSteps += steps;
            canonicalRevision += steps;
            lastInput = input.consumeFixedInput();
            lastFrame = frame(frameDeltaSeconds);
            return lastFrame;
        }

        @Override
        public GameSessionFrame capturePaused() {
            events.add("capture-paused");
            if (state == GameSessionState.FAILED
                    || state == GameSessionState.CLOSED) {
                throw new IllegalStateException(
                        "Cannot capture a " + state + " session");
            }
            capturePausedCalls++;
            return lastFrame.copy();
        }

        @Override
        public void discardFixedTime() {
            events.add("discard-fixed-time");
            discardFixedTimeCalls++;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            events.add("close-session");
            closed = true;
            closeCalls++;
            state = GameSessionState.CLOSED;
        }

        InputSnapshot lastInput() {
            return lastInput;
        }

        List<Double> advancedDeltas() {
            return List.copyOf(advancedDeltas);
        }

        int advanceCalls() {
            return advanceCalls;
        }

        int capturePausedCalls() {
            return capturePausedCalls;
        }

        int discardFixedTimeCalls() {
            return discardFixedTimeCalls;
        }

        int fixedSteps() {
            return fixedSteps;
        }

        long canonicalRevision() {
            return canonicalRevision;
        }

        int closeCalls() {
            return closeCalls;
        }

        boolean closed() {
            return closed;
        }

        void completeLoadOnPoll(boolean complete) {
            completeLoadOnPoll = complete;
        }

        void throwOnNextLoadPoll(RuntimeException failure) {
            nextLoadFailure = failure;
        }

        void failOnNextLoadPoll() {
            failOnNextLoadPoll = true;
        }

        void resetCalls() {
            advanceCalls = 0;
            capturePausedCalls = 0;
            discardFixedTimeCalls = 0;
            fixedSteps = 0;
            advancedDeltas.clear();
        }

        private static GameSessionFrame frame(double frameDeltaSeconds) {
            return new GameSessionFrame(
                    new RenderFrameInput(List.of(), frameDeltaSeconds, 0));
        }
    }

    private static final class RecordingHost implements ProductLoop.FrameHost {
        private final List<String> events;
        private final UiLayoutContext layout;
        private final Deque<Runnable> pollCallbacks = new ArrayDeque<>();
        private final List<Boolean> cursorTransitions = new ArrayList<>();
        private boolean cursorCaptured;
        private GameSessionFrame lastSessionFrame;
        private ProductUiLayout lastProductLayout;
        private int closeAfterPolls;
        private int pollCount;
        private int swapCount;
        private int sessionRenderCount;

        private RecordingHost(
                List<String> events,
                boolean cursorCaptured,
                UiLayoutContext layout) {
            this.events = events;
            this.cursorCaptured = cursorCaptured;
            this.layout = layout;
        }

        @Override
        public boolean shouldClose() {
            return closeAfterPolls > 0 && pollCount >= closeAfterPolls;
        }

        @Override
        public void pollEvents() {
            events.add("poll-events");
            pollCount++;
            if (!pollCallbacks.isEmpty()) {
                pollCallbacks.removeFirst().run();
            }
        }

        @Override
        public UiLayoutContext layoutContext() {
            return layout;
        }

        @Override
        public void setCursorCaptured(boolean captured) {
            if (cursorCaptured == captured) {
                return;
            }
            cursorCaptured = captured;
            events.add("cursor-captured:" + captured);
            cursorTransitions.add(captured);
        }

        @Override
        public void renderSession(GameSessionFrame frame) {
            events.add("render-session");
            lastSessionFrame = frame;
            sessionRenderCount++;
        }

        @Override
        public void renderProduct(ProductUiLayout layout) {
            events.add("render-product");
            lastProductLayout = layout;
        }

        @Override
        public void swapBuffers() {
            events.add("swap-buffers");
            swapCount++;
        }

        void onNextPoll(Runnable callback) {
            pollCallbacks.addLast(callback);
        }

        void closeAfterPolls(int count) {
            closeAfterPolls = count;
        }

        int pollCount() {
            return pollCount;
        }

        int swapCount() {
            return swapCount;
        }

        int sessionRenderCount() {
            return sessionRenderCount;
        }

        List<Boolean> cursorTransitions() {
            return List.copyOf(cursorTransitions);
        }

        boolean cursorCaptured() {
            return cursorCaptured;
        }

        GameSessionFrame lastSessionFrame() {
            return lastSessionFrame;
        }

        ProductUiLayout lastProductLayout() {
            return lastProductLayout;
        }

        void resetRecordings() {
            cursorTransitions.clear();
            lastSessionFrame = null;
            lastProductLayout = null;
            pollCount = 0;
            swapCount = 0;
            sessionRenderCount = 0;
        }
    }
}
