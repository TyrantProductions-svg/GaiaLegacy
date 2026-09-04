package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.MeshPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Primitive-domain validation. No model expansion or per-instance geometry copies. */
final class GeometryChecks {
    record Description(int mesh,int index,MeshPrimitive source,int vertices,int triangles) { }

    static void attributes(GlTF doc,ValidationReport.Collector log) {
        if(doc.getSkins()!=null || doc.getAnimations()!=null || doc.getCameras()!=null)
            log.error("UNSUPPORTED_MODEL_FEATURE","/","Skins, animations and cameras are deferred");
        if(doc.getNodes()!=null) for(int n=0;n<doc.getNodes().size();n++) {
            var node=doc.getNodes().get(n);
            if(node!=null && (node.getSkin()!=null || node.getCamera()!=null || node.getWeights()!=null))
                log.error("UNSUPPORTED_MODEL_FEATURE","/nodes/"+n,"Skin, camera and morph state are deferred");
        }
        var meshes=doc.getMeshes();
        if(meshes==null || meshes.isEmpty() || meshes.size()>HandToolProfile.MAX_MESHES) {
            log.error("MESH_LIMIT","/meshes","Missing geometry or mesh budget exceeded");return;
        }
        long count=0;
        for(int m=0;m<meshes.size();m++) {
            var mesh=meshes.get(m);
            if(mesh!=null && mesh.getWeights()!=null) log.error("UNSUPPORTED_MODEL_FEATURE","/meshes/"+m,"Morph weights are deferred");
            if(mesh==null || mesh.getPrimitives()==null || mesh.getPrimitives().isEmpty()) {
                log.error("INVALID_MESH","/meshes/"+m,"Mesh requires primitives");continue;
            }
            count+=mesh.getPrimitives().size();
            if(count>HandToolProfile.MAX_PRIMITIVES) {log.error("PRIMITIVE_LIMIT","/meshes","Primitive budget exceeded");return;}
            for(int p=0;p<mesh.getPrimitives().size();p++) {
                String path="/meshes/"+m+"/primitives/"+p;
                var primitive=mesh.getPrimitives().get(p);
                if(primitive==null || primitive.getAttributes()==null) {log.error("INVALID_PRIMITIVE",path,"Missing primitive attributes");continue;}
                var attrs=primitive.getAttributes();
                if(primitive.getTargets()!=null) log.error("UNSUPPORTED_MODEL_FEATURE",path+"/targets","Morph targets are deferred");
                for(String required:List.of("POSITION","NORMAL")) if(!attrs.containsKey(required))
                    log.error("REQUIRED_ATTRIBUTE",path+"/attributes/"+required,"Required attribute missing");
                for(String semantic:new TreeMap<>(attrs).keySet()) {
                    if(List.of("POSITION","NORMAL","TEXCOORD_0").contains(semantic)) continue;
                    String code;
                    if(!validSemantic(semantic,attrs)) code="INVALID_GLTF_ATTRIBUTE";
                    else if(semantic.startsWith("COLOR_")) code="UNSUPPORTED_VERTEX_COLOR";
                    else code="UNSUPPORTED_ATTRIBUTE";
                    log.error(code,path+"/attributes/"+pointer(semantic),"Attribute not supported by this profile");
                }
                if(primitive.getMode()!=null && primitive.getMode()!=4)
                    log.error("UNSUPPORTED_PRIMITIVE_MODE",path,"Only TRIANGLES supported");
            }
        }
    }

    private static boolean validSemantic(String s,Map<String,Integer> attributes) {
        if(s.equals("TANGENT") || s.startsWith("_")) return true;
        if(!s.matches("(COLOR|TEXCOORD|JOINTS|WEIGHTS)_(0|[1-9][0-9]*)")) return false;
        int split=s.lastIndexOf('_');
        try {int index=Integer.parseInt(s.substring(split+1));return index==0 || attributes.containsKey(s.substring(0,split+1)+(index-1));}
        catch(NumberFormatException invalid) {return false;}
    }
    private static String pointer(String s) {return s.replace("~","~0").replace("/","~1");}

    static List<Description> count(GlTF doc,BufferAccess data,SceneChecks.Result scene,
            HandToolProfile.GeometryBudget budget,ValidationReport.Collector log) {
        List<Description> descriptions=new ArrayList<>();
        for(int m=0;m<doc.getMeshes().size();m++) {
            var primitives=doc.getMeshes().get(m).getPrimitives();
            for(int p=0;p<primitives.size();p++) {
                String path="/meshes/"+m+"/primitives/"+p;
                try {
                    var primitive=primitives.get(p);int vertices=data.accessor(primitive.getAttributes().get("POSITION")).getCount();
                    int elements=primitive.getIndices()==null?vertices:data.accessor(primitive.getIndices()).getCount();
                    require(elements%3==0);int triangles=elements/3;
                    budget.addUnique(triangles,vertices);
                    if(scene!=null) for(var instance:scene.instances()) if(instance.mesh()==m) budget.addExpanded(triangles,vertices,1);
                    descriptions.add(new Description(m,p,primitive,vertices,triangles));
                } catch(IllegalArgumentException|NullPointerException ex) {log.error("INVALID_GEOMETRY",path,"Invalid primitive counts or references");}
            }
        }
        return descriptions;
    }

