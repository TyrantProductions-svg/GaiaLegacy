package com.gaia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GameBootstrapStructureTest {
    @Test
    void composesIndependentChunkMeshingAndReverseSafeShutdown()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameBootstrap.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(compact.contains("newChunkMeshManager("));
        assertTrue(compact.contains("Executors.newFixedThreadPool("));
        assertTrue(
                compact.contains(
                        "namedThreadFactory(\"Gaia-Chunk-Mesher\")"));
        assertTrue(
                compact.contains(
                        "newChunkMeshManager("
                                + "engine.getWorld().chunks(),"
                                + "newChunkMeshBuilder(blocks),"
                                + "meshExecutor,"
                                + "engine.getRenderer(),"
                                + "mainThreadGuard,2)"));
        assertTrue(compact.contains("newShutdownBarrier("));
        assertTrue(
                compact.contains(
                        "shutdownBarrier.registerChunkMeshes("));
        assertTrue(
                compact.contains(
                        "shutdownBarrier.registerWorldExecutor("));

        int engineConstruction = compact.indexOf("newEngine(");
        int engineRegistration =
                compact.indexOf(
                        "register(\"engine\","
                                + "()->shutdownBarrier.closeEngine("
                                + "engine::shutdown))");
        int managerConstruction =
                compact.indexOf("newChunkMeshManager(");
        int meshLifecycleRegistration =
                compact.indexOf(
                        "shutdownBarrier.registerChunkMeshes(");
        int worldExecutorRegistration =
                compact.indexOf(
                        "shutdownBarrier.registerWorldExecutor(");
        int worldLoadRegistration =
                compact.indexOf(
                        "register(\"world-load\"");

        assertTrue(engineConstruction >= 0);
        assertTrue(engineConstruction < engineRegistration);
        assertTrue(engineRegistration < meshLifecycleRegistration);
        assertTrue(meshLifecycleRegistration < managerConstruction);
        assertTrue(
                managerConstruction < worldExecutorRegistration);
        assertTrue(worldExecutorRegistration < worldLoadRegistration);
        assertTrue(
                compact.contains(
                        "register(\"chunk-meshes\","
                                + "()->closeManager("));
        assertTrue(
                compact.contains(
                        "register(\"mesh-executor\","
                                + "()->stopMeshExecutor("));
        assertTrue(
                compact.contains(
                        "register(\"world-executor\","
                                + "()->stopWorldExecutor("));
    }

    @Test
    void composesIndexedAssetsBeforeEngineAndWorldWork()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameBootstrap.java"));

        assertTrue(source.contains("new AssetManager("));
        assertTrue(source.contains("new GaiaResourceLoader("));
        assertTrue(source.contains("catalog.renderAssets()"));
        assertTrue(source.contains("new GaiaWorldGenerator("));
        assertTrue(source.contains("new ChunkMeshBuilder("));
        assertTrue(
                source.contains(
                        "() -> worldLoader.load(engine.getWorld())"));
        assertFalse(
                source.contains(
                        "BlockRegistry." + "init()"));
        assertFalse(
                source.contains(
                        "BlockRegistry."
                                + "loadAllFromResources()"));
        assertFalse(
                source.contains(
                        "BlockRegistry." + "GRASS"));
        assertFalse(
                source.contains(
                        "BlockRegistry." + "DIRT"));
        assertFalse(
                source.contains(
                        "BlockRegistry." + "STONE"));
        assertFalse(source.contains("AssetLoadException"));
        assertFalse(source.contains("ServiceLocator"));

        int assetLoad = source.indexOf("new GaiaResourceLoader(");
        int engineConstruction = source.indexOf("new Engine(");
        int engineInitialization = source.indexOf("engine.init()");
        int worldConstruction = source.indexOf("new WorldLoader(");
        assertTrue(assetLoad < engineConstruction);
        assertTrue(engineConstruction < engineInitialization);
        assertTrue(engineInitialization < worldConstruction);
    }

    @Test
    void verifiesRequiredResourcesInThePackagedGameJar()
            throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertTrue(
                buildScript.contains(
                        "tasks.register('verifyPackagedResources')"));
        assertTrue(buildScript.contains("dependsOn tasks.named('jar')"));
        assertTrue(buildScript.contains("new java.util.zip.ZipFile(archive)"));
        assertTrue(
                buildScript.contains(
                        "tasks.named('check')"));
        assertTrue(
                buildScript.contains(
                        "dependsOn tasks.named("
                                + "'verifyPackagedResources')"));

        for (String required :
                new String[] {
                    "META-INF/gaialegacy/resource-indexes.list",
                    "assets/gaia/resource-index.json",
                    "assets/gaia/blocks/air.json",
                    "assets/gaia/blocks/grass.json",
                    "assets/gaia/blocks/dirt.json",
                    "assets/gaia/blocks/stone.json",
                    "assets/gaia/materials/opaque.json",
                    "assets/gaia/materials/missing.json",
                    "assets/gaia/atlases/blocks.json",
                    "assets/gaia/textures/atlas.png"
                }) {
            assertTrue(
                    buildScript.contains("'" + required + "'"),
                    "Missing packaged-resource check for " + required);
        }
    }
}
