package com.gaia.tools.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Owned deterministic GLB edits, never downloaded model data. */
final class SemanticFixtures {
    final ObjectNode json;
    byte[] bin;
    SemanticFixtures() throws Exception {
        var checked=GlbPreflight.read(new ByteArrayInputStream(GlbFixtures.triangle()));
        json=(ObjectNode)new ObjectMapper().readTree(checked.openJsonStream());
        bin=new byte[78]; checked.binaryData().get(bin);
    }
    ObjectNode primitive() { return (ObjectNode)json.at("/meshes/0/primitives/0"); }
    ObjectNode attributes() { return (ObjectNode)primitive().get("attributes"); }
    ObjectNode accessor(int i) { return (ObjectNode)json.withArray("accessors").get(i); }
    ObjectNode node() { return (ObjectNode)json.at("/nodes/0"); }
    int floats(float[] values,String type,int count) {
        int offset=(bin.length+3)&~3;
        bin=Arrays.copyOf(bin,offset+values.length*4);
        var buffer=ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN); buffer.position(offset);
        for(float value:values) buffer.putFloat(value);
        int view=json.withArray("bufferViews").size();
        json.withArray("bufferViews").addObject().put("buffer",0).put("byteOffset",offset).put("byteLength",values.length*4);
        int index=json.withArray("accessors").size();
        json.withArray("accessors").addObject().put("bufferView",view).put("componentType",5126).put("count",count).put("type",type);
        return index;
    }
    void uv() { attributes().put("TEXCOORD_0",floats(new float[]{0,0,1,0,0,1},"VEC2",3)); }
    void scalar(int byteOffset,float value) { ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).putFloat(byteOffset,value); }
    int image(byte[] encoded,String mime) {
        int offset=(bin.length+3)&~3;bin=Arrays.copyOf(bin,offset+encoded.length);
        System.arraycopy(encoded,0,bin,offset,encoded.length);
        int view=json.withArray("bufferViews").size();json.withArray("bufferViews").addObject().put("buffer",0).put("byteOffset",offset).put("byteLength",encoded.length);
        int index=json.withArray("images").size();json.withArray("images").addObject().put("bufferView",view).put("mimeType",mime);return index;
    }
    void repeatTriangles(int triangles) {
        int offset=(bin.length+3)&~3; bin=Arrays.copyOf(bin,offset+triangles*6);
        var b=ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);b.position(offset);
        for(int i=0;i<triangles;i++) b.putShort((short)0).putShort((short)1).putShort((short)2);
        var view=(ObjectNode)json.at("/bufferViews/2");view.put("byteOffset",offset).put("byteLength",triangles*6);
        accessor(2).put("count",triangles*3);
    }
    void twoInstances() {
        node().remove("mesh");node().putArray("children").add(1).add(2);
        json.withArray("nodes").addObject().put("mesh",0);
        json.withArray("nodes").addObject().put("mesh",0);
    }
    byte[] bytes() {
        ((ObjectNode)json.at("/buffers/0")).put("byteLength",bin.length);
        return GlbFixtures.container(GlbFixtures.chunk(GlbFixtures.JSON,json.toString().getBytes(StandardCharsets.UTF_8)),GlbFixtures.chunk(GlbFixtures.BIN,bin));
    }
}
