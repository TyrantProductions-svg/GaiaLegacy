package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFactory;
import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioAssetSource;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameBootstrapTest {
    private static final ResourceLocation GAIA_MUSIC =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");

    @Test
    void gameContextCarriesOnlyApplicationAndSessionFactoryBoundaries() {
        assertEquals(
                GameSessionFactory.class,
                componentNamed("sessionFactory").getType());
        assertEquals(
                GameSessionConfig.class,
                componentNamed("initialSessionConfig").getType());
        for (String forbidden :
                new String[] {
                    "World",
                    "WorldLoader",
                    "PhysicsWorld",
                    "CollisionWorld",
                    "PlayerController",
                    "FixedStepClock",
                    "ChunkMeshManager",
                    "ShutdownCoordinator"
                }) {
            assertFalse(
                    Arrays.stream(GameContext.class.getRecordComponents())
                            .anyMatch(
                                    component ->
                                            component.getType()
                                                    .getSimpleName()
                                                    .equals(forbidden)),
                    "GameContext must not own " + forbidden);
        }
    }

    @Test
    void factoryComposesFiniteLoaderAndEightStepCatchUp()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/session/"
                                        + "GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(
                source.contains(
                        "WorldGenerationConfig.visualRevisionCandidate()"));
        assertTrue(
                source.contains(
                        "GaiaWorldGenerator.createVisualRevisionCandidate()"));
        assertTrue(source.contains("new SafeSpawnSelector()"));
        assertTrue(source.contains("new WorldLoader("));
        assertTrue(
                compact.contains(
                        "MAX_FIXED_STEPS_PER_FRAME=8;"));
        assertTrue(compact.contains("newCollisionWorld(world,shapes)"));
        assertTrue(compact.contains("newBlockRaycast(world,shapes)"));
        assertTrue(compact.contains("newPlayerController("));
        assertTrue(compact.contains("newPhysicsWorld("));
        assertFalse(source.contains("PhysicsManager"));
    }

    @Test
    void suppressesCleanupFailureOnPrimaryFailure() {
        RuntimeException primary =
                new RuntimeException("startup failed");
        RuntimeException cleanup =
                new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator =
                new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        GameBootstrap.closeAfterRun(coordinator, primary);

        assertSame(cleanup, primary.getSuppressed()[0]);
    }

    @Test
    void throwsCleanupFailureWhenThereIsNoPrimaryFailure() {
        RuntimeException cleanup =
                new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator =
                new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                GameBootstrap.closeAfterRun(
                                        coordinator, null));

        assertSame(cleanup, thrown);
    }

    @Test
    void samePrimaryAndCoordinatorFailureIdentityDoesNotMaskOrAbortRemainingCleanup() {
        RuntimeException shared = new RuntimeException("shared primary and cleanup failure");
        List<String> events = new ArrayList<>();
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register("final", () -> events.add("final"));
        coordinator.register(
                "same-primary",
                () -> {
                    events.add("same-primary");
                    throw shared;
                });

        assertDoesNotThrow(() -> GameBootstrap.closeAfterRun(coordinator, shared));

        assertEquals(List.of("same-primary", "final"), events);
        assertEquals(0, shared.getSuppressed().length);
    }

    @Test
    void productionAudioAssetSourceReturnsIndependentCallerOwnedDirectBuffers() {
        AudioAssetSource source = GameBootstrap.createAudioAssetSource(
                new AssetManager(getClass().getClassLoader()));
        ByteBuffer first = source.read(GAIA_MUSIC);
        ByteBuffer second = source.read(GAIA_MUSIC);
        boolean firstReleased = false;
        try {
            assertTrue(first.isDirect());
            assertTrue(second.isDirect());
            assertNotSame(first, second);
            assertEquals(0, first.position());
            assertEquals(0, second.position());
            assertTrue(first.remaining() > 4);
            assertEquals('O', first.get(0));
            assertEquals('g', first.get(1));
            assertEquals('g', first.get(2));
            assertEquals('S', first.get(3));
            source.release(first);
            firstReleased = true;
            assertThrows(IllegalArgumentException.class, () -> source.release(first));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source.release(ByteBuffer.allocateDirect(4)));
            assertEquals('O', second.get(0));
        } finally {
            if (!firstReleased) {
                source.release(first);
            }
            source.release(second);
        }
    }

    @Test
    void productionAudioAssetSourcePreservesStructuredMissingResourceDiagnostic() {
        AudioAssetSource source = GameBootstrap.createAudioAssetSource(
                new AssetManager(getClass().getClassLoader()));
        ResourceLocation missing = ResourceLocation.parse("gaia:audio/music/missing.ogg");

        AssetLoadException failure = assertThrows(
                AssetLoadException.class,
                () -> source.read(missing));

        assertEquals(1, failure.report().errors().size());
        assertEquals("ASSET_NOT_FOUND", failure.report().errors().get(0).code());
        assertEquals(missing, failure.report().errors().get(0).resource());
    }

    @Test
    void logsEveryStructuredAssetDiagnostic() {
        AssetLoadReport.Builder report =
                AssetLoadReport.builder();
        report.add(
                new AssetDiagnostic(
                        AssetSeverity.WARNING,
                        "ASSET_MISSING_REGION",
                        "assets/gaia/blocks/grass.json",
                        ResourceLocation.parse("gaia:not_found"),
                        "textures.top",
                        "Block face references missing region",
                        ResourceLocation.parse("gaia:missing")));
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(
                    new PrintStream(
                            output,
                            true,
                            StandardCharsets.UTF_8));
            GameBootstrap.logAssetReport(report.build());
        } finally {
            System.setOut(original);
        }

        String logged =
                output.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("WARNING"));
        assertTrue(logged.contains("ASSET_MISSING_REGION"));
        assertTrue(
                logged.contains(
                        "assets/gaia/blocks/grass.json"));
        assertTrue(logged.contains("gaia:not_found"));
        assertTrue(logged.contains("textures.top"));
        assertTrue(
                logged.contains(
                        "Block face references missing region"));
        assertTrue(logged.contains("gaia:missing"));
    }

    private static RecordComponent componentNamed(String name) {
        return Arrays.stream(GameContext.class.getRecordComponents())
                .filter(
                        component ->
                                component.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
