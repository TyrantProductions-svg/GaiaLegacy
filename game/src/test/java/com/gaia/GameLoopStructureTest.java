package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.feedback.FirstPersonMovementState;
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
    void movementPresentationCaptureReadsButNeverMutatesAuthoritativeBody() {
        World world = new World();
        PlayerController player = playerController(world);
        Vector3f expectedPosition =
                new Vector3f(3.0f, 4.0f, 5.0f);
        Vector3f expectedVelocity =
                new Vector3f(3.0f, -2.0f, 4.0f);
        player.body().teleport(expectedPosition);
        player.body().setLinearVelocity(expectedVelocity);

        FirstPersonMovementState state =
                GameLoop.movementState(
                        player,
                        new Vector3f(),
                        new Vector3f());

        assertEquals(4.0f, state.feetY());
        assertEquals(5.0f, state.horizontalSpeed());
        assertEquals(-2.0f, state.verticalSpeed());
        assertFalse(state.grounded());
        assertFalse(state.noclip());
        assertEquals(
                expectedPosition,
                player.body().position(new Vector3f()));
        assertEquals(
                expectedVelocity,
                player.body().linearVelocity(new Vector3f()));
    }

    @Test
    void productLoopDelegatesSessionLifecycleWithoutOwningWorldLoadOrFixedTime()
            throws IOException {
        String productLoop =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/shell/"
                                        + "ProductLoop.java"));
        String gameplayHelpers =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameLoop.java"));

        assertTrue(productLoop.contains("GameSession"));
        assertTrue(productLoop.contains("session.pollLoadResponsive()"));
        assertTrue(productLoop.contains("session.advancePlaying("));
        assertTrue(productLoop.contains("session.capturePaused()"));
        assertTrue(productLoop.contains("session.discardFixedTime()"));
        assertTrue(productLoop.contains("inputManager.invalidateGameplayInput()"));
        assertFalse(
                java.util.Arrays.stream(
                                GameLoop.class.getDeclaredFields())
                        .anyMatch(
                                field ->
                                        field.getType()
                                                == WorldLoadResult.class));
        assertFalse(productLoop.contains("completeLoadingIfReady("));
        assertFalse(productLoop.contains("fixedStepClock().advance("));
        assertFalse(productLoop.contains("new World("));
        assertFalse(productLoop.contains("ChunkRepository"));
        assertFalse(productLoop.contains("SessionPersistenceClock"));
        assertFalse(productLoop.contains("StreamedChunkStore"));
        assertFalse(productLoop.contains("StreamedWorldItemPageBackend"));
        assertFalse(productLoop.contains("BlockInteractionController"));
        assertFalse(productLoop.contains("InteractionFeedbackCoordinator"));
        assertEquals(1, occurrences(productLoop, "while ("));
        assertEquals(0, occurrences(gameplayHelpers, "while ("));
    }

    @Test
    void sessionRuntimePreservesFixedOrderingAndImmutableCapture()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/session/"
                                        + "GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(
                compact.contains(
                        "FixedBatchfixedBatch=runFixedBatch(fixedSteps)"));
        assertTrue(
                compact.contains(
                        "inputManager.consumeFixedInput()"));
        assertTrue(
                compact.contains(
                        "step==0?frameInput:frameInput.heldOnly()"));
        assertTrue(
                compact.contains(
                        "inventoryInput.handleSelection(stepInput)"));
        assertTrue(
                compact.contains(
                        "physicalWorldItems.prepareStep(inventoryTick)"));
        assertTrue(compact.contains("physicsWorld.step(fixedDelta)"));
        assertTrue(
                compact.contains(
                        "physicalWorldItems.finishStep()"));
        assertTrue(
                compact.contains(
                        "worldItemPickup.fixedUpdate("));
        assertTrue(
                compact.contains(
                        "blockInteraction.fixedUpdate("));
        assertTrue(
                compact.contains(
                        "feedback.fixedUpdate("));
        assertTrue(
                compact.contains(
                        "ModuleManager.getInstance().updateAll("));
        assertTrue(
                compact.contains(
                        "EventBus.getInstance().processAll()"));
        assertTrue(
                compact.contains(
                        "fixedStepClock.interpolationAlpha()"));
        assertTrue(
                compact.contains("newGameSessionFrame("));

        int selection =
                compact.indexOf(
                        "inventoryInput.handleSelection(stepInput)");
        int player =
                compact.indexOf("playerManager.fixedUpdate(");
        int prepare =
                compact.indexOf(
                        "physicalWorldItems.prepareStep(inventoryTick)");
        int physics =
                compact.indexOf("physicsWorld.step(fixedDelta)");
        int finish =
                compact.indexOf(
                        "physicalWorldItems.finishStep()");
        int pickup =
                compact.indexOf("worldItemPickup.fixedUpdate(");
        int interaction =
                compact.indexOf("blockInteraction.fixedUpdate(");
        int feedback =
                compact.indexOf("feedback.fixedUpdate(");
        int modules =
                compact.indexOf(
                        "ModuleManager.getInstance().updateAll(");
        int events =
                compact.indexOf(
                        "EventBus.getInstance().processAll()");
        assertTrue(selection < player);
        assertTrue(player < prepare);
        assertTrue(prepare < physics);
        assertTrue(physics < finish);
        assertTrue(finish < pickup);
        assertTrue(pickup < interaction);
        assertTrue(interaction < feedback);
        assertTrue(feedback < modules);
        assertTrue(modules < events);
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
                        new Vector3f(0.5f, 0.0f, 0.5f),
                        "fingerprint",
                        "hash");

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
                    assertTrue(
                            world.setBlock(
                                    x, y, z, (byte) 1));
                }
            }
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
