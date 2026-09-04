package com.gaia.tools.viewer;

import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.GL_RENDERER;
import static org.lwjgl.opengl.GL11C.GL_VENDOR;
import static org.lwjgl.opengl.GL11C.GL_VERSION;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL11C.glGetString;
import static org.lwjgl.opengl.GL20C.GL_SHADING_LANGUAGE_VERSION;

import com.gaia.tools.model.GaiaGlbValidator;
import com.gaia.tools.model.ValidationReportWriter;
import com.overlord.core.Window;
import com.overlord.core.thread.MainThreadGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** One isolated GLFW/OpenGL 4.1 process for a validated model snapshot. */
final class ViewerApplication {
    private static final String TITLE = "Gaia Model Inspector";

    private ViewerApplication() { }

    static void launch(Path path, GaiaGlbValidator.Result result, ViewerCpuModel initialCpu)
            throws IOException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        Window window = null;
        InspectorRenderer renderer = null;
        ViewerReloadController<InspectorGpuModel> reload = null;
        try {
            window = new Window(guard, TITLE, 1280, 720, true);
            long handle = window.getWindow();
            ViewerInput input = new ViewerInput();
            installCallbacks(handle, input);
            input.focus(true);

            OpenGlViewerResources gpuResources = new OpenGlViewerResources(guard);
            renderer = new InspectorRenderer(new OpenGlInspectorRenderBackend(guard));
            ViewerCpuModel acceptedInitial = initialCpu;
            Window activeWindow = window;
            reload = new ViewerReloadController<>(guard,
                    () -> validate(path),
                    snapshot -> snapshot.sourceSha256().equals(acceptedInitial.sourceSha256())
                            ? acceptedInitial : ViewerPresentation.prepare(snapshot,
                                    activeWindow.getFramebufferWidth(),
                                    activeWindow.getFramebufferHeight()),
                    candidate -> InspectorGpuModel.upload(candidate, gpuResources));
            if (!reload.loadInitial(result) || reload.current().isEmpty()) {
                throw new IllegalStateException("validated initial model could not reach the GPU");
            }

            OrbitCamera camera = new OrbitCamera(initialCpu.bounds(),
                    window.getFramebufferWidth(), window.getFramebufferHeight());
            ViewerControls controls = new ViewerControls();
            printGlIdentity();
            printStatus(reload);
            updateTitle(handle, reload);

            while (!window.shouldClose()) {
                window.pollEvents();
                window.consumeSurfaceUpdate().ifPresent(metrics ->
                        camera.resize(metrics.framebufferWidth(), metrics.framebufferHeight()));
                ViewerControls.Intents intents = controls.apply(
                        input.consume(), camera, window.getHeight());
                if (intents.close()) glfwSetWindowShouldClose(handle, true);
                if (intents.reload()) {
                    InspectorGpuModel before = reload.current().map(ViewerReloadController.Current::gpu)
                            .orElse(null);
                    reload.requestReload();
                    reload.reloadIfRequested();
                    InspectorGpuModel after = reload.current().map(ViewerReloadController.Current::gpu)
                            .orElse(null);
                    if (after != null && after != before) camera.frame(after.cpu().bounds());
                    System.out.print(reload.status().report());
                    printStatus(reload);
                    updateTitle(handle, reload);
                }
                InspectorGpuModel current = reload.current().orElseThrow().gpu();
                renderer.render(current, camera, controls.visibility(),
                        window.getFramebufferWidth(), window.getFramebufferHeight());
                int error = glGetError();
                if (error != GL_NO_ERROR) {
                    throw new IllegalStateException(
                            "OpenGL rejected a viewer operation: 0x" + Integer.toHexString(error));
                }
                window.swapBuffers();
            }
        } finally {
            Throwable failure = null;
            try { if (reload != null) reload.close(); }
            catch (RuntimeException | Error caught) { failure = caught; }
            try { if (renderer != null) renderer.close(); }
            catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught; else failure.addSuppressed(caught);
            }
            try { if (window != null) window.destroy(); }
            catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught; else failure.addSuppressed(caught);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    private static GaiaGlbValidator.Result validate(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return GaiaGlbValidator.validate(input);
        }
    }

    private static void installCallbacks(long window, ViewerInput input) {
        glfwSetKeyCallback(window, (ignored, key, scanCode, action, mods) ->
                input.key(key, action, mods));
        glfwSetMouseButtonCallback(window, (ignored, button, action, mods) ->
                input.mouseButton(button, action, mods));
        glfwSetCursorPosCallback(window, (ignored, x, y) -> input.cursor(x, y));
        glfwSetScrollCallback(window, (ignored, x, y) -> input.scroll(y));
        glfwSetWindowFocusCallback(window, (ignored, focused) -> input.focus(focused));
    }

    private static void updateTitle(long window, ViewerReloadController<?> reload) {
        String current = shortSha(reload.currentSha256());
        String candidate = shortSha(reload.status().candidateSha256());
        String state = reload.status().code().name().toLowerCase().replace('_', '-');
        String suffix = candidate.isEmpty() ? "" : " candidate " + candidate;
        glfwSetWindowTitle(window, TITLE + " | " + state + " | current " + current + suffix);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isEmpty() ? "none" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static void printStatus(ViewerReloadController<? extends InspectorGpuModel> reload) {
        InspectorGpuModel model = reload.current().map(ViewerReloadController.Current::gpu).orElse(null);
        int handles = model == null ? 0 : model.handleCount();
        long bytes = model == null ? 0 : model.estimatedBytes();
        System.out.println("VIEWER_RESOURCES current=" + reload.liveCurrentCount()
                + " candidate=" + reload.liveCandidateCount()
                + " handles=" + handles + " estimatedBytes=" + bytes);
    }

    private static void printGlIdentity() {
        System.out.println("VIEWER_GL vendor=" + safeGl(GL_VENDOR)
                + " renderer=" + safeGl(GL_RENDERER)
                + " version=" + safeGl(GL_VERSION)
                + " glsl=" + safeGl(GL_SHADING_LANGUAGE_VERSION));
    }

    private static String safeGl(int name) {
        String value = glGetString(name);
        if (value == null) return "unavailable";
        return value.replaceAll("[\\p{Cntrl}]", "?");
    }
}
