package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockBreakTrackerTest {
    private static final BlockHitResult TARGET = hit(1, 2, 3);
    private static final BreakRule TIMED = new BreakRule(true, 1);

    @Test
    void startsAndAdvancesTheSameSessionOnlyForStableInputs() {
        BlockBreakTracker tracker = new BlockBreakTracker();

        BreakTrackerResult first = tracker.update(
                update(TARGET, 4, GameMode.SURVIVAL, true, false, TIMED),
                1.0 / 60.0);
        BreakTrackerResult second = tracker.update(
                update(TARGET, 4, GameMode.SURVIVAL, true, false, TIMED),
                1.0 / 60.0);

        assertEquals(BreakTrackerResult.Status.STARTED, first.status());
        assertEquals(BreakTrackerResult.Status.ADVANCED, second.status());
        assertEquals(2.0 / 60.0,
                second.session().orElseThrow().elapsedSeconds(), 1.0e-9);
    }

    @Test
    void everyCancellationConditionClearsProgressWithoutStartingReplacement() {
        List<BreakUpdate> cancellations = List.of(
                update(TARGET, 4, GameMode.SURVIVAL, false, false, TIMED),
                update(hit(2, 2, 3), 4, GameMode.SURVIVAL, true, false, TIMED),
                update(TARGET, 5, GameMode.SURVIVAL, true, false, TIMED),
                new BreakUpdate(Optional.empty(), 0, GameMode.SURVIVAL,
                        true, false, Optional.empty()),
                update(TARGET, 4, GameMode.CREATIVE, true, false,
                        new BreakRule(true, 0)),
                update(TARGET, 4, GameMode.SURVIVAL, true, true, TIMED));

        for (BreakUpdate cancellation : cancellations) {
            BlockBreakTracker tracker = startedTracker();

            BreakTrackerResult result = tracker.update(cancellation, 1.0 / 60.0);

            assertEquals(BreakTrackerResult.Status.CANCELLED, result.status());
            assertTrue(result.session().isEmpty());
            assertTrue(tracker.session().isEmpty());
        }
    }

    @Test
    void unbreakableTargetNeverCreatesSession() {
        BlockBreakTracker tracker = new BlockBreakTracker();

        BreakTrackerResult result = tracker.update(
                update(TARGET, 4, GameMode.SURVIVAL, true, false,
                        BreakRule.unbreakable()),
                1.0 / 60.0);

        assertEquals(BreakTrackerResult.Status.UNBREAKABLE, result.status());
        assertTrue(result.session().isEmpty());
    }

    private static BlockBreakTracker startedTracker() {
        BlockBreakTracker tracker = new BlockBreakTracker();
        tracker.update(
                update(TARGET, 4, GameMode.SURVIVAL, true, false, TIMED),
                1.0 / 60.0);
        return tracker;
    }

    private static BreakUpdate update(
            BlockHitResult target,
            long revision,
            GameMode mode,
            boolean held,
            boolean blocked,
            BreakRule rule) {
        return new BreakUpdate(
                Optional.of(target), revision, mode, held, blocked, Optional.of(rule));
    }

    private static BlockHitResult hit(int x, int y, int z) {
        return new BlockHitResult(
                x, y, z, x + 1, y, z,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                x + 1, y + 0.5f, z + 0.5f, 2);
    }
}
