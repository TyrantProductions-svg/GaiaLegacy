package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.feedback.CameraImpulseVisual;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CameraImpulseControllerTest {
    @ParameterizedTest
    @ValueSource(ints = {10, 30, 60, 144, 240})
    void placementEnvelopeHasApprovedPeakAndSettlesByFrameQuantizedDeadline(int fps) {
        CameraImpulseController controller = new CameraImpulseController();

        controller.triggerPlacement(31L);
        CameraImpulseVisual peak = controller.snapshot();

        assertTrue(peak.pitchDegrees() >= 0.30f && peak.pitchDegrees() <= 0.40f);
        assertTrue(peak.translationY() <= -0.0045f && peak.translationY() >= -0.0075f);
        assertEquals(0.0f, peak.yawDegrees());

        double elapsed = 0.0;
        double frame = 1.0 / fps;
        while (elapsed + 1.0e-12 < 0.15) {
            controller.update(frame);
            elapsed += frame;
        }
        assertEquals(CameraImpulseVisual.identity(), controller.snapshot());
        assertTrue(elapsed <= 0.15 + frame + 1.0e-9);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 30, 60, 144, 240})
    void breakEnvelopeHasApprovedPitchYawAndSettlesByFrameQuantizedDeadline(int fps) {
        CameraImpulseController controller = new CameraImpulseController();

        controller.triggerBreak(17L);
        CameraImpulseVisual peak = controller.snapshot();

        assertTrue(peak.pitchDegrees() >= 0.48f && peak.pitchDegrees() <= 0.62f);
        assertTrue(Math.abs(peak.yawDegrees()) >= 0.11f
                && Math.abs(peak.yawDegrees()) <= 0.17f);
        assertEquals(0.0f, peak.translationY());

        double elapsed = 0.0;
        double frame = 1.0 / fps;
        while (elapsed + 1.0e-12 < 0.20) {
            controller.update(frame);
            elapsed += frame;
        }
        assertEquals(CameraImpulseVisual.identity(), controller.snapshot());
        assertTrue(elapsed <= 0.20 + frame + 1.0e-9);
    }

    @Test
    void committedImpulsesAreDeterministicBoundedAndSettleExactlyToZero() {
        CameraImpulseController first = new CameraImpulseController();
        CameraImpulseController second = new CameraImpulseController();
        first.triggerBreak(17L);
        second.triggerBreak(17L);
        first.update(1.0 / 60.0);
        second.update(1.0 / 60.0);
        assertEquals(first.snapshot(), second.snapshot());
        assertTrue(Math.abs(first.snapshot().pitchDegrees()) <= 1.0f);
        assertTrue(Math.abs(first.snapshot().yawDegrees()) <= 1.0f);

        for (int index = 0; index < 80; index++) {
            first.triggerPlacement(index);
            first.update(1.0 / 240.0);
            assertTrue(Math.abs(first.snapshot().pitchDegrees()) <= 1.0f);
            assertTrue(Math.abs(first.snapshot().yawDegrees()) <= 1.0f);
            assertTrue(Math.abs(first.snapshot().translationY()) <= 0.025f);
        }
        for (int index = 0; index < 600; index++) {
            first.update(1.0 / 120.0);
        }
        assertEquals(CameraImpulseVisual.identity(), first.snapshot());
    }

    @Test
    void renderViewApplicationNeverMutatesCanonicalViewAndResetClearsImmediately() {
        CameraImpulseController controller = new CameraImpulseController();
        Matrix4f canonical = new Matrix4f().lookAt(
                1, 2, 3, 1, 2, 2, 0, 1, 0);
        Matrix4f before = new Matrix4f(canonical);

        controller.triggerPlacement(5L);
        controller.update(1.0 / 60.0);
        Matrix4f visual = controller.applyToView(canonical);

        assertEquals(before, canonical);
        assertTrue(!visual.equals(canonical));
        controller.reset();
        assertEquals(CameraImpulseVisual.identity(), controller.snapshot());
        assertEquals(canonical, controller.applyToView(canonical));
    }

    @Test
    void closeIsIdempotentAndCommittedEventsCannotResurrectImpulse() {
        CameraImpulseController controller = new CameraImpulseController();
        controller.triggerBreak(4L);

        controller.close();
        controller.close();
        controller.triggerPlacement(5L);
        controller.triggerBreak(6L);
        controller.update(1.0);

        assertEquals(CameraImpulseVisual.identity(), controller.snapshot());
    }
}
