package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudSlotSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class Task10WidgetTestSupport {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final Map<Integer, BitmapGlyph> GLYPHS = glyphs();

    private Task10WidgetTestSupport() {}

    static TextRenderer textRenderer() {
        return new TextRenderer(new BitmapFont(128, 64, GLYPHS, GLYPHS.get(0xfffd)));
    }

    static UiLayoutContext layout(
            int logicalWidth,
            int logicalHeight,
            int framebufferWidth,
            int framebufferHeight,
            float contentScaleX,
            float contentScaleY) {
        return new UiLayoutContext(new RenderSurfaceMetrics(
                logicalWidth,
                logicalHeight,
                framebufferWidth,
                framebufferHeight,
                contentScaleX,
                contentScaleY));
    }

    static HudVisibility visible(boolean debugVisible) {
        return new HudVisibility(
                true,
                debugVisible,
                true,
                HudVisibility.Lifecycle.RUNNING,
                HudVisibility.Reason.VISIBLE);
    }

    static HudVisibility hudDisabledDebugVisible() {
        return new HudVisibility(
                false,
                true,
                false,
                HudVisibility.Lifecycle.RUNNING,
                HudVisibility.Reason.HUD_DISABLED);
    }

    static HudVisibility hidden(HudVisibility.Reason reason) {
        HudVisibility.Lifecycle lifecycle = switch (reason) {
            case LOADING -> HudVisibility.Lifecycle.LOADING;
            case SHUTDOWN -> HudVisibility.Lifecycle.SHUTDOWN;
            default -> HudVisibility.Lifecycle.RUNNING;
        };
        return new HudVisibility(false, false, false, lifecycle, reason);
    }

    static HudPresentationSnapshot snapshot(
            GameMode mode,
            HudPresentationSnapshot.InteractionPresentation interaction,
            HudVisibility visibility,
            Optional<HudPresentationSnapshot.ModeNotice> notice,
            HudDebugSnapshot debug) {
        EnumMap<BodySlot, HudSlotSnapshot> slots = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            slots.put(slot, HudSlotSnapshot.empty(slot, slot == BodySlot.LEFT_HAND));
        }
        return new HudPresentationSnapshot(
                slots,
                BodySlot.LEFT_HAND,
                false,
                Optional.empty(),
                Optional.empty(),
                mode,
                interaction,
                visibility,
                Optional.empty(),
                Optional.empty(),
                notice,
                debug);
    }

    static HudPresentationSnapshot basic(GameMode mode, HudVisibility visibility) {
        return snapshot(
                mode,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                visibility,
                Optional.empty(),
                emptyDebug());
    }

    static HudPresentationSnapshot failure(String code, HudVisibility visibility) {
        return snapshot(
                GameMode.SURVIVAL,
                new HudPresentationSnapshot.InteractionPresentation(
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        InteractionMode.NONE,
                        Optional.empty(),
                        Optional.of(new InteractionFailureReason(ResourceLocation.parse(code))),
                        0),
                visibility,
                Optional.empty(),
                emptyDebug());
    }

    static HudPresentationSnapshot debug(
            HudVisibility visibility, HudDebugSnapshot debug, boolean targetPresent) {
        Optional<BlockHitResult> target = targetPresent ? Optional.of(target()) : Optional.empty();
        Optional<BlockFace> face = targetPresent ? Optional.of(BlockFace.EAST) : Optional.empty();
        return snapshot(
                GameMode.SURVIVAL,
                new HudPresentationSnapshot.InteractionPresentation(
                        target,
                        face,
                        0,
                        InteractionMode.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        0),
                visibility,
                Optional.empty(),
                debug);
    }

    static HudDebugSnapshot emptyDebug() {
        return new HudDebugSnapshot(
                Optional.empty(),
                new HudDebugSnapshot.FeetPosition(0, 0, 0),
                new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0));
    }

    static List<String> fontLines(UiFrame frame) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Double currentTop = null;
        for (UiDrawCommand command : frame.commands()) {
            if (command.texture() != UiTextureId.FONT_ATLAS) {
                continue;
            }
            if (currentTop != null && currentTop.doubleValue() != command.framebufferBounds().top()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            currentTop = command.framebufferBounds().top();
            current.appendCodePoint(codePoint(command.uv()));
        }
        if (currentTop != null) {
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    static List<UiDrawCommand> fontCommands(UiFrame frame) {
        return frame.commands().stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .toList();
    }

    static ExpectedField field(Class<?> type, int modifiers) {
        return new ExpectedField(type, modifiers);
    }

    static void assertExactProductionBoundary(
            Class<?> widgetType,
            String sourceFile,
            Map<String, ExpectedField> expectedFields,
            Set<String> expectedImports,
            Set<Class<?>> allowedSignatureTypes) throws IOException {
        Map<String, java.lang.reflect.Field> actualFields = Arrays.stream(
                        widgetType.getDeclaredFields())
                .collect(Collectors.toMap(java.lang.reflect.Field::getName, field -> field));
        assertEquals(expectedFields.keySet(), actualFields.keySet(), "declared field whitelist");
        for (Map.Entry<String, ExpectedField> entry : expectedFields.entrySet()) {
            java.lang.reflect.Field actual = actualFields.get(entry.getKey());
            assertEquals(entry.getValue().type(), actual.getType(), entry.getKey() + " type");
            assertEquals(
                    entry.getValue().modifiers(),
                    actual.getModifiers(),
                    entry.getKey() + " modifiers");
        }

        Path source = Path.of("src/main/java/com/gaia/ui/widget", sourceFile);
        String sourceText = Files.readString(source);
        Set<String> actualImports = sourceText.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length(), line.length() - 1))
                .collect(Collectors.toSet());
        assertEquals(expectedImports, actualImports, "production source import allowlist");
        for (String forbidden : List.of(
                "InputSnapshot",
                "InventoryService",
                "BodyInventoryService",
                "WorldMutation",
                "WorldItemService",
                "BlockInteractionController",
                "ChunkRepository",
                "PhysicsBody",
                "ServiceLocator",
                "AtomicReference",
                "EventBus",
                "EventDispatcher",
                "org.lwjgl",
                "OpenGL",
                "GLFW")) {
            assertFalse(sourceText.contains(forbidden), "forbidden production dependency " + forbidden);
        }

        Set<Class<?>> signatureTypes = new HashSet<>();
        actualFields.values().forEach(field -> signatureTypes.add(field.getType()));
        Arrays.stream(widgetType.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .forEach(signatureTypes::add);
        Arrays.stream(widgetType.getDeclaredMethods()).forEach(method -> {
            signatureTypes.add(method.getReturnType());
            signatureTypes.addAll(List.of(method.getParameterTypes()));
        });
        assertTrue(
                allowedSignatureTypes.containsAll(signatureTypes),
                "unexpected production bytecode signature types " + signatureTypes.stream()
                        .filter(type -> !allowedSignatureTypes.contains(type))
                        .map(Class::getName)
                        .sorted()
                        .toList());
    }

    private static int codePoint(UiUvRect uv) {
        return GLYPHS.entrySet().stream()
                .filter(entry -> entry.getValue().uv().equals(uv))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static Map<Integer, BitmapGlyph> glyphs() {
        Map<Integer, BitmapGlyph> result = new LinkedHashMap<>();
        int cell = 0;
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            result.put(codePoint, glyph(codePoint, cell++));
        }
        result.put(0x221e, glyph(0x221e, cell++));
        result.put(0xfffd, glyph(0xfffd, cell));
        return result;
    }

    private static BitmapGlyph glyph(int codePoint, int cell) {
        int column = cell % 16;
        int row = cell / 16;
        return new BitmapGlyph(
                codePoint,
                new UiUvRect(
                        column / 16.0f,
                        row / 8.0f,
                        (column + 1) / 16.0f,
                        (row + 1) / 8.0f),
                8,
                0,
                8);
    }

    private static BlockHitResult target() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3, DIRT, 1, 0, 0, 2, 2.5f, 3.5f, 2);
    }

    record ExpectedField(Class<?> type, int modifiers) {
        ExpectedField {
            if (type == null) {
                throw new NullPointerException("type");
            }
            if ((modifiers & Modifier.FINAL) == 0) {
                throw new IllegalArgumentException("widget fields must be final");
            }
        }
    }
}
