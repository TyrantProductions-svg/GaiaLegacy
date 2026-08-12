package com.overlord.core.input;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class InputManager {
    public static final int MAX_TYPED_CODE_POINTS_PER_SAMPLE = 64;

    private final MainThreadGuard mainThreadGuard;
    private final boolean[] downKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] pressedKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] downMouseButtons =
            new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] pressedMouseButtons =
            new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] suppressedKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] suppressedMouseButtons =
            new boolean[GLFW_MOUSE_BUTTON_LAST + 1];

    private boolean hasMouseBaseline;
    private double lastMouseX;
    private double lastMouseY;
    private double pointerX;
    private double pointerY;
    private double accumulatedMouseX;
    private double accumulatedMouseY;
    private final List<Integer> accumulatedScrollDeltas = new ArrayList<>();
    private final List<Integer> typedCodePoints = new ArrayList<>();
    private int accumulatedScrollMagnitude;
    private boolean mouseInteractionInvalidated;
    private boolean windowFocused = true;

    public InputManager() {
        this(MainThreadGuard.captureCurrentThread());
    }

    public InputManager(MainThreadGuard mainThreadGuard) {
        this.mainThreadGuard =
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
    }

    public void install(long window) {
        mainThreadGuard.assertMainThread("GLFW input callback installation");
        glfwSetKeyCallback(window, (ignored, key, scancode, action, mods) -> onKey(key, action));
        glfwSetCharCallback(window, (ignored, codePoint) -> onCharacter(codePoint));
        glfwSetMouseButtonCallback(
                window,
                (ignored, button, action, mods) ->
                        onMouseButton(button, action));
        glfwSetCursorPosCallback(window, (ignored, x, y) -> onCursorPosition(x, y));
        glfwSetScrollCallback(window, (ignored, xOffset, yOffset) -> onScroll(xOffset, yOffset));
        glfwSetWindowFocusCallback(window, (ignored, focused) -> onWindowFocus(focused));
    }

    public InputSnapshot consumeFixedInput() {
        mainThreadGuard.assertMainThread("fixed input consumption");
        Set<Integer> down = copySetBits(downKeys);
        Set<Integer> pressed = copySetBits(pressedKeys);
        Set<Integer> downButtons = copySetBits(downMouseButtons);
        Set<Integer> pressedButtons = copySetBits(pressedMouseButtons);
        removeSuppressedKeys(down);
        removeSuppressedKeys(pressed);
        removeSuppressedButtons(downButtons);
        removeSuppressedButtons(pressedButtons);
        List<Integer> scrollDeltas = List.copyOf(accumulatedScrollDeltas);
        Arrays.fill(pressedKeys, false);
        Arrays.fill(pressedMouseButtons, false);
        clearScrollEdges();
        return new InputSnapshot(
                down, pressed, downButtons, pressedButtons, scrollDeltas);
    }

    /**
     * Captures callback-owned UI input without consuming gameplay input edges.
     * Pointer coordinates are the logical window coordinates supplied by GLFW.
     */
    public UiInputSnapshot captureUiInput(long sampleId) {
        mainThreadGuard.assertMainThread("UI input capture");
        List<Integer> capturedCodePoints = List.copyOf(typedCodePoints);
        clearTypedCodePoints();
        return new UiInputSnapshot(
                copySetBits(downKeys),
                copySetBits(pressedKeys),
                copySetBits(downMouseButtons),
                copySetBits(pressedMouseButtons),
                accumulatedScrollDeltas,
                capturedCodePoints,
                pointerX,
                pointerY,
                windowFocused,
                sampleId);
    }

    /** Suppresses controls already held by the player until their next physical release. */
    public void invalidateGameplayInput() {
        mainThreadGuard.assertMainThread("gameplay input invalidation");
        suppressDownInputs(downKeys, suppressedKeys);
        suppressDownInputs(downMouseButtons, suppressedMouseButtons);
        Arrays.fill(pressedKeys, false);
        Arrays.fill(pressedMouseButtons, false);
        clearScrollEdges();
        clearTypedCodePoints();
        resetMouseState();
    }

    /** Discards gameplay edges while retaining held-key state. */
    public void discardFixedInputEdges() {
        mainThreadGuard.assertMainThread("fixed input edge discard");
        Arrays.fill(pressedKeys, false);
        Arrays.fill(pressedMouseButtons, false);
        clearScrollEdges();
        clearTypedCodePoints();
    }

    public boolean isKeyDown(int key) {
        mainThreadGuard.assertMainThread("key state query");
        return key >= 0 && key < downKeys.length && downKeys[key];
    }

    public boolean isKeyPressed(int key) {
        mainThreadGuard.assertMainThread("key edge query");
        return key >= 0 && key < pressedKeys.length && pressedKeys[key];
    }

    public boolean consumeKeyPress(int key) {
        mainThreadGuard.assertMainThread("key edge consumption");
        if (key < 0 || key >= pressedKeys.length || !pressedKeys[key]) {
            return false;
        }
        pressedKeys[key] = false;
        return true;
    }

    public void resetMouseBaseline() {
        mainThreadGuard.assertMainThread("mouse baseline reset");
        resetMouseState();
        suppressHeldDestructiveMouseButtons();
        Arrays.fill(downMouseButtons, false);
        Arrays.fill(pressedMouseButtons, false);
    }

    /** Returns and clears the focus-loss signal for the game interaction boundary. */
    public boolean consumeMouseInteractionInvalidation() {
        mainThreadGuard.assertMainThread("mouse interaction invalidation consumption");
        boolean invalidated = mouseInteractionInvalidated;
        mouseInteractionInvalidated = false;
        return invalidated;
    }

    /** Reads the callback-owned GLFW focus state without introducing another authority. */
    public boolean isWindowFocused() {
        mainThreadGuard.assertMainThread("window focus state query");
        return windowFocused;
    }

    public MouseDelta consumeMouseDelta() {
        mainThreadGuard.assertMainThread("mouse input consumption");
        if (accumulatedMouseX == 0.0 && accumulatedMouseY == 0.0) {
            return MouseDelta.ZERO;
        }
        MouseDelta delta = new MouseDelta(accumulatedMouseX, accumulatedMouseY);
        accumulatedMouseX = 0.0;
        accumulatedMouseY = 0.0;
        return delta;
    }

    void onKey(int key, int action) {
        mainThreadGuard.assertMainThread("GLFW key callback");
        if (key < 0 || key >= downKeys.length) {
            return;
        }
        if (action == GLFW_PRESS) {
            if (!downKeys[key]) {
                pressedKeys[key] = true;
            }
            downKeys[key] = true;
        } else if (action == GLFW_RELEASE) {
            downKeys[key] = false;
            suppressedKeys[key] = false;
        }
    }

    void onMouseButton(int button, int action) {
        mainThreadGuard.assertMainThread("GLFW mouse button callback");
        if (button < 0 || button >= downMouseButtons.length) {
            return;
        }
        if (action == GLFW_PRESS) {
            if (!downMouseButtons[button]) {
                pressedMouseButtons[button] = true;
            }
            downMouseButtons[button] = true;
        } else if (action == GLFW_RELEASE) {
            downMouseButtons[button] = false;
            suppressedMouseButtons[button] = false;
        }
    }

    void onCursorPosition(double x, double y) {
        mainThreadGuard.assertMainThread("GLFW cursor callback");
        pointerX = x;
        pointerY = y;
        if (!hasMouseBaseline) {
            lastMouseX = x;
            lastMouseY = y;
            hasMouseBaseline = true;
            return;
        }
        accumulatedMouseX += x - lastMouseX;
        accumulatedMouseY += lastMouseY - y;
        lastMouseX = x;
        lastMouseY = y;
    }

    void onScroll(double xOffset, double yOffset) {
        mainThreadGuard.assertMainThread("GLFW scroll callback");
        if (!Double.isFinite(xOffset) || !Double.isFinite(yOffset) || yOffset == 0.0) {
            return;
        }
        int available = InputSnapshot.MAX_SCROLL_STEPS_PER_SAMPLE
                - accumulatedScrollMagnitude;
        if (available == 0) {
            return;
        }
        long roundedMagnitude = Math.max(1L, Math.round(Math.abs(yOffset)));
        int acceptedMagnitude = (int) Math.min(available, roundedMagnitude);
        accumulatedScrollDeltas.add(
                yOffset > 0.0 ? acceptedMagnitude : -acceptedMagnitude);
        accumulatedScrollMagnitude += acceptedMagnitude;
    }

    void onCharacter(int codePoint) {
        mainThreadGuard.assertMainThread("GLFW character callback");
        if (!windowFocused
                || !isUnicodeScalarValue(codePoint)
                || typedCodePoints.size() >= MAX_TYPED_CODE_POINTS_PER_SAMPLE) {
            return;
        }
        typedCodePoints.add(codePoint);
    }

    void onWindowFocus(boolean focused) {
        mainThreadGuard.assertMainThread("GLFW focus callback");
        windowFocused = focused;
        resetMouseState();
        if (!focused) {
            suppressHeldDestructiveMouseButtons();
            Arrays.fill(downKeys, false);
            Arrays.fill(pressedKeys, false);
            Arrays.fill(downMouseButtons, false);
            Arrays.fill(pressedMouseButtons, false);
            clearScrollEdges();
            clearTypedCodePoints();
            mouseInteractionInvalidated = true;
        }
    }

    private void clearScrollEdges() {
        accumulatedScrollDeltas.clear();
        accumulatedScrollMagnitude = 0;
    }

    private void clearTypedCodePoints() {
        typedCodePoints.clear();
    }

    private static boolean isUnicodeScalarValue(int codePoint) {
        return Character.isValidCodePoint(codePoint)
                && (codePoint < Character.MIN_SURROGATE
                        || codePoint > Character.MAX_SURROGATE);
    }

    private void resetMouseState() {
        hasMouseBaseline = false;
        accumulatedMouseX = 0.0;
        accumulatedMouseY = 0.0;
    }

    private void suppressHeldDestructiveMouseButtons() {
        suppressIfDown(GLFW_MOUSE_BUTTON_LEFT);
        suppressIfDown(GLFW_MOUSE_BUTTON_RIGHT);
    }

    private void suppressIfDown(int button) {
        if (downMouseButtons[button]) {
            suppressedMouseButtons[button] = true;
        }
    }

    private static void suppressDownInputs(boolean[] down, boolean[] suppressed) {
        for (int index = 0; index < down.length; index++) {
            if (down[index]) {
                suppressed[index] = true;
            }
        }
    }

    private void removeSuppressedKeys(Set<Integer> keys) {
        keys.removeIf(key -> suppressedKeys[key]);
    }

    private void removeSuppressedButtons(Set<Integer> buttons) {
        buttons.removeIf(button -> suppressedMouseButtons[button]);
    }

    private static Set<Integer> copySetBits(boolean[] values) {
        Set<Integer> keys = new HashSet<>();
        for (int key = 0; key < values.length; key++) {
            if (values[key]) {
                keys.add(key);
            }
        }
        return keys;
    }
}
