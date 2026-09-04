package com.gaia.tools.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Optional;

/** Sole headless HAND_TOOL_V0 coordinator. No IO except the explicit caller stream. */
public final class GaiaGlbValidator {
    public record Statistics(long uniqueTriangleCount,long uniqueVertexCount,long expandedTriangleCount,
            long expandedVertexCount,int meshCount,int primitiveCount,int nodeCount,int hierarchyDepth) { }
    public static final class Result {
        private final String sha;
        private final ValidationReport report;
        private final Statistics statistics;
        private final ValidatedModelSnapshot snapshot;
        private Result(String sha,ValidationReport report,Statistics statistics,ValidatedModelSnapshot snapshot) {
            if(report.outcome()==ValidationReport.Outcome.FAIL && snapshot!=null) throw new IllegalArgumentException("Failed model cannot publish snapshot");
            this.sha=sha;this.report=report;this.statistics=statistics;this.snapshot=snapshot;
        }
        public String sourceSha256() {return sha;}
        public ValidationReport report() {return report;}
        public Statistics statistics() {return statistics;}
        public Optional<ValidatedModelSnapshot> snapshot() {return Optional.ofNullable(snapshot);}
    }
    private GaiaGlbValidator() { }
    public static Result validate(InputStream input) throws IOException {
        return validate(input,reader->reader.read(0));
    }
    static Result validate(InputStream input,EmbeddedImages.PixelDecoder decoder) throws IOException {
        var log=new ValidationReport.Collector();var budget=new HandToolProfile.GeometryBudget();
        GlbAdmission.Admitted admitted;
        try {admitted=GlbAdmission.admit(input);}
        catch(PreflightException reject) {log.error("ADMISSION_"+reject.code(),"/","Structural admission rejected");return result("",log,budget,0,0,0,0,null);}
        var doc=admitted.document();String sha=admitted.receipt().sha256();
        GeometryChecks.attributes(doc,log);
        if(failed(log)) return result(sha,log,budget,0,0,0,0,null);
        int meshes=doc.getMeshes().size(),nodes=doc.getNodes()==null?0:doc.getNodes().size(),primitiveCount=0,depth=0;
        ValidatedModelSnapshot snapshot=null;
        try {
            var data=new BufferAccess(admitted);
            var scene=SceneChecks.analyze(doc,log);depth=scene==null?0:scene.depth();
            var descriptions=GeometryChecks.count(doc,data,scene,budget,log);primitiveCount=descriptions.size();
            for(var d:budget.report().diagnostics()) {
                if(d.severity()==ValidationReport.Severity.ERROR) log.error(d.code(),d.path(),d.message());else log.warning(d.code(),d.path(),d.message());
            }
            var materials=MaterialTextureChecks.analyze(doc,log);
            if(!failed(log)) {
                var primitives=GeometryChecks.decode(descriptions,data,log);
                if(!failed(log)) {
                    var images=EmbeddedImages.decode(doc,data,log,decoder);
                    if(failed(log)) return result(sha,log,budget,meshes,primitiveCount,nodes,depth,null);
                    var bounds=GeometryChecks.bounds(primitives,scene);
                    var placed=new ArrayList<ValidatedModelSnapshot.Node>();
                    for(var n:scene.nodes()) {var source=doc.getNodes().get(n.node());placed.add(new ValidatedModelSnapshot.Node(n.node(),n.parent(),source.getMesh()==null?-1:source.getMesh(),source.getName(),n.transform().values()));}
                    snapshot=new ValidatedModelSnapshot(sha,primitives,placed,bounds,materials.materials(),materials.textures(),images);
                }
            }
        } catch(IllegalArgumentException|ArithmeticException|NullPointerException rejected) {log.error("SEMANTIC_INVALID","/","Invalid bounded model data");}
        return result(sha,log,budget,meshes,primitiveCount,nodes,depth,snapshot);
    }
    private static boolean failed(ValidationReport.Collector log) {return log.report().outcome()==ValidationReport.Outcome.FAIL;}
    private static Result result(String sha,ValidationReport.Collector log,HandToolProfile.GeometryBudget b,int meshes,int primitives,int nodes,int depth,ValidatedModelSnapshot snapshot) {
        return new Result(sha,log.report(),new Statistics(b.uniqueTriangles(),b.uniqueVertices(),b.expandedTriangles(),b.expandedVertices(),meshes,primitives,nodes,depth),snapshot);
    }
}
