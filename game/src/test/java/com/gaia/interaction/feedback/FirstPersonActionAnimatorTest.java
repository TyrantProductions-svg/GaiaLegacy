package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.feedback.VisualTransform;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FirstPersonActionAnimatorTest {
    @ParameterizedTest
    @EnumSource(
            value = FirstPersonActionAnimator.State.class,
            names = {"PLACE", "BREAK_SWING", "DROP"})
    void committedActionIsNonIdentityBeforeAnyRenderTimeAdvances(
            FirstPersonActionAnimator.State action) {
        FirstPersonActionAnimator animator = new FirstPersonActionAnimator();

        trigger(animator, action, 41L);

        assertNotEquals(VisualTransform.identity(), animator.snapshot());
        for (int fixedStep = 0; fixedStep < 12; fixedStep++) {
            assertNotEquals(
                    VisualTransform.identity(),
                    animator.snapshot(),
                    "fixed-step catch-up must not consume render-time animation");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 30, 60, 144, 240})
    void placeBreakAndDropRemainVisibleAndSettleWithinOneQuantizedFrame(int fps) {
        for (FirstPersonActionAnimator.State action : List.of(
                FirstPersonActionAnimator.State.PLACE,
                FirstPersonActionAnimator.State.BREAK_SWING,
                FirstPersonActionAnimator.State.DROP)) {
            FirstPersonActionAnimator animator = new FirstPersonActionAnimator();
            trigger(animator, action, 73L);
            double approvedDuration = animator.durationSeconds();
            double frameSeconds = 1.0 / fps;
            double elapsed = 0.0;

            assertNotEquals(VisualTransform.identity(), animator.snapshot());
            while (animator.state() != FirstPersonActionAnimator.State.IDLE) {
                animator.update(frameSeconds);
                elapsed += frameSeconds;
            }

            assertTrue(elapsed >= approvedDuration);
            assertTrue(elapsed <= approvedDuration + frameSeconds + 1.0e-9);
            assertEquals(VisualTransform.identity(), animator.snapshot());
        }
    }

    @Test
    void closeIsIdempotentAndCommittedEventsCannotResurrectAnimation() {
        FirstPersonActionAnimator animator = new FirstPersonActionAnimator();
        animator.triggerBreak(8L);

        animator.close();
        animator.close();
        animator.triggerPlacement(9L);
        animator.triggerBreak(10L);
        animator.triggerDrop(11L);
        animator.update(1.0);

        assertEquals(FirstPersonActionAnimator.State.IDLE, animator.state());
        assertEquals(VisualTransform.identity(), animator.snapshot());
    }

    @Test
    void committedActionsUseExactStatesDurationsAndReturnToIdentity() {
        FirstPersonActionAnimator animator = new FirstPersonActionAnimator();

        assertEquals(FirstPersonActionAnimator.State.IDLE, animator.state());
        assertEquals(VisualTransform.identity(), animator.snapshot());

        animator.triggerPlacement(1L);
        assertEquals(FirstPersonActionAnimator.State.PLACE, animator.state());
        assertEquals(0.14, animator.durationSeconds(), 1.0e-9);
        animator.update(0.049);
        VisualTransform placementAttack = animator.snapshot();
        assertTrue(placementAttack.translationY() <= -0.099f);
        assertTrue(placementAttack.translationZ() >= 0.034f);
        assertTrue(Math.abs(placementAttack.pitchDegrees()) <= 12.001f);
        animator.update(0.091);
        assertEquals(FirstPersonActionAnimator.State.IDLE, animator.state());
        assertEquals(VisualTransform.identity(), animator.snapshot());

        animator.triggerBreak(2L);
        assertEquals(0.19, animator.durationSeconds(), 1.0e-9);
        animator.update(0.19);
        assertEquals(VisualTransform.identity(), animator.snapshot());

        animator.triggerDrop(3L);
        assertEquals(0.12, animator.durationSeconds(), 1.0e-9);
        animator.update(0.12);
        assertEquals(VisualTransform.identity(), animator.snapshot());
    }

    @Test
    void newestCommittedActionRestartsDeterministicallyAndRemainsBounded() {
        FirstPersonActionAnimator animator = new FirstPersonActionAnimator();
        for (int index = 0; index < 20; index++) {
            animator.triggerBreak(42L + index);
            animator.update(0.02);
            VisualTransform transform = animator.snapshot();
            assertTrue(Math.abs(transform.translationX()) <= 0.101f);
            assertTrue(Math.abs(transform.translationY()) <= 0.051f);
            assertTrue(Math.abs(transform.yawDegrees()) <= 16.001f);
            assertTrue(Math.abs(transform.rollDegrees()) <= 10.001f);
        }

        animator.reset();
        assertEquals(FirstPersonActionAnimator.State.IDLE, animator.state());
        assertEquals(VisualTransform.identity(), animator.snapshot());
    }

    private static void trigger(
            FirstPersonActionAnimator animator,
            FirstPersonActionAnimator.State action,
            long identity) {
        switch (action) {
            case PLACE -> animator.triggerPlacement(identity);
            case BREAK_SWING -> animator.triggerBreak(identity);
            case DROP -> animator.triggerDrop(identity);
            case IDLE -> throw new IllegalArgumentException("IDLE is not an action");
        }
    }
}
