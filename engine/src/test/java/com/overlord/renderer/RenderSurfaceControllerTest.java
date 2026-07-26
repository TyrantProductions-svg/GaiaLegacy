package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RenderSurfaceControllerTest {
    @Test void appliesOnlyFramebufferChangesAndPausesThenResumes() {
        RenderSurfaceController controller = new RenderSurfaceController(new RenderSurfaceMetrics(800,600,1600,900,2,1.5f));
        assertEquals(16.0f / 9.0f, (float) controller.lastPositive().framebufferWidth() / controller.lastPositive().framebufferHeight());
        assertFalse(controller.update(new RenderSurfaceMetrics(900,700,1600,900,1,1)));
        assertFalse(controller.update(new RenderSurfaceMetrics(900,700,0,0,1,1)));
        assertFalse(controller.drawable());
        assertEquals(1600, controller.lastPositive().framebufferWidth());
        assertTrue(controller.update(new RenderSurfaceMetrics(900,700,800,600,1,1)));
        assertTrue(controller.drawable());
        assertFalse(controller.update(new RenderSurfaceMetrics(900,700,800,600,2,2)));
    }
}
