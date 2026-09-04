package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InspectorGpuModelTest {
    @Test
    void uploadsOneMeshPerPrimitiveOneTexturePerImageAndOneSamplerPerTexture() throws Exception {
        FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(true, false));

        InspectorGpuModel model = InspectorGpuModel.upload(cpu, backend);

        assertEquals(6, model.handleCount());
        assertEquals(112, model.estimatedBytes());
        assertEquals(1, model.primitives().size());
        assertEquals(3, model.primitives().get(0).indexCount());
        assertEquals(32, backend.stride);
        assertArrayEquals(new int[]{0, 12, 24}, backend.offsets);
        assertEquals(1, backend.textureUploads);
        assertArrayEquals(new byte[]{17, 85, (byte) 204, (byte) 255}, backend.rgba);
        assertEquals(1, backend.mipmapGenerations);
        assertEquals(List.of(
                new SamplerConfig(9728, 9728, 33071, 33648),
                new SamplerConfig(9729, 9987, 10497, 10497)), backend.samplers);
    }

    @Test
    void closeDeletesAllHandlesInReverseAcquisitionOrderAndIsIdempotent() throws Exception {
        FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);
        InspectorGpuModel model = InspectorGpuModel.upload(
                ViewerCpuModel.from(ViewerFixtures.snapshot(true, false)), backend);
        List<Integer> acquired = List.copyOf(backend.acquired);

        model.close();
        model.close();

        List<Integer> reverse = new ArrayList<>(acquired);
        java.util.Collections.reverse(reverse);
        assertEquals(reverse, backend.deleted);
        assertEquals(0, backend.live.size());
        assertEquals(0, model.handleCount());
    }

    @Test
    void everyPartialCreateOrUploadFailureRollsBackAllAcquiredHandles() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(true, false));
        FakeResources probe = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);
        InspectorGpuModel successful = InspectorGpuModel.upload(cpu, probe);
        successful.close();

        for (int failure = 1; failure <= probe.operations; failure++) {
            FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), failure);
            assertThrows(RuntimeException.class, () -> InspectorGpuModel.upload(cpu, backend),
                    "operation " + failure + " must reject");
            assertEquals(0, backend.live.size(), "operation " + failure + " leaked handles");
            List<Integer> reverse = new ArrayList<>(backend.acquired);
            java.util.Collections.reverse(reverse);
            assertEquals(reverse, backend.deleted, "operation " + failure + " cleanup order");
        }
    }

    @Test
    void uploadRejectsNonOwnerThreadWithoutTouchingBackend() throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        FakeResources backend = new FakeResources(guard, -1);
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { InspectorGpuModel.upload(cpu, backend); }
            catch (Throwable rejected) { failure.set(rejected); }
        }, "gpu-test-worker");
        worker.start(); worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, backend.operations);
        assertEquals(0, backend.live.size());
    }

    @Test
    void closeRejectsNonOwnerThreadWithoutPartialDestructionThenOwnerClosesExactlyOnce()
            throws Exception {
        FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);
        InspectorGpuModel model = InspectorGpuModel.upload(
                ViewerCpuModel.from(ViewerFixtures.snapshot(true, false)), backend);
        List<Integer> acquired = List.copyOf(backend.acquired);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { model.close(); }
            catch (Throwable rejected) { failure.set(rejected); }
        }, "gpu-close-test-worker");
        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(acquired.size(), model.handleCount());
        assertTrue(backend.deleted.isEmpty());
        assertEquals(new HashSet<>(acquired), backend.live);

        model.close();
        model.close();

        List<Integer> reverse = new ArrayList<>(acquired);
        java.util.Collections.reverse(reverse);
        assertEquals(reverse, backend.deleted);
        assertEquals(0, backend.live.size());
    }

    @Test
    void nonThrowingUploadWithOpenGlErrorRejectsAndRollsBackBeforePublication() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        FakeResources backend = new FakeResources(
                MainThreadGuard.captureCurrentThread(), -1, true);

        assertThrows(RuntimeException.class, () -> InspectorGpuModel.upload(cpu, backend));

        assertTrue(backend.errorChecks >= 4);
        assertEquals(0, backend.live.size());
        List<Integer> reverse = new ArrayList<>(backend.acquired);
        java.util.Collections.reverse(reverse);
        assertEquals(reverse, backend.deleted);
    }

    @Test
    void preExistingGlErrorRejectsBeforeCandidateResourcesAreCreated() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);
        backend.queueGlErrors(0x0500, 0x0501);

        assertThrows(RuntimeException.class, () -> InspectorGpuModel.upload(cpu, backend));

        assertTrue(backend.acquired.isEmpty());
        assertTrue(backend.deleted.isEmpty());
        assertEquals(0, backend.live.size());
        backend.assertNoError("preserved current render");
    }

    @Test
    void oneTwoAndThreeCandidateErrorsAreFullyDrainedBeforeCurrentResumes() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        for (int errorCount = 1; errorCount <= 3; errorCount++) {
            FakeResources backend = new FakeResources(
                    MainThreadGuard.captureCurrentThread(), -1, errorCount, false);

            assertThrows(RuntimeException.class, () -> InspectorGpuModel.upload(cpu, backend));

            assertEquals(0, backend.live.size());
            backend.assertNoError("preserved current render");
        }
    }

    @Test
    void cleanupErrorsAreDrainedAndAttachedWithoutLeakingIntoCurrent() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        FakeResources backend = new FakeResources(
                MainThreadGuard.captureCurrentThread(), -1, 1, true);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> InspectorGpuModel.upload(cpu, backend));

        assertTrue(failure.getSuppressed().length > 0,
                "cleanup GL failure must remain explicit");
        assertEquals(0, backend.live.size());
        backend.assertNoError("preserved current render");
    }

    @Test
    void estimatedBytesIncludesEachGeneratedMipmapLevelOnce() throws Exception {
        var validation = ViewerFixtures.result(
                ViewerFixtures.triangle(true, false, 0, 2, 2));
        ViewerCpuModel cpu = ViewerCpuModel.from(validation.snapshot().orElseThrow());
        FakeResources backend = new FakeResources(MainThreadGuard.captureCurrentThread(), -1);

        InspectorGpuModel model = InspectorGpuModel.upload(cpu, backend);

        assertEquals(128, model.estimatedBytes());
        assertEquals(1, backend.mipmapGenerations);
        model.close();
    }

    private record SamplerConfig(int mag, int min, int wrapS, int wrapT) { }

    private static final class FakeResources implements ViewerGlResources {
        private final MainThreadGuard guard;
        private final int failAt;
        private int uploadErrorCount;
        private final boolean cleanupLeavesGlError;
        private int nextHandle = 1;
        int operations;
        int stride;
        int[] offsets;
        int textureUploads;
        int mipmapGenerations;
        int errorChecks;
        int glErrorReads;
        byte[] rgba;
        final ArrayDeque<Integer> glErrors = new ArrayDeque<>();
        final List<Integer> acquired = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>();
        final Set<Integer> live = new HashSet<>();
        final List<SamplerConfig> samplers = new ArrayList<>();

        FakeResources(MainThreadGuard guard, int failAt) { this(guard, failAt, 0, false); }
        FakeResources(MainThreadGuard guard, int failAt, boolean uploadLeavesGlError) {
            this(guard, failAt, uploadLeavesGlError ? 1 : 0, false);
        }
        FakeResources(MainThreadGuard guard, int failAt, int uploadErrorCount,
                boolean cleanupLeavesGlError) {
            this.guard = guard;
            this.failAt = failAt;
            this.uploadErrorCount = uploadErrorCount;
            this.cleanupLeavesGlError = cleanupLeavesGlError;
        }
        @Override public void assertOwner(String operation) { guard.assertMainThread(operation); }
        @Override public void assertNoError(String operation) {
            errorChecks++;
            OpenGlViewerResources.assertNoErrors(operation, () -> {
                glErrorReads++;
                return glErrors.isEmpty() ? 0 : glErrors.removeFirst();
            });
        }
        @Override public int createVertexArray() { return create(); }
        @Override public int createBuffer() { return create(); }
        @Override public int createTexture() { return create(); }
        @Override public int createSampler() { return create(); }
        private int create() { step(); int handle=nextHandle++; acquired.add(handle); live.add(handle); return handle; }
        @Override public void uploadMesh(int vao,int vbo,int ebo,float[] vertices,int[] indices,
                int stride,int positionOffset,int normalOffset,int uvOffset) {
            step(); this.stride=stride; offsets=new int[]{positionOffset,normalOffset,uvOffset};
            for (int index = 0; index < uploadErrorCount; index++) {
                glErrors.add(0x0500 + index);
            }
            uploadErrorCount = 0;
        }
        @Override public void uploadSrgbTexture(int texture,int width,int height,byte[] rgba) {
            step(); textureUploads++; this.rgba=rgba.clone();
        }
        @Override public void generateMipmaps(int texture) { step(); mipmapGenerations++; }
        @Override public void configureSampler(int sampler,int mag,int min,int wrapS,int wrapT) {
            step(); samplers.add(new SamplerConfig(mag,min,wrapS,wrapT));
        }
        @Override public void deleteVertexArray(int handle) { delete(handle); }
        @Override public void deleteBuffer(int handle) { delete(handle); }
        @Override public void deleteTexture(int handle) { delete(handle); }
        @Override public void deleteSampler(int handle) { delete(handle); }
        private void delete(int handle) {
            guard.assertMainThread("delete");
            deleted.add(handle);
            live.remove(handle);
            if (cleanupLeavesGlError) glErrors.add(0x0505);
        }
        void queueGlErrors(int... errors) {
            for (int error : errors) glErrors.add(error);
        }
        private void step() { guard.assertMainThread("GPU operation"); operations++; if(operations==failAt)throw new IllegalStateException("injected failure"); }
    }
}
