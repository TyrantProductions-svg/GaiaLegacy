package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.BufferView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Range validation precedes bounded decode. Never creates an upstream GltfModel. */
final class BufferAccess {
    private final List<Accessor> accessors;
    private final List<BufferView> views;
    private final ByteBuffer bin;
    private final int declaredBytes;

    BufferAccess(GlbAdmission.Admitted admitted) {
        var doc=admitted.document(); bin=admitted.binary();
        var buffers=doc.getBuffers()==null ? List.<de.javagl.jgltf.impl.v2.Buffer>of() : doc.getBuffers();
        require(buffers.size() <= 1);
        if (buffers.isEmpty()) { declaredBytes=0; require(bin.remaining()==0); }
        else {
            require(buffers.get(0)!=null && buffers.get(0).getByteLength()!=null);
            declaredBytes=buffers.get(0).getByteLength();
            require(declaredBytes>0 && declaredBytes<=bin.remaining() && bin.remaining()-declaredBytes<=3);
            for(int i=declaredBytes;i<bin.remaining();i++) { require(bin.get(i)==0); }
        }
        views=doc.getBufferViews()==null ? List.of() : doc.getBufferViews();
        accessors=doc.getAccessors()==null ? List.of() : doc.getAccessors();
        for(BufferView view:views) {
            require(view!=null && view.getBuffer()!=null && view.getBuffer()==0 && !buffers.isEmpty());
            require(view.getByteLength()!=null && view.getByteLength()>0);
            range(offset(view.getByteOffset()), view.getByteLength(),declaredBytes);
            if(view.getByteStride()!=null) {
                int s=view.getByteStride(); require(s>=4 && s<=252 && s%4==0);
            }
            require(view.getTarget()==null || view.getTarget()==34962 || view.getTarget()==34963);
        }
        for(Accessor accessor:accessors) { layout(accessor); }
        usage(doc);
    }

