package com.overlord.core;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowContentScaleCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;
import com.overlord.renderer.RenderSurfaceMetrics;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

public class Window {
    private final MainThreadGuard mainThreadGuard;
    private final String title;
    private final int requestedWidth;
    private final int requestedHeight;
    private final boolean initialVsync;
    private long window;
    private WindowMetrics metrics;
    private boolean glfwInitialized;

    public Window() {
        this(MainThreadGuard.captureCurrentThread());
    }

    public Window(MainThreadGuard mainThreadGuard) {
        this(
                mainThreadGuard,
                GameConfig.Window.TITLE,
                GameConfig.Window.WIDTH,
                GameConfig.Window.HEIGHT,
                GameConfig.Window.VSYNC);
    }

    public Window(MainThreadGuard mainThreadGuard, boolean initialVsync) {
        this(
                mainThreadGuard,
                GameConfig.Window.TITLE,
                GameConfig.Window.WIDTH,
                GameConfig.Window.HEIGHT,
                initialVsync);
    }

    public Window(String title, int width, int height) {
        this(
                MainThreadGuard.captureCurrentThread(),
                title,
                width,
                height,
                GameConfig.Window.VSYNC);
    }

    public Window(MainThreadGuard mainThreadGuard, String title, int width, int height) {
        this(mainThreadGuard, title, width, height, GameConfig.Window.VSYNC);
    }

    public Window(
            MainThreadGuard mainThreadGuard,
            String title,
            int width,
            int height,
            boolean initialVsync) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.title = Objects.requireNonNull(title, "title");
        this.requestedWidth = width;
        this.requestedHeight = height;
        this.initialVsync = initialVsync;
        init();
    }

    private void init() {
        mainThreadGuard.assertMainThread("GLFW window initialization");
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwInitialized = true;

        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GameConfig.Window.RESIZABLE);
            glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR,
                    GameConfig.Window.OPENGL_VERSION_MAJOR);
            glfwWindowHint(
                    org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR,
                    GameConfig.Window.OPENGL_VERSION_MINOR);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

            window = glfwCreateWindow(requestedWidth, requestedHeight, title, 0, 0);
            if (window == 0) {
                throw new IllegalStateException("Failed to create the GLFW window");
            }

            GLFWVidMode videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (videoMode != null) {
                glfwSetWindowPos(
                        window,
                        (videoMode.width() - requestedWidth) / 2,
                        (videoMode.height() - requestedHeight) / 2);
            }

            configureContext(
                    mainThreadGuard,
                    window,
                    initialVsync,
                    org.lwjgl.glfw.GLFW::glfwMakeContextCurrent,
                    org.lwjgl.glfw.GLFW::glfwSwapInterval);
            GL.createCapabilities();
            initializeMetrics();
            installResizeCallbacks();
            glfwShowWindow(window);
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    static void configureContext(
            MainThreadGuard mainThreadGuard,
            long window,
            boolean vsync,
            LongConsumer makeContextCurrent,
            IntConsumer setSwapInterval) {
        Objects.requireNonNull(mainThreadGuard, "mainThreadGuard")
                .assertMainThread("GLFW context activation and VSync");
        Objects.requireNonNull(makeContextCurrent, "makeContextCurrent")
                .accept(window);
        applySwapInterval(vsync, setSwapInterval);
    }

    static void applySwapInterval(
            boolean vsync, IntConsumer setSwapInterval) {
        Objects.requireNonNull(setSwapInterval, "setSwapInterval")
                .accept(vsync ? 1 : 0);
    }

    static void applySwapInterval(
            MainThreadGuard mainThreadGuard,
            boolean vsync,
            IntConsumer setSwapInterval) {
        Objects.requireNonNull(mainThreadGuard, "mainThreadGuard")
                .assertMainThread("runtime VSync application");
        applySwapInterval(vsync, setSwapInterval);
    }

    private void initializeMetrics() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer logicalWidth = stack.mallocInt(1);
            IntBuffer logicalHeight = stack.mallocInt(1);
            IntBuffer framebufferWidth = stack.mallocInt(1);
            IntBuffer framebufferHeight = stack.mallocInt(1);
            FloatBuffer scaleX = stack.mallocFloat(1);
            FloatBuffer scaleY = stack.mallocFloat(1);
            glfwGetWindowSize(window, logicalWidth, logicalHeight);
            glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            glfwGetWindowContentScale(window, scaleX, scaleY);
            metrics =
                    new WindowMetrics(new RenderSurfaceMetrics(
                            logicalWidth.get(0),
                            logicalHeight.get(0),
                            framebufferWidth.get(0),
                            framebufferHeight.get(0), scaleX.get(0), scaleY.get(0)));
        }
    }

    private void installResizeCallbacks() {
        glfwSetWindowSizeCallback(
                window, (ignored, width, height) -> metrics.updateLogicalSize(width, height));
        glfwSetFramebufferSizeCallback(
                window, (ignored, width, height) -> metrics.updateFramebufferSize(width, height));
        glfwSetWindowContentScaleCallback(
                window, (ignored, x, y) -> metrics.updateContentScale(x, y));
    }

    public void setCursorCaptured(boolean captured) {
        mainThreadGuard.assertMainThread("cursor capture");
        glfwSetInputMode(
                window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
    }

    public void setVsync(boolean vsync) {
        applySwapInterval(
                mainThreadGuard,
                vsync,
                org.lwjgl.glfw.GLFW::glfwSwapInterval);
    }

    public void pollEvents() {
        mainThreadGuard.assertMainThread("GLFW event polling");
        glfwPollEvents();
    }

    public void swapBuffers() {
        mainThreadGuard.assertMainThread("GLFW buffer swap");
        glfwSwapBuffers(window);
    }

    public boolean shouldClose() {
        mainThreadGuard.assertMainThread("GLFW close query");
        return glfwWindowShouldClose(window);
    }

    public void destroy() {
        mainThreadGuard.assertMainThread("GLFW window destruction");
        if (window != 0) {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = 0;
        }
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
    }

    public long getWindow() {
        mainThreadGuard.assertMainThread("GLFW window handle access");
        return window;
    }

    public int getWidth() {
        return metrics.logicalWidth();
    }

    public int getHeight() {
        return metrics.logicalHeight();
    }

    public int getFramebufferWidth() {
        return metrics.framebufferWidth();
    }

    public int getFramebufferHeight() {
        return metrics.framebufferHeight();
    }

    public Optional<RenderSurfaceMetrics> consumeSurfaceUpdate() {
        mainThreadGuard.assertMainThread("surface update consumption");
        return metrics.consumeSurfaceUpdate();
    }

    public RenderSurfaceMetrics currentSurfaceMetrics() { return metrics.current(); }
}
