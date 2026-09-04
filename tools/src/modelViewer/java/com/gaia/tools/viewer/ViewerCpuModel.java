package com.gaia.tools.viewer;

import com.gaia.tools.model.ValidatedModelSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;

/** Immutable presentation packing. Only Gate B can supply the input snapshot. */
public final class ViewerCpuModel {
    public static final int FLOATS_PER_VERTEX = 8;
    public record Primitive(float[] vertices, int[] indices, int material) {
        public Primitive { vertices=vertices.clone(); indices=indices.clone(); }
        @Override public float[] vertices() { return vertices.clone(); }
        @Override public int[] indices() { return indices.clone(); }
        public int indexCount() { return indices.length; }
    }
    public record Draw(int primitive, double[] worldTransform) {
        public Draw { worldTransform=worldTransform.clone(); }
        @Override public double[] worldTransform() { return worldTransform.clone(); }
    }
    private static final ValidatedModelSnapshot.Material DEFAULT_MATERIAL =
            new ValidatedModelSnapshot.Material("",new double[]{1,1,1,1},1,1,-1);
    private final String sha;
    private final List<Primitive> primitives;
    private final List<Draw> draws;
    private final List<ValidatedModelSnapshot.Material> materials;
    private final List<ValidatedModelSnapshot.Texture> textures;
    private final List<ValidatedModelSnapshot.Image> images;
    private final ValidatedModelSnapshot.Bounds bounds;
    private final long bufferBytes;
    private final long triangleCount;

    private ViewerCpuModel(ValidatedModelSnapshot source, List<Primitive> primitives,
            List<Draw> draws, long bufferBytes, long triangles) {
        sha=source.sourceSha256(); this.primitives=List.copyOf(primitives);
        this.draws=List.copyOf(draws); materials=source.materials();
        textures=source.textures(); images=source.images(); bounds=source.bounds();
        this.bufferBytes=bufferBytes; triangleCount=triangles;
    }

    public static ViewerCpuModel from(ValidatedModelSnapshot source) {
        Objects.requireNonNull(source,"validated snapshot");
        var packed=new ArrayList<Primitive>();
        long bytes=0;
        for (var primitive:source.primitives()) {
            double[] positions=primitive.positions(), normals=primitive.normals(), uv=primitive.texCoords();
            int[] indices=primitive.indices();
            int vertices=positions.length/3;
            float[] data=new float[Math.multiplyExact(vertices,FLOATS_PER_VERTEX)];
            for (int i=0;i<vertices;i++) {
                for (int axis=0;axis<3;axis++) {
                    data[i*8+axis]=gpuFloat(positions[i*3+axis]);
                    data[i*8+3+axis]=gpuFloat(normals[i*3+axis]);
                }
                if (uv.length!=0) {
                    data[i*8+6]=gpuFloat(uv[i*2]); data[i*8+7]=gpuFloat(uv[i*2+1]);
                }
            }
            bytes=Math.addExact(bytes,Math.multiplyExact((long)data.length+indices.length,4L));
            packed.add(new Primitive(data,indices,primitive.material()));
        }
        var draws=new ArrayList<Draw>();
        long triangles=0;
        for (var node:source.nodes()) {
            if (node.mesh()<0) continue;
            for (int primitive=0;primitive<source.primitives().size();primitive++) {
                if (source.primitives().get(primitive).mesh()==node.mesh()) {
                    draws.add(new Draw(primitive,node.worldTransform()));
                    triangles=Math.addExact(triangles,packed.get(primitive).indexCount()/3L);
                }
            }
        }
        return new ViewerCpuModel(source,packed,draws,bytes,triangles);
    }

    static float gpuFloat(double value) {
        float packed=(float)value;
        if (!Float.isFinite(packed)) throw new IllegalArgumentException("GPU value is not representable");
        return packed;
    }
    public String sourceSha256() { return sha; }
    public List<Primitive> primitives() { return primitives; }
    public List<Draw> draws() { return draws; }
    public List<ValidatedModelSnapshot.Material> materials() { return materials; }
    public List<ValidatedModelSnapshot.Texture> textures() { return textures; }
    public List<ValidatedModelSnapshot.Image> images() { return images; }
    public ValidatedModelSnapshot.Bounds bounds() { return bounds; }
    public long bufferBytes() { return bufferBytes; }
    public long triangleCount() { return triangleCount; }
    public ValidatedModelSnapshot.Material material(int primitive) {
        int material=primitives.get(primitive).material();
        return material<0?DEFAULT_MATERIAL:materials.get(material);
    }
    public boolean textured(int primitive) { return material(primitive).baseColorTexture()>=0; }
    public Matrix4d modelView(int draw, Matrix4dc view) {
        return new Matrix4d(view).mul(new Matrix4d().set(draws.get(draw).worldTransform()));
    }
}
