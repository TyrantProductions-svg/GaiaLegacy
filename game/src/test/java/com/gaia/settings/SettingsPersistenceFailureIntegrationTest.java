package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

import com.gaia.audio.GaiaMusicCatalog;
import com.gaia.audio.MusicManager;
import com.gaia.session.GameSessionFrame;
import com.gaia.shell.ModalId;
import com.gaia.shell.ProductLoop;
import com.gaia.shell.ProductShellController;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenRouter;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.overlord.audio.AudioDevice;
import com.overlord.audio.SilentAudioBackend;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsPersistenceFailureIntegrationTest {
    private final AudioDevice audioDevice = AudioDevice.open(
            SilentAudioBackend::new,
            MainThreadGuard.captureCurrentThread(),
            ignored -> {});
    private final MusicManager musicManager = new MusicManager(
            audioDevice, new GaiaMusicCatalog(), ignored -> {});

    @AfterEach
    void closeAudio() {
        musicManager.close();
        audioDevice.close();
    }

    @Test
    void moveAndCleanupFailureStopsTheShellFrameWithCleanupEvidenceVisible(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        SettingsSnapshot defaults = SettingsDefaults.schemaV1();
        new JsonSettingsStore(target).save(defaults);
        String previousJson = Files.readString(target, StandardCharsets.UTF_8);
        IOException moveFailure = new IOException("injected move failure");
        IOException cleanupFailure = new IOException("injected cleanup failure");
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    throw moveFailure;
                },
                temporary -> {
                    throw cleanupFailure;
                });
        JsonSettingsStore store = new JsonSettingsStore(target, writer);
        RecordingHotRuntime runtime = new RecordingHotRuntime();
        SettingsController settings =
                new SettingsController(defaults, store, runtime.applier());
        ProductShellController shell =
                new ProductShellController(ScreenRouter.mainMenu(), settings);
        shell.handle(new ScreenCommand.OpenSettings());
        shell.handle(new ScreenCommand.AdjustSetting(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        shell.handle(new ScreenCommand.Back());
        assertEquals(ScreenId.SETTINGS, shell.snapshot().screen());
        assertEquals(
                Optional.of(ModalId.DIRTY_SETTINGS_CONFIRMATION),
                shell.snapshot().modal());

        InputManager input = new InputManager();
        ProductScreenPresenter presenter = new ProductScreenPresenter(
                new EmptySaveCatalog(), textRenderer(), settings::snapshot);
        RecordingFrameHost host = new RecordingFrameHost();
        ProductLoop loop = new ProductLoop(
                input,
                shell,
                new ProductScreenInputController(),
                presenter,
                () -> {
                    throw new AssertionError("settings persistence must not launch a session");
                },
                () -> 1.0d / 60.0d,
                host,
                musicManager,
                () -> {});
        ProductUiLayout layout = presenter.present(shell.snapshot(), host.layoutContext());
        InputManagerTestDriver.cursor(
                input,
                layout.region(UiActionId.APPLY_SETTINGS).centerX(),
                layout.region(UiActionId.APPLY_SETTINGS).centerY());
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        SettingsPersistenceException thrown = assertThrows(
                SettingsPersistenceException.class,
                () -> loop.runFrame(1.0d / 60.0d));

        assertSame(moveFailure, thrown.getCause());
        assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
        assertEquals(previousJson, Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(defaults, settings.applied());
        assertTrue(settings.snapshot().dirty());
        assertEquals(71.0, settings.snapshot().draft().fovDegrees());
        assertEquals(List.of(71.0f, 70.0f), runtime.fovTransitions());
        assertEquals(70.0f, runtime.fov());
        assertEquals(0, host.productRenderCount());
        assertEquals(0, host.swapCount());
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static final class RecordingHotRuntime {
        private final List<Float> fovTransitions = new ArrayList<>();
        private float fov = 70.0f;

        private SettingsApplier applier() {
            return new SettingsApplier(
                    ignored -> {},
                    nextFov -> {
                        fovTransitions.add(nextFov);
                        fov = nextFov;
                    },
                    (ignoredSensitivity, ignoredInvertY) -> {},
                    (ignoredMaster, ignoredMusic, ignoredSfx, ignoredMute) -> {});
        }

        private List<Float> fovTransitions() {
            return List.copyOf(fovTransitions);
        }

        private float fov() {
            return fov;
        }
    }

    private static final class RecordingFrameHost implements ProductLoop.FrameHost {
        private final UiLayoutContext context = new UiLayoutContext(
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
        private int productRenderCount;
        private int swapCount;

        @Override
        public boolean shouldClose() {
            return false;
        }

        @Override
        public void pollEvents() {}

        @Override
        public UiLayoutContext layoutContext() {
            return context;
        }

        @Override
        public void setCursorCaptured(boolean captured) {}

        @Override
        public void renderSession(GameSessionFrame frame) {
            throw new AssertionError("settings persistence must not render a session");
        }

        @Override
        public void renderProduct(ProductUiLayout layout) {
            productRenderCount++;
        }

        @Override
        public void swapBuffers() {
            swapCount++;
        }

        private int productRenderCount() {
            return productRenderCount;
        }

        private int swapCount() {
            return swapCount;
        }
    }
}
