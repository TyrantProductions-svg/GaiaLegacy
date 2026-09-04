package com.gaia.tools.model;

import java.util.List;

/** Owned immutable geometry. Construction is confined to the validator package. */
public final class ValidatedModelSnapshot {
    public record Bounds(double[] min,double[] max) {
        public Bounds { min=min.clone();max=max.clone(); }
        @Override public double[] min() { return min.clone(); }
        @Override public double[] max() { return max.clone(); }
        public double[] dimensions() { return new double[]{max[0]-min[0],max[1]-min[1],max[2]-min[2]}; }
    }
    public record Primitive(int mesh,int primitive,int material,double[] positions,double[] normals,double[] texCoords,int[] indices) {
        public Primitive { positions=positions.clone();normals=normals.clone();texCoords=texCoords.clone();indices=indices.clone(); }
        @Override public double[] positions() { return positions.clone(); }
        @Override public double[] normals() { return normals.clone(); }
        @Override public double[] texCoords() { return texCoords.clone(); }
        @Override public int[] indices() { return indices.clone(); }
    }
    public record Node(int index,int parent,int mesh,String name,double[] worldTransform) {
        public Node { worldTransform=worldTransform.clone(); }
        @Override public double[] worldTransform() { return worldTransform.clone(); }
    }
    public record Material(String name,double[] baseColor,double metallic,double roughness,int baseColorTexture) {
        public Material {baseColor=baseColor.clone();}
        @Override public double[] baseColor() {return baseColor.clone();}
    }
    /** Null filters preserve glTF's unspecified-filter state; wrap defaults are explicit. */
    public record Texture(int image,Integer magFilter,Integer minFilter,int wrapS,int wrapT) { }
    /** Straight RGBA8 bytes, row-major top-to-bottom; no decoder or file handle. */
    public record Image(int width,int height,byte[] rgba) {
        public Image {rgba=rgba.clone();}
        @Override public byte[] rgba() {return rgba.clone();}
    }
    private final String sourceSha256;
    private final List<Primitive> primitives;
    private final List<Node> nodes;
    private final Bounds bounds;
    private final List<Material> materials;
    private final List<Texture> textures;
    private final List<Image> images;
    ValidatedModelSnapshot(String sha,List<Primitive> primitives,List<Node> nodes,Bounds bounds,List<Material> materials,List<Texture> textures,List<Image> images) {
        sourceSha256=sha;this.primitives=List.copyOf(primitives);this.nodes=List.copyOf(nodes);this.bounds=bounds;
        this.materials=List.copyOf(materials);this.textures=List.copyOf(textures);
        this.images=List.copyOf(images);
    }
    public String sourceSha256() { return sourceSha256; }
    public String profile() { return HandToolProfile.ID; }
    public int profileVersion() { return HandToolProfile.VERSION; }
    public List<Primitive> primitives() { return primitives; }
    public List<Node> nodes() { return nodes; }
    public Bounds bounds() { return bounds; }
    public List<Material> materials() {return materials;}
    public List<Texture> textures() {return textures;}
    public List<Image> images() {return images;}
}
