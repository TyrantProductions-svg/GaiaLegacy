package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudSlotSnapshot;
import com.gaia.ui.HudVisibility;
import com.gaia.ui.UiIconAtlas;
import com.gaia.ui.UiIconDefinition;
import com.gaia.ui.UiIconResolver;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class BodyInventoryHudTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation GRASS = ResourceLocation.parse("gaia:grass");
    private static final ResourceLocation LONG = ResourceLocation.parse("gaia:long_name");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final ResourceLocation UNKNOWN = ResourceLocation.parse("gaia:unknown");

    @Test
    void ordersNormalLayersAndSnapsTheExactCompactSlotAndIconRectangles() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.LEFT_HAND,
                Map.of(
                        BodySlot.LEFT_HAND, new ItemStack(DIRT, 3),
                        BodySlot.RIGHT_HAND, new ItemStack(STONE, 4)));

        List<UiDrawCommand> commands = fixtures.render(snapshot).commands();

        assertTrue(commands.size() >= 3, "three physical membrane backgrounds must be first");
        assertEquals(
                List.of(
                        new UiRect(350, 542, 396, 588),
                        new UiRect(404, 542, 450, 588),
                        new UiRect(381, 498, 419, 536)),
                commands.subList(0, 3).stream().map(UiDrawCommand::framebufferBounds).toList());
        assertTrue(commands.subList(0, 3).stream()
                .allMatch(command -> command.texture() == UiTextureId.SOLID));
        assertEquals(
                List.of(
                        new UiRect(350, 542, 396, 588),
                        new UiRect(404, 542, 450, 588),
                        new UiRect(381, 498, 419, 536),
                        new UiRect(404, 542, 450, 543),
                        new UiRect(404, 587, 450, 588),
                        new UiRect(404, 543, 405, 587),
                        new UiRect(449, 543, 450, 587),
                        new UiRect(381, 498, 419, 499),
                        new UiRect(381, 535, 419, 536),
                        new UiRect(381, 499, 382, 535),
                        new UiRect(418, 499, 419, 535),
                        new UiRect(350, 542, 396, 543),
                        new UiRect(350, 587, 396, 588),
                        new UiRect(350, 543, 351, 587),
                        new UiRect(395, 543, 396, 587),
                        new UiRect(353, 545, 393, 546),
                        new UiRect(353, 584, 393, 585),
                        new UiRect(353, 546, 354, 584),
                        new UiRect(392, 546, 393, 584),
                        new UiRect(392, 532, 398, 533),
                        new UiRect(397, 533, 403, 535),
                        new UiRect(402, 532, 408, 533)),
                commands.subList(0, 22).stream()
                        .map(UiDrawCommand::framebufferBounds).toList());
        assertEquals(
                List.of(
                        com.gaia.ui.GaiaUiTheme.VOID_BACKGROUND,
                        com.gaia.ui.GaiaUiTheme.VOID_BACKGROUND,
                        com.gaia.ui.GaiaUiTheme.VOID_BACKGROUND,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO,
                        com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM,
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM),
                commands.subList(0, 22).stream().map(UiDrawCommand::tint).toList());
        assertEquals(
                List.of(new UiRect(361, 549, 385, 573), new UiRect(415, 549, 439, 573)),
                iconCommands(commands).stream().map(UiDrawCommand::framebufferBounds).toList());
        assertEquals(List.of(fixtures.uv(DIRT), fixtures.uv(STONE)),
                iconCommands(commands).stream().map(UiDrawCommand::uv).toList());
        assertLayerOrder(commands);
        assertEquals(57, commands.size());
        assertEquals(22, firstIndex(commands, UiTextureId.ICON_ATLAS));
        assertEquals(24, firstIndex(commands, UiTextureId.FONT_ATLAS));
        assertTrue(hasGlyphAt(commands, fixtures, '3', new UiRect(385, 576, 389, 580)));
        assertTrue(hasGlyphAt(commands, fixtures, '4', new UiRect(439, 576, 443, 580)));
    }

    @Test
    void movesTheActiveHaloWithTheApprovedCubicEaseOutSlotTransition() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot target = fixtures.normal(
                BodySlot.RIGHT_HAND,
                Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 3),
                        BodySlot.RIGHT_HAND, new ItemStack(STONE, 4)));

        assertEquals(new UiRect(350, 542, 396, 543), activePrimaryTop(
                fixtures.render(withTransition(target, 0.0d)).commands()));
        assertEquals(new UiRect(397, 542, 443, 543), activePrimaryTop(
                fixtures.render(withTransition(target, 0.5d)).commands()));
        assertEquals(new UiRect(404, 542, 450, 543), activePrimaryTop(
                fixtures.render(withTransition(target, 0.149d / 0.150d)).commands()));
        assertEquals(new UiRect(404, 542, 450, 543), activePrimaryTop(
                fixtures.render(target).commands()));
    }

    @Test
    void keepsInventoryGlyphsOnPixelGridSafeScalesAcrossSupportedDpi() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.LEFT_HAND,
                Map.of(BodySlot.RIGHT_HAND, new ItemStack(STONE, 12)));

        for (float scale : List.of(1.0f, 1.25f, 1.5f, 2.0f)) {
            RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                    800,
                    600,
                    Math.round(800 * scale),
                    Math.round(600 * scale),
                    scale,
                    scale);
            List<UiDrawCommand> glyphs = fixtures.render(snapshot, surface).commands().stream()
                    .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                    .toList();

            assertFalse(glyphs.isEmpty(), "scale=" + scale);
            for (UiDrawCommand glyph : glyphs) {
                assertPixelGridSafe(glyph.framebufferBounds().right()
                        - glyph.framebufferBounds().left(), scale, "width");
                assertPixelGridSafe(glyph.framebufferBounds().bottom()
                        - glyph.framebufferBounds().top(), scale, "height");
            }
            if (scale == 1.5f) {
                assertTrue(glyphs.stream().allMatch(command ->
                                command.framebufferBounds().right()
                                                - command.framebufferBounds().left()
                                        >= 8.0d),
                        "150% DPI inventory text must not downsample 8x8 glyphs");
            }
        }
    }

    @Test
    void keepsActiveAndEmptyLabelsSeparatedAfterTheCrispDpiScaleStep() {
        Fixtures fixtures = new Fixtures();
        List<Fixtures.TextRun> runs = fixtures.textRuns(fixtures.render(
                fixtures.normal(BodySlot.LEFT_HAND, Map.of()),
                new RenderSurfaceMetrics(800, 600, 1200, 900, 1.5f, 1.5f))
                .commands());

        UiRect active = runs.stream()
                .filter(run -> run.text().equals("ACTIVE"))
                .findFirst().orElseThrow().bounds();
        UiRect empty = runs.stream()
                .filter(run -> run.text().equals("EMPTY"))
                .findFirst().orElseThrow().bounds();

        assertTrue(active.bottom() <= empty.top(), active + " overlaps " + empty);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(floats = {1f, 1.25f, 1.5f, 2f, 3f})
    void productionInterStateLabelsRemainSeparatedAndInsideFramebuffer(float dpi) {
        var assets = new com.gaia.ui.GaiaUiAssetLoader(new com.overlord.assets.AssetManager(
                getClass().getClassLoader())).load();
        var catalog = assets.renderAssets().typography();
        var font = catalog.resolve(com.overlord.renderer.ui.TypographyRole.HUD).font();
        var hud = new BodyInventoryHud(new UiIconResolver(assets.icons(), ignored -> {}),
                new TextRenderer(catalog));
        UiDrawList out = new UiDrawList();
        hud.append(new Fixtures().normal(BodySlot.LEFT_HAND, Map.of()),
                new UiLayoutContext(new RenderSurfaceMetrics(800, 600,
                        Math.round(800 * dpi), Math.round(600 * dpi), dpi, dpi)), out);
        List<UiDrawCommand> glyphs = out.seal().commands().stream()
                .filter(command -> command.texture() == UiTextureId.FONT_BODY).toList();
        List<UiDrawCommand> active = productionLabel(glyphs, font, "ACTIVE");
        List<UiDrawCommand> empty = productionLabel(glyphs, font, "EMPTY");
        double activeBottom = active.stream().mapToDouble(c -> c.framebufferBounds().bottom())
                .max().orElseThrow();
        double emptyTop = empty.stream().mapToDouble(c -> c.framebufferBounds().top())
                .min().orElseThrow();
        assertTrue(activeBottom < emptyTop,
                "DPI " + dpi + ": ACTIVE bottom " + activeBottom + " overlaps EMPTY top " + emptyTop);
        assertTrue(glyphs.stream().allMatch(c -> c.framebufferBounds().bottom() <= 600 * dpi),
                "all state labels must remain on screen at DPI " + dpi);
    }

    private static List<UiDrawCommand> productionLabel(
            List<UiDrawCommand> glyphs, BitmapFont font, String label) {
        var expected = label.codePoints().mapToObj(cp -> font.glyph(cp).uv()).toList();
        for (int start = 0; start <= glyphs.size() - expected.size(); start++) {
            var candidate = glyphs.subList(start, start + expected.size());
            if (candidate.stream().map(UiDrawCommand::uv).toList().equals(expected)) {
                return candidate;
            }
        }
        throw new AssertionError("missing production label " + label);
    }

    @Test
    void fullyOrdersPopulatedNormalTemporaryNameCommandSignature() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.LEFT_HAND,
                Map.of(
                        BodySlot.LEFT_HAND, new ItemStack(DIRT, 7),
                        BodySlot.RIGHT_HAND, new ItemStack(STONE, 8),
                        BodySlot.MOUTH, new ItemStack(GRASS, 9)),
                Optional.of(new HudPresentationSnapshot.TimedItemName(LONG, 1, 0.75)));

        List<String> signatures = fixtures.commandSignatures(
                fixtures.render(snapshot).commands());

        assertEquals(signatureLines("""
                SOLID|[350,542..396,588]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[404,542..450,588]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[381,498..419,536]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[404,542..450,543]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[404,587..450,588]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[404,543..405,587]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[449,543..450,587]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,498..419,499]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,535..419,536]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,499..382,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[418,499..419,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,542..396,543]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[350,587..396,588]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[350,543..351,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[395,543..396,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[353,545..393,546]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[353,584..393,585]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[353,546..354,584]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[392,546..393,584]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[392,532..398,533]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[397,533..403,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[402,532..408,533]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                ICON_ATLAS|[361,549..385,573]|uv=[0.0,0.0..0.25,0.5]|tint=PRIMARY_TEXT|clip=none
                ICON_ATLAS|[415,549..439,573]|uv=[0.25,0.0..0.5,0.5]|tint=PRIMARY_TEXT|clip=none
                ICON_ATLAS|[392,509..408,525]|uv=[0.5,0.0..0.75,0.5]|tint=PRIMARY_TEXT|clip=none
                TEXT|"7"|[385,576..389,580]|tint=PRIMARY_TEXT|clip=none
                TEXT|"8"|[439,576..443,580]|tint=PRIMARY_TEXT|clip=none
                TEXT|"9"|[408,525..412,529]|tint=PRIMARY_TEXT|clip=none
                TEXT|"1 LEFT"|[361,544..385,548]|tint=PRIMARY_TEXT|clip=none
                TEXT|"2 RIGHT"|[413,544..441,548]|tint=PRIMARY_TEXT|clip=none
                TEXT|"3 MOUTH"|[386,500..414,504]|tint=PRIMARY_TEXT|clip=none
                TEXT|"ACTIVE"|[361,589..385,593]|tint=PRIMARY_TEXT|clip=none
                TEXT|"ABCDEFGHIJKLMNO..."|[328,480..472,488]|tint=PRIMARY_TEXT@0.75|clip=none
                """), signatures);
    }

    @Test
    void fullyOrdersRightActiveTwoHandedSharedCoreCommandSignature() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot =
                fixtures.twoHanded(BodySlot.RIGHT_HAND, BodySlot.RIGHT_HAND);

        List<String> signatures = fixtures.commandSignatures(
                fixtures.render(snapshot).commands());

        assertEquals(signatureLines("""
                SOLID|[350,542..396,588]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[404,542..450,588]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[381,498..419,536]|uv=[0.0,0.0..1.0,1.0]|tint=VOID_BACKGROUND|clip=none
                SOLID|[352,542..358,543]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[352,587..358,588]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[364,542..370,543]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[364,587..370,588]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[376,542..382,543]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[376,587..382,588]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[388,542..394,543]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[388,587..394,588]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,544..351,550]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[395,544..396,550]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,556..351,562]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[395,556..396,562]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,568..351,574]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[395,568..396,574]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,580..351,586]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[395,580..396,586]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,498..419,499]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,535..419,536]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[381,499..382,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[418,499..419,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[350,542..450,543]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[350,587..450,588]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[350,543..351,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[449,543..450,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[396,564..404,566]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[404,542..450,543]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[404,587..450,588]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[404,543..405,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[449,543..450,587]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_PRIMARY_RIM|clip=none
                SOLID|[407,545..447,546]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[407,584..447,585]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[407,546..408,584]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[446,546..447,584]|uv=[0.0,0.0..1.0,1.0]|tint=ACTIVE_SECONDARY_HALO|clip=none
                SOLID|[392,532..398,533]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[397,533..403,535]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                SOLID|[402,532..408,533]|uv=[0.0,0.0..1.0,1.0]|tint=INACTIVE_RIM|clip=none
                ICON_ATLAS|[388,549..412,573]|uv=[0.0,0.0..0.25,0.5]|tint=PRIMARY_TEXT|clip=none
                TEXT|"6"|[412,576..416,580]|tint=PRIMARY_TEXT|clip=none
                TEXT|"1 LEFT"|[361,544..385,548]|tint=PRIMARY_TEXT|clip=none
                TEXT|"2 RIGHT"|[413,544..441,548]|tint=PRIMARY_TEXT|clip=none
                TEXT|"3 MOUTH"|[386,500..414,504]|tint=PRIMARY_TEXT|clip=none
                TEXT|"LOCKED"|[361,589..385,593]|tint=PRIMARY_TEXT|clip=none
                TEXT|"ACTIVE"|[415,589..439,593]|tint=PRIMARY_TEXT|clip=none
                TEXT|"EMPTY"|[390,537..410,541]|tint=PRIMARY_TEXT|clip=none
                """), signatures);
    }

    private static List<String> signatureLines(String signatures) {
        return signatures.strip().lines().toList();
    }

    @Test
    void preservesPhysicalIdentityAndAddsTheSimpleMouthArc() {
        Fixtures fixtures = new Fixtures();

        List<UiDrawCommand> commands = fixtures.render(fixtures.normal(
                BodySlot.RIGHT_HAND,
                Map.of(BodySlot.MOUTH, new ItemStack(GRASS, 2)))).commands();

        assertEquals(1, fixtures.countText(commands, "1 LEFT"));
        assertEquals(1, fixtures.countText(commands, "2 RIGHT"));
        assertEquals(1, fixtures.countText(commands, "3 MOUTH"));
        assertTrue(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                && command.framebufferBounds().equals(new UiRect(392, 532, 398, 533))));
        assertTrue(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                && command.framebufferBounds().equals(new UiRect(402, 532, 408, 533))));
    }

    @Test
    void centersTheMouthIconAndKeepsItsQuantitySeparateAfter125PercentEdgeSnapping() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.RIGHT_HAND,
                Map.of(BodySlot.MOUTH, new ItemStack(GRASS, 2)));
        RenderSurfaceMetrics surface =
                new RenderSurfaceMetrics(800, 600, 1000, 750, 1.25f, 1.25f);

        List<UiDrawCommand> commands = fixtures.render(snapshot, surface).commands();
        UiDrawCommand icon = iconCommands(commands).get(0);
        UiDrawCommand quantity = commands.stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .filter(command -> command.uv().equals(fixtures.glyphUv('2')))
                .filter(command -> command.framebufferBounds().top() > 600)
                .findFirst()
                .orElseThrow();

        assertEquals(new UiRect(490, 636, 510, 656), icon.framebufferBounds());
        assertEquals(new UiRect(511, 657, 515, 661), quantity.framebufferBounds());
        assertTrue(icon.framebufferBounds().bottom() <= quantity.framebufferBounds().top());
        assertTrue(quantity.framebufferBounds().left() >= 485);
        assertTrue(quantity.framebufferBounds().right() <= 515);
        assertTrue(quantity.framebufferBounds().bottom() <= 661);
    }

    @Test
    void redundantlyDrawsActiveEmptyAndLockedTruthWithShapesAndLiteralLabels() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot emptyActive = fixtures.normal(BodySlot.LEFT_HAND, Map.of());

        List<UiDrawCommand> emptyCommands = fixtures.render(emptyActive).commands();

        assertEquals(1, fixtures.countText(emptyCommands, "ACTIVE"));
        assertEquals(3, fixtures.countText(emptyCommands, "EMPTY"));
        assertEquals(4, colorCommands(emptyCommands, com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM));
        assertEquals(4, colorCommands(emptyCommands, com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO));

        List<UiDrawCommand> shared = fixtures.render(
                fixtures.twoHanded(BodySlot.LEFT_HAND, BodySlot.LEFT_HAND)).commands();
        assertEquals(1, fixtures.countText(shared, "LOCKED"));
        assertTrue(shared.stream().filter(command -> command.texture() == UiTextureId.SOLID)
                .map(UiDrawCommand::framebufferBounds)
                .anyMatch(bounds -> bounds.equals(new UiRect(406, 542, 412, 543))));
        assertFalse(iconCommands(shared).isEmpty());
    }

    @Test
    void resolvesCanonicalStacksAndUsesTheResolversExplicitFallbackAndDisplayName() {
        List<ResourceLocation> diagnostics = new ArrayList<>();
        Fixtures fixtures = new Fixtures(diagnostics);
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.LEFT_HAND,
                Map.of(BodySlot.LEFT_HAND, new ItemStack(UNKNOWN, 7)),
                Optional.of(new HudPresentationSnapshot.TimedItemName(UNKNOWN, 1, 0.5)));

        List<UiDrawCommand> first = fixtures.render(snapshot).commands();
        fixtures.render(snapshot);

        assertEquals(List.of(MISSING), iconCommands(first).stream()
                .map(command -> fixtures.itemId(command.uv())).toList());
        assertEquals(List.of(UNKNOWN), diagnostics);
        assertEquals(1, fixtures.countText(first, "Missing"));
        List<UiDrawCommand> nameGlyphs = glyphsFor(first, fixtures, "Missing");
        assertTrue(nameGlyphs.stream().allMatch(command ->
                Math.abs(command.tint().alpha() - 0.5f) < 0.000_001f));
    }

    @Test
    void truncatesLongResolvedItemNamesToAsciiEllipsisAboveTheCluster() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot snapshot = fixtures.normal(
                BodySlot.LEFT_HAND,
                Map.of(BodySlot.LEFT_HAND, new ItemStack(LONG, 1)),
                Optional.of(new HudPresentationSnapshot.TimedItemName(LONG, 1, 1)));

        List<UiDrawCommand> commands = fixtures.render(snapshot).commands();
        String expected = "ABCDEFGHIJKLMNO...";
        List<UiDrawCommand> name = glyphsFor(commands, fixtures, expected);

        assertEquals(1, fixtures.countText(commands, expected));
        assertEquals(144, name.get(name.size() - 1).framebufferBounds().right()
                - name.get(0).framebufferBounds().left());
        assertTrue(name.stream().allMatch(command -> command.framebufferBounds().bottom() <= 490));
        assertTrue(name.stream().allMatch(command -> command.framebufferBounds().left() >= 0
                && command.framebufferBounds().right() <= 800));
    }

    @Test
    void hiddenHudReturnsNoBodyCommandsRegardlessOfDebugVisibility() {
        Fixtures fixtures = new Fixtures();
        HudPresentationSnapshot hidden = fixtures.withVisibility(
                fixtures.normal(BodySlot.LEFT_HAND, Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 1))),
                new HudVisibility(false, true, false,
                        HudVisibility.Lifecycle.RUNNING, HudVisibility.Reason.HUD_DISABLED));

        assertEquals(List.of(), fixtures.render(hidden).commands());
    }

    @Test
    void twoHandedLeftRightAndMouthActiveKeepOneSharedCoreAndSnapshotAnchorTruth() {
        Fixtures fixtures = new Fixtures();
        Map<BodySlot, UiRect> activeTop = Map.of(
                BodySlot.LEFT_HAND, new UiRect(350, 542, 396, 543),
                BodySlot.RIGHT_HAND, new UiRect(404, 542, 450, 543),
                BodySlot.MOUTH, new UiRect(381, 498, 419, 499));
        Map<BodySlot, UiRect> activeLabel = Map.of(
                BodySlot.LEFT_HAND, new UiRect(361, 589, 385, 593),
                BodySlot.RIGHT_HAND, new UiRect(415, 589, 439, 593),
                BodySlot.MOUTH, new UiRect(388, 537, 412, 541));
        for (BodySlot active : BodySlot.values()) {
            BodySlot anchor = active == BodySlot.RIGHT_HAND ? BodySlot.RIGHT_HAND : BodySlot.LEFT_HAND;
            List<UiDrawCommand> commands = fixtures.render(fixtures.twoHanded(active, anchor)).commands();

            assertEquals(1, iconCommands(commands).size(), active.toString());
            assertEquals(new UiRect(388, 549, 412, 573),
                    iconCommands(commands).get(0).framebufferBounds(), active.toString());
            assertEquals(1, quantityGlyphs(commands, fixtures, '6').size(), active.toString());
            assertEquals(1, fixtures.countText(commands, "LOCKED"), active.toString());
            assertEquals(1, fixtures.countText(commands, "ACTIVE"), active.toString());
            assertTrue(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                    && command.tint().equals(com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM)
                    && command.framebufferBounds().equals(activeTop.get(active))), active.toString());
            assertEquals(activeLabel.get(active), fixtures.textRuns(commands).stream()
                    .filter(run -> run.text().equals("ACTIVE"))
                    .findFirst().orElseThrow().bounds(), active.toString());
            UiRect companionTopDash = active == BodySlot.RIGHT_HAND
                    ? new UiRect(352, 542, 358, 543)
                    : new UiRect(406, 542, 412, 543);
            assertTrue(commands.stream().anyMatch(command ->
                    command.framebufferBounds().equals(companionTopDash)), active.toString());
            assertTrue(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                    && command.framebufferBounds().equals(new UiRect(396, 564, 404, 566))),
                    active.toString());
            if (active == BodySlot.MOUTH) {
                assertFalse(commands.stream().anyMatch(command -> command.texture() == UiTextureId.SOLID
                        && command.tint().equals(com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM)
                        && (command.framebufferBounds().equals(activeTop.get(BodySlot.LEFT_HAND))
                                || command.framebufferBounds().equals(
                                        activeTop.get(BodySlot.RIGHT_HAND)))));
            }
        }
    }

    @Test
    void creativeDrawsOneDetachedInfiniteSelectionAndPreservesAllSurvivalSlots() {
        Fixtures fixtures = new Fixtures();
        Map<BodySlot, ItemStack> stacks = Map.of(
                BodySlot.LEFT_HAND, new ItemStack(DIRT, 2),
                BodySlot.RIGHT_HAND, new ItemStack(STONE, 3),
                BodySlot.MOUTH, new ItemStack(GRASS, 4));
        HudPresentationSnapshot creative = fixtures.creative(BodySlot.RIGHT_HAND, stacks, GRASS);

        List<UiDrawCommand> creativeCommands = fixtures.render(creative).commands();

        assertEquals(
                List.of(
                        new UiRect(350, 542, 396, 543),
                        new UiRect(350, 587, 396, 588),
                        new UiRect(350, 543, 351, 587),
                        new UiRect(395, 543, 396, 587),
                        new UiRect(404, 542, 450, 543),
                        new UiRect(404, 587, 450, 588),
                        new UiRect(404, 543, 405, 587),
                        new UiRect(449, 543, 450, 587),
                        new UiRect(381, 498, 419, 499),
                        new UiRect(381, 535, 419, 536),
                        new UiRect(381, 499, 382, 535),
                        new UiRect(418, 499, 419, 535),
                        new UiRect(381, 436, 419, 437),
                        new UiRect(381, 473, 419, 474),
                        new UiRect(381, 437, 382, 473),
                        new UiRect(418, 437, 419, 473)),
                creativeCommands.subList(4, 20).stream()
                        .map(UiDrawCommand::framebufferBounds).toList());
        assertTrue(creativeCommands.subList(4, 16).stream()
                .allMatch(command -> command.tint().equals(
                        com.gaia.ui.GaiaUiTheme.INACTIVE_RIM)));
        assertTrue(creativeCommands.subList(16, 20).stream()
                .allMatch(command -> command.tint().equals(
                        com.gaia.ui.GaiaUiTheme.CREATIVE_ACCENT)));
        assertEquals(4, iconCommands(creativeCommands).size());
        assertTrue(iconCommands(creativeCommands).subList(0, 3).stream()
                .allMatch(command -> command.tint().equals(com.gaia.ui.GaiaUiTheme.INACTIVE_RIM)));
        assertEquals(com.gaia.ui.GaiaUiTheme.CREATIVE_ACCENT,
                iconCommands(creativeCommands).get(3).tint());
        assertEquals(1, fixtures.countText(creativeCommands, "\u221e"));
        assertEquals(1, fixtures.countText(creativeCommands, "CREATIVE"));
        assertEquals(3, fixtures.countText(creativeCommands, "PRESERVED"));
        assertEquals(0, fixtures.countText(creativeCommands, "ACTIVE"));
        assertEquals(1, quantityGlyphs(creativeCommands, fixtures, '2').size());
        assertEquals(1, quantityGlyphs(creativeCommands, fixtures, '3').size());
        assertEquals(1, fixtures.countText(creativeCommands, "4"));
        assertTrue(quantityGlyphs(creativeCommands, fixtures, '2').stream()
                .allMatch(command -> command.tint().equals(com.gaia.ui.GaiaUiTheme.INACTIVE_RIM)));
        assertTrue(quantityGlyphs(creativeCommands, fixtures, '3').stream()
                .allMatch(command -> command.tint().equals(com.gaia.ui.GaiaUiTheme.INACTIVE_RIM)));
        assertTrue(hasTintedGlyphAt(
                creativeCommands, fixtures, '4', new UiRect(408, 525, 412, 529),
                com.gaia.ui.GaiaUiTheme.INACTIVE_RIM));
        assertTrue(hasTintedGlyphAt(
                creativeCommands, fixtures, 0x221e, new UiRect(408, 462, 412, 466),
                com.gaia.ui.GaiaUiTheme.CREATIVE_ACCENT));

        List<UiDrawCommand> restored = fixtures.render(
                fixtures.normal(BodySlot.RIGHT_HAND, stacks)).commands();
        assertEquals(3, iconCommands(restored).size());
        assertEquals(0, fixtures.countText(restored, "CREATIVE"));
        assertEquals(0, fixtures.countText(restored, "PRESERVED"));
        assertEquals(1, fixtures.countText(restored, "ACTIVE"));
    }

    @Test
    void sealsTheOutputAndRejectsSurfacesSmallerThanTheCompactCluster() {
        Fixtures fixtures = new Fixtures();
        UiFrame frame = fixtures.render(fixtures.normal(BodySlot.LEFT_HAND, Map.of()));

        assertThrows(UnsupportedOperationException.class,
                () -> frame.commands().add(frame.commands().get(0)));
        assertThrows(IllegalArgumentException.class, () -> fixtures.screen.compose(
                fixtures.normal(BodySlot.LEFT_HAND, Map.of()),
                new UiLayoutContext(new RenderSurfaceMetrics(99, 117, 99, 117, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> fixtures.screen.compose(
                fixtures.creative(BodySlot.LEFT_HAND, Map.of(), GRASS),
                new UiLayoutContext(new RenderSurfaceMetrics(109, 200, 109, 200, 1, 1))));
    }

    private static void assertLayerOrder(List<UiDrawCommand> commands) {
        int firstIcon = firstIndex(commands, UiTextureId.ICON_ATLAS);
        int firstFont = firstIndex(commands, UiTextureId.FONT_ATLAS);
        int lastSolid = lastIndex(commands, UiTextureId.SOLID);
        int lastIcon = lastIndex(commands, UiTextureId.ICON_ATLAS);
        assertTrue(lastSolid < firstIcon);
        assertTrue(lastIcon < firstFont);
    }

    private static int firstIndex(List<UiDrawCommand> commands, UiTextureId texture) {
        for (int index = 0; index < commands.size(); index++) {
            if (commands.get(index).texture() == texture) {
                return index;
            }
        }
        return -1;
    }

    private static int lastIndex(List<UiDrawCommand> commands, UiTextureId texture) {
        for (int index = commands.size() - 1; index >= 0; index--) {
            if (commands.get(index).texture() == texture) {
                return index;
            }
        }
        return -1;
    }

    private static HudPresentationSnapshot withTransition(
            HudPresentationSnapshot snapshot, double progress) {
        return new HudPresentationSnapshot(
                snapshot.slots(),
                snapshot.activeSlot(),
                snapshot.twoHanded(),
                snapshot.twoHandedAnchor(),
                snapshot.creative(),
                snapshot.mode(),
                snapshot.interaction(),
                snapshot.visibility(),
                Optional.of(new HudPresentationSnapshot.SlotTransition(
                        BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND, progress)),
                snapshot.itemName(),
                snapshot.modeNotice(),
                snapshot.debug());
    }

    private static void assertPixelGridSafe(
            double glyphExtent, float contentScale, String axis) {
        double sourceScale = glyphExtent / 8.0d;
        boolean halfScale = sourceScale == 0.5d;
        boolean integerScale = sourceScale >= 1.0d
                && sourceScale == Math.rint(sourceScale);
        assertTrue(
                halfScale || integerScale,
                axis + " uses fractional source-pixel scale " + sourceScale
                        + " at content scale " + contentScale);
    }

    private static UiRect activePrimaryTop(List<UiDrawCommand> commands) {
        return commands.stream()
                .filter(command -> command.tint().equals(
                        com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM))
                .findFirst()
                .orElseThrow()
                .framebufferBounds();
    }

    private static List<UiDrawCommand> iconCommands(List<UiDrawCommand> commands) {
        return commands.stream().filter(command -> command.texture() == UiTextureId.ICON_ATLAS).toList();
    }

    private static int colorCommands(List<UiDrawCommand> commands, UiColor color) {
        return (int) commands.stream().filter(command -> command.texture() == UiTextureId.SOLID)
                .filter(command -> command.tint().equals(color)).count();
    }

    private static boolean hasGlyphAt(
            List<UiDrawCommand> commands, Fixtures fixtures, char glyph, UiRect bounds) {
        return commands.stream().anyMatch(command -> command.texture() == UiTextureId.FONT_ATLAS
                && command.uv().equals(fixtures.glyphUv(glyph))
                && command.framebufferBounds().equals(bounds));
    }

    private static boolean hasTintedGlyphAt(
            List<UiDrawCommand> commands,
            Fixtures fixtures,
            int glyph,
            UiRect bounds,
            UiColor tint) {
        return commands.stream().anyMatch(command -> command.texture() == UiTextureId.FONT_ATLAS
                && command.uv().equals(fixtures.glyphUv(glyph))
                && command.framebufferBounds().equals(bounds)
                && command.tint().equals(tint));
    }

    private static List<UiDrawCommand> quantityGlyphs(
            List<UiDrawCommand> commands, Fixtures fixtures, char glyph) {
        return commands.stream().filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .filter(command -> command.uv().equals(fixtures.glyphUv(glyph)))
                .filter(command -> command.framebufferBounds().top() >= 570).toList();
    }

    private static List<UiDrawCommand> glyphsFor(
            List<UiDrawCommand> commands, Fixtures fixtures, String text) {
        List<UiDrawCommand> fonts = commands.stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS).toList();
        List<UiUvRect> needle = text.codePoints().mapToObj(fixtures::glyphUv).toList();
        for (int start = 0; start <= fonts.size() - needle.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.size(); offset++) {
                if (!fonts.get(start + offset).uv().equals(needle.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return fonts.subList(start, start + needle.size());
            }
        }
        return List.of();
    }

    public static final class Fixtures {
        private static final UiUvRect ICON_DIRT = new UiUvRect(0, 0, 0.25f, 0.5f);
        private static final UiUvRect ICON_STONE = new UiUvRect(0.25f, 0, 0.5f, 0.5f);
        private static final UiUvRect ICON_GRASS = new UiUvRect(0.5f, 0, 0.75f, 0.5f);
        private static final UiUvRect ICON_LONG = new UiUvRect(0.75f, 0, 1, 0.5f);
        private static final UiUvRect ICON_MISSING = new UiUvRect(0, 0.5f, 0.25f, 1);
        public final BodyOnlyScreen screen;
        private final Map<ResourceLocation, UiIconDefinition> definitions;
        private final Map<UiUvRect, ResourceLocation> idsByUv;
        private final Map<Integer, BitmapGlyph> glyphs;
        private final Map<UiUvRect, Integer> codePointsByUv;

        public Fixtures() {
            this(new ArrayList<>());
        }

        public Fixtures(List<ResourceLocation> diagnostics) {
            definitions = new LinkedHashMap<>();
            addIcon(DIRT, "Dirt", ICON_DIRT);
            addIcon(STONE, "Stone", ICON_STONE);
            addIcon(GRASS, "Grass", ICON_GRASS);
            addIcon(LONG, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", ICON_LONG);
            addIcon(MISSING, "Missing", ICON_MISSING);
            UiIconAtlas atlas = new UiIconAtlas(
                    ResourceLocation.parse("gaia:ui/test.png"), 128, 64,
                    definitions, definitions.get(MISSING), List.of(5, 6, 7));
            glyphs = glyphs();
            codePointsByUv = new LinkedHashMap<>();
            glyphs.forEach((codePoint, glyph) -> codePointsByUv.put(glyph.uv(), codePoint));
            BitmapGlyph missingGlyph = glyphs.get(0xfffd);
            TextRenderer text = new TextRenderer(new BitmapFont(128, 64, glyphs, missingGlyph));
            screen = new BodyOnlyScreen(
                    new BodyInventoryHud(new UiIconResolver(atlas, diagnostics::add), text));
            idsByUv = new LinkedHashMap<>();
            definitions.forEach((id, definition) -> idsByUv.put(definition.region(), id));
        }

        public UiFrame render(HudPresentationSnapshot snapshot) {
            return screen.compose(snapshot, new UiLayoutContext(
                    new RenderSurfaceMetrics(800, 600, 800, 600, 1, 1)));
        }

        public UiFrame render(HudPresentationSnapshot snapshot, RenderSurfaceMetrics surface) {
            return screen.compose(snapshot, new UiLayoutContext(surface));
        }

        public HudPresentationSnapshot normal(BodySlot active, Map<BodySlot, ItemStack> stacks) {
            return normal(active, stacks, Optional.empty());
        }

        public HudPresentationSnapshot normal(
                BodySlot active,
                Map<BodySlot, ItemStack> stacks,
                Optional<HudPresentationSnapshot.TimedItemName> itemName) {
            return snapshotFromStacks(active, stacks, Optional.empty(), Optional.empty(), itemName);
        }

        public HudPresentationSnapshot creative(
                BodySlot active, Map<BodySlot, ItemStack> stacks, ResourceLocation selected) {
            return creative(active, stacks, selected, Optional.empty());
        }

        public HudPresentationSnapshot creative(
                BodySlot active,
                Map<BodySlot, ItemStack> stacks,
                ResourceLocation selected,
                Optional<HudPresentationSnapshot.TimedItemName> itemName) {
            return snapshotFromStacks(
                    active,
                    stacks,
                    Optional.empty(),
                    Optional.of(new HudPresentationSnapshot.CreativeSelection(selected, true)),
                    itemName);
        }

        public HudPresentationSnapshot twoHanded(BodySlot active, BodySlot anchor) {
            EnumMap<BodySlot, HudSlotSnapshot> slots = new EnumMap<>(BodySlot.class);
            BodySlot companion = anchor == BodySlot.LEFT_HAND
                    ? BodySlot.RIGHT_HAND : BodySlot.LEFT_HAND;
            slots.put(anchor, new HudSlotSnapshot(
                    anchor, Optional.of(new ItemStack(DIRT, 6)), active == anchor,
                    false, Optional.empty()));
            slots.put(companion, new HudSlotSnapshot(
                    companion, Optional.empty(), false, true, Optional.of(anchor)));
            slots.put(BodySlot.MOUTH, HudSlotSnapshot.empty(
                    BodySlot.MOUTH, active == BodySlot.MOUTH));
            return snapshotFromSlots(active, slots, Optional.of(anchor), Optional.empty(), Optional.empty());
        }

        HudPresentationSnapshot withVisibility(
                HudPresentationSnapshot source, HudVisibility visibility) {
            return new HudPresentationSnapshot(
                    source.slots(), source.activeSlot(), source.twoHanded(),
                    source.twoHandedAnchor(), source.creative(), source.mode(), source.interaction(),
                    visibility, source.slotTransition(), source.itemName(), source.modeNotice(), source.debug());
        }

        public UiUvRect uv(ResourceLocation id) {
            return definitions.get(id).region();
        }

        ResourceLocation itemId(UiUvRect uv) {
            return idsByUv.get(uv);
        }

        public UiUvRect glyphUv(int codePoint) {
            return glyphs.getOrDefault(codePoint, glyphs.get(0xfffd)).uv();
        }

        public int countText(List<UiDrawCommand> commands, String text) {
            List<UiUvRect> haystack = commands.stream()
                    .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                    .map(UiDrawCommand::uv).toList();
            List<UiUvRect> needle = text.codePoints().mapToObj(this::glyphUv).toList();
            int matches = 0;
            for (int start = 0; start <= haystack.size() - needle.size(); start++) {
                if (haystack.subList(start, start + needle.size()).equals(needle)) {
                    matches++;
                }
            }
            return matches;
        }

        public List<TextRun> textRuns(List<UiDrawCommand> commands) {
            List<TextRun> runs = new ArrayList<>();
            for (int index = 0; index < commands.size(); ) {
                UiDrawCommand first = commands.get(index);
                if (first.texture() != UiTextureId.FONT_ATLAS) {
                    index++;
                    continue;
                }
                StringBuilder decoded = new StringBuilder();
                UiDrawCommand previous = null;
                int start = index;
                while (index < commands.size()) {
                    UiDrawCommand glyph = commands.get(index);
                    if (glyph.texture() != UiTextureId.FONT_ATLAS
                            || (previous != null && !continuesTextRun(previous, glyph))) {
                        break;
                    }
                    Integer codePoint = codePointsByUv.get(glyph.uv());
                    if (codePoint == null) {
                        throw new AssertionError("unknown test glyph UV " + glyph.uv());
                    }
                    decoded.appendCodePoint(codePoint);
                    previous = glyph;
                    index++;
                }
                UiDrawCommand last = commands.get(index - 1);
                runs.add(new TextRun(
                        decoded.toString(),
                        new UiRect(
                                commands.get(start).framebufferBounds().left(),
                                commands.get(start).framebufferBounds().top(),
                                last.framebufferBounds().right(),
                                commands.get(start).framebufferBounds().bottom()),
                        first.tint()));
            }
            return List.copyOf(runs);
        }

        public record TextRun(String text, UiRect bounds, UiColor tint) {}

        public List<String> commandSignatures(List<UiDrawCommand> commands) {
            List<String> signatures = new ArrayList<>();
            for (int index = 0; index < commands.size(); ) {
                UiDrawCommand command = commands.get(index);
                if (command.texture() != UiTextureId.FONT_ATLAS) {
                    signatures.add(command.texture() + "|" + rect(command.framebufferBounds())
                            + "|uv=" + uv(command.uv())
                            + "|tint=" + color(command.tint())
                            + "|clip=" + clip(command.clip()));
                    index++;
                    continue;
                }

                int start = index;
                StringBuilder decoded = new StringBuilder();
                UiDrawCommand previous = null;
                while (index < commands.size()) {
                    UiDrawCommand glyph = commands.get(index);
                    if (glyph.texture() != UiTextureId.FONT_ATLAS
                            || (previous != null && !continuesTextRun(previous, glyph))) {
                        break;
                    }
                    Integer codePoint = codePointsByUv.get(glyph.uv());
                    if (codePoint == null) {
                        throw new AssertionError("unknown test glyph UV " + glyph.uv());
                    }
                    decoded.appendCodePoint(codePoint);
                    previous = glyph;
                    index++;
                }
                UiDrawCommand first = commands.get(start);
                UiDrawCommand last = commands.get(index - 1);
                UiRect bounds = new UiRect(
                        first.framebufferBounds().left(),
                        first.framebufferBounds().top(),
                        last.framebufferBounds().right(),
                        first.framebufferBounds().bottom());
                signatures.add("TEXT|\"" + decoded + "\"|" + rect(bounds)
                        + "|tint=" + color(first.tint())
                        + "|clip=" + clip(first.clip()));
            }
            return List.copyOf(signatures);
        }

        private static boolean continuesTextRun(
                UiDrawCommand previous, UiDrawCommand next) {
            return next.texture() == UiTextureId.FONT_ATLAS
                    && previous.framebufferBounds().right()
                            == next.framebufferBounds().left()
                    && previous.framebufferBounds().top()
                            == next.framebufferBounds().top()
                    && previous.framebufferBounds().bottom()
                            == next.framebufferBounds().bottom()
                    && previous.tint().equals(next.tint())
                    && previous.clip().equals(next.clip());
        }

        private static String rect(UiRect bounds) {
            return "[" + (int) bounds.left() + "," + (int) bounds.top()
                    + ".." + (int) bounds.right() + "," + (int) bounds.bottom() + "]";
        }

        private static String uv(UiUvRect uv) {
            return "[" + uv.left() + "," + uv.top()
                    + ".." + uv.right() + "," + uv.bottom() + "]";
        }

        private static String color(UiColor color) {
            if (color.equals(com.gaia.ui.GaiaUiTheme.VOID_BACKGROUND)) {
                return "VOID_BACKGROUND";
            }
            if (color.equals(com.gaia.ui.GaiaUiTheme.PRIMARY_TEXT)) {
                return "PRIMARY_TEXT";
            }
            if (color.equals(com.gaia.ui.GaiaUiTheme.INACTIVE_RIM)) {
                return "INACTIVE_RIM";
            }
            if (color.equals(com.gaia.ui.GaiaUiTheme.ACTIVE_PRIMARY_RIM)) {
                return "ACTIVE_PRIMARY_RIM";
            }
            if (color.equals(com.gaia.ui.GaiaUiTheme.ACTIVE_SECONDARY_HALO)) {
                return "ACTIVE_SECONDARY_HALO";
            }
            if (color.equals(com.gaia.ui.GaiaUiTheme.CREATIVE_ACCENT)) {
                return "CREATIVE_ACCENT";
            }
            if (sameRgb(color, com.gaia.ui.GaiaUiTheme.PRIMARY_TEXT)) {
                return "PRIMARY_TEXT@" + color.alpha();
            }
            return color.toString();
        }

        private static boolean sameRgb(UiColor first, UiColor second) {
            return first.red() == second.red()
                    && first.green() == second.green()
                    && first.blue() == second.blue();
        }

        private static String clip(Optional<UiRect> clip) {
            return clip.map(Fixtures::rect).orElse("none");
        }

        private void addIcon(ResourceLocation id, String displayName, UiUvRect uv) {
            definitions.put(id, new UiIconDefinition(id, displayName, uv));
        }

        private static HudPresentationSnapshot snapshotFromStacks(
                BodySlot active,
                Map<BodySlot, ItemStack> stacks,
                Optional<BodySlot> anchor,
                Optional<HudPresentationSnapshot.CreativeSelection> creative,
                Optional<HudPresentationSnapshot.TimedItemName> itemName) {
            EnumMap<BodySlot, HudSlotSnapshot> slots = new EnumMap<>(BodySlot.class);
            for (BodySlot slot : BodySlot.values()) {
                slots.put(slot, new HudSlotSnapshot(
                        slot, Optional.ofNullable(stacks.get(slot)), slot == active,
                        false, Optional.empty()));
            }
            return snapshotFromSlots(active, slots, anchor, creative, itemName);
        }

        private static HudPresentationSnapshot snapshotFromSlots(
                BodySlot active,
                Map<BodySlot, HudSlotSnapshot> slots,
                Optional<BodySlot> anchor,
                Optional<HudPresentationSnapshot.CreativeSelection> creative,
                Optional<HudPresentationSnapshot.TimedItemName> itemName) {
            return new HudPresentationSnapshot(
                    slots,
                    active,
                    anchor.isPresent(),
                    anchor,
                    creative,
                    creative.isPresent() ? GameMode.CREATIVE : GameMode.SURVIVAL,
                    HudPresentationSnapshot.InteractionPresentation.cleared(),
                    new HudVisibility(true, false, true,
                            HudVisibility.Lifecycle.RUNNING, HudVisibility.Reason.VISIBLE),
                    Optional.empty(),
                    itemName,
                    Optional.empty(),
                    new HudDebugSnapshot(
                            Optional.empty(),
                            new HudDebugSnapshot.FeetPosition(0, 0, 0),
                            new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0)));
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
                    new UiUvRect(column / 16.0f, row / 8.0f,
                            (column + 1) / 16.0f, (row + 1) / 8.0f),
                    8,
                    0,
                    8);
        }
    }

    public static final class BodyOnlyScreen {
        private final BodyInventoryHud body;

        private BodyOnlyScreen(BodyInventoryHud body) {
            this.body = body;
        }

        public UiFrame compose(HudPresentationSnapshot snapshot, UiLayoutContext layout) {
            UiDrawList out = new UiDrawList();
            body.append(snapshot, layout, out);
            return out.seal();
        }
    }
}
