package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ViewerPresentationTest {
    @Test
    void normalValidatedSnapshotIsPresentationViable() throws Exception {
        var snapshot = ViewerFixtures.snapshot(false, false);

        ViewerCpuModel cpu = ViewerPresentation.prepare(snapshot, 1280, 720);

        assertEquals(snapshot.sourceSha256(), cpu.sourceSha256());
    }

    @Test
    void gpuUnrepresentableWorldTransformIsRejectedBeforePublication() throws Exception {
        var far = ViewerFixtures.result(ViewerFixtures.triangle(false, false, 1.0e100))
                .snapshot().orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> ViewerPresentation.prepare(far, 1280, 720));
    }

    @Test
    void unrepresentableReloadCandidateKeepsCurrentGpuAndReportsCpuRejection() throws Exception {
        var initial = ViewerFixtures.snapshot(false, false);
        var farResult = ViewerFixtures.result(ViewerFixtures.triangle(false, false, 1.0e100));
        AtomicInteger uploads = new AtomicInteger();
        var controller = new ViewerReloadController<TestGpu>(
                MainThreadGuard.captureCurrentThread(), () -> farResult,
                snapshot -> ViewerPresentation.prepare(snapshot, 1280, 720),
                cpu -> new TestGpu(uploads.incrementAndGet()));
        assertTrue(controller.loadInitial(ViewerFixtures.result(
                ViewerFixtures.triangle(false, false, 3))));
        TestGpu before = controller.current().orElseThrow().gpu();

        controller.requestReload();
        assertTrue(controller.reloadIfRequested());

        assertSame(before, controller.current().orElseThrow().gpu());
        assertEquals(ViewerReloadController.Code.CPU_REJECTED, controller.status().code());
        assertEquals(1, uploads.get());
        assertEquals(0, before.closes);
        controller.close();
        assertEquals(1, before.closes);
    }

    private static final class TestGpu implements ViewerReloadController.GpuResource {
        final int id;
        int closes;
        TestGpu(int id) { this.id = id; }
        @Override public void close() { closes++; }
    }
}
