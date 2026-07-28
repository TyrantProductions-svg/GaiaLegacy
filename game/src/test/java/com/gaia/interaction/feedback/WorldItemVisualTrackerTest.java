package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldItemVisualTrackerTest {
    private static final ResourceLocation ITEM = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation FAILING_ITEM = ResourceLocation.parse("gaia:failing");
    private static final TextureRegion REGION =
            new TextureRegion(ResourceLocation.parse("gaia:stone_top"), 16, 0, 16, 16, 32, 16);

    @Test
    void addUpdateRemoveAndReorderMaintainOneIdSortedVisualPerStableId() {
        WorldItemVisualTracker tracker = tracker();
        WorldItemId firstId = new WorldItemId(2);
        WorldItemId secondId = new WorldItemId(7);

        List<WorldItemVisual> initial =
                tracker.reconcile(
                        List.of(
                                snapshot(secondId, 0, 7.25, 8.5, 9.75),
                                snapshot(firstId, 0, 1.25, 2.5, 3.75)));
        WorldItemVisual unchangedFirst = initial.get(0);

        List<WorldItemVisual> updated =
                tracker.reconcile(
                        List.of(
                                snapshot(firstId, 0, 1.25, 2.5, 3.75),
                                snapshot(secondId, 1, -4.0, 5.0, 6.0)));

        assertEquals(List.of(firstId, secondId), ids(updated));
        assertEquals(2, updated.size());
        assertSame(unchangedFirst, updated.get(0));
        assertEquals(1, updated.get(1).sourceRevision());
        assertEquals(-4.0, updated.get(1).x());
        assertEquals(5.0, updated.get(1).y());
        assertEquals(6.0, updated.get(1).z());
        assertEquals(REGION, updated.get(1).region());

        List<WorldItemVisual> remaining =
                tracker.reconcile(List.of(snapshot(secondId, 1, -4.0, 5.0, 6.0)));
        assertEquals(List.of(secondId), ids(remaining));
    }

    @Test
    void duplicateStableIdRejectsWholeInputWithoutChangingExistingPresentation() {
        WorldItemVisualTracker tracker = tracker();
        WorldItemSnapshot existing = snapshot(new WorldItemId(3), 0, 1, 2, 3);
        List<WorldItemVisual> before = tracker.reconcile(List.of(existing));

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                tracker.reconcile(
                                        List.of(
                                                snapshot(new WorldItemId(4), 0, 4, 5, 6),
                                                snapshot(new WorldItemId(4), 1, 7, 8, 9))));

        assertTrue(failure.getMessage().contains("Duplicate world item id"));
        List<WorldItemVisual> after = tracker.reconcile(List.of(existing));
        assertEquals(before, after);
        assertSame(before.get(0), after.get(0));
    }

    @Test
    void nullElementRejectsWholeInputWithoutChangingExistingPresentation() {
        WorldItemVisualTracker tracker = tracker();
        WorldItemSnapshot existing = snapshot(new WorldItemId(3), 0, 1, 2, 3);
        WorldItemVisual before = tracker.reconcile(List.of(existing)).get(0);

        assertThrows(
                NullPointerException.class,
                () -> tracker.reconcile(java.util.Collections.singletonList(null)));

        assertSame(before, tracker.reconcile(List.of(existing)).get(0));
    }

    @Test
    void resolverFailureRejectsWholeInputWithoutChangingExistingPresentation() {
        IllegalStateException failure = new IllegalStateException("region failed");
        WorldItemVisualTracker tracker =
                new WorldItemVisualTracker(
                        itemId -> {
                            if (itemId.equals(FAILING_ITEM)) {
                                throw failure;
                            }
                            return REGION;
                        });
        WorldItemSnapshot existing = snapshot(new WorldItemId(3), 0, 1, 2, 3);
        WorldItemVisual before = tracker.reconcile(List.of(existing)).get(0);

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                tracker.reconcile(
                                        List.of(
                                                snapshot(new WorldItemId(4), 0, 4, 5, 6),
                                                snapshot(
                                                        new WorldItemId(5),
                                                        0,
                                                        7,
                                                        8,
                                                        9,
                                                        FAILING_ITEM))));

        assertSame(failure, escaped);
        assertSame(before, tracker.reconcile(List.of(existing)).get(0));
    }

    @Test
    void persistentUnsupportedItemResolvesAndDiagnosesOnlyOnceAcrossSnapshotUpdates() {
        List<ResourceLocation> diagnosedItems = new ArrayList<>();
        WorldItemVisualTracker tracker = new WorldItemVisualTracker(itemId -> {
            diagnosedItems.add(itemId);
            return REGION;
        });
        WorldItemId stableId = new WorldItemId(9);

        WorldItemVisual initial = tracker.reconcile(List.of(
                        snapshot(stableId, 0, 1, 2, 3, FAILING_ITEM)))
                .get(0);
        WorldItemVisual repeated = tracker.reconcile(List.of(
                        snapshot(stableId, 0, 1, 2, 3, FAILING_ITEM)))
                .get(0);
        WorldItemVisual moved = tracker.reconcile(List.of(
                        snapshot(stableId, 1, 4, 5, 6, FAILING_ITEM)))
                .get(0);

        assertEquals(List.of(FAILING_ITEM), diagnosedItems);
        assertSame(initial, repeated);
        assertEquals(stableId, moved.id());
        assertEquals(1, moved.sourceRevision());
        assertEquals(4, moved.x());
        assertEquals(5, moved.y());
        assertEquals(6, moved.z());
        assertEquals(REGION, moved.region());
    }

    @Test
    void changedCanonicalItemRefreshesCachedIdentityEvenWhenRegionIsEqual() {
        List<ResourceLocation> resolvedItems = new ArrayList<>();
        WorldItemVisualTracker tracker = new WorldItemVisualTracker(itemId -> {
            resolvedItems.add(itemId);
            return REGION;
        });
        WorldItemId stableId = new WorldItemId(11);

        tracker.reconcile(List.of(snapshot(stableId, 0, 1, 2, 3, ITEM)));
        tracker.reconcile(List.of(snapshot(stableId, 0, 1, 2, 3, FAILING_ITEM)));
        tracker.reconcile(List.of(snapshot(stableId, 0, 1, 2, 3, FAILING_ITEM)));

        assertEquals(List.of(ITEM, FAILING_ITEM), resolvedItems);
    }

    @Test
    void inputAndOutputAreDefensiveAndClearDoesNotMutatePriorSnapshot() {
        WorldItemVisualTracker tracker = tracker();
        ArrayList<WorldItemSnapshot> callerInput =
                new ArrayList<>(List.of(snapshot(new WorldItemId(1), 0, 1, 2, 3)));

        List<WorldItemVisual> output = tracker.reconcile(callerInput);
        callerInput.clear();

        assertEquals(1, output.size());
        assertThrows(UnsupportedOperationException.class, () -> output.clear());
        tracker.clear();
        assertEquals(1, output.size());
        assertEquals(List.of(), tracker.reconcile(List.of()));
    }

    @Test
    void trackerBytecodeHasNoServiceReservationMutableStackOrAlternateIdentityDependency()
            throws Exception {
        for (Field field : WorldItemVisualTracker.class.getDeclaredFields()) {
            String type = field.getGenericType().getTypeName();
            assertFalse(type.contains("WorldItemService"), type);
            assertFalse(type.contains("Reservation"), type);
            assertFalse(type.contains("ItemStack"), type);
            assertFalse(field.getType().equals(WorldItemId.class), type);
        }

        String resource =
                WorldItemVisualTracker.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input =
                WorldItemVisualTracker.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            bytecode = input.readAllBytes();
        }
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        for (String forbidden :
                List.of(
                        "WorldItemService",
                        "LogicalWorldItemService",
                        "WorldItemReservation",
                        "InventoryReservation",
                        "PhysicsBody",
                        "WorldMutation")) {
            assertFalse(constantPool.contains(forbidden), forbidden);
        }
    }

    private static WorldItemVisualTracker tracker() {
        return new WorldItemVisualTracker(itemId -> REGION);
    }

    private static WorldItemSnapshot snapshot(
            WorldItemId id, long revision, double x, double y, double z) {
        return snapshot(id, revision, x, y, z, ITEM);
    }

    private static WorldItemSnapshot snapshot(
            WorldItemId id,
            long revision,
            double x,
            double y,
            double z,
            ResourceLocation itemId) {
        return new WorldItemSnapshot(
                id,
                new ItemStack(itemId, 4),
                x,
                y,
                z,
                0,
                0,
                0,
                revision);
    }

    private static List<WorldItemId> ids(List<WorldItemVisual> visuals) {
        return visuals.stream().map(WorldItemVisual::id).toList();
    }
}
