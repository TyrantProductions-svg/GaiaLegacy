package com.gaia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GameLoopStructureTest {
    @Test
    void pumpsChunkLifecycleBeforeRenderingOnlyCompleteInitialTerrain()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameLoop.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(compact.contains("privateWorldLoadResultloadResult;"));
        assertTrue(
                compact.contains(
                        "completeLoadingIfReady(){"
                                + "if(loadResult!=null){return;}"));
        assertTrue(compact.contains("chunkMeshes().scheduleEligible()"));
        assertTrue(
                compact.contains(
                        "chunkMeshes().processMainThreadWork()"));
        assertTrue(compact.contains("chunkMeshes().pollFailure()"));
        assertTrue(compact.contains("loadResult.initialChunks()"));
        assertTrue(compact.contains("chunkMeshes().allRenderable("));
        assertTrue(
                compact.contains(
                        "renderChunks("
                                + "context.chunkMeshes().renderObjects())"));

        int schedule = compact.indexOf("chunkMeshes().scheduleEligible()");
        int process =
                compact.indexOf(
                        "chunkMeshes().processMainThreadWork()");
        int failure = compact.indexOf("chunkMeshes().pollFailure()");
        int allRenderable =
                compact.indexOf("chunkMeshes().allRenderable(");
        int pump = compact.indexOf("pumpChunkMeshes();");
        int render = compact.indexOf("renderChunks(");
        assertTrue(schedule < process);
        assertTrue(process < failure);
        assertTrue(failure < allRenderable);
        assertTrue(pump < render);

        assertTrue(
                compact.contains(
                        "clear();if(state==State.RUNNING){"
                                + "context.engine().getRenderer()"
                                + ".renderChunks("));
        assertFalse(source.contains("result.meshData()"));
        assertFalse(source.contains("combineMeshData"));
        assertFalse(source.contains("new Mesh("));
        assertFalse(source.contains("replaceMesh("));
    }

    @Test
    void preservesMeshFailureIdentity() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameLoop.java"));

        assertTrue(
                source.contains(
                        "if (failure instanceof RuntimeException "
                                + "runtimeException)"));
        assertTrue(source.contains("throw runtimeException;"));
        assertTrue(source.contains("throw (Error) failure;"));
    }
}
