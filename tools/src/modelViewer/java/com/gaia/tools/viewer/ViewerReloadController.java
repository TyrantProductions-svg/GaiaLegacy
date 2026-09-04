package com.gaia.tools.viewer;

import com.gaia.tools.model.GaiaGlbValidator;
import com.gaia.tools.model.ValidatedModelSnapshot;
import com.gaia.tools.model.ValidationReport;
import com.gaia.tools.model.ValidationReportWriter;
import com.overlord.core.thread.MainThreadGuard;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** One synchronous current/candidate transaction. It never parses or retries input. */
public final class ViewerReloadController<M extends ViewerReloadController.GpuResource>
        implements AutoCloseable {
    @FunctionalInterface public interface Loader { GaiaGlbValidator.Result load() throws IOException; }
    public interface GpuResource extends AutoCloseable { @Override void close(); }
    public enum Code { EMPTY, READY, VALIDATION_REJECTED, READ_FAILED, CPU_REJECTED, GPU_REJECTED, CLOSED }
    public record Status(Code code,String candidateSha256,String report) {
        public Status { Objects.requireNonNull(code);candidateSha256=Objects.requireNonNull(candidateSha256);
            report=Objects.requireNonNull(report); }
    }
    public record Current<T extends GpuResource>(ViewerCpuModel cpu,T gpu) {
        public Current {Objects.requireNonNull(cpu);Objects.requireNonNull(gpu);}
    }

    private final MainThreadGuard guard;
    private final Loader loader;
    private final Function<ValidatedModelSnapshot,ViewerCpuModel> projector;
    private final Function<ViewerCpuModel,M> uploader;
    private Current<M> current;
    private Status status=new Status(Code.EMPTY,"","");
    private boolean pending,closed;

    public ViewerReloadController(MainThreadGuard guard,Loader loader,
            Function<ValidatedModelSnapshot,ViewerCpuModel> projector,
            Function<ViewerCpuModel,M> uploader) {
        this.guard=Objects.requireNonNull(guard);this.loader=Objects.requireNonNull(loader);
        this.projector=Objects.requireNonNull(projector);this.uploader=Objects.requireNonNull(uploader);
    }
    public boolean loadInitial(GaiaGlbValidator.Result result) {
        owner("initial model");
        if(current!=null||closed)throw new IllegalStateException("initial model already decided");
        return replace(Objects.requireNonNull(result));
    }
    public void requestReload() {owner("reload request");if(!closed)pending=true;}
    public boolean reloadIfRequested() {
        owner("reload");if(closed||!pending)return false;pending=false;
        try {replace(loader.load());}
        catch(IOException rejected){status=new Status(Code.READ_FAILED,"","Input could not be read\n");}
        return true;
    }
    private boolean replace(GaiaGlbValidator.Result result) {
        String hash=result.sourceSha256();
        if(result.report().outcome()== ValidationReport.Outcome.FAIL||result.snapshot().isEmpty()) {
            status=new Status(Code.VALIDATION_REJECTED,hash,ValidationReportWriter.text(result));return false;
        }
        ViewerCpuModel cpu;
        try {cpu=projector.apply(result.snapshot().orElseThrow());}
        catch(RuntimeException rejected){status=new Status(Code.CPU_REJECTED,hash,"Validated data could not be packed for this GPU\n");return false;}
        M gpu;
        try {gpu=Objects.requireNonNull(uploader.apply(cpu),"uploader returned null");}
        catch(RuntimeException rejected){status=new Status(Code.GPU_REJECTED,hash,"GPU candidate upload failed\n");return false;}
        Current<M> previous=current; current=new Current<>(cpu,gpu);
        status=new Status(Code.READY,"",ValidationReportWriter.text(result));
        if(previous!=null)previous.gpu().close();
        return true;
    }
    public Optional<Current<M>> current(){return Optional.ofNullable(current);}
    public String currentSha256(){return current==null?"":current.cpu().sourceSha256();}
    public Status status(){return status;}
    public int liveCurrentCount(){return current==null?0:1;}
    public int liveCandidateCount(){return 0;}
    @Override public void close(){owner("viewer reload close");if(closed)return;closed=true;pending=false;
        try {if(current!=null)current.gpu().close();} finally {current=null;status=new Status(Code.CLOSED,"","");}}
    private void owner(String operation){guard.assertMainThread(operation);}
}
