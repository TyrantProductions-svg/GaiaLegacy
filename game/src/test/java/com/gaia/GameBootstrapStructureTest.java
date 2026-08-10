package com.gaia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GameBootstrapStructureTest {
    private enum LexicalState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTED,
        DOUBLE_QUOTED
    }

    @Test
    void worldLoadingDoesNotPublishCombinedMeshData()
            throws IOException {
        String worldLoader =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/"
                                        + "WorldLoader.java"));
        String worldLoadResult =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/"
                                        + "WorldLoadResult.java"));

        assertFalse(worldLoader.contains("combineMeshData"));
        assertFalse(worldLoadResult.contains("float[]"));
    }

    @Test
    void composesIndependentChunkMeshingAndReverseSafeShutdown()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/session/"
                                        + "GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(compact.contains("newChunkMeshManager("));
        assertTrue(compact.contains("Executors.newFixedThreadPool("));
        assertTrue(
                compact.contains(
                        "namedThreadFactory(\"Gaia-Chunk-Mesher\")"));
        assertTrue(
                compact.contains(
                        "newChunkMeshManager("
                                + "world.chunks(),"
                                + "newChunkMeshBuilder(blocks),"
                                + "meshExecutor,"
                                + "engine.getRenderer(),"
                                + "mainThreadGuard,2)"));
        assertTrue(compact.contains("newSessionShutdownBarrier("));
        assertTrue(
                compact.contains(
                        "shutdownBarrier.registerChunkMeshes("));
        assertTrue(
                compact.contains(
                        "shutdownBarrier.registerWorldExecutor("));

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
        String bootstrap =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameBootstrap.java"));
        String factory =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/session/"
                                        + "GameSessionFactory.java"));
        String compact = bootstrap.replaceAll("\\s+", "");

        assertTrue(
                compact.contains(
                        "AssetManagerassetManager="
                                + "newAssetManager("
                                + "GameBootstrap.class.getClassLoader());"));
        assertTrue(
                compact.contains(
                        "newGaiaResourceLoader(assetManager).load();"));
        assertTrue(
                compact.contains(
                        "newEngine(mainThreadGuard,catalog.renderAssets(),"
                                + "assetManager,RenderVisualSettings."
                                + "milestoneOneDefaults(),initialVsync);"));
        assertTrue(compact.contains("newDefaultSettingsPathProvider()"));
        assertTrue(compact.contains("JsonSettingsStore::new"));
        assertTrue(
                factory.contains(
                        "GaiaWorldGenerator.createVisualRevisionCandidate()"));
        assertTrue(
                factory.contains(
                        "WorldGenerationConfig.visualRevisionCandidate()"));
        assertTrue(
                factory.contains(
                        "new SafeSpawnSelector()"));
        assertTrue(factory.contains("new WorldLoader("));
        assertTrue(factory.contains("new ChunkMeshBuilder("));
        assertTrue(
                factory.contains("worldLoader.loadAsync(world)"));
        assertFalse(factory.contains("CompletableFuture.supplyAsync("));
        assertFalse(
                bootstrap.contains(
                        "BlockRegistry." + "init()"));
        assertFalse(
                bootstrap.contains(
                        "BlockRegistry."
                                + "loadAllFromResources()"));
        assertFalse(
                bootstrap.contains(
                        "BlockRegistry." + "GRASS"));
        assertFalse(
                bootstrap.contains(
                        "BlockRegistry." + "DIRT"));
        assertFalse(
                bootstrap.contains(
                        "BlockRegistry." + "STONE"));
        assertFalse(bootstrap.contains("AssetLoadException"));
        assertFalse(bootstrap.contains("ServiceLocator"));

        int assetLoad = compact.indexOf("newGaiaResourceLoader(");
        int settingsOpen = compact.indexOf("ProductSettingsLifecycle.open(");
        int engineConstruction = compact.indexOf("newEngine(");
        int engineInitialization = compact.indexOf("initializedEngine.init()");
        int factoryConstruction =
                compact.indexOf("newGameSessionFactory(");
        assertTrue(assetLoad < engineConstruction);
        assertTrue(assetLoad < settingsOpen);
        assertTrue(settingsOpen < engineConstruction);
        assertTrue(engineConstruction < engineInitialization);
        assertTrue(engineInitialization < factoryConstruction);
    }

    @Test
    void composesReleaseCandidateSimulationAndModeDefaults() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/session/"
                                        + "GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(
                compact.contains(
                        "FIXED_STEP_SECONDS=1.0/60.0;"));
        assertTrue(
                compact.contains(
                        "MAX_FIXED_STEPS_PER_FRAME=8;"));
        assertTrue(
                compact.contains(
                        "newGameModeManager(config.defaultGameMode(),"));
        assertEquals(
                1,
                occurrences(
                        compact,
                        "WorldGenerationConfig.visualRevisionCandidate()"));
    }

    @Test
    void composesOneCommittedFeedbackAuthorityAfterGameplayTransactions() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/gaia/session/GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertEquals(1, occurrences(compact, "newParticleSystem("));
        assertEquals(1, occurrences(compact, "newWorldItemVisualTracker("));
        assertEquals(1, occurrences(compact, "newCommittedBreakVisualAdapter("));
        assertEquals(1, occurrences(compact, "newInteractionFeedbackCoordinator("));
        assertEquals(1, occurrences(compact, "newSynchronousBlockChangeEventPublisher("));
        assertFalse(compact.contains("SynchronousBlockChangeEventPublisher.noSubscribers()"));
        assertTrue(compact.contains("ignored->BlockChangeDecision.ALLOW"));
        assertFalse(compact.contains("feedback::onBlockChanged"));
        assertTrue(occurrences(compact, "ignored->{}") >= 2);
        assertTrue(compact.contains("GameConfig.Interaction.BASE_BREAK_SPEED,feedback"));
        assertTrue(compact.contains("worldItemPickupTransaction,feedback"));
    }

    @Test
    void bootstrapCapturesAppliedSettingsOnlyWhenCreatingANewSession()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/"
                                        + "GameBootstrap.java"));
        String compact = source.replaceAll("\\s+", "");

        assertTrue(source.contains("new GameSessionFactory("));
        assertTrue(
                compact.contains(
                        "()->sessionFactory.create("
                                + "settingsLifecycle.newSessionConfig())"));
        assertTrue(source.contains("ProductSettingsLifecycle.open("));
        assertTrue(source.contains("settingsLifecycle::close"));
        assertFalse(source.contains("InMemorySettingsStore"));
        assertFalse(compact.contains("initialSessionConfig"));
        assertTrue(source.contains("new ProductLoop("));
        assertTrue(source.contains("new EmptySaveCatalog()"));
        assertFalse(source.contains("new GameLoop("));
        assertFalse(source.contains("worldLoader.loadAsync("));
        assertFalse(source.contains("new WorldLoader("));
        assertFalse(source.contains("new FixedStepClock("));
    }

    @Test
    void registersQAndBlockSpawnBarriersForIdempotentShutdownResolution()
            throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/gaia/session/GameSessionFactory.java"));
        String compact = source.replaceAll("\\s+", "");

        assertEquals(1, occurrences(compact, "newInventoryDropController("));
        assertEquals(1, occurrences(compact, "newBlockBreakTransaction("));
        assertTrue(compact.contains(
                "shutdown.register(\"inventory-drop\",inventoryDrop::close)"));
        assertTrue(compact.contains(
                "shutdown.register(\"block-break\",blockBreak::close)"));
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

    @Test
    void verifiesShaderResourcesInTheInstalledEngineJar()
            throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertTrue(
                buildScript.contains(
                        "tasks.register('verifyInstalledShaderResources')"));
        assertTrue(buildScript.contains("dependsOn tasks.named('installDist')"));
        assertTrue(
                buildScript.contains("build/install/game/lib"));
        assertTrue(buildScript.contains("engine-*.jar"));
        assertTrue(buildScript.contains("new java.util.zip.ZipFile(engineJar)"));
        assertTrue(
                buildScript.contains(
                        "assets/overlord/shaders/world.vert"));
        assertTrue(
                buildScript.contains(
                        "assets/overlord/shaders/world.frag"));
        assertTrue(buildScript.contains("assets/overlord/shaders/sky.vert"));
        assertTrue(buildScript.contains("assets/overlord/shaders/sky.frag"));
        assertTrue(
                taskBlockDependsOn(
                        buildScript,
                        "check",
                        "verifyInstalledShaderResources"));
    }

    @Test
    void installedShaderDependencyMustAppearInsideCheckBlock() {
        String script =
                "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('other')\n"
                        + "}\n"
                        + "dependsOn tasks.named('verifyInstalledShaderResources')";

        assertFalse(
                taskBlockDependsOn(
                        script,
                        "check",
                        "verifyInstalledShaderResources"));
    }

    @Test
    void installedShaderDependencyMayAppearInALaterCheckBlock() {
        String script =
                "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('other')\n"
                        + "}\n"
                        + "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('verifyInstalledShaderResources')\n"
                        + "}";

        assertTrue(
                taskBlockDependsOn(
                        script,
                        "check",
                        "verifyInstalledShaderResources"));
    }

    @Test
    void installedShaderDependencyIgnoresCommentsStringsAndQuotedBraces() {
        for (String script :
                java.util.List.of(
                        "tasks.named('check') {\n"
                                + "    // dependsOn tasks.named('verifyInstalledShaderResources')\n"
                                + "}",
                        "tasks.named('check') {\n"
                                + "    /*\n"
                                + "    dependsOn tasks.named('verifyInstalledShaderResources')\n"
                                + "    */\n"
                                + "}",
                        "tasks.named('check') {\n"
                                + "    \"dependsOn tasks.named('verifyInstalledShaderResources')\"\n"
                                + "}",
                        "\"tasks.named('check') { dependsOn tasks.named('verifyInstalledShaderResources') }\"")) {
            assertFalse(
                    taskBlockDependsOn(
                            script,
                            "check",
                            "verifyInstalledShaderResources"));
        }

        String validScript =
                "tasks.named('check') {\n"
                        + "    def message = \"{ escaped quote: \\\" }\"\n"
                        + "    dependsOn tasks.named('verifyInstalledShaderResources')\n"
                        + "}";
        assertTrue(
                taskBlockDependsOn(
                        validScript,
                        "check",
                        "verifyInstalledShaderResources"));
    }

    private static boolean taskBlockDependsOn(
            String script, String taskName, String dependencyName) {
        String code = sanitizeCode(script);
        Matcher taskBlock =
                Pattern.compile(
                                "tasks\\.named\\(\\s*['\"]"
                                        + Pattern.quote(taskName)
                                        + "['\"]\\s*\\)\\s*\\{")
                        .matcher(script);
        Pattern dependency =
                Pattern.compile(
                        "(?m)^\\s*dependsOn\\s+tasks\\.named\\(\\s*['\"]"
                                + Pattern.quote(dependencyName)
                                + "['\"]\\s*\\)");
        while (taskBlock.find()) {
            if (!isCodeAt(script, code, taskBlock.start())) {
                continue;
            }
            int openingBrace =
                    nextCodeCharacter(code, taskBlock.end() - 1, '{');
            if (openingBrace < 0) {
                continue;
            }
            int closingBrace =
                    matchingDelimiter(code, openingBrace, '{', '}');
            String body =
                    script.substring(
                            openingBrace + 1,
                            closingBrace);
            String bodyCode =
                    code.substring(openingBrace + 1, closingBrace);
            Matcher dependencies = dependency.matcher(body);
            while (dependencies.find()) {
                int dependencyToken =
                        dependencies.start()
                                + dependencies.group().indexOf("dependsOn");
                if (isCodeAt(body, bodyCode, dependencyToken)) {
                    return true;
                }
            }
        }
        return false;
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

    private static int matchingDelimiter(
            String source, int openingIndex, char opening, char closing) {
        String code = sanitizeCode(source);
        int depth = 0;
        for (int index = openingIndex; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == opening) {
                depth++;
            } else if (current == closing && --depth == 0) {
                return index;
            }
        }
        throw new AssertionError(
                "Unclosed delimiter starting at " + openingIndex);
    }

    private static String sanitizeCode(String source) {
        StringBuilder sanitized = new StringBuilder(source.length());
        LexicalState state = LexicalState.CODE;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.BLOCK_COMMENT;
                    } else if (current == '\'') {
                        sanitized.append(' ');
                        state = LexicalState.SINGLE_QUOTED;
                    } else if (current == '"') {
                        sanitized.append(' ');
                        state = LexicalState.DOUBLE_QUOTED;
                    } else {
                        sanitized.append(current);
                    }
                }
                case LINE_COMMENT -> {
                    sanitized.append(whitespace(current));
                    if (current == '\n' || current == '\r') {
                        state = LexicalState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.CODE;
                    } else {
                        sanitized.append(whitespace(current));
                    }
                }
                case SINGLE_QUOTED -> {
                    if (current == '\\' && next != '\0') {
                        sanitized.append(' ').append(whitespace(next));
                        index++;
                    } else {
                        sanitized.append(whitespace(current));
                        if (current == '\'') {
                            state = LexicalState.CODE;
                        }
                    }
                }
                case DOUBLE_QUOTED -> {
                    if (current == '\\' && next != '\0') {
                        sanitized.append(' ').append(whitespace(next));
                        index++;
                    } else {
                        sanitized.append(whitespace(current));
                        if (current == '"') {
                            state = LexicalState.CODE;
                        }
                    }
                }
            }
        }
        return sanitized.toString();
    }

    private static char whitespace(char character) {
        return character == '\n' || character == '\r' ? character : ' ';
    }

    private static int nextCodeCharacter(String code, int start, char expected) {
        for (int index = start; index < code.length(); index++) {
            if (code.charAt(index) == expected) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isCodeAt(String source, String code, int index) {
        return index >= 0
                && index < source.length()
                && code.charAt(index) == source.charAt(index);
    }
}
