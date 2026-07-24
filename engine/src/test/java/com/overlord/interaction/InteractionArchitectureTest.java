package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.InventoryService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InteractionArchitectureTest {
    private static final Path ENGINE_MAIN = Path.of("src/main/java");
    private static final Path GAME_MAIN = Path.of("../game/src/main/java");

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
    void gameplaySourcesDoNotCallWorldSetBlock() throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources = javaSources(GAME_MAIN)) {
            offenders =
                    sources.filter(
                                    source ->
                                            !source.startsWith(
                                                    GAME_MAIN.resolve("com/gaia/world")))
                            .filter(
                                    source -> {
                                        String text = read(source);
                                        return text.contains("com.overlord.voxel.World")
                                                && text.contains(".setBlock(");
                                    })
                            .toList();
        }
        assertTrue(
                offenders.isEmpty(),
                "Gameplay bypasses WorldMutationService: " + offenders);
    }

    @Test
    void standardMutationServiceDoesNotUseQueuedEventBus() throws IOException {
        String source =
                Files.readString(
                        ENGINE_MAIN.resolve(
                                "com/overlord/interaction/DefaultWorldMutationService.java"));
        assertFalse(source.contains("EventBus"));
        assertFalse(source.contains("ServiceLocator"));
    }

    private static Stream<Path> javaSources(Path... roots) throws IOException {
        Stream<Path> combined = Stream.empty();
        for (Path root : roots) {
            combined =
                    Stream.concat(
                            combined,
                            Files.walk(root)
                                    .filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(".java")));
        }
        return combined;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + source, failure);
        }
    }
}
