package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RenderArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern COMPOSITION_CONTRACT = Pattern.compile(
            "\\bUi(?:Frame|DrawList|DrawCommand|Renderer)\\b");
    private static final Pattern IMPLEMENTS_CLAUSE =
            Pattern.compile("\\bimplements\\s+([^\\{]+)\\{");
    private static final Pattern WIDGET_INTERFACE = Pattern.compile(
            "(?:^|,)\\s*(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*"
                    + "(?!NotAWidget(?=\\s*(?:<|,|$)))"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$]*)?Widget"
                    + "(?=\\s*(?:<|,|$))");
    private static final Pattern COMPOSITION_METHOD =
            Pattern.compile("\\b(?:append|compose|layout|render)\\s*\\(");
    private static final List<ForbiddenPattern> COMPOSITION_FORBIDDEN = List.of(
            forbiddenPattern("GameLoop", "\\bGameLoop\\b"),
            forbiddenPattern("Renderer", "\\bRenderer\\b"),
            forbiddenPattern("RenderFrameInput", "\\bRenderFrameInput\\b"),
            forbiddenPattern("RenderContext", "\\bRenderContext\\b"),
            forbiddenPattern("RenderPass", "\\b(?:[A-Za-z_$][A-Za-z0-9_$]*)?RenderPass\\b"),
            forbiddenPattern("mutable service", "\\b[A-Za-z_$][A-Za-z0-9_$]*Service\\b"),
            forbiddenPattern("BodyInventory", "\\bBodyInventory\\b"),
            forbiddenPattern("Reservation", "\\b(?:[A-Za-z_$][A-Za-z0-9_$]*)?Reservation\\b"),
            forbiddenPattern("World", "\\bWorld\\b"),
            forbiddenPattern("ChunkRepository", "\\bChunkRepository\\b"),
            forbiddenPattern("PhysicsBody", "\\bPhysicsBody\\b"),
            forbiddenPattern("ServiceLocator", "\\bServiceLocator\\b"),
            forbiddenPattern("InputSnapshot", "\\bInputSnapshot\\b"),
            forbiddenPattern("LWJGL", "\\borg\\.lwjgl\\b"),
            forbiddenPattern("OpenGL package", "\\borg\\.opengl\\b"),
            forbiddenPattern("GLFW", "\\bGLFW\\b"),
            forbiddenPattern("OpenGl", "\\bOpenGl[A-Za-z0-9_$]*\\b"),
            forbiddenPattern("OpenGL", "\\bOpenGL[A-Za-z0-9_$]*\\b"),
            forbiddenPattern("OpenGL call", "\\bgl[A-Z][A-Za-z0-9_$]*\\b"));

    @Test
    void gameSourcesDoNotCallOpenGlDirectly() throws IOException {
        List<String> forbidden =
                List.of(
                        "org.lwjgl.opengl",
                        "glUseProgram",
                        "glBindTexture",
                        "glBindVertexArray",
                        "glDraw");

        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> offenders =
                    sources.filter(Files::isRegularFile)
                            .filter(source -> source.toString().endsWith(".java"))
                            .filter(
                                    source ->
                                            !forbiddenCodeTokens(read(source), forbidden).isEmpty()
                                                    || codeMatches(read(source), "\\bgl[A-Z]\\w*"))
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "Game sources call OpenGL directly: " + offenders);
        }
    }

    @Test
    void gameUiCompositionDependsOnlyOnPresentationAndGenericDrawContracts() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> offenders =
                    sources.filter(Files::isRegularFile)
                            .filter(source -> source.toString().endsWith(".java"))
                            .filter(source -> isCompositionSource(source, read(source)))
                            .filter(source -> !forbiddenCompositionTokens(source, read(source)).isEmpty())
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "Game UI composition reaches renderer integration or mutable gameplay: "
                            + offenders);
        }
        assertTrue(forbiddenCodeTokens("// glDraw\n\"org.lwjgl\"", List.of("org.lwjgl", "glDraw")).isEmpty());
        assertFalse(forbiddenCodeTokens("glDrawArrays();", List.of("glDraw")).isEmpty());
        assertTrue(codeMatches("glDrawArrays();", "\\bgl[A-Z]\\w*"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compositionClassificationCases")
    void classifiesCompositionFromPathNameAndSourceRoleWithoutSupportSuffixBypass(
            String scenario, Path path, String source, boolean expected) {
        assertEquals(expected, isCompositionSource(path, source), scenario);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenDependencyCases")
    void rejectsBoundaryMatchedMutableOrRendererDependency(
            String scenario, String source) {
        assertFalse(forbiddenCompositionTokens(source).isEmpty(), scenario);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedDependencyCases")
    void ignoresCommentsStringsAndAdjacentIdentifiers(
            String scenario, String source) {
        assertTrue(forbiddenCompositionTokens(source).isEmpty(), scenario);
    }

    @Test
    void reportsForbiddenDependencyWhenWidgetIsTheSecondImplementedInterface() {
        Path source = Path.of("elsewhere/Overlay.java");
        String sourceText =
                "abstract class Overlay implements Runnable, Widget { GameLoop loop; }";

        List<Path> offenders =
                Stream.of(source)
                        .filter(candidate -> isCompositionSource(candidate, sourceText))
                        .filter(candidate -> !forbiddenCompositionTokens(sourceText).isEmpty())
                        .toList();

        assertEquals(List.of(source), offenders);
    }

    private static Stream<Arguments> compositionClassificationCases() {
        return Stream.of(
                Arguments.of("external widget path", Path.of("elsewhere/ui/widget/Badge.java"),
                        "final class Badge {}", true),
                Arguments.of("external Widget name", Path.of("elsewhere/BadgeWidget.java"),
                        "final class BadgeWidget {}", true),
                Arguments.of("external Screen name", Path.of("elsewhere/PauseScreen.java"),
                        "final class PauseScreen {}", true),
                Arguments.of("external Hud compositor", Path.of("elsewhere/StatusHud.java"),
                        "final class StatusHud { UiFrame compose() { return null; } }", true),
                Arguments.of("disguised HudSnapshot compositor", Path.of("elsewhere/HudSnapshot.java"),
                        "final class HudSnapshot { void append(UiDrawList out) {} }", true),
                Arguments.of("disguised HudResolver compositor", Path.of("elsewhere/HudResolver.java"),
                        "final class HudResolver { UiDrawCommand render() { return null; } }", true),
                Arguments.of("Widget interface implementation", Path.of("elsewhere/Overlay.java"),
                        "final class Overlay implements Widget {}", true),
                Arguments.of("named Widget interface implementation",
                        Path.of("elsewhere/Overlay.java"),
                        "final class Overlay implements StatusWidget {}", true),
                Arguments.of("Widget as second interface", Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements Runnable, Widget {}", true),
                Arguments.of("fully-qualified Widget as second interface",
                        Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements Runnable, "
                                + "com.overlord.renderer.ui.Widget {}",
                        true),
                Arguments.of("Widget in middle of three interfaces",
                        Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements Runnable, Widget, AutoCloseable {}",
                        true),
                Arguments.of("fully-qualified Widget last of three interfaces",
                        Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements Runnable, AutoCloseable, "
                                + "com.overlord.renderer.ui.Widget {}",
                        true),
                Arguments.of("multiline generic interface list ending in Widget",
                        Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay<T> implements\n"
                                + "        Comparable<Overlay<T>>,\n"
                                + "        com.overlord.renderer.ui.Widget<T>\n"
                                + "        {}",
                        true),
                Arguments.of("NotAWidget interface", Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements NotAWidget {}", false),
                Arguments.of("Widget contract only in comment", Path.of("elsewhere/Overlay.java"),
                        "abstract class Overlay implements Runnable {} "
                                + "// implements Runnable, Widget {}",
                        false),
                Arguments.of("Widget contract only in string", Path.of("elsewhere/Overlay.java"),
                        "String sample = \"class Fake implements Runnable, Widget {}\";",
                        false),
                Arguments.of("Task7 snapshot role", Path.of("elsewhere/HudPresentationSnapshot.java"),
                        "record HudPresentationSnapshot(int activeSlot) {}", false),
                Arguments.of("Task7 presenter role", Path.of("elsewhere/HudPresenter.java"),
                        "final class HudPresenter { HudPresentationSnapshot present() { return null; } }", false),
                Arguments.of("theme support role", Path.of("elsewhere/HudTheme.java"),
                        "final class HudTheme { static final int ACCENT = 1; }", false),
                Arguments.of("assets support role", Path.of("elsewhere/HudAssets.java"),
                        "record HudAssets(String atlas) {}", false));
    }

    private static Stream<Arguments> forbiddenDependencyCases() {
        Stream<Arguments> typeVariants = Stream.of(
                        new ForbiddenType("GameLoop", "com.gaia.GameLoop"),
                        new ForbiddenType("Renderer", "com.overlord.renderer.Renderer"),
                        new ForbiddenType(
                                "RenderFrameInput", "com.overlord.renderer.RenderFrameInput"),
                        new ForbiddenType(
                                "RenderContext", "com.overlord.renderer.pass.RenderContext"),
                        new ForbiddenType(
                                "UiRenderPass", "com.overlord.renderer.pass.UiRenderPass"),
                        new ForbiddenType(
                                "WorldMutationService",
                                "com.overlord.interaction.api.WorldMutationService"),
                        new ForbiddenType(
                                "BodyInventoryService", "com.gaia.inventory.BodyInventoryService"),
                        new ForbiddenType(
                                "InventoryService", "com.overlord.inventory.api.InventoryService"),
                        new ForbiddenType("BodyInventory", "com.gaia.inventory.BodyInventory"),
                        new ForbiddenType(
                                "InventoryReservation",
                                "com.overlord.inventory.api.InventoryReservation"),
                        new ForbiddenType("Reservation", "com.overlord.inventory.api.Reservation"),
                        new ForbiddenType(
                                "WorldItemService", "com.overlord.worlditem.api.WorldItemService"),
                        new ForbiddenType("World", "com.overlord.voxel.World"),
                        new ForbiddenType(
                                "ChunkRepository", "com.overlord.voxel.ChunkRepository"),
                        new ForbiddenType("PhysicsBody", "com.overlord.physics.PhysicsBody"),
                        new ForbiddenType("ServiceLocator", "com.overlord.core.ServiceLocator"),
                        new ForbiddenType(
                                "InputSnapshot", "com.overlord.core.input.InputSnapshot"),
                        new ForbiddenType("GLFW", "org.lwjgl.glfw.GLFW"),
                        new ForbiddenType(
                                "OpenGlRenderStateApi",
                                "com.overlord.renderer.state.OpenGlRenderStateApi"))
                .flatMap(RenderArchitectureTest::typeVariants);
        return Stream.concat(
                typeVariants,
                Stream.of(
                        forbidden("LWJGL package", "import org.lwjgl.*;"),
                        forbidden("OpenGL spelling", "OpenGLFacade graphics;"),
                        forbidden("glXxx call", "glBindTexture(0);")));
    }

    private static Stream<Arguments> allowedDependencyCases() {
        return Stream.of(
                Arguments.of("comments and strings",
                        "// GameLoop Renderer BodyInventory GLFW glDraw\n"
                                + "String text = \"WorldMutationService OpenGl\";"),
                Arguments.of("adjacent identifiers",
                        "GameLoopback loop; TextRenderer text; Worldview view; "
                                + "ChunkRepositoryView chunks; PhysicsBodyState physics;"),
                Arguments.of("presentation inventory values",
                        "BodySlot slot; ItemStack stack; HudPresentationSnapshot snapshot;"));
    }

    private static Arguments forbidden(String scenario, String source) {
        return Arguments.of(scenario, source);
    }

    private static Stream<Arguments> typeVariants(ForbiddenType type) {
        int packageEnd = type.fullyQualified().lastIndexOf('.');
        String packageName = type.fullyQualified().substring(0, packageEnd);
        return Stream.of(
                forbidden(type.simple() + " simple", type.simple() + " value;"),
                forbidden(type.simple() + " FQ", type.fullyQualified() + " value;"),
                forbidden(type.simple() + " wildcard",
                        "import " + packageName + ".*; " + type.simple() + " value;"));
    }

    private static boolean isCompositionSource(Path source, String sourceText) {
        String normalized = source.toString().replace('\\', '/');
        String fileName = source.getFileName().toString();
        String code = sanitizeCode(sourceText);
        if (normalized.contains("/widget/")
                || fileName.endsWith("Widget.java")
                || fileName.endsWith("Screen.java")) {
            return true;
        }
        if (COMPOSITION_CONTRACT.matcher(code).find() || implementsWidget(code)) {
            return true;
        }
        boolean hudName = fileName.contains("Hud") && fileName.endsWith(".java");
        return hudName
                && (!isNonCompositionRole(fileName)
                        || COMPOSITION_METHOD.matcher(code).find());
    }

    private static boolean implementsWidget(String code) {
        var clauses = IMPLEMENTS_CLAUSE.matcher(code);
        while (clauses.find()) {
            if (WIDGET_INTERFACE.matcher(clauses.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonCompositionRole(String fileName) {
        return Pattern.compile(
                ".*(Snapshot|Presenter|Presentation|Visibility|Theme|Assets|AssetLoader|Definition|Resolver|Atlas)\\.java$")
                .matcher(fileName)
                .matches();
    }

    private static List<String> forbiddenCompositionTokens(String source) {
        String code = sanitizeCode(source);
        return COMPOSITION_FORBIDDEN.stream()
                .filter(forbidden -> forbidden.pattern().matcher(code).find())
                .map(ForbiddenPattern::label)
                .toList();
    }

    private static List<String> forbiddenCompositionTokens(Path source, String sourceText) {
        List<String> violations = new java.util.ArrayList<>(
                forbiddenCompositionTokens(sourceText));
        if (source.getFileName().toString().equals("HudFrameCoordinator.java")) {
            violations.remove("InputSnapshot");
        }
        return List.copyOf(violations);
    }

    private static ForbiddenPattern forbiddenPattern(String label, String regex) {
        return new ForbiddenPattern(label, Pattern.compile(regex));
    }

    private static List<String> forbiddenCodeTokens(String source, List<String> forbidden) {
        String code = sanitizeCode(source);
        return forbidden.stream().filter(code::contains).toList();
    }

    private static boolean codeMatches(String source, String pattern) {
        return Pattern.compile(pattern).matcher(sanitizeCode(source)).find();
    }

    private static String sanitizeCode(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'", " ");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }

    private record ForbiddenPattern(String label, Pattern pattern) {}

    private record ForbiddenType(String simple, String fullyQualified) {}
}
