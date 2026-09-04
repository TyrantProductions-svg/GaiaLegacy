package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Node;
import java.util.ArrayList;
import java.util.List;

/** Bounded graph traversal; instances reference declared mesh indices, never copies. */
final class SceneChecks {
    record Instance(int node, int mesh, RigidTransform transform) { }
    record PlacedNode(int node, int parent, RigidTransform transform) { }
    record Result(List<Instance> instances, List<PlacedNode> nodes, int depth) {
        Result { instances=List.copyOf(instances); nodes=List.copyOf(nodes); }
    }

    static Result analyze(GlTF doc, ValidationReport.Collector log) {
        try { return new Walk(doc,log).run(); }
        catch(IllegalArgumentException rejected) {
            log.error("SCENE_INVALID","/scenes","Invalid scene, hierarchy or rigid transform");
            return null;
        }
    }

    private static final class Walk {
        private final GlTF doc;
        private final ValidationReport.Collector log;
        private final List<Node> nodes;
        private final boolean[] visited;
        private final boolean[] usedMeshes;
        private final List<Instance> instances=new ArrayList<>();
        private final List<PlacedNode> placed=new ArrayList<>();
        private int maxDepth;

        Walk(GlTF doc, ValidationReport.Collector log) {
            this.doc=doc;this.log=log;
            nodes=doc.getNodes()==null?List.of():doc.getNodes();
            int meshes=doc.getMeshes()==null?0:doc.getMeshes().size();
            require(!nodes.isEmpty() && nodes.size()<=HandToolProfile.MAX_NODES && meshes<=HandToolProfile.MAX_MESHES);
            visited=new boolean[nodes.size()]; usedMeshes=new boolean[meshes];
        }

        Result run() {
            require(doc.getScene()!=null && doc.getScenes()!=null && doc.getScene()>=0
                    && doc.getScene()<doc.getScenes().size());
            int[] parents=new int[nodes.size()];java.util.Arrays.fill(parents,-1);
            for(int parent=0;parent<nodes.size();parent++) {
                Node node=nodes.get(parent);require(node!=null);
                if(node.getChildren()!=null) {
                    require(node.getChildren().size()<=HandToolProfile.MAX_NODES);
                    for(Integer reference:node.getChildren()) {
                        int child=nodeIndex(reference);require(parents[child]==-1);parents[child]=parent;
                    }
                }
            }
            // Validate references in every declaration, but expand only the default scene.
            for(var scene:doc.getScenes()) {
                require(scene!=null);
                long seenRoots=0;
                if(scene.getNodes()!=null) {
                    for(Integer reference:scene.getNodes()) {
                        int index=nodeIndex(reference);long bit=1L<<index;
                        require(parents[index]==-1 && (seenRoots&bit)==0);seenRoots|=bit;
                    }
                }
            }
            var roots=doc.getScenes().get(doc.getScene()).getNodes();
            require(roots!=null && roots.size()==1); int root=nodeIndex(roots.get(0));
            require(nodes.get(root)!=null && "GAIA_ASSET_ROOT".equals(nodes.get(root).getName()));
            visit(root,-1,1,null);
            for(int i=0;i<visited.length;i++) {
                if(!visited[i]) { log.error("UNREACHABLE_NODE","/nodes/"+i,"Node is outside default scene"); }
            }
            for(int i=0;i<usedMeshes.length;i++) {
                if(!usedMeshes[i]) { log.error("UNREACHABLE_MESH","/meshes/"+i,"Mesh is outside default scene"); }
            }
            return new Result(instances,placed,maxDepth);
        }

        void visit(int index, int parent, int depth, RigidTransform parentWorld) {
            // Check before recursion or traversal allocation. A second visit is either
            // a cycle or repeated-parent ambiguity; both are forbidden.
            require(depth<=HandToolProfile.MAX_DEPTH && !visited[index]);
            Node node=nodes.get(index);require(node!=null);
            visited[index]=true; maxDepth=Math.max(maxDepth,depth);
            var local=RigidTransform.from(node);
            var world=parentWorld==null?local:parentWorld.compose(local);
            placed.add(new PlacedNode(index,parent,world));
            if(node.getMesh()!=null) {
                int mesh=node.getMesh();require(mesh>=0 && mesh<usedMeshes.length);
                usedMeshes[mesh]=true;instances.add(new Instance(index,mesh,world));
            }
            if(node.getChildren()!=null) {
                require(node.getChildren().size()<=HandToolProfile.MAX_NODES);
                for(Integer child:node.getChildren()) { visit(nodeIndex(child),index,depth+1,world); }
            }
        }
        int nodeIndex(Integer index) { require(index!=null && index>=0 && index<nodes.size());return index; }
    }
    private static void require(boolean condition) {
        if(!condition) { throw new IllegalArgumentException("SCENE_INVALID"); }
    }
}
