package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockRegistry;
import com.gaia.interaction.BlockInteractionSnapshot;
import com.gaia.interaction.GameMode;
import com.gaia.ui.GaiaHudScreen;
import com.gaia.ui.GaiaUiAssetLoader;
import com.gaia.ui.GaiaUiAssets;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudFrameCoordinator;
import com.gaia.ui.HudPresenter;
import com.gaia.ui.HudVisibility;
import com.gaia.ui.UiIconResolver;
import com.overlord.assets.AssetManager;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.TextRenderer;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UiGameLoopIntegrationTest {
    private static final RenderSurfaceMetrics SURFACE =
            new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1);

    @Test
    void realFramePolicyPreservesPendingToggleAcrossZeroStepThenConsumesItOnce() {
        Fixture fixture = fixture();
        InputSnapshot pendingPress = keyPress(GameConfig.Input.KEY_TOGGLE_HUD);
        AtomicReference<InputSnapshot> pending = new AtomicReference<>(pendingPress);
        AtomicInteger inputConsumes = new AtomicInteger();
        AtomicInteger fixedSystems = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();

        GameLoopFrameOrchestrator.FixedBatch zeroStep =
                GameLoopFrameOrchestrator.runFixedBatch(
                        0,
                        () -> {
                            inputConsumes.incrementAndGet();
                            return pending.getAndSet(null);
                        },
                        ignored -> fixedSystems.incrementAndGet());
        HudFrameCoordinator.CapturedFrame visible =
                GameLoopFrameOrchestrator.captureAndRender(
                        zeroStep,
                        fixedInput -> {
                            captures.incrementAndGet();
                            return fixture.coordinator.capture(frame(
                                    fixture,
                                    fixedInput,
                                    true,
                                    true,
                                    HudVisibility.Lifecycle.RUNNING));
                        },
                        ignored -> renders.incrementAndGet());

        assertTrue(visible.presentation().visibility().hudVisible());
        assertEquals(0, inputConsumes.get());
        assertEquals(0, fixedSystems.get());
        assertEquals(1, captures.get());
        assertEquals(1, renders.get());
        assertEquals(pendingPress, pending.get());

        GameLoopFrameOrchestrator.FixedBatch nextFixedFrame =
                GameLoopFrameOrchestrator.runFixedBatch(
                        1,
                        () -> {
                            inputConsumes.incrementAndGet();
                            return pending.getAndSet(null);
                        },
                        ignored -> fixedSystems.incrementAndGet());
        HudFrameCoordinator.CapturedFrame hidden =
                GameLoopFrameOrchestrator.captureAndRender(
                        nextFixedFrame,
                        fixedInput -> {
                            captures.incrementAndGet();
                            return fixture.coordinator.capture(frame(
                                    fixture,
                                    fixedInput,
                                    true,
                                    true,
                                    HudVisibility.Lifecycle.RUNNING));
                        },
                        ignored -> renders.incrementAndGet());

        assertFalse(hidden.presentation().visibility().hudVisible());
        assertEquals(1, inputConsumes.get());
        assertEquals(1, fixedSystems.get());
        assertEquals(2, captures.get());
        assertEquals(2, renders.get());
        assertEquals(null, pending.get());
    }

    @Test
    void threeStepCatchUpConsumesAndCapturesOnceAndPreservesGameplaySnapshot() {
        InputSnapshot gameplay = new InputSnapshot(
                Set.of(
                        GameConfig.Input.KEY_SELECT_LEFT,
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_SELECT_MOUTH,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE,
                        GameConfig.Input.KEY_TOGGLE_HUD,
                        GameConfig.Input.KEY_TOGGLE_DEBUG_HUD),
                Set.of(
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE,
                        GameConfig.Input.KEY_TOGGLE_HUD),
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                List.of(1, -2));
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();
        List<InputSnapshot> fixedInputs = new ArrayList<>();

        GameLoopFrameOrchestrator.FixedBatch batch =
                GameLoopFrameOrchestrator.runFixedBatch(
                        3,
                        () -> {
                            consumes.incrementAndGet();
                            return gameplay;
                        },
                        fixedInputs::add);
        InputSnapshot captured = GameLoopFrameOrchestrator.captureAndRender(
                batch,
                input -> {
                    captures.incrementAndGet();
                    return input.orElseThrow();
                },
                ignored -> renders.incrementAndGet());

        assertEquals(1, consumes.get());
        assertEquals(List.of(gameplay, gameplay.heldOnly(), gameplay.heldOnly()), fixedInputs);
        assertEquals(1, captures.get());
        assertEquals(1, renders.get());
        assertEquals(gameplay, captured);
        assertEquals(
                Set.of(
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE,
                        GameConfig.Input.KEY_TOGGLE_HUD),
                gameplay.pressedKeys());
        assertEquals(
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                gameplay.pressedMouseButtons());
        assertEquals(List.of(1, -2), gameplay.scrollDeltas());
    }

    @Test
    void uiRenderFailurePropagatesOnceWithoutRetryOrPostRenderGameplayWork() {
        List<String> trace = new ArrayList<>();
        RuntimeException renderFailure = new RuntimeException("ui render failed");
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger dirtyNotifications = new AtomicInteger();
        GameLoopFrameOrchestrator.FixedBatch batch =
                GameLoopFrameOrchestrator.runFixedBatch(
                        1,
                        () -> {
                            trace.add("consume");
                            return keyPress(GameConfig.Input.KEY_TOGGLE_HUD);
                        },
                        ignored -> {
                            trace.add("reserve");
                            reservations.incrementAndGet();
                            trace.add("mutation");
                            mutations.incrementAndGet();
                            trace.add("commit");
                            commits.incrementAndGet();
                            trace.add("dirty");
                            dirtyNotifications.incrementAndGet();
                        });

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> GameLoopFrameOrchestrator.captureAndRender(
                        batch,
                        input -> {
                            trace.add("capture");
                            return input.orElseThrow();
                        },
                        ignored -> {
                            trace.add("render");
                            throw renderFailure;
                        }));

        assertEquals(renderFailure, thrown);
        assertEquals(
                List.of(
                        "consume", "reserve", "mutation", "commit", "dirty",
                        "capture", "render"),
                trace);
        assertEquals(1, reservations.get());
        assertEquals(1, mutations.get());
        assertEquals(1, commits.get());
        assertEquals(0, rollbacks.get());
        assertEquals(1, dirtyNotifications.get());
    }

    @Test
    void fixedEdgeTogglesOnceWhileHeldAndZeroStepFrameDoesNotConsumePendingEdge() {
        Fixture fixture = fixture();

        var hidden = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(keyPress(GameConfig.Input.KEY_TOGGLE_HUD)),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertFalse(hidden.presentation().visibility().hudVisible());

        var held = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(new InputSnapshot(
                        Set.of(GameConfig.Input.KEY_TOGGLE_HUD), Set.of())),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertFalse(held.presentation().visibility().hudVisible());

        var zeroStep = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertFalse(zeroStep.presentation().visibility().hudVisible());

        var repressed = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(keyPress(GameConfig.Input.KEY_TOGGLE_HUD)),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertTrue(repressed.presentation().visibility().hudVisible());
    }

    @Test
    void zeroStepLifecycleCaptureHidesImmediatelyAndUsesAuthoritativeFeetCopy() {
        Fixture fixture = fixture();
        HudDebugSnapshot.FeetPosition feet = new HudDebugSnapshot.FeetPosition(4.25, 7.5, -2.75);

        var visible = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING,
                feet));
        assertTrue(visible.presentation().visibility().hudVisible());
        assertEquals(feet, visible.presentation().debug().feet());

        var f1Released = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                true,
                false,
                HudVisibility.Lifecycle.RUNNING,
                feet));
        assertFalse(f1Released.presentation().visibility().hudVisible());
        assertTrue(f1Released.frame().commands().isEmpty());

        var focusLost = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                false,
                true,
                HudVisibility.Lifecycle.RUNNING,
                feet));
        assertFalse(focusLost.presentation().visibility().hudVisible());
    }

    @Test
    void debugToggleIsIndependentAndOnlyAConsumedFixedEdgeCanToggleIt() {
        Fixture fixture = fixture();

        var zeroStep = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertFalse(zeroStep.presentation().visibility().debugVisible());

        var toggled = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(keyPress(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD)),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertTrue(toggled.presentation().visibility().debugVisible());

        var laterHeld = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(new InputSnapshot(
                        Set.of(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD), Set.of())),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING));
        assertTrue(laterHeld.presentation().visibility().debugVisible());
    }

    @Test
    void zeroWidthCapturesHudToggleButDefersCompositionUntilDrawableRecovery() {
        Fixture fixture = fixture();
        RenderSurfaceMetrics zeroWidth =
                new RenderSurfaceMetrics(640, 360, 0, 360, 1, 1);

        var hidden = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(new InputSnapshot(
                        Set.of(
                                GameConfig.Input.KEY_TOGGLE_HUD,
                                GameConfig.Input.KEY_TOGGLE_DEBUG_HUD),
                        Set.of(
                                GameConfig.Input.KEY_TOGGLE_HUD,
                                GameConfig.Input.KEY_TOGGLE_DEBUG_HUD))),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING,
                zeroWidth));

        assertFalse(hidden.presentation().visibility().hudVisible());
        assertTrue(hidden.presentation().visibility().debugVisible());
        assertEquals(com.overlord.renderer.ui.UiFrame.empty(), hidden.frame());

        var recovered = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(keyPress(GameConfig.Input.KEY_TOGGLE_HUD)),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING,
                SURFACE));

        assertTrue(recovered.presentation().visibility().hudVisible());
        assertTrue(recovered.presentation().visibility().debugVisible());
        assertFalse(recovered.frame().commands().isEmpty());
    }

    @Test
    void zeroHeightCapturesDebugToggleButDefersCompositionUntilDrawableRecovery() {
        Fixture fixture = fixture();
        RenderSurfaceMetrics zeroHeight =
                new RenderSurfaceMetrics(640, 360, 640, 0, 1, 1);

        var hiddenSurface = fixture.coordinator.capture(frame(
                fixture,
                Optional.of(keyPress(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD)),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING,
                zeroHeight));

        assertTrue(hiddenSurface.presentation().visibility().debugVisible());
        assertEquals(com.overlord.renderer.ui.UiFrame.empty(), hiddenSurface.frame());

        var recovered = fixture.coordinator.capture(frame(
                fixture,
                Optional.empty(),
                true,
                true,
                HudVisibility.Lifecycle.RUNNING,
                SURFACE));

        assertTrue(recovered.presentation().visibility().debugVisible());
        assertFalse(recovered.frame().commands().isEmpty());
    }

    @Test
    void captureUsesSameFrameActiveSlotAndModeWithoutRewritingGameplayInput() {
        Fixture fixture = fixture();
        fixture.inventory.activeSlot = BodySlot.RIGHT_HAND;
        InputSnapshot gameplay = new InputSnapshot(
                Set.of(
                        GameConfig.Input.KEY_SELECT_LEFT,
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_SELECT_MOUTH,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                List.of(1));
        BlockInteractionSnapshot creative = new BlockInteractionSnapshot(
                Optional.empty(), Optional.empty(), 0, InteractionMode.NONE,
                Optional.empty(), Optional.empty(), 0, GameMode.CREATIVE);

        var captured = fixture.coordinator.capture(new HudFrameCoordinator.FrameCapture(
                fixture.inventory,
                creative,
                Optional.empty(),
                new HudDebugSnapshot.FeetPosition(1, 2, 3),
                new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0),
                Optional.of(gameplay),
                1.0 / 60.0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                true,
                false,
                SURFACE));

        assertEquals(BodySlot.RIGHT_HAND, captured.presentation().activeSlot());
        assertEquals(GameMode.CREATIVE, captured.presentation().mode());
        assertEquals(Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE), gameplay.pressedKeys());
        assertEquals(
                Set.of(GameConfig.Input.MOUSE_PRIMARY, GameConfig.Input.MOUSE_SECONDARY),
                gameplay.pressedMouseButtons());
        assertEquals(List.of(1), gameplay.scrollDeltas());
    }

    private static HudFrameCoordinator.FrameCapture frame(
            Fixture fixture,
            Optional<InputSnapshot> fixedInput,
            boolean focused,
            boolean captured,
            HudVisibility.Lifecycle lifecycle) {
        return frame(
                fixture,
                fixedInput,
                focused,
                captured,
                lifecycle,
                new HudDebugSnapshot.FeetPosition(0, 0, 0));
    }

    private static HudFrameCoordinator.FrameCapture frame(
            Fixture fixture,
            Optional<InputSnapshot> fixedInput,
            boolean focused,
            boolean captured,
            HudVisibility.Lifecycle lifecycle,
            HudDebugSnapshot.FeetPosition feet) {
        return frame(fixture, fixedInput, focused, captured, lifecycle, feet, SURFACE);
    }

    private static HudFrameCoordinator.FrameCapture frame(
            Fixture fixture,
            Optional<InputSnapshot> fixedInput,
            boolean focused,
            boolean captured,
            HudVisibility.Lifecycle lifecycle,
            RenderSurfaceMetrics surface) {
        return frame(
                fixture,
                fixedInput,
                focused,
                captured,
                lifecycle,
                new HudDebugSnapshot.FeetPosition(0, 0, 0),
                surface);
    }

    private static HudFrameCoordinator.FrameCapture frame(
            Fixture fixture,
            Optional<InputSnapshot> fixedInput,
            boolean focused,
            boolean captured,
            HudVisibility.Lifecycle lifecycle,
            HudDebugSnapshot.FeetPosition feet,
            RenderSurfaceMetrics surface) {
        return new HudFrameCoordinator.FrameCapture(
                fixture.inventory,
                interaction(),
                Optional.empty(),
                feet,
                new HudDebugSnapshot.Counts(1, 1, 0, 0, 0, 0),
                fixedInput,
                1.0 / 60.0,
                lifecycle,
                focused,
                captured,
                false,
                surface);
    }

    private static InputSnapshot keyPress(int key) {
        return new InputSnapshot(Set.of(key), Set.of(key));
    }

    private static BlockInteractionSnapshot interaction() {
        return new BlockInteractionSnapshot(
                Optional.empty(),
                Optional.empty(),
                0,
                InteractionMode.NONE,
                Optional.empty(),
                Optional.empty(),
                0,
                GameMode.SURVIVAL);
    }

    private static Fixture fixture() {
        AssetManager assets = new AssetManager(UiGameLoopIntegrationTest.class.getClassLoader());
        BlockRegistry blocks = new GaiaResourceLoader(assets).load().blockRegistry();
        GaiaUiAssets uiAssets = new GaiaUiAssetLoader(assets).load();
        HudPresenter presenter = new HudPresenter(blocks::itemForm);
        GaiaHudScreen screen = new GaiaHudScreen(
                new UiIconResolver(uiAssets.icons()),
                new TextRenderer(uiAssets.renderAssets().glyphs()));
        EntityRef owner = new EntityRef(99);
        InventoryView inventory = new InventoryView() {
            @Override public EntityRef owner() { return owner; }
            @Override public long revision() { return 0; }
            @Override public Optional<com.overlord.inventory.api.ItemStackView> stack(BodySlot slot) {
                return Optional.empty();
            }
        };
        MutableBodyView view = new MutableBodyView(owner, inventory);
        return new Fixture(new HudFrameCoordinator(presenter, screen), view);
    }

    private static final class MutableBodyView implements BodyInventoryViewModel {
        private final EntityRef owner;
        private final InventoryView inventory;
        private BodySlot activeSlot = BodySlot.LEFT_HAND;

        private MutableBodyView(EntityRef owner, InventoryView inventory) {
            this.owner = owner;
            this.inventory = inventory;
        }

        @Override public EntityRef owner() { return owner; }
        @Override public BodySlot activeSlot() { return activeSlot; }
        @Override public InventoryView inventory() { return inventory; }
    }

    private record Fixture(HudFrameCoordinator coordinator, MutableBodyView inventory) {}
}
