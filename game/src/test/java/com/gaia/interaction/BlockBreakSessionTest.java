package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.time.FixedStepClock;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockBreakSessionTest {
    private static final BlockHitResult TARGET = hit(1, 2, 3, 1, 0, 0);

    @Test
    void advancesOnlyByFixedStepAndCompletesAtRequiredTime() {
        BlockBreakSession session = BlockBreakSession.start(TARGET, 17, 1.0);

        for (int step = 0; step < 59; step++) {
            session = session.advance(1.0 / 60.0);
        }

        assertFalse(session.complete());
        assertEquals(59.0 / 60.0, session.progress(), 1.0e-9);
        session = session.advance(1.0 / 60.0);
        assertTrue(session.complete());
        assertEquals(1.0, session.progress());
        assertEquals(9, session.crackStage());
    }

    @Test
    void renderFrameSchedulesProduceExactlyTheSameFixedBreakSteps() {
        for (int fps : List.of(10, 60, 144, 240)) {
            FixedStepClock clock = new FixedStepClock(1.0 / 60.0, 8);
            BlockBreakSession session = BlockBreakSession.start(TARGET, 2, 1.0);
            int fixedSteps = 0;
            while (!session.complete()) {
                int steps = clock.advance(1.0 / fps);
                for (int step = 0; step < steps && !session.complete(); step++) {
                    session = session.advance(clock.fixedStepSeconds());
                    fixedSteps++;
                }
            }
            assertEquals(60, fixedSteps, "fps=" + fps);
            assertEquals(1.0, session.elapsedSeconds(), 1.0e-9, "fps=" + fps);
        }
    }

    @Test
    void matchingIncludesPositionFaceExpectedBlockAndChunkRevision() {
        BlockBreakSession session = BlockBreakSession.start(TARGET, 8, 2.0);

        assertTrue(session.matches(TARGET, 8));
        assertTrue(session.matches(new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                ResourceLocation.parse("gaia:stone"), 1, 0, 0,
                1.25f, 2.1f, 3.75f, 1.2f), 8));
        assertFalse(session.matches(hit(2, 2, 3, 1, 0, 0), 8));
        assertFalse(session.matches(hit(1, 2, 3, 0, 1, 0), 8));
        assertFalse(session.matches(new BlockHitResult(
                1, 2, 3, 2, 2, 3,
                ResourceLocation.parse("gaia:dirt"), 1, 0, 0,
                2, 2.5f, 3.5f, 2), 8));
        assertFalse(session.matches(TARGET, 9));
    }

    @Test
    void zeroRequiredTimeIsImmediatelyCompleteWithoutNaNProgress() {
        BlockBreakSession session = BlockBreakSession.start(TARGET, 1, 0);

        assertTrue(session.complete());
        assertEquals(1.0, session.progress());
        assertEquals(9, session.crackStage());
    }

    private static BlockHitResult hit(
            int x, int y, int z, int normalX, int normalY, int normalZ) {
        return new BlockHitResult(
                x, y, z,
                x + normalX, y + normalY, z + normalZ,
                ResourceLocation.parse("gaia:stone"),
                normalX, normalY, normalZ,
                x + 0.5f, y + 0.5f, z + 0.5f,
                2);
    }
}
