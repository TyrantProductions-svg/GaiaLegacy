package com.overlord;

import com.overlord.core.Engine;
import java.util.List;
import com.overlord.core.time.FrameClock;
import com.overlord.renderer.RenderFrameInput;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Engine engine = new Engine();
        FrameClock frameClock = new FrameClock(System::nanoTime, 0.25d);
        try {
            engine.init();
            while (engine.isRunning() && !engine.getWindow().shouldClose()) {
                engine.getWindow().pollEvents();
                engine.getWindow()
                        .consumeFramebufferResize()
                        .ifPresent(
                                size ->
                                        engine.getRenderer()
                                                .resizeFramebuffer(size.width(), size.height()));
                engine.getRenderer().renderFrame(
                        new RenderFrameInput(List.of(), frameClock.tick(), 0));
                engine.getWindow().swapBuffers();
            }
        } finally {
            engine.shutdown();
        }
    }
}
