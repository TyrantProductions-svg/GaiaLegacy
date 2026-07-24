package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.WorldLoadResult;
import com.overlord.config.GameConfig;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PlayerController;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.joml.Vector3f;
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

    @Test
    void loadsFeetThenRunsOrderedPhysicsAndRenderInterpolation()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameLoop.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(
                compact.contains(
                        "completePlayerLoading("
                                + "context.playerController(),loadResult)"));
        assertTrue(
                compact.contains(
                        "playerController.teleport("
                                + "loadResult.playerFeetPosition())"));
        assertTrue(
                compact.contains(
                        "if(!playerController"
                                + ".recoverFromPenetration()){"
                                + "thrownewIllegalStateException("));
        assertTrue(
                compact.contains(
                        "step==0?input:input.heldOnly()"));
        assertTrue(compact.contains("physicsWorld().step(fixedDelta)"));
        assertTrue(
                compact.contains(
                        "playerController().body()"
                                + ".interpolatedPosition("));
        assertTrue(
                compact.contains(
                        "fixedStepClock().interpolationAlpha()"));

        int playerUpdate =
                compact.indexOf("playerManager().fixedUpdate(");
        int physicsStep =
                compact.indexOf("physicsWorld().step(fixedDelta)");
        int moduleUpdate =
                compact.indexOf(
                        "ModuleManager.getInstance()"
                                + ".updateAll(fixedDelta)");
        int eventProcessing =
                compact.indexOf(
                        "EventBus.getInstance().processAll()");
        int interpolation =
                compact.indexOf(".interpolatedPosition(");
        int cameraPosition =
                compact.indexOf(".getCamera().setPosition(");
        int renderCameraUpdate =
                compact.indexOf("updateRenderCamera();");
        int render = compact.indexOf("renderChunks(");
        assertTrue(playerUpdate < physicsStep);
        assertTrue(physicsStep < moduleUpdate);
        assertTrue(moduleUpdate < eventProcessing);
        assertTrue(interpolation < cameraPosition);
        assertTrue(renderCameraUpdate < render);
        assertFalse(source.contains("PhysicsManager"));
    }

    @Test
    void loadingFailsWhenPlayerCannotRecoverToSafeSpawn() {
        World world = new World();
        fillBlocks(
                world,
                0,
                9,
                0,
                GameConfig.Chunk.MAX_HEIGHT - 1,
                0,
                0);
        PlayerController player = playerController(world);
        WorldLoadResult result =
                new WorldLoadResult(
                        Set.of(new ChunkKey(0, 0)),
                        new Vector3f(0.5f, 0.0f, 0.5f));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GameLoop.completePlayerLoading(
                                        player, result));

        assertEquals(
                "Player safe spawn recovery failed after world loading",
                failure.getMessage());
        assertEquals(
                result.playerFeetPosition(),
                player.body().position(new Vector3f()));
    }

    private static PlayerController playerController(World world) {
        PhysicsBody body =
                new PhysicsBody(
                        new Aabb(
                                -GameConfig.Player.WIDTH / 2.0f,
                                0,
                                -GameConfig.Player.WIDTH / 2.0f,
                                GameConfig.Player.WIDTH / 2.0f,
                                GameConfig.Player.HEIGHT,
                                GameConfig.Player.WIDTH / 2.0f),
                        MassProperties.dynamic(1.0f));
        return new PlayerController(
                body,
                new CollisionWorld(
                        world,
                        BlockCollisionShapeResolver
                                .fullCubesForNonAir()),
                GameConfig.Player.MOVEMENT_SPEED,
                GameConfig.Player.NOCLIP_SPEED,
                GameConfig.Player.JUMP_VELOCITY,
                GameConfig.Physics.GRAVITY,
                GameConfig.Physics.TERMINAL_VELOCITY);
    }

    private static void fillBlocks(
            World world,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    assertTrue(world.setBlock(x, y, z, (byte) 1));
                }
            }
        }
    }
}
