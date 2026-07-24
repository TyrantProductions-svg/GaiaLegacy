package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.InventoryService;
import com.overlord.interaction.api.InteractionViewModel;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.worlditem.api.WorldItemService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InteractionArchitectureTest {
    private static final Path REPOSITORY_ROOT =
            Path.of("..").toAbsolutePath().normalize();
    private static final Path ENGINE_MAIN =
            Path.of("src/main/java").toAbsolutePath().normalize();
    private static final Path GAME_MAIN =
            REPOSITORY_ROOT.resolve("game/src/main/java");
    private static final Set<Path> DIRECT_WORLD_WRITE_ALLOWLIST =
            Set.of(
                    GAME_MAIN.resolve(
                                    "com/gaia/world/WorldLoader.java")
                            .normalize(),
                    GAME_MAIN.resolve(
                                    "com/gaia/world/GaiaWorldGenerator.java")
                            .normalize());
    private static final Pattern DIRECT_SET_BLOCK_CALL =
            Pattern.compile("\\.\\s*setBlock\\s*\\(");

    @Test
    void engineContractsDoNotDependOnGameGraphicsOrGlfw() throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources =
                javaSources(
                        ENGINE_MAIN.resolve("com/overlord/interaction/api"),
                        ENGINE_MAIN.resolve("com/overlord/inventory/api"))) {
            offenders =
                    sources.filter(
                                    source -> {
                                        String text = read(source);
                                        return text.contains("com.gaia")
                                                || text.contains("com.overlord.renderer")
                                                || text.contains("org.lwjgl")
                                                || text.contains("GLFW");
                                    })
                            .toList();
        }
        assertTrue(offenders.isEmpty(), "API boundary offenders: " + offenders);
    }

    @Test
    void uiViewModelCannotReturnMutableInventoryService() {
        for (Method method : BodyInventoryViewModel.class.getMethods()) {
            assertFalse(
                    InventoryService.class.isAssignableFrom(method.getReturnType()),
                    method.toString());
        }
    }

    @Test
    void interactionViewModelCannotReturnMutableServices() {
        for (Method method : InteractionViewModel.class.getMethods()) {
            assertFalse(
                    InventoryService.class.isAssignableFrom(method.getReturnType()),
                    method.toString());
            assertFalse(
                    WorldMutationService.class.isAssignableFrom(method.getReturnType()),
                    method.toString());
            assertFalse(
                    WorldItemService.class.isAssignableFrom(method.getReturnType()),
                    method.toString());
        }
    }

    @Test
    void gameplaySourcesDoNotCallWorldSetBlock() throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources = javaSources(GAME_MAIN)) {
            offenders =
                    sources.filter(
                                    source ->
                                            !isDirectWorldWriteAllowlisted(
                                                    source))
                            .filter(
                                    source -> {
                                        String text = read(source);
                                        return DIRECT_SET_BLOCK_CALL.matcher(text).find();
                                    })
                            .toList();
        }
        assertTrue(
                offenders.isEmpty(),
                "Gameplay bypasses WorldMutationService: " + offenders);
    }

    @Test
    void directWorldWriteWhitelistAllowsOnlyExactGenerationFiles() {
        Path worldLoader =
                GAME_MAIN.resolve(
                        "com/gaia/world/WorldLoader.java");
        Path worldGenerator =
                GAME_MAIN.resolve(
                        "com/gaia/world/GaiaWorldGenerator.java");

        assertTrue(Files.isRegularFile(worldLoader));
        assertTrue(Files.isRegularFile(worldGenerator));
        assertTrue(isDirectWorldWriteAllowlisted(worldLoader));
        assertTrue(isDirectWorldWriteAllowlisted(worldGenerator));
        assertFalse(
                isDirectWorldWriteAllowlisted(
                        GAME_MAIN.resolve(
                                "com/gaia/world/FutureGameplayWriter.java")));
    }

    @Test
    void directWorldWritePatternRecognizesSetBlockCalls() {
        assertTrue(DIRECT_SET_BLOCK_CALL.matcher("world.setBlock(x, y, z)").find());
        assertTrue(DIRECT_SET_BLOCK_CALL.matcher("world.setBlock (x, y, z)").find());
        assertFalse(DIRECT_SET_BLOCK_CALL.matcher("setBlock is forbidden").find());
    }

    @Test
    void standardMutationServiceDoesNotUseQueuedEventBus() throws IOException {
        String source =
                Files.readString(
                        ENGINE_MAIN.resolve(
                                "com/overlord/interaction/DefaultWorldMutationService.java"));
        assertFalse(
                source.contains("EventBus"),
                "DefaultWorldMutationService must not queue completion through EventBus");
        assertFalse(
                source.contains("ServiceLocator"),
                "DefaultWorldMutationService must use explicit dependencies");
        assertFalse(
                source.contains("ChunkDirtyTracker"),
                "DefaultWorldMutationService must use repository-issued dirty revisions");
        assertFalse(
                source.contains("affectedByBlock"),
                "DefaultWorldMutationService must not calculate dirty chunks");
        assertFalse(
                source.contains("ChunkKey.fromWorld"),
                "DefaultWorldMutationService must not derive dirty chunk keys");
        assertFalse(
                source.contains("localCoordinate"),
                "DefaultWorldMutationService must not calculate theoretical neighbors");
    }

    @Test
    void blockWorldAccessExposesOutcomeCompareAndSetWithoutBooleanSetBlock()
            throws Exception {
        Method compareAndSet =
                BlockWorldAccess.class.getMethod(
                        "compareAndSetBlock",
                        int.class,
                        int.class,
                        int.class,
                        com.overlord.assets.ResourceLocation.class,
                        com.overlord.assets.ResourceLocation.class);

        assertEquals(
                BlockWorldMutationOutcome.class,
                compareAndSet.getReturnType());
        for (Method method : BlockWorldAccess.class.getMethods()) {
            assertFalse(
                    method.getName().equals("setBlock")
                            && method.getReturnType() == boolean.class,
                    method.toString());
        }
    }

    @Test
    void phaseSevenDocumentsProtectPromptSuiteV21Decisions()
            throws IOException {
        Path contract =
                REPOSITORY_ROOT.resolve(
                        "docs/architecture/interaction-inventory-contract.md");
        Path handoff =
                REPOSITORY_ROOT.resolve(
                        "docs/agent-handoffs/phase-07-handoff.md");

        assertPromptSuiteV21Decisions(read(contract), contract);
        assertPromptSuiteV21Decisions(read(handoff), handoff);
    }

    private static void assertPromptSuiteV21Decisions(
            String document, Path source) {
        assertContainsAll(
                document,
                source,
                "canonical stack and command/view distinction",
                "canonical immutable `ItemStack`",
                "`ResourceLocation`",
                "positive count",
                "`ItemStackView`",
                "read-only snapshot/projection",
                "not a second domain stack",
                "no second item registry",
                "Phase 8 must not define another `ItemStack`");
        assertContainsAll(
                document,
                source,
                "inventory reservation semantics",
                "`InventoryReservation`",
                "`reserve`",
                "`commit`",
                "`rollback`",
                "idempotent",
                "ordinary inventory state changes",
                "`INSERT`",
                "`EXTRACT`",
                "remainder",
                "explicit failure");
        assertContainsAll(
                document,
                source,
                "world-item source of truth",
                "unique `WorldItemService`",
                "`WorldItemSpawnRequest`",
                "`WorldItemSpawnResult`",
                "`WorldItemReservation`",
                "`WorldItemSnapshot`",
                "stable `WorldItemId`",
                "Q drop",
                "block drops",
                "Phase 11 physics drops",
                "share the service");
        assertContainsAll(
                document,
                source,
                "repository-owned dirty outcomes",
                "`ChunkRepository` owns dirty propagation and revision outcomes",
                "`ChunkMutationOutcome`",
                "`BlockWorldMutationOutcome`",
                "`ChunkDirtyEvent` is post-commit observation only",
                "missing boundary neighbors are not reported as dirtied",
                "Phase 3 stale-result and mesh lifecycle remain authoritative");
        assertContainsAll(
                document,
                source,
                "read-only interaction projection",
                "read-only `InteractionViewModel`",
                "target",
                "face",
                "progress",
                "mode",
                "active item",
                "failure reason");
        assertContainsAll(
                document,
                source,
                "mutation dispatch failure policy",
                "Before-event mutation reentrancy is prohibited",
                "post-write subscriber failure does not roll back",
                "not automatically retried",
                "`mutationApplied() == true` forbids blind caller retry");
        assertContainsAll(
                document,
                source,
                "v2.1 implementation non-goals",
                "no Phase 8 gameplay",
                "formal inventory",
                "production world entity",
                "physics drop",
                "renderer",
                "controller",
                "mesh-manager",
                "UI implementation was added");
    }

    private static void assertContainsAll(
            String document,
            Path source,
            String decision,
            String... requiredTerms) {
        List<String> missing =
                Stream.of(requiredTerms)
                        .filter(term -> !document.contains(term))
                        .toList();
        assertTrue(
                missing.isEmpty(),
                source + " is missing " + decision + ": " + missing);
    }

    private static Stream<Path> javaSources(Path... roots) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walked = Files.walk(root)) {
                sources.addAll(
                        walked.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".java"))
                                .toList());
            }
        }
        return List.copyOf(sources).stream();
    }

    private static boolean isDirectWorldWriteAllowlisted(
            Path source) {
        return DIRECT_WORLD_WRITE_ALLOWLIST.contains(
                source.toAbsolutePath().normalize());
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + source, failure);
        }
    }
}
