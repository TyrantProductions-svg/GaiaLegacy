package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.feedback.FirstPersonMovementVisual;
import org.junit.jupiter.api.Test;

class FirstPersonMovementPresentationTest {
    private static final double FIXED_DELTA = 1.0 / 60.0;

    @Test
    void zeroMovementProducesExactIdentity() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();

        step(presentation, state(8.0f, 0.0f, 0.0f, true), 30);

        assertEquals(FirstPersonMovementVisual.identity(), presentation.snapshot(1.0f));
    }

    @Test
    void groundedHorizontalMovementProducesBoundedBob() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();

        step(presentation, state(8.0f, 4.5f, 0.0f, true), 20);
        FirstPersonMovementVisual visual = presentation.snapshot(1.0f);

        assertNotEquals(FirstPersonMovementVisual.identity(), visual);
        assertTrue(Math.abs(visual.translationX()) <= 0.012f);
        assertTrue(Math.abs(visual.translationY()) <= 0.025f);
        assertTrue(Math.abs(visual.rollDegrees()) <= 0.18f);
    }

    @Test
    void airborneHorizontalMovementSuppressesWalkingBob() {
        FirstPersonMovementPresentation grounded = new FirstPersonMovementPresentation();
        FirstPersonMovementPresentation airborne = new FirstPersonMovementPresentation();

        step(grounded, state(8.0f, 4.5f, 0.0f, true), 20);
        step(airborne, state(8.0f, 4.5f, 0.0f, false), 20);

        assertNotEquals(FirstPersonMovementVisual.identity(), grounded.snapshot(1.0f));
        assertEquals(FirstPersonMovementVisual.identity(), airborne.snapshot(1.0f));
    }

    @Test
    void stoppingReturnsCleanlyToIdentityWithoutDrift() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        step(presentation, state(8.0f, 4.5f, 0.0f, true), 20);

        step(presentation, state(8.0f, 0.0f, 0.0f, true), 30);
        FirstPersonMovementVisual settled = presentation.snapshot(1.0f);
        step(presentation, state(8.0f, 0.0f, 0.0f, true), 120);

        assertEquals(FirstPersonMovementVisual.identity(), settled);
        assertEquals(settled, presentation.snapshot(1.0f));
    }

    @Test
    void repeatedInputSequenceProducesDeterministicSnapshots() {
        FirstPersonMovementPresentation first = new FirstPersonMovementPresentation();
        FirstPersonMovementPresentation second = new FirstPersonMovementPresentation();

        for (int index = 0; index < 45; index++) {
            FirstPersonMovementState sample = state(
                    8.0f,
                    index < 30 ? 3.25f : 0.0f,
                    0.0f,
                    true);
            first.fixedUpdate(FIXED_DELTA, sample);
            second.fixedUpdate(FIXED_DELTA, sample);
            assertEquals(first.snapshot(0.37f), second.snapshot(0.37f));
        }
    }

    @Test
    void renderAlphaInterpolatesImmutablePreviousAndCurrentSnapshots() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        step(presentation, state(8.0f, 4.5f, 0.0f, true), 12);

        FirstPersonMovementVisual previous = presentation.snapshot(0.0f);
        FirstPersonMovementVisual current = presentation.snapshot(1.0f);
        FirstPersonMovementVisual midpoint = presentation.snapshot(0.5f);

        assertNotEquals(previous, current);
        assertEquals((previous.translationX() + current.translationX()) * 0.5f,
                midpoint.translationX(), 1.0e-6f);
        assertEquals((previous.translationY() + current.translationY()) * 0.5f,
                midpoint.translationY(), 1.0e-6f);
        assertEquals((previous.rollDegrees() + current.rollDegrees()) * 0.5f,
                midpoint.rollDegrees(), 1.0e-6f);
    }

    @Test
    void groundedToGroundedStepUpCancelsTheSnapThenSettlesExactly() {
        FirstPersonMovementPresentation presentation = initialized(
                state(8.0f, 0.0f, 0.0f, true));

        presentation.fixedUpdate(FIXED_DELTA, state(9.0f, 0.0f, 0.0f, true));
        float initial = presentation.snapshot(1.0f).translationY();
        step(presentation, state(9.0f, 0.0f, 0.0f, true), 12);

        assertTrue(initial < -0.5f && initial >= -1.0f);
        assertEquals(0.0f, presentation.snapshot(1.0f).translationY());
    }

    @Test
    void groundedToGroundedStepDownCancelsTheSnapThenSettlesExactly() {
        FirstPersonMovementPresentation presentation = initialized(
                state(9.0f, 0.0f, 0.0f, true));

        presentation.fixedUpdate(FIXED_DELTA, state(8.0f, 0.0f, 0.0f, true));
        float initial = presentation.snapshot(1.0f).translationY();
        step(presentation, state(8.0f, 0.0f, 0.0f, true), 12);

        assertTrue(initial > 0.5f && initial <= 1.0f);
        assertEquals(0.0f, presentation.snapshot(1.0f).translationY());
    }

    @Test
    void groundedToAirbornePositiveVelocityIsTakeoffNotStepUp() {
        FirstPersonMovementPresentation presentation = initialized(
                state(8.0f, 0.0f, 0.0f, true));

        presentation.fixedUpdate(FIXED_DELTA, state(8.12f, 0.0f, 8.0f, false));
        float takeoff = presentation.snapshot(1.0f).translationY();

        assertTrue(takeoff > 0.0f);
        assertTrue(takeoff <= 0.018f);
    }

    @Test
    void landingCompressionScalesWithImpactSpeedAndCapsAtPointZeroThreeFive() {
        float zero = landingCompression(0.0f);
        float slow = landingCompression(-2.0f);
        float fast = landingCompression(-50.0f);

        assertEquals(0.0f, zero);
        assertTrue(slow < 0.0f);
        assertTrue(Math.abs(fast) > Math.abs(slow));
        assertEquals(-0.035f, fast);
    }

    @Test
    void landingResponseSettlesExactlyToZero() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        presentation.fixedUpdate(FIXED_DELTA, state(9.0f, 0.0f, -12.0f, false));
        presentation.fixedUpdate(FIXED_DELTA, state(8.0f, 0.0f, 0.0f, true));
        assertTrue(presentation.snapshot(1.0f).translationY() < 0.0f);

        step(presentation, state(8.0f, 0.0f, 0.0f, true), 20);

        assertEquals(0.0f, presentation.snapshot(1.0f).translationY());
    }

    @Test
    void repeatedGroundedStepsRemainBoundedAndDoNotAccumulateDrift() {
        FirstPersonMovementPresentation presentation = initialized(
                state(8.0f, 0.0f, 0.0f, true));
        float feetY = 8.0f;

        for (int index = 0; index < 20; index++) {
            feetY = feetY == 8.0f ? 9.0f : 8.0f;
            presentation.fixedUpdate(
                    FIXED_DELTA, state(feetY, 0.0f, 0.0f, true));
            float offset = presentation.snapshot(1.0f).translationY();
            assertNotEquals(0.0f, offset);
            assertTrue(Math.abs(offset) <= 1.0f);
        }
        step(presentation, state(feetY, 0.0f, 0.0f, true), 20);

        assertEquals(FirstPersonMovementVisual.identity(), presentation.snapshot(1.0f));
    }

    @Test
    void movementSamplesRemainImmutablePresentationInputs() {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        FirstPersonMovementState sample = state(8.0f, 3.0f, -1.0f, true);

        presentation.fixedUpdate(FIXED_DELTA, sample);

        assertEquals(new FirstPersonMovementState(8.0f, 3.0f, -1.0f, true, false), sample);
    }

    private static void step(
            FirstPersonMovementPresentation presentation,
            FirstPersonMovementState state,
            int count) {
        for (int step = 0; step < count; step++) {
            presentation.fixedUpdate(FIXED_DELTA, state);
        }
    }

    private static FirstPersonMovementPresentation initialized(
            FirstPersonMovementState initial) {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        presentation.fixedUpdate(FIXED_DELTA, initial);
        return presentation;
    }

    private static float landingCompression(float impactSpeed) {
        FirstPersonMovementPresentation presentation = new FirstPersonMovementPresentation();
        presentation.fixedUpdate(
                FIXED_DELTA, state(9.0f, 0.0f, impactSpeed, false));
        presentation.fixedUpdate(
                FIXED_DELTA, state(8.0f, 0.0f, 0.0f, true));
        return presentation.snapshot(1.0f).translationY();
    }

    private static FirstPersonMovementState state(
            float feetY,
            float horizontalSpeed,
            float verticalSpeed,
            boolean grounded) {
        return new FirstPersonMovementState(
                feetY, horizontalSpeed, verticalSpeed, grounded, false);
    }
}
