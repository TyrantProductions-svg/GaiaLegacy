package com.overlord.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.InventoryService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InteractionArchitectureTest {
    private static final Path ENGINE_MAIN = Path.of("src/main/java");
    private static final Path GAME_MAIN = Path.of("../game/src/main/java");
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
                                        return DIRECT_SET_BLOCK_CALL.matcher(text).find();
                                    })
                            .toList();
        }
        assertTrue(
                offenders.isEmpty(),
                "Gameplay bypasses WorldMutationService: " + offenders);
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

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + source, failure);
        }
    }
}
