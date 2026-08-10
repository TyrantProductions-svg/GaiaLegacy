package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

import com.gaia.interaction.GameMode;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionConfig;
import com.gaia.settings.AudioSettingsPort;
import com.gaia.settings.SettingsApplier;
import com.gaia.settings.SettingsController;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsLoadResult;
import com.gaia.settings.SettingsPersistenceException;
import com.gaia.settings.SettingsSnapshot;
import com.gaia.settings.SettingsStore;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SettingsShellIntegrationTest {
    @Test
    void typedSettingCommandsUseApprovedDiscreteSteps() {
        Fixture fixture = new Fixture(SettingsDefaults.schemaV1());

        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.CHUNK_RADIUS,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(toggle(ScreenCommand.ToggleTarget.VSYNC));
        fixture.handle(toggle(ScreenCommand.ToggleTarget.INVERT_Y));
        fixture.handle(toggle(ScreenCommand.ToggleTarget.MUTE_WHEN_UNFOCUSED));
        fixture.handle(toggle(ScreenCommand.ToggleTarget.DEFAULT_GAME_MODE));
        fixture.handle(toggle(ScreenCommand.ToggleTarget.DEBUG_HUD_DEFAULT));

        SettingsSnapshot draft = fixture.settings().snapshot().draft();
        assertEquals(71.0, draft.fovDegrees());
        assertEquals(0.11, draft.mouseSensitivity(), 0.000000001);
        assertEquals(5, draft.chunkRadius());
        assertEquals(0.95, draft.masterVolume(), 0.000000001);
        assertEquals(0.70, draft.musicVolume(), 0.000000001);
        assertEquals(0.95, draft.sfxVolume(), 0.000000001);
        assertFalse(draft.vsync());
        assertTrue(draft.invertY());
        assertFalse(draft.muteWhenUnfocused());
        assertEquals(GameMode.CREATIVE, draft.defaultGameMode());
        assertTrue(draft.debugHudDefault());
        assertTrue(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());
    }

    @Test
    void adjustmentCommandsClampAtEveryApprovedNumericLimit() {
        SettingsSnapshot limits = new SettingsSnapshot(
                1,
                true,
                100.0,
                0.02,
                false,
                8,
                0.0,
                1.0,
                0.0,
                true,
                GameMode.SURVIVAL,
                false);
        Fixture fixture = new Fixture(limits);

        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.CHUNK_RADIUS,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));

        assertEquals(limits, fixture.settings().snapshot().draft());
        assertFalse(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());
    }

    @Test
    void incrementsPreserveEveryValidOffGridNumericOffset() {
        Fixture fixture = new Fixture(offGridSettings(0.025));

        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                ScreenCommand.AdjustmentDirection.INCREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                ScreenCommand.AdjustmentDirection.INCREMENT));

        SettingsSnapshot draft = fixture.settings().snapshot().draft();
        assertEquals(71.125, draft.fovDegrees(), 0.000000001);
        assertEquals(0.035, draft.mouseSensitivity(), 0.000000001);
        assertEquals(0.383, draft.masterVolume(), 0.000000001);
        assertEquals(0.383, draft.musicVolume(), 0.000000001);
        assertEquals(0.383, draft.sfxVolume(), 0.000000001);
    }

    @Test
    void decrementsPreserveValidOffGridOffsetsAndStillClampAtTheBoundary() {
        Fixture fixture = new Fixture(offGridSettings(0.025));

        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.FOV,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MASTER_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MUSIC_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));
        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.SFX_VOLUME,
                ScreenCommand.AdjustmentDirection.DECREMENT));

        SettingsSnapshot draft = fixture.settings().snapshot().draft();
        assertEquals(69.125, draft.fovDegrees(), 0.000000001);
        assertEquals(0.02, draft.mouseSensitivity(), 0.000000001);
        assertEquals(0.283, draft.masterVolume(), 0.000000001);
        assertEquals(0.283, draft.musicVolume(), 0.000000001);
        assertEquals(0.283, draft.sfxVolume(), 0.000000001);
    }

    @Test
    void sensitivityDecrementPreservesAnOffGridOffsetWhenWithinBounds() {
        Fixture fixture = new Fixture(offGridSettings(0.125));

        fixture.handle(adjust(
                ScreenCommand.AdjustmentTarget.MOUSE_SENSITIVITY,
                ScreenCommand.AdjustmentDirection.DECREMENT));

        assertEquals(
                0.115,
                fixture.settings().snapshot().draft().mouseSensitivity(),
                0.000000001);
    }

    @Test
    void dirtyBackCancelDismissesModalAndPreservesDraftOnSettingsScreen() {
        Fixture fixture = new Fixture(SettingsDefaults.schemaV1());
        fixture.makeDirty();

        fixture.handle(new ScreenCommand.Back());

        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(
                Optional.of(ModalId.DIRTY_SETTINGS_CONFIRMATION),
                fixture.shell().snapshot().modal());

        fixture.handle(new ScreenCommand.CancelSettings());

        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(Optional.empty(), fixture.shell().snapshot().modal());
        assertEquals(71.0, fixture.settings().snapshot().draft().fovDegrees());
        assertTrue(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());
    }

    @Test
    void dirtyBackApplyPersistsPublishesAndReturnsToOpeningScreen() {
        Fixture fixture = new Fixture(SettingsDefaults.schemaV1());
        fixture.makeDirty();
        fixture.handle(new ScreenCommand.Back());

        fixture.handle(new ScreenCommand.ApplySettings());

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertEquals(Optional.empty(), fixture.shell().snapshot().modal());
        assertEquals(71.0, fixture.settings().applied().fovDegrees());
        assertFalse(fixture.settings().snapshot().dirty());
        assertEquals(List.of(fixture.settings().applied()), fixture.store().saved());
    }

    @Test
    void recoverablePersistenceFailureStaysInSettingsAndRemainsRoutable() {
        Fixture fixture = new Fixture(SettingsDefaults.schemaV1());
        fixture.makeDirty();
        fixture.store().failNextSave(new SettingsPersistenceException(
                "injected persistence failure",
                new IllegalStateException("disk unavailable")));
        fixture.handle(new ScreenCommand.Back());

        fixture.handle(new ScreenCommand.ApplySettings());

        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(
                Optional.of(ModalId.DIRTY_SETTINGS_CONFIRMATION),
                fixture.shell().snapshot().modal());
        assertTrue(fixture.settings().snapshot().blockingDiagnostic().isEmpty());
        assertEquals(SettingsDefaults.schemaV1(), fixture.settings().applied());
        assertTrue(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());

        fixture.handle(new ScreenCommand.CancelSettings());

        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(Optional.empty(), fixture.shell().snapshot().modal());
    }

    @Test
    void fatalOwnerThreadHotApplicationFailurePropagatesUnchanged() {
        IllegalStateException fatalFailure =
                new IllegalStateException("owner-thread hot apply failed");
        Fixture fixture = new Fixture(
                SettingsDefaults.schemaV1(), fovApplicationFailureApplier(fatalFailure));
        fixture.makeDirty();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> fixture.shell().handle(new ScreenCommand.ApplySettings()));

        assertSame(fatalFailure, thrown);
        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(SettingsDefaults.schemaV1(), fixture.settings().applied());
        assertTrue(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());
    }

    @Test
    void persistenceRollbackFailurePropagatesBeforeRenderOrSwapWithSuppressionIntact() {
        SettingsPersistenceException persistenceFailure =
                new SettingsPersistenceException(
                        "injected persistence failure",
                        new IllegalStateException("disk unavailable"));
        IllegalStateException rollbackFailure =
                new IllegalStateException("injected hot rollback failure");
        Fixture fixture = new Fixture(
                SettingsDefaults.schemaV1(),
                fovRollbackFailureApplier(rollbackFailure));
        fixture.makeDirty();
        fixture.store().failNextSave(persistenceFailure);
        fixture.handle(new ScreenCommand.Back());
        InputManager input = new InputManager();
        ProductScreenPresenter presenter = new ProductScreenPresenter(
                new EmptySaveCatalog(), textRenderer(), fixture.settings()::snapshot);
        RecordingFrameHost host = new RecordingFrameHost();
        ProductLoop loop = new ProductLoop(
                input,
                fixture.shell(),
                new ProductScreenInputController(),
                presenter,
                () -> {
                    throw new AssertionError("settings apply must not launch a session");
                },
                () -> 1.0d / 60.0d,
                host);
        ProductUiLayout layout = presenter.present(
                fixture.shell().snapshot(), host.layoutContext());
        InputManagerTestDriver.cursor(
                input,
                layout.region(UiActionId.APPLY_SETTINGS).centerX(),
                layout.region(UiActionId.APPLY_SETTINGS).centerY());
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS);

        SettingsPersistenceException thrown = assertThrows(
                SettingsPersistenceException.class,
                () -> loop.runFrame(1.0d / 60.0d));

        assertSame(persistenceFailure, thrown);
        assertEquals(List.of(rollbackFailure), List.of(thrown.getSuppressed()));
        assertEquals(0, host.productRenderCount());
        assertEquals(0, host.swapCount());
        assertEquals(ScreenId.SETTINGS, fixture.shell().snapshot().screen());
        assertEquals(
                Optional.of(ModalId.DIRTY_SETTINGS_CONFIRMATION),
                fixture.shell().snapshot().modal());
    }

    @Test
    void dirtyBackDiscardRestoresAppliedStateAndReturnsWithoutWriting() {
        Fixture fixture = new Fixture(SettingsDefaults.schemaV1());
        fixture.makeDirty();
        fixture.handle(new ScreenCommand.Back());

        fixture.handle(new ScreenCommand.DiscardSettings());

        assertEquals(ScreenId.MAIN_MENU, fixture.shell().snapshot().screen());
        assertEquals(Optional.empty(), fixture.shell().snapshot().modal());
        assertEquals(SettingsDefaults.schemaV1(), fixture.settings().applied());
        assertEquals(
                SettingsDefaults.schemaV1(), fixture.settings().snapshot().draft());
        assertFalse(fixture.settings().snapshot().dirty());
        assertTrue(fixture.store().saved().isEmpty());
    }

    @Test
    void gameSessionConfigCapturesOnlyApprovedNextSessionSettings() {
        SettingsSnapshot settings = new SettingsSnapshot(
                1,
                false,
                95.0,
                0.30,
                true,
                7,
                0.20,
                0.30,
                0.40,
                false,
                GameMode.CREATIVE,
                true);

        GameSessionConfig config = GameSessionConfig.from(settings);

        assertEquals(
                new GameSessionConfig(12345L, 7, GameMode.CREATIVE, true),
                config);
    }

    private static ScreenCommand.AdjustSetting adjust(
            ScreenCommand.AdjustmentTarget target,
            ScreenCommand.AdjustmentDirection direction) {
        return new ScreenCommand.AdjustSetting(target, direction);
    }

    private static ScreenCommand.ToggleSetting toggle(
            ScreenCommand.ToggleTarget target) {
        return new ScreenCommand.ToggleSetting(target);
    }

    private static SettingsSnapshot offGridSettings(double sensitivity) {
        return new SettingsSnapshot(
                1,
                true,
                70.125,
                sensitivity,
                false,
                4,
                0.333,
                0.333,
                0.333,
                true,
                GameMode.SURVIVAL,
                false);
    }

    private static final class Fixture {
        private final RecordingStore store;
        private final SettingsController settings;
        private final ProductShellController shell;

        private Fixture(SettingsSnapshot initial) {
            this(initial, noOpApplier());
        }

        private Fixture(SettingsSnapshot initial, SettingsApplier applier) {
            store = new RecordingStore(initial);
            settings = new SettingsController(initial, store, applier);
            shell = new ProductShellController(ScreenRouter.mainMenu(), settings);
            shell.handle(new ScreenCommand.OpenSettings());
        }

        private void makeDirty() {
            handle(adjust(
                    ScreenCommand.AdjustmentTarget.FOV,
                    ScreenCommand.AdjustmentDirection.INCREMENT));
        }

        private void handle(ScreenCommand command) {
            assertEquals(
                    ProductShellController.LifecycleIntent.NONE,
                    shell.handle(command));
        }

        private RecordingStore store() {
            return store;
        }

        private SettingsController settings() {
            return settings;
        }

        private ProductShellController shell() {
            return shell;
        }
    }

    private static final class RecordingStore implements SettingsStore {
        private final List<SettingsSnapshot> saved = new ArrayList<>();
        private SettingsSnapshot persisted;
        private SettingsPersistenceException nextSaveFailure;

        private RecordingStore(SettingsSnapshot initial) {
            persisted = initial;
        }

        @Override
        public SettingsLoadResult load() {
            return new SettingsLoadResult(persisted, List.of());
        }

        @Override
        public void save(SettingsSnapshot snapshot) {
            if (nextSaveFailure != null) {
                SettingsPersistenceException failure = nextSaveFailure;
                nextSaveFailure = null;
                throw failure;
            }
            saved.add(snapshot);
            persisted = snapshot;
        }

        private void failNextSave(SettingsPersistenceException failure) {
            nextSaveFailure = failure;
        }

        private List<SettingsSnapshot> saved() {
            return List.copyOf(saved);
        }
    }

    private static SettingsApplier noOpApplier() {
        return applier(ignored -> {});
    }

    private static SettingsApplier fovApplicationFailureApplier(
            RuntimeException applicationFailure) {
        return applier(fov -> {
            if (Float.compare(fov, 71.0f) == 0) {
                throw applicationFailure;
            }
        });
    }

    private static SettingsApplier fovRollbackFailureApplier(
            RuntimeException rollbackFailure) {
        return applier(fov -> {
            if (Float.compare(fov, 70.0f) == 0) {
                throw rollbackFailure;
            }
        });
    }

    private static SettingsApplier applier(Consumer<Float> fov) {
        try {
            Constructor<SettingsApplier> constructor =
                    SettingsApplier.class.getDeclaredConstructor(
                            Consumer.class,
                            Consumer.class,
                            BiConsumer.class,
                            AudioSettingsPort.class);
            if (!constructor.trySetAccessible()) {
                throw new AssertionError("SettingsApplier test seam is inaccessible");
            }
            Consumer<Boolean> vsync = ignored -> {};
            BiConsumer<Float, Boolean> look = (sensitivity, invertY) -> {};
            AudioSettingsPort audio = (master, music, sfx, mute) -> {};
            return constructor.newInstance(vsync, fov, look, audio);
        } catch (NoSuchMethodException
                | InstantiationException
                | IllegalAccessException
                | InvocationTargetException failure) {
            throw new AssertionError("Unable to create the no-op SettingsApplier", failure);
        }
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
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
            throw new AssertionError("settings apply must not render a session");
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
