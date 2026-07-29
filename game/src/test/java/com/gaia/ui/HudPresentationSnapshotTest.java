package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HudPresentationSnapshotTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void copiesEverySlotAndRejectsMutationWithoutCreatingAnotherStackType() {
        ItemStack canonicalStack = new ItemStack(DIRT, 3);
        EnumMap<BodySlot, HudSlotSnapshot> source = new EnumMap<>(BodySlot.class);
        source.put(BodySlot.LEFT_HAND, new HudSlotSnapshot(
                BodySlot.LEFT_HAND, Optional.of(canonicalStack), true, false, Optional.empty()));
        source.put(BodySlot.RIGHT_HAND, HudSlotSnapshot.empty(BodySlot.RIGHT_HAND, false));
        source.put(BodySlot.MOUTH, HudSlotSnapshot.empty(BodySlot.MOUTH, false));

        HudPresentationSnapshot snapshot = snapshot(source);
        source.put(BodySlot.LEFT_HAND, HudSlotSnapshot.empty(BodySlot.LEFT_HAND, true));

        ItemStack projected = snapshot.slot(BodySlot.LEFT_HAND).stack().orElseThrow();
        assertSame(canonicalStack, projected);
        assertInstanceOf(ItemStack.class, projected);
        assertEquals(DIRT, projected.itemId());
        assertEquals(3, projected.count());
        assertTrue(snapshot.slot(BodySlot.RIGHT_HAND).stack().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.slots().put(
                        BodySlot.MOUTH, HudSlotSnapshot.empty(BodySlot.MOUTH, false)));
    }

    @Test
    void missingMetricsRemainExplicitlyAbsentWhileAuthoritativeFeetAndCountsRemainExact() {
        HudDebugSnapshot debug = new HudDebugSnapshot(
                Optional.empty(),
                new HudDebugSnapshot.FeetPosition(12.5, -4.25, 99.75),
                new HudDebugSnapshot.Counts(7, 8, 9, 1, 2, 3));

        assertTrue(debug.previousFrameMetrics().isEmpty());
        assertEquals(12.5, debug.feet().x());
        assertEquals(-4.25, debug.feet().y());
        assertEquals(99.75, debug.feet().z());
        assertEquals(7, debug.counts().loadedChunks());
        assertEquals(8, debug.counts().physicsBodies());
        assertEquals(9, debug.counts().worldItems());
        assertEquals(1, debug.counts().blockDamageVisuals());
        assertEquals(2, debug.counts().feedbackWorldItems());
        assertEquals(3, debug.counts().particles());
    }

    @Test
    void invalidValueProjectionsFailAtTheImmutableBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new HudDebugSnapshot.FeetPosition(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new HudDebugSnapshot.Counts(0, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new HudPresentationSnapshot.CreativeSelection(DIRT, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HudPresentationSnapshot.TimedItemName(DIRT, 0.5, 1.1));
    }

    @Test
    void lifecycleVisibilityCarriesTheExactReasonAndNeverClaimsHiddenInteractionIsEligible() {
        HudVisibility hidden = new HudVisibility(
                false,
                false,
                false,
                HudVisibility.Lifecycle.LOADING,
                HudVisibility.Reason.LOADING);

        assertFalse(hidden.hudVisible());
        assertFalse(hidden.interactionEligible());
        assertEquals(HudVisibility.Lifecycle.LOADING, hidden.lifecycle());
        assertEquals(HudVisibility.Reason.LOADING, hidden.reason());
        assertThrows(IllegalArgumentException.class,
                () -> new HudVisibility(
                        false,
                        false,
                        true,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.VISIBLE));
    }

    @Test
    void rejectsRunningUnsafeReasonsThatClaimVisiblePresentation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudVisibility(
                        true,
                        false,
                        true,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.FOCUS_LOST));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudVisibility(
                        false,
                        true,
                        false,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.CURSOR_RELEASED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudVisibility(
                        false,
                        true,
                        false,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.BLOCKING_UI));
    }

    @Test
    void rejectsLoadingAndShutdownReasonMismatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudVisibility(
                        false,
                        false,
                        false,
                        HudVisibility.Lifecycle.LOADING,
                        HudVisibility.Reason.SHUTDOWN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HudVisibility(
                        false,
                        false,
                        false,
                        HudVisibility.Lifecycle.SHUTDOWN,
                        HudVisibility.Reason.LOADING));
    }

    @Test
    void permitsIndependentDebugVisibilityForRunningHudDisabled() {
        HudVisibility visibility = new HudVisibility(
                false,
                true,
                false,
                HudVisibility.Lifecycle.RUNNING,
                HudVisibility.Reason.HUD_DISABLED);

        assertTrue(visibility.debugVisible());
        assertFalse(visibility.hudVisible());
        assertFalse(visibility.interactionEligible());
    }

    @Test
    void rejectsContradictoryTwoHandedAnchorCompanionAndActiveHandTopology() {
        ItemStack stack = new ItemStack(DIRT, 1);
        EnumMap<BodySlot, HudSlotSnapshot> leftAnchored = new EnumMap<>(BodySlot.class);
        leftAnchored.put(
                BodySlot.LEFT_HAND,
                new HudSlotSnapshot(
                        BodySlot.LEFT_HAND,
                        Optional.of(stack),
                        true,
                        false,
                        Optional.empty()));
        leftAnchored.put(
                BodySlot.RIGHT_HAND,
                new HudSlotSnapshot(
                        BodySlot.RIGHT_HAND,
                        Optional.empty(),
                        false,
                        true,
                        Optional.of(BodySlot.LEFT_HAND)));
        leftAnchored.put(BodySlot.MOUTH, HudSlotSnapshot.empty(BodySlot.MOUTH, false));

        assertThrows(
                IllegalArgumentException.class,
                () -> twoHandedSnapshot(
                        leftAnchored, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND));
        assertThrows(
                IllegalArgumentException.class,
                () -> twoHandedSnapshot(
                        leftAnchored, BodySlot.RIGHT_HAND, BodySlot.LEFT_HAND));
    }

    private static HudPresentationSnapshot snapshot(Map<BodySlot, HudSlotSnapshot> slots) {
        return new HudPresentationSnapshot(
                slots,
                BodySlot.LEFT_HAND,
                false,
                Optional.empty(),
                Optional.empty(),
                GameMode.SURVIVAL,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                new HudVisibility(
                        true,
                        false,
                        true,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.VISIBLE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new HudDebugSnapshot(
                        Optional.empty(),
                        new HudDebugSnapshot.FeetPosition(0, 0, 0),
                        new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0)));
    }

    private static HudPresentationSnapshot twoHandedSnapshot(
            Map<BodySlot, HudSlotSnapshot> slots,
            BodySlot activeSlot,
            BodySlot anchor) {
        return new HudPresentationSnapshot(
                slots,
                activeSlot,
                true,
                Optional.of(anchor),
                Optional.empty(),
                GameMode.SURVIVAL,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                new HudVisibility(
                        true,
                        false,
                        true,
                        HudVisibility.Lifecycle.RUNNING,
                        HudVisibility.Reason.VISIBLE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new HudDebugSnapshot(
                        Optional.empty(),
                        new HudDebugSnapshot.FeetPosition(0, 0, 0),
                        new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0)));
    }
}
