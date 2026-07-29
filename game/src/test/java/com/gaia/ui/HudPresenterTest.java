package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class HudPresenterTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation SPEAR = ResourceLocation.parse("gaia:spear");
    private static final ResourceLocation FAILURE = ResourceLocation.parse("gaia:no_target");
    private static final RenderMetricsSnapshot METRICS =
            new RenderMetricsSnapshot(60, 16.5, 11, 12, 13, 14);

    @Test
    void f2AndF3ToggleOnlyOnTheFirstPressedEdgeOfOneInputSample() {
        Fixture fixture = fixture(Map.of());
        HudPresentationSnapshot initial = fixture.capture(emptyInput(), 0, false, 0);
        assertTrue(initial.visibility().hudVisible());
        assertFalse(initial.visibility().debugVisible());

        InputSnapshot pressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3),
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3));
        HudPresentationSnapshot toggled = fixture.capture(pressed, 1, true, 0);
        HudPresentationSnapshot repeatedCatchUp = fixture.capture(pressed, 1, true, 0);
        HudPresentationSnapshot held = fixture.capture(
                new InputSnapshot(Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3), Set.of()),
                2,
                true,
                0);

        assertFalse(toggled.visibility().hudVisible());
        assertTrue(toggled.visibility().debugVisible());
        assertFalse(repeatedCatchUp.visibility().hudVisible());
        assertTrue(repeatedCatchUp.visibility().debugVisible());
        assertFalse(held.visibility().hudVisible());
        assertTrue(held.visibility().debugVisible());

        HudPresentationSnapshot secondPress = fixture.capture(pressed, 3, true, 0);
        assertTrue(secondPress.visibility().hudVisible());
        assertFalse(secondPress.visibility().debugVisible());
    }

    @Test
    void newNonFirstSampleIsConsumedWithoutADeferredToggle() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 0, false, 0);
        InputSnapshot pressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3),
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3));

        HudPresentationSnapshot nonFirst = fixture.capture(pressed, 1, false, 0);
        HudPresentationSnapshot replayedAsFirst = fixture.capture(pressed, 1, true, 0);

        assertTrue(nonFirst.visibility().hudVisible());
        assertFalse(nonFirst.visibility().debugVisible());
        assertTrue(replayedAsFirst.visibility().hudVisible());
        assertFalse(replayedAsFirst.visibility().debugVisible());
    }

    @Test
    void oldPressedSampleCannotReplayAfterANewerSample() {
        Fixture fixture = fixture(Map.of());
        InputSnapshot pressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2), Set.of(GLFW.GLFW_KEY_F2));
        assertFalse(fixture.capture(pressed, 5, true, 0).visibility().hudVisible());
        fixture.capture(emptyInput(), 6, true, 0);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.capture(pressed, 5, true, 0));

        assertTrue(failure.getMessage().contains("monotonically non-decreasing"));
    }

    @Test
    void decreasingSampleIdIsRejectedEvenWhenTheSampleIsNotAFirstStep() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 10, false, 0);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.capture(emptyInput(), 9, false, 0));

        assertTrue(failure.getMessage().contains("monotonically non-decreasing"));
    }

    @Test
    void loadingAndShutdownConsumeSamplesWithoutTogglingOrResettingTheSequence() {
        Fixture fixture = fixture(Map.of());
        InputSnapshot pressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3),
                Set.of(GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3));

        fixture.capture(
                pressed,
                20,
                true,
                0,
                HudVisibility.Lifecycle.LOADING,
                true,
                true,
                false);
        HudPresentationSnapshot afterLoading = fixture.capture(emptyInput(), 21, true, 0);
        assertTrue(afterLoading.visibility().hudVisible());
        assertFalse(afterLoading.visibility().debugVisible());

        fixture.capture(
                pressed,
                22,
                true,
                0,
                HudVisibility.Lifecycle.SHUTDOWN,
                true,
                true,
                false);
        HudPresentationSnapshot afterShutdown = fixture.capture(emptyInput(), 23, true, 0);
        assertTrue(afterShutdown.visibility().hudVisible());
        assertFalse(afterShutdown.visibility().debugVisible());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.capture(emptyInput(), 19, false, 0));
    }

    @Test
    void debugStaysIndependentOfHudToggleButHidesAtUnsafeRunningBoundaries() {
        Fixture fixture = fixture(Map.of());
        InputSnapshot debugPressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F3), Set.of(GLFW.GLFW_KEY_F3));
        InputSnapshot hudPressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2), Set.of(GLFW.GLFW_KEY_F2));

        assertTrue(fixture.capture(debugPressed, 0, true, 0).visibility().debugVisible());
        HudPresentationSnapshot hudDisabled = fixture.capture(hudPressed, 1, true, 0);
        assertEquals(HudVisibility.Reason.HUD_DISABLED, hudDisabled.visibility().reason());
        assertTrue(hudDisabled.visibility().debugVisible());

        HudPresentationSnapshot unfocused = fixture.capture(
                emptyInput(),
                2,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                false,
                true,
                false);
        assertFalse(unfocused.visibility().debugVisible());

        HudPresentationSnapshot cursorReleased = fixture.capture(
                emptyInput(),
                3,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                false,
                false);
        assertFalse(cursorReleased.visibility().debugVisible());

        HudPresentationSnapshot blocked = fixture.capture(
                emptyInput(),
                4,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                true,
                true);
        assertFalse(blocked.visibility().debugVisible());

        HudPresentationSnapshot safeAgain = fixture.capture(emptyInput(), 5, false, 0);
        assertEquals(HudVisibility.Reason.HUD_DISABLED, safeAgain.visibility().reason());
        assertTrue(safeAgain.visibility().debugVisible());
    }

    @Test
    void f2OnlyHidesGameplayPresentationWhileDebugAndReenabledHudKeepCurrentInteractionTruth() {
        Fixture fixture = fixture(Map.of());
        fixture.interaction.activateTarget();
        InputSnapshot debugPressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F3), Set.of(GLFW.GLFW_KEY_F3));
        InputSnapshot hudPressed = new InputSnapshot(
                Set.of(GLFW.GLFW_KEY_F2), Set.of(GLFW.GLFW_KEY_F2));

        HudPresentationSnapshot debugVisible = fixture.capture(debugPressed, 0, true, 0);
        HudPresentationSnapshot hudDisabled = fixture.capture(hudPressed, 1, true, 0);
        HudPresentationSnapshot hudReenabled = fixture.capture(hudPressed, 2, true, 0);

        assertTrue(debugVisible.visibility().debugVisible());
        assertEquals(HudVisibility.Reason.HUD_DISABLED, hudDisabled.visibility().reason());
        assertTrue(hudDisabled.visibility().debugVisible());
        assertFalse(hudDisabled.visibility().interactionEligible());
        assertTrue(hudDisabled.interaction().target().isPresent());
        assertEquals(0.5, hudDisabled.interaction().progress());
        assertTrue(hudReenabled.visibility().hudVisible());
        assertTrue(hudReenabled.interaction().target().isPresent());
        assertEquals(0.5, hudReenabled.interaction().progress());
    }

    @Test
    void leavesGameplayKeysWheelAndMouseSamplesUntouched() {
        Fixture fixture = fixture(Map.of());
        HudPresentationSnapshot before = fixture.capture(emptyInput(), 0, false, 0);
        InputSnapshot input = new InputSnapshot(
                Set.of(
                        GameConfig.Input.KEY_SELECT_LEFT,
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_SELECT_MOUTH,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_DROP, GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.MOUSE_PRIMARY),
                Set.of(GameConfig.Input.MOUSE_PRIMARY),
                List.of(1, -1, 2));

        HudPresentationSnapshot after = fixture.capture(input, 1, true, 0);

        assertEquals(before, after);
        assertEquals(Set.of(
                        GameConfig.Input.KEY_SELECT_LEFT,
                        GameConfig.Input.KEY_SELECT_RIGHT,
                        GameConfig.Input.KEY_SELECT_MOUTH,
                        GameConfig.Input.KEY_DROP,
                        GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                input.downKeys());
        assertEquals(Set.of(GameConfig.Input.KEY_DROP, GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                input.pressedKeys());
        assertEquals(List.of(1, -1, 2), input.scrollDeltas());
        assertEquals(Set.of(GameConfig.Input.MOUSE_PRIMARY), input.downMouseButtons());
        assertEquals(Set.of(GameConfig.Input.MOUSE_PRIMARY), input.pressedMouseButtons());
    }

    @Test
    void eligibleInteractionCopiesEveryFieldAndDefendsTheCompletedSnapshot() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 0, false, 0);
        BlockHitResult target = new BlockHitResult(
                4, 5, 6, 5, 5, 6, DIRT, 1, 0, 0, 4.5f, 5.5f, 6.5f, 3);
        MutableStack activeItem = new MutableStack(STONE, 4);
        fixture.interaction.activateTarget(target);
        fixture.interaction.activeItem = Optional.of(activeItem);

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 1, false, 0);
        HudPresentationSnapshot.InteractionPresentation interaction = snapshot.interaction();

        assertEquals(Optional.of(target), interaction.target());
        assertEquals(Optional.of(BlockFace.EAST), interaction.hitFace());
        assertEquals(0.5, interaction.progress());
        assertEquals(5, interaction.crackStage());
        assertEquals(InteractionMode.BREAKING, interaction.mode());
        assertEquals(Optional.of(new ItemStack(STONE, 4)), interaction.activeItem());
        assertEquals(
                Optional.of(new InteractionFailureReason(FAILURE)),
                interaction.failureReason());

        fixture.interaction.clearTransient();
        fixture.interaction.activeItem = Optional.empty();
        activeItem.itemId = DIRT;
        activeItem.count = 9;

        assertEquals(Optional.of(target), snapshot.interaction().target());
        assertEquals(Optional.of(BlockFace.EAST), snapshot.interaction().hitFace());
        assertEquals(0.5, snapshot.interaction().progress());
        assertEquals(5, snapshot.interaction().crackStage());
        assertEquals(InteractionMode.BREAKING, snapshot.interaction().mode());
        assertEquals(Optional.of(new ItemStack(STONE, 4)), snapshot.interaction().activeItem());
        assertEquals(
                Optional.of(new InteractionFailureReason(FAILURE)),
                snapshot.interaction().failureReason());
    }

    @Test
    void capturesSameRenderFrameSlotAndCreativeModeWithoutReplacingSurvivalSlots() {
        Fixture fixture = fixture(Map.of());
        fixture.inventory.put(BodySlot.LEFT_HAND, new ItemStack(DIRT, 5));
        fixture.inventory.put(BodySlot.RIGHT_HAND, new ItemStack(SPEAR, 1));
        fixture.interaction.activeItem = Optional.of(new ItemStack(DIRT, 5));
        fixture.capture(emptyInput(), 0, false, 0);

        fixture.inventory.activeSlot = BodySlot.RIGHT_HAND;
        fixture.interaction.gameMode = GameMode.CREATIVE;
        fixture.interaction.activeItem = Optional.of(new ItemStack(STONE, 64));
        HudPresentationSnapshot creative = fixture.capture(emptyInput(), 1, false, 0);

        assertEquals(BodySlot.RIGHT_HAND, creative.activeSlot());
        assertEquals(GameMode.CREATIVE, creative.mode());
        assertEquals(DIRT, creative.slot(BodySlot.LEFT_HAND).stack().orElseThrow().itemId());
        assertEquals(STONE, creative.creative().orElseThrow().itemId());
        assertTrue(creative.creative().orElseThrow().infinite());
        assertEquals(BodySlot.LEFT_HAND, creative.slotTransition().orElseThrow().from());
        assertEquals(BodySlot.RIGHT_HAND, creative.slotTransition().orElseThrow().to());
        assertEquals(0, creative.slotTransition().orElseThrow().normalizedProgress());
        assertEquals(GameMode.CREATIVE, creative.modeNotice().orElseThrow().mode());

        fixture.interaction.gameMode = GameMode.SURVIVAL;
        fixture.interaction.activeItem = Optional.of(new ItemStack(SPEAR, 1));
        HudPresentationSnapshot restored = fixture.capture(emptyInput(), 2, false, 0);
        assertTrue(restored.creative().isEmpty());
        assertEquals(BodySlot.RIGHT_HAND, restored.activeSlot());
        assertEquals(SPEAR, restored.slot(BodySlot.RIGHT_HAND).stack().orElseThrow().itemId());
    }

    @Test
    void activeSlotTransitionEndsAtExactlyOneHundredFiftyMilliseconds() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 0, false, 0);
        fixture.inventory.activeSlot = BodySlot.MOUTH;
        fixture.capture(emptyInput(), 1, false, 0);

        HudPresentationSnapshot beforeBoundary = fixture.capture(emptyInput(), 2, false, 0.149);
        assertEquals(0.149 / 0.150,
                beforeBoundary.slotTransition().orElseThrow().normalizedProgress(), 1.0e-12);

        HudPresentationSnapshot atBoundary = fixture.capture(emptyInput(), 3, false, 0.001);
        assertTrue(atBoundary.slotTransition().isEmpty());
    }

    @Test
    void itemNameAndModeNoticeExposeDeterministicRemainingTimeAndFinalFade() {
        Fixture fixture = fixture(Map.of());
        fixture.inventory.put(BodySlot.LEFT_HAND, new ItemStack(DIRT, 1));
        fixture.interaction.activeItem = Optional.of(new ItemStack(DIRT, 1));
        HudPresentationSnapshot initial = fixture.capture(emptyInput(), 0, false, 0);
        assertEquals(1.5, initial.itemName().orElseThrow().remainingSeconds());
        assertTrue(initial.modeNotice().isEmpty());

        fixture.interaction.gameMode = GameMode.CREATIVE;
        fixture.interaction.activeItem = Optional.of(new ItemStack(STONE, 1));
        HudPresentationSnapshot changed = fixture.capture(emptyInput(), 1, false, 0);
        assertEquals(1.5, changed.itemName().orElseThrow().remainingSeconds());
        assertEquals(1.25, changed.modeNotice().orElseThrow().remainingSeconds());

        HudPresentationSnapshot noticeFadeStart = fixture.capture(emptyInput(), 2, false, 1.0);
        assertEquals(0.25, noticeFadeStart.modeNotice().orElseThrow().remainingSeconds());
        assertEquals(1.0, noticeFadeStart.modeNotice().orElseThrow().opacity());

        HudPresentationSnapshot noticeHalfFade = fixture.capture(emptyInput(), 3, false, 0.125);
        assertEquals(0.125, noticeHalfFade.modeNotice().orElseThrow().remainingSeconds());
        assertEquals(0.5, noticeHalfFade.modeNotice().orElseThrow().opacity());

        HudPresentationSnapshot noticeEnded = fixture.capture(emptyInput(), 4, false, 0.125);
        assertTrue(noticeEnded.modeNotice().isEmpty());
        assertEquals(0.25, noticeEnded.itemName().orElseThrow().remainingSeconds());
        assertEquals(1.0, noticeEnded.itemName().orElseThrow().opacity());

        HudPresentationSnapshot itemHalfFade = fixture.capture(emptyInput(), 5, false, 0.125);
        assertEquals(0.125, itemHalfFade.itemName().orElseThrow().remainingSeconds());
        assertEquals(0.5, itemHalfFade.itemName().orElseThrow().opacity());
        assertTrue(fixture.capture(emptyInput(), 6, false, 0.125).itemName().isEmpty());
    }

    @Test
    void zeroStepLifecycleBoundariesClearInteractionAndDoNotRestoreStaleState() {
        assertLifecycleClears(
                HudVisibility.Lifecycle.RUNNING, true, false, false,
                HudVisibility.Reason.CURSOR_RELEASED);
        assertLifecycleClears(
                HudVisibility.Lifecycle.RUNNING, false, true, false,
                HudVisibility.Reason.FOCUS_LOST);
        assertLifecycleClears(
                HudVisibility.Lifecycle.LOADING, true, true, false,
                HudVisibility.Reason.LOADING);
        assertLifecycleClears(
                HudVisibility.Lifecycle.SHUTDOWN, true, true, false,
                HudVisibility.Reason.SHUTDOWN);
        assertLifecycleClears(
                HudVisibility.Lifecycle.RUNNING, true, true, true,
                HudVisibility.Reason.BLOCKING_UI);
    }

    @Test
    void hiddenNeutralRearmsSuppressionSoFreshInteractionAppearsOnRegain() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 0, false, 0);
        fixture.interaction.activateTarget();
        fixture.capture(emptyInput(), 1, false, 0);

        fixture.capture(
                emptyInput(),
                2,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                false,
                false);
        fixture.interaction.clearTransient();
        fixture.capture(
                emptyInput(),
                3,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                false,
                false);

        BlockHitResult freshTarget = new BlockHitResult(
                7, 8, 9, 8, 8, 9, STONE, 1, 0, 0, 7.5f, 8.5f, 9.5f, 2);
        fixture.interaction.activateTarget(freshTarget);
        HudPresentationSnapshot regained = fixture.capture(emptyInput(), 4, false, 0);

        assertEquals(Optional.of(freshTarget), regained.interaction().target());
        assertEquals(Optional.of(BlockFace.EAST), regained.interaction().hitFace());
    }

    @Test
    void unchangedInteractionRemainsSuppressedWhenRegainedWithoutHiddenNeutral() {
        Fixture fixture = fixture(Map.of());
        fixture.capture(emptyInput(), 0, false, 0);
        fixture.interaction.activateTarget();
        fixture.capture(emptyInput(), 1, false, 0);

        fixture.capture(
                emptyInput(),
                2,
                false,
                0,
                HudVisibility.Lifecycle.RUNNING,
                true,
                false,
                false);
        HudPresentationSnapshot regained = fixture.capture(emptyInput(), 3, false, 0);

        assertEquals(HudPresentationSnapshot.InteractionPresentation.cleared(),
                regained.interaction());
    }

    @Test
    void derivesCanonicalTwoHandedTruthOnceAndPreservesTheAnchor() {
        Map<ResourceLocation, ItemFormDefinition> forms = new HashMap<>();
        forms.put(SPEAR, new ItemFormDefinition(SPEAR, 1, false, true));
        Fixture fixture = fixture(forms);
        forms.clear();
        fixture.inventory.put(BodySlot.LEFT_HAND, new ItemStack(SPEAR, 1));
        fixture.inventory.put(BodySlot.RIGHT_HAND, new ItemStack(SPEAR, 1));

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 0, false, 0);

        assertTrue(snapshot.twoHanded());
        assertEquals(BodySlot.LEFT_HAND, snapshot.twoHandedAnchor().orElseThrow());
        assertEquals(SPEAR, snapshot.slot(BodySlot.LEFT_HAND).stack().orElseThrow().itemId());
        assertTrue(snapshot.slot(BodySlot.RIGHT_HAND).stack().isEmpty());
        assertTrue(snapshot.slot(BodySlot.RIGHT_HAND).lockedCompanion());
        assertEquals(BodySlot.LEFT_HAND,
                snapshot.slot(BodySlot.RIGHT_HAND).sharedAnchor().orElseThrow());
        assertEquals(1, snapshot.slots().values().stream()
                .filter(slot -> slot.stack().isPresent())
                .count());
        assertEquals(BodySlot.LEFT_HAND, snapshot.activeSlot());
    }

    @Test
    void twoHandedRightActiveUsesRightAsTheSolePresentationAnchor() {
        Fixture fixture = twoHandedFixture(BodySlot.RIGHT_HAND);

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 0, false, 0);

        assertEquals(BodySlot.RIGHT_HAND, snapshot.twoHandedAnchor().orElseThrow());
        assertEquals(SPEAR, snapshot.slot(BodySlot.RIGHT_HAND).stack().orElseThrow().itemId());
        assertTrue(snapshot.slot(BodySlot.RIGHT_HAND).active());
        assertFalse(snapshot.slot(BodySlot.RIGHT_HAND).lockedCompanion());
        assertTrue(snapshot.slot(BodySlot.LEFT_HAND).stack().isEmpty());
        assertFalse(snapshot.slot(BodySlot.LEFT_HAND).active());
        assertTrue(snapshot.slot(BodySlot.LEFT_HAND).lockedCompanion());
        assertEquals(
                BodySlot.RIGHT_HAND,
                snapshot.slot(BodySlot.LEFT_HAND).sharedAnchor().orElseThrow());
        assertEquals(1, snapshot.slots().values().stream()
                .filter(slot -> slot.stack().isPresent())
                .count());
    }

    @Test
    void twoHandedMouthActiveKeepsLeftStorageAnchorWithoutActivatingEitherHand() {
        Fixture fixture = twoHandedFixture(BodySlot.MOUTH);

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 0, false, 0);

        assertEquals(BodySlot.LEFT_HAND, snapshot.twoHandedAnchor().orElseThrow());
        assertEquals(SPEAR, snapshot.slot(BodySlot.LEFT_HAND).stack().orElseThrow().itemId());
        assertFalse(snapshot.slot(BodySlot.LEFT_HAND).active());
        assertFalse(snapshot.slot(BodySlot.RIGHT_HAND).active());
        assertTrue(snapshot.slot(BodySlot.RIGHT_HAND).lockedCompanion());
        assertTrue(snapshot.slot(BodySlot.MOUTH).active());
    }

    @Test
    void rejectsEveryTwoHandedFalsePositive() {
        assertFalse(twoHanded(
                new ItemFormDefinition(SPEAR, 64, false, false),
                new ItemStack(SPEAR, 1),
                new ItemStack(SPEAR, 1)));
        assertFalse(twoHanded(
                new ItemFormDefinition(SPEAR, 1, false, true),
                new ItemStack(SPEAR, 1),
                new ItemStack(SPEAR, 2)));
        assertFalse(twoHanded(
                new ItemFormDefinition(SPEAR, 1, false, true),
                new ItemStack(SPEAR, 1),
                new ItemStack(STONE, 1)));
        assertFalse(twoHanded(
                null,
                new ItemStack(SPEAR, 1),
                new ItemStack(SPEAR, 1)));
    }

    @Test
    void copiesMutableViewValuesAndCarriesPreviousMetricsFeetAndDebugCounts() {
        Fixture fixture = fixture(Map.of());
        MutableStack mutable = new MutableStack(DIRT, 4);
        fixture.inventory.put(BodySlot.LEFT_HAND, mutable);
        fixture.interaction.activeItem = Optional.of(mutable);
        fixture.metrics = Optional.of(METRICS);
        fixture.feet = new HudDebugSnapshot.FeetPosition(1.25, 2.5, -3.75);
        fixture.counts = new HudDebugSnapshot.Counts(10, 20, 30, 1, 2, 3);

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 0, false, 0);
        mutable.itemId = STONE;
        mutable.count = 9;
        fixture.inventory.clear(BodySlot.LEFT_HAND);

        ItemStack slot = snapshot.slot(BodySlot.LEFT_HAND).stack().orElseThrow();
        assertEquals(DIRT, slot.itemId());
        assertEquals(4, slot.count());
        assertSame(METRICS, snapshot.debug().previousFrameMetrics().orElseThrow());
        assertEquals(new HudDebugSnapshot.FeetPosition(1.25, 2.5, -3.75), snapshot.debug().feet());
        assertEquals(new HudDebugSnapshot.Counts(10, 20, 30, 1, 2, 3), snapshot.debug().counts());
    }

    @Test
    void representsCreativeSelectionAndMetricsAbsenceWithoutFabricatedValues() {
        Fixture fixture = fixture(Map.of());
        fixture.interaction.gameMode = GameMode.CREATIVE;
        fixture.interaction.activeItem = Optional.empty();

        HudPresentationSnapshot snapshot = fixture.capture(emptyInput(), 0, false, 0);

        assertTrue(snapshot.creative().isEmpty());
        assertTrue(snapshot.debug().previousFrameMetrics().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.capture(emptyInput(), 1, false, -0.001));
    }

    @Test
    void publicBoundaryHasOnlyReadOnlyViewsValuesAndCanonicalLookup() {
        Set<String> forbidden = Set.of(
                "InventoryService",
                "BodyInventoryService",
                "WorldMutationService",
                "WorldItemService",
                "BlockInteractionController",
                "ChunkRepository",
                "PhysicsBody",
                "Renderer",
                "Event");
        List<Class<?>> boundary = Arrays.stream(HudPresenter.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList();
        List<Class<?>> frameBoundary = Arrays.stream(HudPresenter.FrameInput.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();

        for (Class<?> type : java.util.stream.Stream.concat(
                boundary.stream(), frameBoundary.stream()).toList()) {
            for (String name : forbidden) {
                assertFalse(type.getName().contains(name), type.getName());
            }
        }
        assertEquals(Set.of(Map.class, java.util.function.Function.class), Set.copyOf(boundary));
        assertTrue(frameBoundary.contains(BodyInventoryViewModel.class));
        assertTrue(frameBoundary.contains(BlockInteractionViewModel.class));
        assertFalse(Arrays.stream(HudPresenter.class.getMethods())
                .map(method -> method.getName().toLowerCase())
                .anyMatch(name -> name.contains("mutat")
                        || name.contains("reserve")
                        || name.contains("drop")
                        || name.contains("insert")
                        || name.contains("extract")));
    }

    private void assertLifecycleClears(
            HudVisibility.Lifecycle lifecycle,
            boolean focused,
            boolean cursorCaptured,
            boolean blockingUi,
            HudVisibility.Reason reason) {
        Fixture fixture = fixture(Map.of());
        fixture.inventory.put(BodySlot.LEFT_HAND, new ItemStack(DIRT, 1));
        fixture.interaction.activeItem = Optional.of(new ItemStack(DIRT, 1));
        fixture.capture(emptyInput(), 0, false, 0);
        fixture.interaction.activateTarget();
        fixture.interaction.gameMode = GameMode.CREATIVE;
        fixture.inventory.activeSlot = BodySlot.RIGHT_HAND;
        assertTrue(fixture.capture(emptyInput(), 1, false, 0).slotTransition().isPresent());

        HudPresentationSnapshot hidden = fixture.capture(
                emptyInput(), 2, false, 0, lifecycle, focused, cursorCaptured, blockingUi);
        assertEquals(reason, hidden.visibility().reason());
        assertFalse(hidden.visibility().interactionEligible());
        assertEquals(HudPresentationSnapshot.InteractionPresentation.cleared(), hidden.interaction());
        assertTrue(hidden.slotTransition().isEmpty());
        assertTrue(hidden.itemName().isEmpty());
        assertTrue(hidden.modeNotice().isEmpty());

        HudPresentationSnapshot regained = fixture.capture(
                emptyInput(), 3, false, 0,
                HudVisibility.Lifecycle.RUNNING, true, true, false);
        assertEquals(HudPresentationSnapshot.InteractionPresentation.cleared(), regained.interaction());

        fixture.interaction.clearTransient();
        fixture.capture(emptyInput(), 4, false, 0);
        fixture.interaction.activateTarget();
        assertTrue(fixture.capture(emptyInput(), 5, false, 0).interaction().target().isPresent());
    }

    private static boolean twoHanded(
            ItemFormDefinition form, ItemStackView left, ItemStackView right) {
        Fixture fixture = fixture(form == null ? Map.of() : Map.of(form.id(), form));
        fixture.inventory.put(BodySlot.LEFT_HAND, left);
        fixture.inventory.put(BodySlot.RIGHT_HAND, right);
        return fixture.capture(emptyInput(), 0, false, 0).twoHanded();
    }

    private static Fixture twoHandedFixture(BodySlot activeSlot) {
        Fixture fixture = fixture(Map.of(
                SPEAR, new ItemFormDefinition(SPEAR, 1, false, true)));
        fixture.inventory.put(BodySlot.LEFT_HAND, new ItemStack(SPEAR, 1));
        fixture.inventory.put(BodySlot.RIGHT_HAND, new ItemStack(SPEAR, 1));
        fixture.inventory.activeSlot = activeSlot;
        return fixture;
    }

    private static Fixture fixture(Map<ResourceLocation, ItemFormDefinition> forms) {
        return new Fixture(new HudPresenter(forms));
    }

    private static InputSnapshot emptyInput() {
        return new InputSnapshot(Set.of(), Set.of());
    }

    private static final class Fixture {
        private final HudPresenter presenter;
        private final MutableInventory inventory = new MutableInventory();
        private final MutableInteraction interaction = new MutableInteraction();
        private Optional<RenderMetricsSnapshot> metrics = Optional.empty();
        private HudDebugSnapshot.FeetPosition feet = new HudDebugSnapshot.FeetPosition(0, 0, 0);
        private HudDebugSnapshot.Counts counts = new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0);

        private Fixture(HudPresenter presenter) {
            this.presenter = presenter;
        }

        private HudPresentationSnapshot capture(
                InputSnapshot input, long sample, boolean firstFixedStep, double delta) {
            return capture(
                    input,
                    sample,
                    firstFixedStep,
                    delta,
                    HudVisibility.Lifecycle.RUNNING,
                    true,
                    true,
                    false);
        }

        private HudPresentationSnapshot capture(
                InputSnapshot input,
                long sample,
                boolean firstFixedStep,
                double delta,
                HudVisibility.Lifecycle lifecycle,
                boolean focused,
                boolean cursorCaptured,
                boolean blockingUi) {
            return presenter.capture(new HudPresenter.FrameInput(
                    inventory,
                    interaction,
                    metrics,
                    feet,
                    counts,
                    input,
                    sample,
                    firstFixedStep,
                    delta,
                    lifecycle,
                    focused,
                    cursorCaptured,
                    blockingUi));
        }
    }

    private static final class MutableInventory implements BodyInventoryViewModel, InventoryView {
        private final EnumMap<BodySlot, ItemStackView> stacks = new EnumMap<>(BodySlot.class);
        private BodySlot activeSlot = BodySlot.LEFT_HAND;

        void put(BodySlot slot, ItemStackView stack) {
            stacks.put(slot, stack);
        }

        void clear(BodySlot slot) {
            stacks.remove(slot);
        }

        @Override
        public EntityRef owner() {
            return new EntityRef(1);
        }

        @Override
        public BodySlot activeSlot() {
            return activeSlot;
        }

        @Override
        public InventoryView inventory() {
            return this;
        }

        @Override
        public long revision() {
            return 0;
        }

        @Override
        public Optional<ItemStackView> stack(BodySlot slot) {
            return Optional.ofNullable(stacks.get(slot));
        }
    }

    private static final class MutableInteraction implements BlockInteractionViewModel {
        private Optional<BlockHitResult> target = Optional.empty();
        private Optional<BlockFace> hitFace = Optional.empty();
        private double progress;
        private InteractionMode mode = InteractionMode.NONE;
        private Optional<ItemStackView> activeItem = Optional.empty();
        private Optional<InteractionFailureReason> failureReason = Optional.empty();
        private int crackStage;
        private GameMode gameMode = GameMode.SURVIVAL;

        void activateTarget() {
            activateTarget(new BlockHitResult(
                    1, 2, 3, 2, 2, 3, DIRT, 1, 0, 0, 1.5f, 2.5f, 3.5f, 4));
        }

        void activateTarget(BlockHitResult nextTarget) {
            target = Optional.of(nextTarget);
            hitFace = Optional.of(BlockFace.fromHit(nextTarget));
            progress = 0.5;
            mode = InteractionMode.BREAKING;
            failureReason = Optional.of(new InteractionFailureReason(FAILURE));
            crackStage = 5;
        }

        void clearTransient() {
            target = Optional.empty();
            hitFace = Optional.empty();
            progress = 0;
            mode = InteractionMode.NONE;
            failureReason = Optional.empty();
            crackStage = 0;
        }

        @Override
        public Optional<BlockHitResult> target() {
            return target;
        }

        @Override
        public Optional<BlockFace> hitFace() {
            return hitFace;
        }

        @Override
        public double progress() {
            return progress;
        }

        @Override
        public InteractionMode mode() {
            return mode;
        }

        @Override
        public Optional<ItemStackView> activeItem() {
            return activeItem;
        }

        @Override
        public Optional<InteractionFailureReason> failureReason() {
            return failureReason;
        }

        @Override
        public int crackStage() {
            return crackStage;
        }

        @Override
        public GameMode gameMode() {
            return gameMode;
        }
    }

    private static final class MutableStack implements ItemStackView {
        private ResourceLocation itemId;
        private int count;

        private MutableStack(ResourceLocation itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        @Override
        public ResourceLocation itemId() {
            return itemId;
        }

        @Override
        public int count() {
            return count;
        }
    }
}
