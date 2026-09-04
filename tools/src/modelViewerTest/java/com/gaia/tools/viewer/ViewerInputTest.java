package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

import org.junit.jupiter.api.Test;

class ViewerInputTest {
    @Test
    void controlsProduceBoundedPressedEdgesAndDragDeltas() {
        ViewerInput input = new ViewerInput();
        input.focus(true);
        input.cursor(100, 100);
        input.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);
        input.cursor(108, 94);
        input.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
        input.mouseButton(GLFW_MOUSE_BUTTON_MIDDLE, GLFW_PRESS, 0);
        input.cursor(111, 99);
        input.mouseButton(GLFW_MOUSE_BUTTON_MIDDLE, GLFW_RELEASE, 0);
        input.scroll(2.5);
        for (int key : new int[]{GLFW_KEY_F, GLFW_KEY_R, GLFW_KEY_G, GLFW_KEY_A,
                GLFW_KEY_B, GLFW_KEY_W, GLFW_KEY_ESCAPE}) input.key(key, GLFW_PRESS, 0);
        input.key(GLFW_KEY_R, GLFW_REPEAT, 0);

        ViewerInput.Frame frame = input.consume();

        assertEquals(8, frame.orbitX(), 0.0);
        assertEquals(-6, frame.orbitY(), 0.0);
        assertEquals(3, frame.panX(), 0.0);
        assertEquals(5, frame.panY(), 0.0);
        assertEquals(2.5, frame.zoom(), 0.0);
        assertTrue(frame.frame()); assertTrue(frame.reload()); assertTrue(frame.grid());
        assertTrue(frame.axes()); assertTrue(frame.bounds()); assertTrue(frame.wireframe());
        assertTrue(frame.close());
        assertEquals(ViewerInput.Frame.idle(), input.consume());
    }

    @Test
    void shiftLeftPansAndFocusLossClearsHeldAndPendingState() {
        ViewerInput input = new ViewerInput();
        input.focus(true); input.cursor(10, 10);
        input.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, GLFW_MOD_SHIFT);
        input.cursor(15, 12);
        ViewerInput.Frame pan = input.consume();
        assertEquals(5, pan.panX(), 0.0);
        assertEquals(2, pan.panY(), 0.0);
        assertEquals(0, pan.orbitX(), 0.0);

        input.key(GLFW_KEY_R, GLFW_PRESS, 0);
        input.scroll(4);
        input.focus(false);
        assertEquals(ViewerInput.Frame.idle(), input.consume());
        input.focus(true);
        input.cursor(200, 200);
        input.cursor(220, 220);
        assertEquals(ViewerInput.Frame.idle(), input.consume());
    }

    @Test
    void nonFiniteOrExtremeCallbackValuesCannotPoisonFrameMath() {
        ViewerInput input = new ViewerInput();
        input.focus(true); input.cursor(0, 0);
        input.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);
        input.cursor(Double.NaN, Double.POSITIVE_INFINITY);
        input.scroll(Double.NaN);
        input.cursor(Double.MAX_VALUE, -Double.MAX_VALUE);

        ViewerInput.Frame frame = input.consume();
        assertTrue(Double.isFinite(frame.orbitX()));
        assertTrue(Double.isFinite(frame.orbitY()));
        assertTrue(Math.abs(frame.orbitX()) <= ViewerInput.MAX_POINTER_DELTA);
        assertTrue(Math.abs(frame.orbitY()) <= ViewerInput.MAX_POINTER_DELTA);
        assertFalse(frame.reload());
    }
}
