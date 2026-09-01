package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.ItemCapability;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.gaia.blocks.StandaloneItemDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.interaction.feedback.CommittedGameplayFeedback;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputManagerTestDriver;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.ParentCellState;
import com.overlord.voxel.VoxelScale;
import com.overlord.worlditem.LogicalWorldItemService;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BlockInteractionControllerTest {
    private static final EntityRef OWNER = new EntityRef(42);
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");

    @Test
    void precisionItemPublishesReadOnlyRouteAndPreviewWithoutMutation() {
        Fixture fixture = fixture();
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        assertTrue(fixture.creativeSelection.select(CHISEL));

        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 1, 1, true);

        assertEquals(0, fixture.mutations.get());
        assertEquals(BlockInteractionRoute.DETAIL_PRECISION_REMOVE,
                fixture.controller.viewModel().route().route());
        assertEquals(BlockInteractionRoute.DETAIL_PRECISION_REMOVE,
                fixture.controller.viewModel().detailPreview().orElseThrow().action());
        assertEquals(STONE,
                fixture.controller.viewModel().detailPreview().orElseThrow().material());

        fixture.controller.fixedUpdate(
                new InputSnapshot(Set.of(GameConfig.Input.KEY_DETAIL_MATERIAL_CYCLE),
                        Set.of(GameConfig.Input.KEY_DETAIL_MATERIAL_CYCLE)),
                1.0 / 60.0, 2, 2, true);

        assertEquals(DIRT,
                fixture.controller.viewModel().detailPreview().orElseThrow().material());
        assertEquals(0, fixture.mutations.get());
        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 3, 3, false);
        assertTrue(fixture.controller.viewModel().detailPreview().isEmpty());
    }

    @Test
    void consumedPickupSuppressesPrecisionPlacementRouteAndPreview() {
        Fixture fixture = fixture();
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        assertTrue(fixture.creativeSelection.select(CHISEL));

        fixture.controller.fixedUpdate(
                mouseReleased(), 1.0 / 60.0, 1, 1, true);
        assertTrue(fixture.controller.viewModel().detailPreview().isPresent());

        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT),
                1.0 / 60.0,
                2,
                2,
                true,
                true);

        assertEquals(
                BlockInteractionRoute.REJECTED,
                fixture.controller.viewModel().route().route());
        assertEquals(
                "pickup_consumed",
                fixture.controller.viewModel().route().reason().orElseThrow());
        assertTrue(fixture.controller.viewModel().detailPreview().isEmpty());
        assertEquals(0, fixture.mutations.get());
    }

    @Test
    void consumedPickupPrecedesUnavailableBlockObservation() {
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        Fixture fixture = fixture(proxyTargeting(ignored ->
                SpatialQueryResult.unavailable(
                        SpatialQueryResult.Status.UNKNOWN, unavailableKey)));

        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT),
                1.0 / 60.0,
                1,
                1,
                true,
                true);

        assertEquals(
                BlockInteractionRoute.REJECTED,
                fixture.controller.viewModel().route().route());
        assertEquals(
                "pickup_consumed",
                fixture.controller.viewModel().route().reason().orElseThrow());
        assertTrue(fixture.controller.viewModel().detailPreview().isEmpty());
        assertEquals(0, fixture.mutations.get());
    }

    @Test
    void detailTargetIsVisibleButCannotTriggerLegacyParentBreakOrPlacement() {
        BlockHitResult detail = new BlockHitResult(
                1, 2, 3, 0, 2, 3, STONE,
                -1, 0, 0, 1.25f, 2.125f, 3.125f, 1,
                1.25, 2.125, 3.125, 7L,
                new DetailRaycastTarget(
                        VoxelScale.DETAIL_4,
                        new LocalSubVoxelPosition(0, 0, 0)));
        Fixture fixture = fixture(() ->
                SpatialQueryResult.available(Optional.of(detail)));
        fixture.modes.setMode(GameMode.CREATIVE, 0);

        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_LEFT),
                1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT),
                1.0 / 60.0, 3, 3, true);

        assertEquals(0, fixture.mutations.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(
                ResourceLocation.parse(
                        "gaia:interaction/detail_target_unsupported"),
                fixture.controller.viewModel().failureReason()
                        .orElseThrow().code());
        assertEquals(detail, fixture.controller.viewModel().target().orElseThrow());
    }

    @Test
    void f4CancelsAndReturnsBeforeInteractionThenHeldEdgeDoesNotRetoggle() {
        Fixture fixture = fixture();
        InputSnapshot switched = new InputSnapshot(
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of());

        fixture.controller.fixedUpdate(switched, 1.0 / 60.0, 1, 1, true);

        assertEquals(GameMode.CREATIVE, fixture.modes.mode());
        assertEquals(0, fixture.mutations.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());

        fixture.controller.fixedUpdate(switched.heldOnly(), 1.0 / 60.0, 2, 2, true);

        assertEquals(GameMode.CREATIVE, fixture.modes.mode());
        assertEquals(0, fixture.mutations.get(),
                "the held button that caused the mode switch must not re-arm Creative break");
        assertEquals(0, fixture.inventory.totalCount(OWNER, STONE));
        assertTrue(fixture.worldItems.snapshots().isEmpty());

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 3, 3, true);
        fixture.controller.fixedUpdate(mousePressed(GLFW_MOUSE_BUTTON_LEFT),
                1.0 / 60.0, 4, 4, true);

        assertEquals(1, fixture.mutations.get());
    }

    @Test
    void creativePrimaryPressBreaksOnlyFrontTargetDuringOneCatchUpBatch() {
        BlockHitResult front = hit(1);
        BlockHitResult behind = hit(2);
        AtomicInteger targetCalls = new AtomicInteger();
        Fixture fixture = fixture(() -> SpatialQueryResult.available(Optional.of(
                targetCalls.getAndIncrement() == 0 ? front : behind)));
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        InputSnapshot pressed = mousePressed(GLFW_MOUSE_BUTTON_LEFT);

        fixture.controller.fixedUpdate(pressed, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 3, 3, true);

        assertEquals(1, fixture.mutations.get(),
                "one Creative press must not break the raycast target behind it");
    }

    @Test
    void creativePrimaryHoldRequiresReleaseAndNewPressBeforeSecondBreak() {
        BlockHitResult front = hit(1);
        BlockHitResult behind = hit(2);
        AtomicInteger targetCalls = new AtomicInteger();
        Fixture fixture = fixture(() -> SpatialQueryResult.available(Optional.of(
                targetCalls.getAndIncrement() == 0 ? front : behind)));
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        InputSnapshot pressed = mousePressed(GLFW_MOUSE_BUTTON_LEFT);

        fixture.controller.fixedUpdate(pressed, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 3, 3, true);
        assertEquals(1, fixture.mutations.get(),
                "holding the same Creative press must not repeat break mutations");

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 4, 4, true);
        fixture.controller.fixedUpdate(pressed, 1.0 / 60.0, 5, 5, true);

        assertEquals(2, fixture.mutations.get(),
                "a release followed by a new press may perform exactly one new break");
    }

    @Test
    void creativeSecondaryPressPlacesOncePerPressAcrossCatchUpSteps() {
        Fixture fixture = fixture();
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        InputSnapshot pressed = mousePressed(GLFW_MOUSE_BUTTON_RIGHT);

        fixture.controller.fixedUpdate(pressed, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(pressed.heldOnly(), 1.0 / 60.0, 3, 3, true);
        assertEquals(1, fixture.mutations.get(),
                "Creative placement must not repeat while the button remains down");

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 4, 4, true);
        fixture.controller.fixedUpdate(pressed, 1.0 / 60.0, 5, 5, true);

        assertEquals(2, fixture.mutations.get(),
                "Creative placement may run once after a new press edge");
    }

    @Test
    void survivalBreakShowsProgressAndCompletesAfterSixtyFixedSteps() {
        Fixture fixture = fixture();
        InputSnapshot held = mouseHeld(GLFW_MOUSE_BUTTON_LEFT);

        for (int step = 0; step < 30; step++) {
            fixture.controller.fixedUpdate(held, 1.0 / 60.0, step, step, true);
        }

        assertEquals(0.5, fixture.controller.viewModel().progress(), 1.0e-9);
        assertEquals(5, fixture.controller.viewModel().crackStage());
        assertEquals(InteractionMode.BREAKING, fixture.controller.viewModel().mode());
        assertEquals(0, fixture.mutations.get());

        for (int step = 30; step < 60; step++) {
            fixture.controller.fixedUpdate(held, 1.0 / 60.0, step, step, true);
        }

        assertEquals(1, fixture.mutations.get());
        assertEquals(0, fixture.inventory.totalCount(OWNER, STONE));
        assertEquals(1, fixture.worldItems.snapshots().size());
        assertEquals(0.0, fixture.controller.viewModel().progress());
    }

    @Test
    void survivalPrimaryPressThenHoldCompletesWhileReleaseCancelsAnUnfinishedSession() {
        Fixture completes = fixture();
        InputSnapshot pressed = mousePressed(GLFW_MOUSE_BUTTON_LEFT);

        completes.controller.fixedUpdate(pressed, 1.0 / 60.0, 1, 1, true);
        for (int step = 2; step <= 60; step++) {
            completes.controller.fixedUpdate(
                    pressed.heldOnly(), 1.0 / 60.0, step, step, true);
        }

        assertEquals(1, completes.mutations.get());
        assertEquals(0, completes.inventory.totalCount(OWNER, STONE));
        assertEquals(1, completes.worldItems.snapshots().size());

        Fixture cancelled = fixture();
        cancelled.controller.fixedUpdate(pressed, 1.0 / 60.0, 1, 1, true);
        cancelled.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 2, 2, true);

        assertEquals(0, cancelled.mutations.get());
        assertEquals(0.0, cancelled.controller.viewModel().progress());
    }

    @Test
    void unknownTargetClearsInProgressBreakWithoutEscapingFixedStep() {
        AtomicBoolean unavailable = new AtomicBoolean();
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        BlockTargetProvider targeting = proxyTargeting((returnType) -> {
            if (!unavailable.get()) {
                return returnType == Optional.class
                        ? Optional.of(hit(1))
                        : SpatialQueryResult.available(Optional.of(hit(1)));
            }
            return SpatialQueryResult.unavailable(
                    SpatialQueryResult.Status.UNKNOWN, unavailableKey);
        });
        Fixture fixture = fixture(targeting);

        fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 1, 1, true);
        assertEquals(InteractionMode.BREAKING, fixture.controller.viewModel().mode());
        unavailable.set(true);

        assertDoesNotThrow(() -> fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 2, 2, true));
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        assertEquals(0, fixture.mutations.get());
    }

    @Test
    void failedTargetSuppressesPlacementWithoutBecomingAnAvailableMiss() {
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        BlockTargetProvider targeting = proxyTargeting(ignored ->
                SpatialQueryResult.unavailable(
                        SpatialQueryResult.Status.FAILED, unavailableKey));
        Fixture fixture = fixture(targeting);

        assertDoesNotThrow(() -> fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT), 1.0 / 60.0, 1, 1, true));

        assertEquals(0, fixture.mutations.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
    }

    @Test
    void oneThousandUnknownFixedStepsRemainNonfatalAndBounded() {
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        Fixture fixture = fixture(proxyTargeting(ignored ->
                SpatialQueryResult.unavailable(
                        SpatialQueryResult.Status.UNKNOWN, unavailableKey)));

        assertDoesNotThrow(() -> {
            for (int step = 0; step < 1_000; step++) {
                fixture.controller.fixedUpdate(
                        mouseHeld(GLFW_MOUSE_BUTTON_LEFT),
                        1.0 / 60.0,
                        step,
                        step,
                        true);
            }
        });

        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        assertEquals(0, fixture.mutations.get());
    }

    @Test
    void unknownThenAvailableResumesTargetingWithoutExplicitRetry() {
        AtomicBoolean available = new AtomicBoolean();
        ChunkKey unavailableKey = new ChunkKey(17, 2);
        BlockTargetProvider targeting = proxyTargeting(returnType -> {
            if (!available.get()) {
                return SpatialQueryResult.unavailable(
                        SpatialQueryResult.Status.UNKNOWN, unavailableKey);
            }
            return returnType == Optional.class
                    ? Optional.of(hit(1))
                    : SpatialQueryResult.available(Optional.of(hit(1)));
        });
        Fixture fixture = fixture(targeting);

        assertDoesNotThrow(() -> fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 1, 1, true));
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());

        available.set(true);
        fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 2, 2, true);

        assertEquals(InteractionMode.BREAKING, fixture.controller.viewModel().mode());
        assertTrue(fixture.controller.viewModel().progress() > 0.0);
    }

    @Test
    void committedFeedbackTriggersOnlyAfterAppliedBreakAndPlacement() {
        AtomicInteger breaks = new AtomicInteger();
        AtomicInteger placements = new AtomicInteger();
        CommittedGameplayFeedback feedback = new CommittedGameplayFeedback() {
            @Override
            public void onBreakCommitted(
                    BlockHitResult target,
                    ResourceLocation item,
                    long eventIdentity) {
                breaks.incrementAndGet();
            }

            @Override
            public void onPlacementCommitted(
                    BlockHitResult target,
                    ResourceLocation item,
                    long eventIdentity) {
                placements.incrementAndGet();
            }
        };
        Fixture fixture = fixture(
                () -> SpatialQueryResult.available(Optional.of(hit(1))), feedback);

        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT), 1.0 / 60.0, 1, 1, true);
        assertEquals(0, placements.get(), "rejected no-item placement has no feedback");

        InputSnapshot held = mouseHeld(GLFW_MOUSE_BUTTON_LEFT);
        for (int step = 0; step < 60; step++) {
            fixture.controller.fixedUpdate(held, 1.0 / 60.0, step + 2, step + 2, true);
        }
        assertEquals(1, breaks.get());

        fixture.inventory.insert(OWNER, new ItemStack(STONE, 1));
        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 70, 70, true);
        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT), 1.0 / 60.0, 71, 71, true);
        assertEquals(1, placements.get());
    }

    @Test
    void secondaryPressPlacesOnceAndCatchUpHeldOnlyCannotDuplicate() {
        Fixture fixture = fixture();
        fixture.inventory.insert(OWNER, new ItemStack(STONE, 2));
        InputSnapshot placed = mousePressed(GLFW_MOUSE_BUTTON_RIGHT);

        fixture.controller.fixedUpdate(placed, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(placed.heldOnly(), 1.0 / 60.0, 2, 2, true);

        assertEquals(1, fixture.mutations.get());
        assertEquals(1, fixture.inventory.totalCount(OWNER, STONE));

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 3, 3, true);
        fixture.controller.fixedUpdate(placed, 1.0 / 60.0, 4, 4, true);

        assertEquals(2, fixture.mutations.get());
        assertEquals(0, fixture.inventory.totalCount(OWNER, STONE));
    }

    @Test
    void cursorReleaseCancelsProgressAndClearsViewModelState() {
        Fixture fixture = fixture();
        fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 1, 1, true);
        assertTrue(fixture.controller.viewModel().progress() > 0);

        fixture.controller.fixedUpdate(
                mouseHeld(GLFW_MOUSE_BUTTON_LEFT), 1.0 / 60.0, 2, 2, false);

        assertEquals(0.0, fixture.controller.viewModel().progress());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0, fixture.mutations.get());
    }

    @Test
    void creativeToSurvivalModeSwitchRequiresPrimaryReleaseBeforeSurvivalCanBreak() {
        Fixture fixture = fixture();
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        InputSnapshot switched = new InputSnapshot(
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of());

        fixture.controller.fixedUpdate(switched, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(switched.heldOnly(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(switched.heldOnly(), 1.0 / 60.0, 3, 3, true);
        for (int tick = 4; tick <= 63; tick++) {
            fixture.controller.fixedUpdate(
                    switched.heldOnly(), 1.0 / 60.0, tick, tick, true);
        }

        assertEquals(GameMode.SURVIVAL, fixture.modes.mode());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        assertEquals(0, fixture.mutations.get());

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 64, 64, true);
        InputSnapshot rePressed = mousePressed(GLFW_MOUSE_BUTTON_LEFT);
        fixture.controller.fixedUpdate(rePressed, 1.0 / 60.0, 65, 65, true);

        assertTrue(fixture.controller.viewModel().progress() > 0.0,
                "a new press after release must start a fresh Survival break");
    }

    @Test
    void disabledInteractionRequiresPrimaryReleaseBeforeAnExistingHoldCanBreakAgain() {
        Fixture fixture = fixture();
        InputSnapshot held = mouseHeld(GLFW_MOUSE_BUTTON_LEFT);

        fixture.controller.fixedUpdate(held, 1.0 / 60.0, 1, 1, true);
        assertTrue(fixture.controller.viewModel().progress() > 0.0);

        fixture.controller.fixedUpdate(held, 1.0 / 60.0, 2, 2, false);
        fixture.controller.fixedUpdate(held, 1.0 / 60.0, 3, 3, true);

        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        assertEquals(0, fixture.mutations.get());

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 4, 4, true);
        fixture.controller.fixedUpdate(held, 1.0 / 60.0, 5, 5, true);

        assertTrue(fixture.controller.viewModel().progress() > 0.0,
                "a fresh hold after release must retain normal Survival breaking");
    }

    @Test
    void cursorCaptureTransitionCancelsImmediatelyAndMasksBothHeldButtonsUntilRelease() {
        Fixture fixture = fixture();
        InputManager input = new InputManager();
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS);

        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 1, 1, true);
        assertTrue(fixture.controller.viewModel().progress() > 0.0);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);

        AtomicBoolean cursorCaptured = new AtomicBoolean(true);
        new MouseInteractionLifecycle(fixture.controller::cancel)
                .toggleCursorCapture(true, cursorCaptured::set);
        input.resetMouseBaseline();

        assertTrue(!cursorCaptured.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 2, 2, true);
        assertEquals(0, fixture.mutations.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());

        fixture.modes.setMode(GameMode.CREATIVE, 4);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 5, 5, true);

        assertEquals(1, fixture.mutations.get(),
                "a release followed by a new right press may place exactly once");
    }

    @Test
    void focusLossCancelsImmediatelyAndMasksBothHeldButtonsUntilRelease() {
        Fixture fixture = fixture();
        InputManager input = new InputManager();
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS);

        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 1, 1, true);
        assertTrue(fixture.controller.viewModel().progress() > 0.0);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);

        InputManagerTestDriver.windowFocus(input, false);
        if (input.consumeMouseInteractionInvalidation()) {
            new MouseInteractionLifecycle(fixture.controller::cancel).onFocusLost();
        }

        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());
        InputManagerTestDriver.windowFocus(input, true);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 2, 2, true);
        assertEquals(0, fixture.mutations.get());
        assertEquals(InteractionMode.NONE, fixture.controller.viewModel().mode());
        assertEquals(0.0, fixture.controller.viewModel().progress());

        fixture.modes.setMode(GameMode.CREATIVE, 4);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        InputManagerTestDriver.mouseButton(input, GLFW_MOUSE_BUTTON_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        fixture.controller.fixedUpdate(input.consumeFixedInput(), 1.0 / 60.0, 5, 5, true);

        assertEquals(1, fixture.mutations.get());
    }

    @Test
    void creativeToSurvivalModeSwitchMasksHeldSecondaryUntilReleaseAndNewPress() {
        Fixture fixture = fixture();
        fixture.modes.setMode(GameMode.CREATIVE, 0);
        fixture.inventory.insert(OWNER, new ItemStack(STONE, 1));
        InputSnapshot switched = new InputSnapshot(
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT),
                Set.of(GLFW_MOUSE_BUTTON_RIGHT),
                List.of());

        fixture.controller.fixedUpdate(switched, 1.0 / 60.0, 1, 1, true);
        fixture.controller.fixedUpdate(switched.heldOnly(), 1.0 / 60.0, 2, 2, true);
        fixture.controller.fixedUpdate(switched.heldOnly(), 1.0 / 60.0, 3, 3, true);

        assertEquals(GameMode.SURVIVAL, fixture.modes.mode());
        assertEquals(0, fixture.mutations.get());
        assertEquals(1, fixture.inventory.totalCount(OWNER, STONE));

        fixture.controller.fixedUpdate(mouseReleased(), 1.0 / 60.0, 4, 4, true);
        fixture.controller.fixedUpdate(
                mousePressed(GLFW_MOUSE_BUTTON_RIGHT), 1.0 / 60.0, 5, 5, true);

        assertEquals(1, fixture.mutations.get());
        assertEquals(0, fixture.inventory.totalCount(OWNER, STONE));
    }

    private static InputSnapshot mouseHeld(int button) {
        return new InputSnapshot(
                Set.of(), Set.of(), Set.of(button), Set.of(), List.of());
    }

    private static InputSnapshot mousePressed(int button) {
        return new InputSnapshot(
                Set.of(), Set.of(), Set.of(button), Set.of(button), List.of());
    }

    private static InputSnapshot mouseReleased() {
        return new InputSnapshot(Set.of(), Set.of(), Set.of(), Set.of(), List.of());
    }

    private static BlockTargetProvider proxyTargeting(
            java.util.function.Function<Class<?>, Object> result) {
        return (BlockTargetProvider) Proxy.newProxyInstance(
                BlockTargetProvider.class.getClassLoader(),
                new Class<?>[] {BlockTargetProvider.class},
                (proxy, method, arguments) -> result.apply(method.getReturnType()));
    }

    private static Fixture fixture() {
        return fixture(() -> SpatialQueryResult.available(Optional.of(hit(1))));
    }

    private static Fixture fixture(BlockTargetProvider targeting) {
        return fixture(targeting, CommittedGameplayFeedback.NONE);
    }

    private static Fixture fixture(
            BlockTargetProvider targeting,
            CommittedGameplayFeedback feedback) {
        BlockRegistry blocks = blocks();
        ChunkRepository chunks = new ChunkRepository();
        chunks.generate(new ChunkKey(0, 0), ignored -> {});
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER, blocks, MainThreadGuard.captureCurrentThread(), event -> {});
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 16, 10);
        AtomicInteger mutations = new AtomicInteger();
        com.overlord.interaction.api.WorldMutationService mutationService = request -> {
            mutations.incrementAndGet();
            return new BlockChangeResult(
                    request,
                    BlockChangeResult.Status.APPLIED,
                    Optional.of(request.expectedBlock()),
                    List.of(new DirtyChunkRevision(new ChunkKey(0, 0), 2)));
        };
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(0, 0, 0));
        BlockPlacementWorldView placementWorld = new BlockPlacementWorldView() {
            @Override
            public boolean isLoaded(int x, int y, int z) {
                return true;
            }

            @Override
            public ParentCellState parentStateAt(int x, int y, int z) {
                return new FullCellState((byte) 0);
            }

            @Override
            public ResourceLocation blockAt(int x, int y, int z) {
                return AIR;
            }
        };
        GameModeManager modes = new GameModeManager(GameMode.SURVIVAL, event -> {});
        CreativeSelection creativeSelection =
                new CreativeSelection(blocks, Optional.of(STONE));
        BlockInteractionController controller = new BlockInteractionController(
                modes,
                targeting,
                chunks,
                blocks,
                inventory,
                OWNER,
                creativeSelection,
                new BlockBreakTransaction(
                        mutationService, inventory, OWNER, worldItems, AIR),
                new BlockPlacementTransaction(
                        mutationService, inventory, OWNER, blocks,
                        placementWorld, body, AIR),
                1,
                feedback);
        return new Fixture(
                controller, modes, inventory, worldItems, mutations, creativeSelection);
    }

    private static BlockHitResult hit(int x) {
        return new BlockHitResult(
                x, 2, 3, x + 1, 2, 3, STONE,
                1, 0, 0, x + 1, 2.5f, 3.5f, 2);
    }

    private static BlockRegistry blocks() {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE, 0.5f, MISSING);
        TextureRegion region = new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);
        BlockDefinition air = definition(0, AIR, material.id());
        BlockDefinition stone = definition(1, STONE, material.id());
        return BlockRegistry.create(
                List.of(air, stone),
                List.of(new StandaloneItemDefinition(
                        new ItemFormDefinition(CHISEL, 1, false, false),
                        Set.of(ItemCapability.DETAIL_PRECISION),
                        new ItemVisualReference(
                                ItemVisualType.ATLAS_REGION,
                                ResourceLocation.parse("gaia:blocks"),
                                ResourceLocation.parse("gaia:chisel")))),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(material, region),
                        1, renderInfo(material, region)));
    }

    private static BlockDefinition definition(
            int id, ResourceLocation name, ResourceLocation material) {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return new BlockDefinition(
                id, name, material, textures, 1, 1, 1,
                false, false, 1,
                id == 0 ? null : new ItemFormDefinition(name, 64, false, false));
    }

    private static BlockRenderInfo renderInfo(
            MaterialDefinition material, TextureRegion region) {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return new BlockRenderInfo(material, faces, true);
    }

    private record Fixture(
            BlockInteractionController controller,
            GameModeManager modes,
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems,
            AtomicInteger mutations,
            CreativeSelection creativeSelection) {}
}
