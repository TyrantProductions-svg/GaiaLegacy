package com.gaia.tools.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Formatting only: no validation rules, paths, timestamps or geometry payloads. */
public final class ValidationReportWriter {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ValidationReportWriter() { }
    public static String json(GaiaGlbValidator.Result result) {
        var root=JSON.createObjectNode();root.put("profile",HandToolProfile.ID).put("profileVersion",HandToolProfile.VERSION);
        if(result.sourceSha256().isEmpty())root.putNull("sourceSha256");else root.put("sourceSha256",result.sourceSha256());
        root.put("outcome",result.report().outcome().name());var s=result.statistics();var stats=root.putObject("statistics");
        stats.put("uniqueTriangleCount",s.uniqueTriangleCount()).put("uniqueVertexCount",s.uniqueVertexCount())
                .put("expandedTriangleCount",s.expandedTriangleCount()).put("expandedVertexCount",s.expandedVertexCount())
                .put("meshCount",s.meshCount()).put("primitiveCount",s.primitiveCount()).put("nodeCount",s.nodeCount()).put("hierarchyDepth",s.hierarchyDepth());
        if(result.snapshot().isEmpty())root.putNull("bounds");
        else {
            var b=result.snapshot().orElseThrow().bounds();var bounds=root.putObject("bounds");
            var min=bounds.putArray("min");for(double v:b.min())min.add(v);
            var max=bounds.putArray("max");for(double v:b.max())max.add(v);
            var dimensions=bounds.putArray("dimensions");for(double v:b.dimensions())dimensions.add(v);
        }
        var diagnostics=root.putArray("diagnostics");
        for(var d:result.report().diagnostics())diagnostics.addObject().put("severity",d.severity().name()).put("code",d.code()).put("path",d.path()).put("message",d.message());
        root.put("truncated",result.report().truncated());
        try {return JSON.writeValueAsString(root);}
        catch(JsonProcessingException impossible) {throw new IllegalStateException("Cannot encode validated report",impossible);}
    }
    public static String text(GaiaGlbValidator.Result result) {
        StringBuilder out=new StringBuilder(HandToolProfile.ID).append(" v").append(HandToolProfile.VERSION).append(": ").append(result.report().outcome()).append('\n');
        out.append("SHA-256: ").append(result.sourceSha256().isEmpty()?"unavailable (admission rejected)":result.sourceSha256()).append('\n');
        var s=result.statistics();out.append("Triangles unique/expanded: ").append(s.uniqueTriangleCount()).append('/').append(s.expandedTriangleCount()).append('\n');
        out.append("Vertices unique/expanded: ").append(s.uniqueVertexCount()).append('/').append(s.expandedVertexCount()).append('\n');
        for(var d:result.report().diagnostics())out.append(d.severity()).append(' ').append(escape(d.code())).append(' ').append(escape(d.path())).append(": ").append(escape(d.message())).append('\n');
        if(result.report().truncated())out.append("Diagnostics truncated\n");
        out.append("Validation is not artistic, production or runtime approval.\n");return out.toString();
    }
    private static String escape(String value) {
        var text=new StringBuilder(value.length());
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c<=0x1f || c>=0x7f && c<=0x9f || c==0x2028 || c==0x2029) {
                text.append("\\u");
                for(int shift=12;shift>=0;shift-=4)text.append("0123456789ABCDEF".charAt((c>>>shift)&15));
            } else text.append(c);
        }
        return text.toString();
    }
}
