package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import com.overlord.config.GameConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InputManagerTest {
    @Test
    void captureUiInputUsesAbsolutePointerAndDoesNotConsumeGameplayEdges() {
        InputManager input = new InputManager();
        InputManagerTestDriver.cursor(input, 320.0, 180.0);
        InputManagerTestDriver.key(input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, GLFW_PRESS);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        InputManagerTestDriver.scroll(input, 0.0, -2.0);

        UiInputSnapshot ui = input.captureUiInput(7L);

        assertEquals(320.0, ui.pointerX());
        assertEquals(180.0, ui.pointerY());
        assertTrue(ui.focused());
        assertEquals(7L, ui.sampleId());
        assertTrue(ui.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER));
        assertTrue(ui.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER));
        assertTrue(ui.isMouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(ui.isMousePressed(GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(List.of(-2), ui.scrollDeltas());
        assertTrue(input.consumeFixedInput().isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gameplayKeys")
    void invalidationSuppressesAndRearmsEveryGameplayKeyAfterPhysicalRelease(
            String control,
            int key) {
        InputManager input = new InputManager();
        InputManagerTestDriver.key(input, key, GLFW_PRESS);

        input.invalidateGameplayInput();

        InputSnapshot suppressed = input.consumeFixedInput();
        assertFalse(suppressed.isKeyDown(key), control);
        assertFalse(suppressed.isKeyPressed(key), control);

        InputManagerTestDriver.key(input, key, GLFW_PRESS);
        InputSnapshot stillSuppressed = input.consumeFixedInput();
        assertFalse(stillSuppressed.isKeyDown(key), control);
        assertFalse(stillSuppressed.isKeyPressed(key), control);

        InputManagerTestDriver.key(input, key, GLFW_RELEASE);
        InputManagerTestDriver.key(input, key, GLFW_PRESS);
        InputSnapshot fresh = input.consumeFixedInput();
        assertTrue(fresh.isKeyDown(key), control);
        assertTrue(fresh.isKeyPressed(key), control);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gameplayMouseButtons")
    void invalidationSuppressesAndRearmsEveryGameplayMouseButtonAfterPhysicalRelease(
            String control,
            int button) {
        InputManager input = new InputManager();
        InputManagerTestDriver.mouseButton(input, button, GLFW_PRESS);

        input.invalidateGameplayInput();

        InputSnapshot suppressed = input.consumeFixedInput();
        assertFalse(suppressed.isMouseButtonDown(button), control);
        assertFalse(suppressed.isMouseButtonPressed(button), control);

        InputManagerTestDriver.mouseButton(input, button, GLFW_PRESS);
        InputSnapshot stillSuppressed = input.consumeFixedInput();
        assertFalse(stillSuppressed.isMouseButtonDown(button), control);
        assertFalse(stillSuppressed.isMouseButtonPressed(button), control);

        InputManagerTestDriver.mouseButton(input, button, GLFW_RELEASE);
        InputManagerTestDriver.mouseButton(input, button, GLFW_PRESS);
        InputSnapshot fresh = input.consumeFixedInput();
        assertTrue(fresh.isMouseButtonDown(button), control);
        assertTrue(fresh.isMouseButtonPressed(button), control);
    }

    @Test
    void windowFocusAccessorTracksTheCallbackOwnedState() {
        InputManager manager = new InputManager();

        assertTrue(manager.isWindowFocused());
        manager.onWindowFocus(false);
        assertFalse(manager.isWindowFocused());
        manager.onWindowFocus(true);
        assertTrue(manager.isWindowFocused());
    }

    @Test
    void windowFocusAccessorRejectsOffOwnerThreadReads() throws InterruptedException {
        InputManager manager = new InputManager();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        manager.isWindowFocused();
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "focus-reader");

        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("window focus state query"));
    }

    @Test
    void accumulatesMouseMovementAfterInitialBaseline() {
        InputManager manager = new InputManager();

        manager.onCursorPosition(100.0, 100.0);
        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());

        manager.onCursorPosition(108.0, 94.0);
        assertEquals(new MouseDelta(8.0, 6.0), manager.consumeMouseDelta());
        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
    }

    @Test
    void focusChangeResetsMouseBaseline() {
        InputManager manager = new InputManager();
        manager.onCursorPosition(100.0, 100.0);
        manager.onCursorPosition(110.0, 90.0);
        assertEquals(new MouseDelta(10.0, 10.0), manager.consumeMouseDelta());

        manager.onWindowFocus(false);
        manager.onWindowFocus(true);
        manager.onCursorPosition(400.0, 300.0);

        assertEquals(MouseDelta.ZERO, manager.consumeMouseDelta());
    }

    @Test
    void losingFocusClearsHeldAndPressedKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onScroll(0.0, 3.0);

        manager.onWindowFocus(false);
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isKeyDown(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(GLFW_KEY_W));
        assertEquals(0, snapshot.scrollSteps());
    }

    @Test
    void keyPressEdgeRemainsLatchedUntilFixedUpdateConsumesIt() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(GLFW_KEY_W, GLFW_RELEASE);

        assertTrue(manager.isKeyPressed(GLFW_KEY_W));
        InputSnapshot first = manager.consumeFixedInput();
        InputSnapshot second = manager.consumeFixedInput();

        assertFalse(first.isKeyDown(GLFW_KEY_W));
        assertTrue(first.isKeyPressed(GLFW_KEY_W));
        assertFalse(second.isKeyDown(GLFW_KEY_W));
        assertFalse(second.isKeyPressed(GLFW_KEY_W));
    }

    @Test
    void consumesOneShortcutEdgeWithoutClearingOtherPressedKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1, GLFW_PRESS);

        assertTrue(manager.consumeKeyPress(org.lwjgl.glfw.GLFW.GLFW_KEY_F1));
        assertFalse(manager.consumeKeyPress(org.lwjgl.glfw.GLFW.GLFW_KEY_F1));
        assertTrue(manager.consumeFixedInput().isKeyPressed(GLFW_KEY_W));
    }

    @Test
    void scrollIsLatchedForOneFixedInputSampleThenCleared() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, -1.0);

        assertEquals(1, manager.consumeFixedInput().scrollSteps());
        assertEquals(0, manager.consumeFixedInput().scrollSteps());
    }

    @Test
    void preservesOpposingScrollCallbacksInOrderWithinOneFixedSample() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 1.0);
        manager.onScroll(0.0, -1.0);
        manager.onScroll(0.0, 1.0);

        InputSnapshot snapshot = manager.consumeFixedInput();
        assertEquals(List.of(1, -1, 1), snapshot.scrollDeltas());
        assertEquals(1, snapshot.scrollSteps());
        assertTrue(manager.consumeFixedInput().scrollDeltas().isEmpty());
    }

    @Test
    void boundsMalformedLargeScrollOffsetsPerFixedSample() {
        InputManager manager = new InputManager();

        manager.onScroll(0.0, 10_000.0);
        manager.onScroll(0.0, -1.0);

        InputSnapshot snapshot = manager.consumeFixedInput();
        assertEquals(List.of(InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE),
                snapshot.scrollDeltas());
        assertEquals(InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE,
                snapshot.scrollSteps());
    }

    @Test
    void discardingGameplayEdgesClearsPressesAndScrollButPreservesHeldKeys() {
        InputManager manager = new InputManager();
        manager.onKey(GLFW_KEY_W, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q, GLFW_PRESS);
        manager.onKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q, GLFW_RELEASE);
        manager.onScroll(0.0, 2.0);

        manager.discardFixedInputEdges();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertTrue(snapshot.isKeyDown(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(GLFW_KEY_W));
        assertFalse(snapshot.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_Q));
        assertEquals(0, snapshot.scrollSteps());
    }

    @Test
    void mousePressEdgeIsLatchedOnceWhileHoldSurvivesFixedSamples() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        InputSnapshot first = manager.consumeFixedInput();
        InputSnapshot second = manager.consumeFixedInput();

        assertTrue(first.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(first.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(second.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(second.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossClearsHeldAndPressedMouseButtons() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.onWindowFocus(false);
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossPublishesOneMouseInteractionInvalidation() {
        InputManager manager = new InputManager();

        manager.onWindowFocus(false);

        assertTrue(manager.consumeMouseInteractionInvalidation());
        assertFalse(manager.consumeMouseInteractionInvalidation());
    }

    @Test
    void cursorCaptureResetClearsHeldAndPressedMouseButtons() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.resetMouseBaseline();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertFalse(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void cursorCaptureResetRejectsAnotherPressUntilPhysicalRelease() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.resetMouseBaseline();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        InputSnapshot stillSuppressed = manager.consumeFixedInput();

        assertFalse(stillSuppressed.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(stillSuppressed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));

        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        InputSnapshot rearmed = manager.consumeFixedInput();

        assertTrue(rearmed.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(rearmed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void focusLossReleaseRearmsAnotherPressForUiAndGameplay() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);

        manager.invalidateGameplayInput();
        InputSnapshot suppressed = manager.consumeFixedInput();
        assertFalse(suppressed.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertFalse(suppressed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));

        manager.onWindowFocus(false);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_RELEASE);
        manager.onWindowFocus(true);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS);
        UiInputSnapshot ui = manager.captureUiInput(8L);

        assertTrue(ui.isMouseDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(ui.isMousePressed(GLFW_MOUSE_BUTTON_RIGHT));

        InputSnapshot rearmed = manager.consumeFixedInput();

        assertTrue(rearmed.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT));
        assertTrue(rearmed.isMouseButtonPressed(GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void suppressedMousePressRemainsVisibleToUiButIsFilteredFromGameplay() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);
        manager.invalidateGameplayInput();

        manager.onWindowFocus(false);
        manager.onWindowFocus(true);
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        UiInputSnapshot ui = manager.captureUiInput(9L);
        InputSnapshot gameplay = manager.consumeFixedInput();

        assertTrue(ui.isMouseDown(GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(ui.isMousePressed(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(gameplay.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(gameplay.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void discardingEdgesPreservesHeldMouseButtonButClearsItsPress() {
        InputManager manager = new InputManager();
        manager.onMouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        manager.discardFixedInputEdges();
        InputSnapshot snapshot = manager.consumeFixedInput();

        assertTrue(snapshot.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(snapshot.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT));
    }

    private static Stream<Arguments> gameplayKeys() {
        return Stream.of(
                Arguments.of("forward", GameConfig.Input.KEY_FORWARD),
                Arguments.of("backward", GameConfig.Input.KEY_BACKWARD),
                Arguments.of("left", GameConfig.Input.KEY_LEFT),
                Arguments.of("right", GameConfig.Input.KEY_RIGHT),
                Arguments.of("jump", GameConfig.Input.KEY_JUMP),
                Arguments.of("descend", GameConfig.Input.KEY_DESCEND),
                Arguments.of(
                        "pickup modifier left",
                        GameConfig.Input.KEY_PICKUP_MODIFIER_LEFT),
                Arguments.of(
                        "pickup modifier right",
                        GameConfig.Input.KEY_PICKUP_MODIFIER_RIGHT),
                Arguments.of("close or pause", GameConfig.Input.KEY_CLOSE),
                Arguments.of(
                        "cursor capture or pause",
                        GameConfig.Input.KEY_CURSOR_CAPTURE),
                Arguments.of("toggle HUD", GameConfig.Input.KEY_TOGGLE_HUD),
                Arguments.of(
                        "toggle debug HUD",
                        GameConfig.Input.KEY_TOGGLE_DEBUG_HUD),
                Arguments.of("select left slot", GameConfig.Input.KEY_SELECT_LEFT),
                Arguments.of("select right slot", GameConfig.Input.KEY_SELECT_RIGHT),
                Arguments.of("select mouth slot", GameConfig.Input.KEY_SELECT_MOUTH),
                Arguments.of("drop", GameConfig.Input.KEY_DROP),
                Arguments.of(
                        "drop all modifier left",
                        GameConfig.Input.KEY_DROP_ALL_LEFT),
                Arguments.of(
                        "drop all modifier right",
                        GameConfig.Input.KEY_DROP_ALL_RIGHT),
                Arguments.of(
                        "toggle game mode",
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Arguments.of(
                        "debug inventory seed",
                        GameConfig.Input.KEY_DEBUG_INVENTORY_SEED),
                Arguments.of(
                        "debug inventory clear",
                        GameConfig.Input.KEY_DEBUG_INVENTORY_CLEAR),
                Arguments.of(
                        "debug inventory fill",
                        GameConfig.Input.KEY_DEBUG_INVENTORY_FILL),
                Arguments.of(
                        "debug inventory print",
                        GameConfig.Input.KEY_DEBUG_INVENTORY_PRINT));
    }

    private static Stream<Arguments> gameplayMouseButtons() {
        return Stream.of(
                Arguments.of("primary mouse", GameConfig.Input.MOUSE_PRIMARY),
                Arguments.of("secondary mouse", GameConfig.Input.MOUSE_SECONDARY));
    }
}