    static List<ValidatedModelSnapshot.Primitive> decode(List<Description> descriptions,BufferAccess data,ValidationReport.Collector log) {
        var out=new ArrayList<ValidatedModelSnapshot.Primitive>();
        for(var d:descriptions) {
            try {
                var attrs=d.source.getAttributes();int position=attrs.get("POSITION"),normal=attrs.get("NORMAL");
                shape(data.accessor(position),"VEC3",5126,d.vertices,false);
                require(data.accessor(position).getMin()!=null && data.accessor(position).getMax()!=null);
                shape(data.accessor(normal),"VEC3",5126,d.vertices,false);
                var pos=data.numbers(position,"VEC3",HandToolProfile.MAX_VERTICES,true);
                var norms=data.numbers(normal,"VEC3",HandToolProfile.MAX_VERTICES,true);
                double[] uv=new double[0];
                if(attrs.containsKey("TEXCOORD_0")) {
                    int index=attrs.get("TEXCOORD_0");var a=data.accessor(index);
                    require(a.getCount()==d.vertices && "VEC2".equals(a.getType()));
                    require(a.getComponentType()==5126 && !Boolean.TRUE.equals(a.isNormalized())
                            || (a.getComponentType()==5121 || a.getComponentType()==5123) && Boolean.TRUE.equals(a.isNormalized()));
                    uv=data.numbers(index,"VEC2",HandToolProfile.MAX_VERTICES,true);
                }
                int[] indices;
                if(d.source.getIndices()==null) {indices=new int[d.vertices];for(int i=0;i<indices.length;i++)indices[i]=i;}
                else indices=data.indices(d.source.getIndices(),d.vertices);
                for(int i=0;i<norms.length;i+=3) require(Math.abs(Math.hypot(Math.hypot(norms[i],norms[i+1]),norms[i+2])-1)<=HandToolProfile.NORMAL_LENGTH_EPSILON);
                for(int i=0;i<indices.length;i+=3) triangle(pos,norms,indices[i],indices[i+1],indices[i+2]);
                out.add(new ValidatedModelSnapshot.Primitive(d.mesh,d.index,d.source.getMaterial()==null?-1:d.source.getMaterial(),pos,norms,uv,indices));
            } catch(IllegalArgumentException|ArithmeticException ex) {log.error("INVALID_GEOMETRY","/meshes/"+d.mesh+"/primitives/"+d.index,"Invalid geometry data, normals or triangle orientation");}
        }
        return List.copyOf(out);
    }
    private static void shape(Accessor a,String type,int component,int count,boolean normalized) {
        require(type.equals(a.getType()) && a.getComponentType()==component && a.getCount()==count && Boolean.TRUE.equals(a.isNormalized())==normalized);
    }
    private static void triangle(double[] p,double[] n,int a,int b,int c) {
        a*=3;b*=3;c*=3;
        double x=p[b]-p[a],y=p[b+1]-p[a+1],z=p[b+2]-p[a+2];
        double u=p[c]-p[a],v=p[c+1]-p[a+1],w=p[c+2]-p[a+2];
        double nx=y*w-z*v,ny=z*u-x*w,nz=x*v-y*u;
        double length=Math.hypot(Math.hypot(nx,ny),nz);
        require(Double.isFinite(length) && length>HandToolProfile.TRIANGLE_AREA_EPSILON);
        double dot=nx*(n[a]+n[b]+n[c])+ny*(n[a+1]+n[b+1]+n[c+1])+nz*(n[a+2]+n[b+2]+n[c+2]);
        require(Double.isFinite(dot) && dot>0);
    }
    static ValidatedModelSnapshot.Bounds bounds(List<ValidatedModelSnapshot.Primitive> primitives,SceneChecks.Result scene) {
        double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        // Each instance transforms values, not a second retained geometry array.
        for(var primitive:primitives) {
            double[] positions=primitive.positions();
            for(var instance:scene.instances()) if(instance.mesh()==primitive.mesh()) {
                for(int i=0;i<positions.length;i+=3) {
                    var point=instance.transform().point(positions[i],positions[i+1],positions[i+2]);
                    for(int c=0;c<3;c++) {min[c]=Math.min(min[c],point[c]);max[c]=Math.max(max[c],point[c]);}
                }
            }
        }
        for(int c=0;c<3;c++) require(Double.isFinite(min[c])&&Double.isFinite(max[c])&&Double.isFinite(max[c]-min[c]));
        return new ValidatedModelSnapshot.Bounds(min,max);
    }
    private static void require(boolean value) {if(!value)throw new IllegalArgumentException("INVALID_GEOMETRY");}
}