    Accessor accessor(int index) { require(index>=0 && index<accessors.size()); return accessors.get(index); }
    BufferView viewDefinition(int index) { require(index>=0 && index<views.size()); return views.get(index); }
    ByteBuffer view(int index) {
        BufferView v=viewDefinition(index);
        return bin.slice(offset(v.getByteOffset()),v.getByteLength()).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    double[] numbers(int index, String expectedType, int maxCount, boolean vertex) {
        Accessor a=accessor(index); Layout l=layout(a);
        require(expectedType.equals(a.getType()) && a.getCount()<=maxCount
                && a.getCount()<=HandToolProfile.MAX_VERTICES);
        if(vertex) {
            require(offset(a.getByteOffset())%4==0 && l.stride%4==0);
            if(a.getBufferView()!=null) { require(viewDefinition(a.getBufferView()).getTarget()==null
                    || viewDefinition(a.getBufferView()).getTarget()==34962); }
        }
        int size=Math.toIntExact(Math.multiplyExact((long)a.getCount(), l.components));
        double[] out=new double[size];
        for(int i=0;i<a.getCount();i++) {
            for(int c=0;c<l.components;c++) {
                double value=a.getBufferView()==null ? 0 : component(l.base+i*l.stride+c*l.componentBytes,a);
                require(Double.isFinite(value)); out[i*l.components+c]=value;
            }
        }
        return out;
    }

    int[] indices(int index, int vertexCount) {
        Accessor a=accessor(index); Layout l=layout(a);
        require("SCALAR".equals(a.getType()) && !Boolean.TRUE.equals(a.isNormalized()));
        require(a.getComponentType()==5121 || a.getComponentType()==5123 || a.getComponentType()==5125);
        require(a.getCount()<=HandToolProfile.MAX_TRIANGLES*3 && vertexCount>=0
                && vertexCount<=HandToolProfile.MAX_VERTICES);
        if(a.getBufferView()!=null) {
            BufferView v=viewDefinition(a.getBufferView());
            require(v.getByteStride()==null && (v.getTarget()==null || v.getTarget()==34963));
        }
        int[] out=new int[a.getCount()];
        long restart=switch(a.getComponentType()){case 5121 -> 255;case 5123 -> 65535;default -> 0xffffffffL;};
        for(int i=0;i<out.length;i++) {
            double value=a.getBufferView()==null ? 0 : component(l.base+i*l.stride,a);
            require(value>=0 && value<vertexCount && value!=restart);
            out[i]=(int)value;
        }
        return out;
    }

    private Layout layout(Accessor a) {
        require(a!=null && a.getSparse()==null && a.getComponentType()!=null
                && a.getCount()!=null && a.getCount()>0 && a.getType()!=null);
        int componentBytes=switch(a.getComponentType()) {
            case 5120,5121 -> 1; case 5122,5123 -> 2; case 5125,5126 -> 4;
            default -> throw invalid();
        };
        require(!Boolean.TRUE.equals(a.isNormalized()) || (a.getComponentType()!=5125 && a.getComponentType()!=5126));
        int rows=switch(a.getType()) {
            case "SCALAR" -> 1; case "VEC2","MAT2" -> 2; case "VEC3","MAT3" -> 3;
            case "VEC4","MAT4" -> 4; default -> throw invalid();
        };
        int columns=a.getType().startsWith("MAT")?rows:1;
        metadataBounds(a,rows*columns);
        int element=columns==1 ? rows*componentBytes : columns*((rows*componentBytes+3)&~3);
        int off=offset(a.getByteOffset()); require(off%componentBytes==0);
        if(a.getBufferView()==null) { require(off==0); return new Layout(0,element,componentBytes,rows*columns); }
        BufferView v=viewDefinition(a.getBufferView());
        int stride=v.getByteStride()==null ? element : v.getByteStride();
        require(stride>=element && stride%componentBytes==0);
        long base=Math.addExact((long)offset(v.getByteOffset()),off);
        require(base%componentBytes==0);
        long span=Math.addExact(Math.multiplyExact((long)a.getCount()-1,stride),element);
        range(off,span,v.getByteLength()); range(base,span,declaredBytes);
        return new Layout(Math.toIntExact(base),stride,componentBytes,rows*columns);
    }

    private double component(int at, Accessor a) {
        double raw=switch(a.getComponentType()) {
            case 5120 -> bin.get(at); case 5121 -> Byte.toUnsignedInt(bin.get(at));
            case 5122 -> bin.getShort(at); case 5123 -> Short.toUnsignedInt(bin.getShort(at));
            case 5125 -> Integer.toUnsignedLong(bin.getInt(at)); case 5126 -> bin.getFloat(at);
            default -> throw invalid();
        };
        if(!Boolean.TRUE.equals(a.isNormalized())) { return raw; }
        return switch(a.getComponentType()) {
            case 5120 -> Math.max(raw/127,-1); case 5121 -> raw/255;
            case 5122 -> Math.max(raw/32767,-1); case 5123 -> raw/65535;
            default -> throw invalid();
        };
    }
    private record Layout(int base,int stride,int componentBytes,int components) { }
    private static void metadataBounds(Accessor a,int components) {
        for(Number[] bounds:new Number[][]{a.getMin(),a.getMax()}) if(bounds!=null) {
            require(bounds.length==components);
            for(Number n:bounds) {
                require(n!=null && Double.isFinite(n.doubleValue()));double v=n.doubleValue();
                if(a.getComponentType()==5126) require(Float.isFinite((float)v));
                else {
                    require(v==Math.rint(v));
                    double min=switch(a.getComponentType()){case 5120 -> -128;case 5122 -> -32768;default -> 0;};
                    double max=switch(a.getComponentType()){case 5120 -> 127;case 5121 -> 255;case 5122 -> 32767;case 5123 -> 65535;default -> 4294967295.0;};
                    require(v>=min && v<=max);
                }
            }
        }
        if(a.getMin()!=null && a.getMax()!=null) for(int i=0;i<components;i++)require(a.getMin()[i].doubleValue()<=a.getMax()[i].doubleValue());
    }
    private void usage(de.javagl.jgltf.impl.v2.GlTF doc) {
        Map<Integer,String> roles=new HashMap<>();Map<Integer,Set<Integer>> vertexAccessors=new HashMap<>();
        if(doc.getMeshes()!=null)for(var mesh:doc.getMeshes())if(mesh!=null && mesh.getPrimitives()!=null)for(var p:mesh.getPrimitives()) {
            if(p==null)continue;
            if(p.getAttributes()!=null)for(Integer index:p.getAttributes().values()) {
                require(index!=null);var a=accessor(index);
                if(a.getBufferView()!=null) {role(roles,a.getBufferView(),"vertex");vertexAccessors.computeIfAbsent(a.getBufferView(),k->new HashSet<>()).add(index);}
            }
            if(p.getIndices()!=null) {var a=accessor(p.getIndices());if(a.getBufferView()!=null)role(roles,a.getBufferView(),"index");}
        }
        if(doc.getImages()!=null)for(var image:doc.getImages())if(image!=null && image.getBufferView()!=null)role(roles,image.getBufferView(),"image");
        for(var entry:vertexAccessors.entrySet())if(entry.getValue().size()>1)require(viewDefinition(entry.getKey()).getByteStride()!=null);
        for(var entry:roles.entrySet())if(!entry.getValue().equals("vertex"))require(viewDefinition(entry.getKey()).getByteStride()==null);
    }
    private void role(Map<Integer,String> roles,int view,String role) {
        viewDefinition(view);String previous=roles.putIfAbsent(view,role);require(previous==null || previous.equals(role));
    }
    private static int offset(Integer value) { int result=value==null?0:value; require(result>=0); return result; }
    private static void range(long base,long length,long limit) {
        require(base>=0 && length>=0 && Math.addExact(base,length)<=limit);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_BUFFER_ACCESS"); }
    private static void require(boolean condition) { if(!condition) { throw invalid(); } }
}
