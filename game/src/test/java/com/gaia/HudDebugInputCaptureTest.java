package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.ui.HudDebugSnapshot;
import com.overlord.physics.Aabb;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class HudDebugInputCaptureTest {
    private static final RenderMetricsSnapshot FIRST_METRICS =
            new RenderMetricsSnapshot(60, 16.67, 11, 12, 13, 14);
    private static final RenderMetricsSnapshot SECOND_METRICS =
            new RenderMetricsSnapshot(75, 13.33, 21, 22, 23, 24);

    @Test
    void copiesAuthoritativeBodyFeetAndPublishesMetricsOnlyAfterSuccessfulRenderCompletion() {
        PhysicsBody body = bodyAt(4.25f, 7.5f, -2.75f);
        Vector3f divergentCamera = new Vector3f(100, 200, 300);
        AtomicReference<RenderMetricsSnapshot> currentMetrics =
                new AtomicReference<>(FIRST_METRICS);
        AtomicInteger metricsCalls = new AtomicInteger();
        FrameDebugInputCapture capture = new FrameDebugInputCapture(
                body,
                () -> {
                    metricsCalls.incrementAndGet();
                    return currentMetrics.get();
                });

        FrameDebugInputCapture.CapturedInput firstFrame = capture.capture();

        assertTrue(firstFrame.previousFrameMetrics().isEmpty());
        assertEquals(new HudDebugSnapshot.FeetPosition(4.25, 7.5, -2.75), firstFrame.feet());
        assertEquals(0, metricsCalls.get());
        assertNotEquals(
                new HudDebugSnapshot.FeetPosition(
                        divergentCamera.x, divergentCamera.y, divergentCamera.z),
                firstFrame.feet());

        capture.recordCompletedRender();
        currentMetrics.set(SECOND_METRICS);
        body.teleport(new Vector3f(-1.5f, 2.25f, 9.75f));
        FrameDebugInputCapture.CapturedInput secondFrame = capture.capture();

        assertEquals(FIRST_METRICS, secondFrame.previousFrameMetrics().orElseThrow());
        assertEquals(new HudDebugSnapshot.FeetPosition(-1.5, 2.25, 9.75), secondFrame.feet());
        assertEquals(1, metricsCalls.get());

        capture.recordCompletedRender();
        FrameDebugInputCapture.CapturedInput thirdFrame = capture.capture();

        assertEquals(SECOND_METRICS, thirdFrame.previousFrameMetrics().orElseThrow());
        assertEquals(2, metricsCalls.get());
    }

    private static PhysicsBody bodyAt(float x, float y, float z) {
        PhysicsBody body = new PhysicsBody(
                new Aabb(-0.3f, 0, -0.3f, 0.3f, 1.8f, 0.3f),
                MassProperties.dynamic(1));
        body.teleport(new Vector3f(x, y, z));
        return body;
    }
}
