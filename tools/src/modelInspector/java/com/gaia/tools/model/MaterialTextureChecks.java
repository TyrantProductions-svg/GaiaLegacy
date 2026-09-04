package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Material;
import de.javagl.jgltf.impl.v2.Sampler;
import java.util.ArrayList;
import java.util.List;

/** Document-local material values only, never a Gaia item/material registry. */
final class MaterialTextureChecks {
    record Result(List<ValidatedModelSnapshot.Material> materials,List<ValidatedModelSnapshot.Texture> textures) {
        Result {materials=List.copyOf(materials);textures=List.copyOf(textures);}
    }
    static Result analyze(GlTF doc,ValidationReport.Collector log) {
        var materials=doc.getMaterials()==null?List.<Material>of():doc.getMaterials();
        var sourceTextures=doc.getTextures()==null?List.<de.javagl.jgltf.impl.v2.Texture>of():doc.getTextures();
        var samplers=doc.getSamplers()==null?List.<Sampler>of():doc.getSamplers();
        int images=doc.getImages()==null?0:doc.getImages().size();
        boolean overLimit=false;
        if(materials.size()>HandToolProfile.MAX_MATERIALS) {log.error("MATERIAL_LIMIT","/materials","Material budget exceeded");overLimit=true;}
        if(images>HandToolProfile.MAX_IMAGES) {log.error("IMAGE_LIMIT","/images","Image budget exceeded");overLimit=true;}
        if(sourceTextures.size()>HandToolProfile.MAX_TEXTURES) {log.error("TEXTURE_LIMIT","/textures","Texture declaration budget exceeded");overLimit=true;}
        if(samplers.size()>HandToolProfile.MAX_SAMPLERS) {log.error("SAMPLER_LIMIT","/samplers","Sampler declaration budget exceeded");overLimit=true;}
        if(overLimit) return new Result(List.of(),List.of());
        for(int i=0;i<samplers.size();i++) {
            try {sampler(samplers.get(i));}catch(IllegalArgumentException|NullPointerException bad) {log.error("INVALID_SAMPLER","/samplers/"+i,"Invalid texture sampling values");}
        }
        var textures=new ArrayList<ValidatedModelSnapshot.Texture>();
        for(int i=0;i<sourceTextures.size();i++) {
            try {
                var t=sourceTextures.get(i);require(t!=null && t.getSource()!=null && t.getSource()>=0 && t.getSource()<images);
                Sampler s=null;
                if(t.getSampler()!=null) {require(t.getSampler()>=0 && t.getSampler()<samplers.size());s=samplers.get(t.getSampler());sampler(s);}
                textures.add(new ValidatedModelSnapshot.Texture(t.getSource(),s==null?null:s.getMagFilter(),s==null?null:s.getMinFilter(),s==null||s.getWrapS()==null?10497:s.getWrapS(),s==null||s.getWrapT()==null?10497:s.getWrapT()));
            }catch(IllegalArgumentException|NullPointerException bad) {log.error("INVALID_TEXTURE","/textures/"+i,"Invalid image or sampler reference");}
        }
        var result=new ArrayList<ValidatedModelSnapshot.Material>();
        for(int i=0;i<materials.size();i++) {
            String path="/materials/"+i;
            try {
                var m=materials.get(i);require(m!=null);var p=m.getPbrMetallicRoughness();
                if(m.getNormalTexture()!=null || m.getOcclusionTexture()!=null || m.getEmissiveTexture()!=null
                        || (p!=null && p.getMetallicRoughnessTexture()!=null) || Boolean.TRUE.equals(m.isDoubleSided())
                        || (m.getAlphaMode()!=null && !m.getAlphaMode().equals("OPAQUE")) || nonzero(m.getEmissiveFactor())) {
                    log.error("UNSUPPORTED_MATERIAL",path,"Appearance outside HAND_TOOL_V0 subset");continue;
                }
                if(m.getEmissiveFactor()!=null) factor(m.getEmissiveFactor(),3);
                if(m.getAlphaCutoff()!=null) require(Double.isFinite(m.getAlphaCutoff()) && m.getAlphaCutoff()>=0);
                double[] color=p==null || p.getBaseColorFactor()==null?new double[]{1,1,1,1}:p.getBaseColorFactor();factor(color,4);
                double metal=p==null||p.getMetallicFactor()==null?1:p.getMetallicFactor();unit(metal);
                double rough=p==null||p.getRoughnessFactor()==null?1:p.getRoughnessFactor();unit(rough);
                int texture=-1;
                if(p!=null && p.getBaseColorTexture()!=null) {
                    var info=p.getBaseColorTexture();
                    if(info.getTexCoord()!=null && info.getTexCoord()!=0) {
                        log.error("UNSUPPORTED_TEXTURE_COORDINATE_SET",path+"/pbrMetallicRoughness/baseColorTexture/texCoord","Only texture coordinate set zero supported");continue;
                    }
                    require(info.getIndex()!=null && info.getIndex()>=0 && info.getIndex()<sourceTextures.size());texture=info.getIndex();
                }
                result.add(new ValidatedModelSnapshot.Material(m.getName(),color,metal,rough,texture));
            }catch(IllegalArgumentException|NullPointerException bad) {log.error("INVALID_MATERIAL",path,"Invalid material factors or texture reference");}
        }
        for(int m=0;m<doc.getMeshes().size();m++) {
            var primitives=doc.getMeshes().get(m).getPrimitives();
            for(int p=0;p<primitives.size();p++) {
                var primitive=primitives.get(p);Integer index=primitive.getMaterial();String path="/meshes/"+m+"/primitives/"+p;
                if(index==null) continue;
                if(index<0 || index>=materials.size()) {log.error("INVALID_MATERIAL_REFERENCE",path+"/material","Invalid material index");continue;}
                var material=materials.get(index);
                if(material!=null && material.getPbrMetallicRoughness()!=null && material.getPbrMetallicRoughness().getBaseColorTexture()!=null
                        && !primitive.getAttributes().containsKey("TEXCOORD_0")) log.error("REQUIRED_ATTRIBUTE",path+"/attributes/TEXCOORD_0","Textured primitive requires UV0");
            }
        }
        return new Result(result,textures);
    }
    private static void sampler(Sampler s) {
        require(s!=null);
        require(s.getMagFilter()==null || s.getMagFilter()==9728 || s.getMagFilter()==9729);
        require(s.getMinFilter()==null || List.of(9728,9729,9984,9985,9986,9987).contains(s.getMinFilter()));
        require(s.getWrapS()==null || List.of(33071,33648,10497).contains(s.getWrapS()));
        require(s.getWrapT()==null || List.of(33071,33648,10497).contains(s.getWrapT()));
    }
    private static boolean nonzero(double[] v) {if(v==null)return false;for(double n:v)if(n!=0)return true;return false;}
    private static void factor(double[] v,int length) {require(v.length==length);for(double n:v)unit(n);}
    private static void unit(double v) {require(Double.isFinite(v) && v>=0 && v<=1);}
    private static void require(boolean v) {if(!v)throw new IllegalArgumentException("INVALID_MATERIAL");}
}
