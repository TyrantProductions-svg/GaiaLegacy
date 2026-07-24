package com.overlord;

import com.overlord.core.Engine;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Engine engine = new Engine();
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
                engine.getRenderer().clear();
                engine.getWindow().swapBuffers();
            }
        } finally {
            engine.shutdown();
        }
    }
}
