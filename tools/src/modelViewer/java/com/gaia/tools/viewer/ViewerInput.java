package com.gaia.tools.viewer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/** Bounded callback accumulator consumed once per viewer frame. */
public final class ViewerInput {
    public static final double MAX_POINTER_DELTA = 8_192.0;
    private static final double MAX_SCROLL_DELTA = 128.0;

    public record Frame(double orbitX, double orbitY, double panX, double panY, double zoom,
            boolean frame, boolean reload, boolean grid, boolean axes, boolean bounds,
            boolean wireframe, boolean close) {
        public static Frame idle() {
            return new Frame(0, 0, 0, 0, 0,
                    false, false, false, false, false, false, false);
        }
    }

    private boolean focused;
    private boolean hasCursor;
    private boolean left;
    private boolean leftPans;
    private boolean middle;
    private double cursorX;
    private double cursorY;
    private double orbitX;
    private double orbitY;
    private double panX;
    private double panY;
    private double zoom;
    private boolean frame;
    private boolean reload;
    private boolean grid;
    private boolean axes;
    private boolean bounds;
    private boolean wireframe;
    private boolean close;

    public void focus(boolean focused) {
        this.focused = focused;
        hasCursor = false;
        left = false;
        leftPans = false;
        middle = false;
        clearPending();
    }

    public void cursor(double x, double y) {
        if (!focused || !Double.isFinite(x) || !Double.isFinite(y)) return;
        if (!hasCursor) {
            cursorX = x;
            cursorY = y;
            hasCursor = true;
            return;
        }
        double dx = clamp(x - cursorX, MAX_POINTER_DELTA);
        double dy = clamp(y - cursorY, MAX_POINTER_DELTA);
        cursorX = x;
        cursorY = y;
        if (middle || (left && leftPans)) {
            panX = boundedAdd(panX, dx, MAX_POINTER_DELTA);
            panY = boundedAdd(panY, dy, MAX_POINTER_DELTA);
        } else if (left) {
            orbitX = boundedAdd(orbitX, dx, MAX_POINTER_DELTA);
            orbitY = boundedAdd(orbitY, dy, MAX_POINTER_DELTA);
        }
    }

    public void mouseButton(int button, int action, int mods) {
        if (!focused) return;
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW_PRESS) {
                left = true;
                leftPans = (mods & GLFW_MOD_SHIFT) != 0;
            } else if (action == GLFW_RELEASE) {
                left = false;
                leftPans = false;
            }
        } else if (button == GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action == GLFW_PRESS) middle = true;
            else if (action == GLFW_RELEASE) middle = false;
        }
    }

    public void scroll(double yOffset) {
        if (focused && Double.isFinite(yOffset)) {
            zoom = boundedAdd(zoom, yOffset, MAX_SCROLL_DELTA);
        }
    }

    public void key(int key, int action, int mods) {
        if (!focused || action != GLFW_PRESS) return;
        switch (key) {
            case GLFW_KEY_F -> frame = true;
            case GLFW_KEY_R -> reload = true;
            case GLFW_KEY_G -> grid = true;
            case GLFW_KEY_A -> axes = true;
            case GLFW_KEY_B -> bounds = true;
            case GLFW_KEY_W -> wireframe = true;
            case GLFW_KEY_ESCAPE -> close = true;
            default -> { }
        }
    }

    public Frame consume() {
        Frame result = new Frame(orbitX, orbitY, panX, panY, zoom,
                frame, reload, grid, axes, bounds, wireframe, close);
        clearPending();
        return result;
    }

    private void clearPending() {
        orbitX = 0; orbitY = 0; panX = 0; panY = 0; zoom = 0;
        frame = false; reload = false; grid = false; axes = false;
        bounds = false; wireframe = false; close = false;
    }

    private static double boundedAdd(double current, double delta, double limit) {
        return clamp(current + delta, limit);
    }

    private static double clamp(double value, double limit) {
        if (!Double.isFinite(value)) return Math.copySign(limit, value);
        return Math.max(-limit, Math.min(limit, value));
    }
}
