package com.overlord.core.input;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class InputManager {
    private final MainThreadGuard mainThreadGuard;
    private final boolean[] downKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] pressedKeys = new boolean[GLFW_KEY_LAST + 1];

    private boolean hasMouseBaseline;
    private double lastMouseX;
    private double lastMouseY;
    private double accumulatedMouseX;
    private double accumulatedMouseY;

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
        glfwSetCursorPosCallback(window, (ignored, x, y) -> onCursorPosition(x, y));
        glfwSetWindowFocusCallback(window, (ignored, focused) -> onWindowFocus(focused));
    }

    public InputSnapshot consumeFixedInput() {
        mainThreadGuard.assertMainThread("fixed input consumption");
        Set<Integer> down = copySetBits(downKeys);
        Set<Integer> pressed = copySetBits(pressedKeys);
        Arrays.fill(pressedKeys, false);
        return new InputSnapshot(down, pressed);
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
        }
    }

    void onCursorPosition(double x, double y) {
        mainThreadGuard.assertMainThread("GLFW cursor callback");
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

    void onWindowFocus(boolean focused) {
        mainThreadGuard.assertMainThread("GLFW focus callback");
        resetMouseState();
        if (!focused) {
            Arrays.fill(downKeys, false);
            Arrays.fill(pressedKeys, false);
        }
    }

    private void resetMouseState() {
        hasMouseBaseline = false;
        accumulatedMouseX = 0.0;
        accumulatedMouseY = 0.0;
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
