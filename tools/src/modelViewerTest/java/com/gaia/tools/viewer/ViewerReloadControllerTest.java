package com.gaia.tools.viewer;

import com.overlord.core.thread.MainThreadGuard;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewerReloadControllerTest {
    @Test void validationFailureKeepsCurrentAndReportsCandidateHash() throws Exception {
        var valid=ViewerFixtures.result(ViewerFixtures.triangle(false,false,3));
        var invalid=ViewerFixtures.invalidScaleResult();
        var first=new Resource();
        var loader=new AtomicReference<ViewerReloadController.Loader>(() -> valid);
        var controller=controller(valid,first,loader,new AtomicReference<>(ViewerCpuModel::from),
                new AtomicReference<>(cpu->first));
        loader.set(()->invalid);
        controller.requestReload();
        assertTrue(controller.reloadIfRequested());
        assertSame(first,controller.current().orElseThrow().gpu());
        assertEquals(valid.sourceSha256(),controller.currentSha256());
        assertEquals(invalid.sourceSha256(),controller.status().candidateSha256());
        assertEquals(ViewerReloadController.Code.VALIDATION_REJECTED,controller.status().code());
        assertEquals(0,first.closes);
    }

    @Test void cpuAndGpuFailuresNeverPublishOrDestroyCurrent() throws Exception {
        var valid=ViewerFixtures.result(ViewerFixtures.triangle(false,false,3));
        var first=new Resource();
        var loader=new AtomicReference<ViewerReloadController.Loader>(() -> valid);
        var projector=new AtomicReference<java.util.function.Function<com.gaia.tools.model.ValidatedModelSnapshot,ViewerCpuModel>>(ViewerCpuModel::from);
        var uploader=new AtomicReference<java.util.function.Function<ViewerCpuModel,Resource>>(cpu->first);
        var controller=controller(valid,first,loader,projector,uploader);
        projector.set(snapshot->{throw new IllegalArgumentException("test CPU rejection");});
        controller.requestReload(); controller.reloadIfRequested();
        assertSame(first,controller.current().orElseThrow().gpu());
        assertEquals(ViewerReloadController.Code.CPU_REJECTED,controller.status().code());
        projector.set(ViewerCpuModel::from);
        uploader.set(cpu->{throw new IllegalStateException("test upload rejection");});
        controller.requestReload(); controller.reloadIfRequested();
        assertSame(first,controller.current().orElseThrow().gpu());
        assertEquals(ViewerReloadController.Code.GPU_REJECTED,controller.status().code());
        assertEquals(0,first.closes);
    }

    @Test void successfulCandidatePublishesOnceBeforeOldDestruction() throws Exception {
        var firstResult=ViewerFixtures.result(ViewerFixtures.triangle(false,false,3));
        var nextResult=ViewerFixtures.result(ViewerFixtures.triangle(false,false,7));
        var first=new Resource(); var next=new Resource();
        var loader=new AtomicReference<ViewerReloadController.Loader>(() -> firstResult);
        var uploader=new AtomicReference<java.util.function.Function<ViewerCpuModel,Resource>>(cpu->first);
        var controller=controller(firstResult,first,loader,new AtomicReference<>(ViewerCpuModel::from),uploader);
        var duringClose=new AtomicReference<String>();
        first.onClose=()->duringClose.set(controller.currentSha256());
        loader.set(()->nextResult); uploader.set(cpu->next);
        controller.requestReload(); assertTrue(controller.reloadIfRequested());
        assertSame(next,controller.current().orElseThrow().gpu());
        assertEquals(nextResult.sourceSha256(),controller.currentSha256());
        assertEquals(nextResult.sourceSha256(),duringClose.get());
        assertEquals(1,first.closes); assertEquals(0,next.closes);
        assertEquals(ViewerReloadController.Code.READY,controller.status().code());
    }

    @Test void requestsCoalesceAndNoHistoryIsRetained() throws Exception {
        var valid=ViewerFixtures.result(ViewerFixtures.triangle(false,false,3));
        var first=new Resource(); var loads=new AtomicInteger(); var uploaded=new ArrayList<Resource>();
        var loader=new AtomicReference<ViewerReloadController.Loader>(() -> valid);
        var uploader=new AtomicReference<java.util.function.Function<ViewerCpuModel,Resource>>(cpu->first);
        var controller=controller(valid,first,loader,new AtomicReference<>(ViewerCpuModel::from),uploader);
        loader.set(()->{loads.incrementAndGet();return valid;});
        uploader.set(cpu->{var resource=new Resource();uploaded.add(resource);return resource;});
        for(int i=0;i<20;i++) controller.requestReload();
        assertTrue(controller.reloadIfRequested()); assertFalse(controller.reloadIfRequested());
        assertEquals(1,loads.get()); assertEquals(1,uploaded.size()); assertEquals(1,first.closes);
        assertEquals(1,controller.liveCurrentCount()); assertEquals(0,controller.liveCandidateCount());
    }

    @Test void ioFailureAndCloseAfterFailureLeaveNoCandidate() throws Exception {
        var valid=ViewerFixtures.result(ViewerFixtures.triangle(false,false,3));
        var first=new Resource(); var loader=new AtomicReference<ViewerReloadController.Loader>(() -> valid);
        var controller=controller(valid,first,loader,new AtomicReference<>(ViewerCpuModel::from),
                new AtomicReference<>(cpu->first));
        loader.set(()->{throw new IOException("test only");});
        controller.requestReload(); controller.reloadIfRequested();
        assertEquals(ViewerReloadController.Code.READ_FAILED,controller.status().code());
        assertEquals("",controller.status().candidateSha256());
        controller.close(); controller.close();
        assertEquals(1,first.closes); assertTrue(controller.current().isEmpty());
        assertEquals(0,controller.liveCurrentCount()); assertEquals(0,controller.liveCandidateCount());
        controller.requestReload(); assertFalse(controller.reloadIfRequested());
    }

    private static ViewerReloadController<Resource> controller(
            com.gaia.tools.model.GaiaGlbValidator.Result initial,Resource first,
            AtomicReference<ViewerReloadController.Loader> loader,
            AtomicReference<java.util.function.Function<com.gaia.tools.model.ValidatedModelSnapshot,ViewerCpuModel>> projector,
            AtomicReference<java.util.function.Function<ViewerCpuModel,Resource>> uploader) {
        var controller=new ViewerReloadController<Resource>(MainThreadGuard.captureCurrentThread(),
                ()->loader.get().load(),snapshot->projector.get().apply(snapshot),cpu->uploader.get().apply(cpu));
        assertTrue(controller.loadInitial(initial)); return controller;
    }

    static final class Resource implements ViewerReloadController.GpuResource {
        int closes; Runnable onClose=()->{};
        @Override public void close() { if(++closes>1) throw new AssertionError("double close");onClose.run(); }
    }
}
