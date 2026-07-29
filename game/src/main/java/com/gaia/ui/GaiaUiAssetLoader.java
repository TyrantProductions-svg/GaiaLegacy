package com.gaia.ui;

import com.gaia.assets.StrictJsonDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.UiAssetBundle;
import com.overlord.renderer.ui.UiTextureData;
import com.overlord.renderer.ui.UiUvRect;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.imageio.ImageIO;

public final class GaiaUiAssetLoader {
    public static final ResourceLocation MANIFEST =
            ResourceLocation.parse("gaia:ui/ui-assets.json");
    public static final ResourceLocation FONT_IMAGE =
            ResourceLocation.parse("gaia:ui/ui_font.png");
    public static final ResourceLocation FONT_METADATA =
            ResourceLocation.parse("gaia:ui/ui_font.json");
    public static final ResourceLocation ICON_IMAGE =
            ResourceLocation.parse("gaia:ui/ui_icons.png");
    public static final ResourceLocation ICON_METADATA =
            ResourceLocation.parse("gaia:ui/ui_icons.json");

    private static final int ATLAS_WIDTH = 128;
    private static final int ATLAS_HEIGHT = 64;
    private static final List<ResourceLocation> REQUIRED_ICON_IDS = List.of(
            ResourceLocation.parse("gaia:grass"),
            ResourceLocation.parse("gaia:dirt"),
            ResourceLocation.parse("gaia:stone"),
            ResourceLocation.parse("gaia:oak_log"),
            ResourceLocation.parse("gaia:oak_leaves"),
            ResourceLocation.parse("gaia:missing"));

    private final AssetManager assetManager;

    public GaiaUiAssetLoader(AssetManager assetManager) {
        this.assetManager = Objects.requireNonNull(assetManager, "assetManager");
    }

    public GaiaUiAssets load() {
        Manifest manifest = readJson(MANIFEST, this::parseManifest);
        UiTextureData fontTexture = readImage(
                manifest.fontImage(), manifest.fontWidth(), manifest.fontHeight());
        BitmapFont font = readJson(manifest.fontMetadata(),
                root -> parseFont(root, manifest.fontWidth(), manifest.fontHeight()));
        UiTextureData iconTexture = readImage(
                manifest.iconImage(), manifest.iconWidth(), manifest.iconHeight());
        UiIconAtlas icons = readJson(manifest.iconMetadata(),
                root -> parseIcons(root, manifest.iconImage(),
                        manifest.iconWidth(), manifest.iconHeight()));
        return new GaiaUiAssets(
                new UiAssetBundle(iconTexture, fontTexture, font), icons);
    }

    private Manifest parseManifest(JsonObject root) {
        requireInt(root, "provenanceVersion", 1);
        JsonObject font = requireObject(root, "font");
        JsonObject icons = requireObject(root, "icons");
        ResourceLocation fontImage = requiredPath(font, "image", FONT_IMAGE);
        ResourceLocation fontMetadata = requiredPath(font, "metadata", FONT_METADATA);
        ResourceLocation iconImage = requiredPath(icons, "image", ICON_IMAGE);
        ResourceLocation iconMetadata = requiredPath(icons, "metadata", ICON_METADATA);
        requireInt(font, "atlasVersion", 1);
        requireInt(icons, "atlasVersion", 1);
        int fontWidth = requireInt(font, "width");
        int fontHeight = requireInt(font, "height");
        int iconWidth = requireInt(icons, "width");
        int iconHeight = requireInt(icons, "height");
        requireDimensions("font", fontWidth, fontHeight);
        requireDimensions("icons", iconWidth, iconHeight);
        return new Manifest(
                fontImage, fontMetadata, fontWidth, fontHeight,
                iconImage, iconMetadata, iconWidth, iconHeight);
    }

    private BitmapFont parseFont(JsonObject root, int expectedWidth, int expectedHeight) {
        JsonObject atlas = requireObject(root, "atlas");
        int width = requireInt(atlas, "width");
        int height = requireInt(atlas, "height");
        if (width != expectedWidth || height != expectedHeight) {
            throw new IllegalArgumentException("font metadata atlas dimensions are inconsistent");
        }
        JsonObject cell = requireObject(root, "cell");
        int cellWidth = requireInt(cell, "width");
        int cellHeight = requireInt(cell, "height");
        if (cellWidth != 8 || cellHeight != 8) {
            throw new IllegalArgumentException("font metadata requires 8x8 cells");
        }
        if (!"RGBA8".equals(requireString(root, "pixelFormat"))) {
            throw new IllegalArgumentException("font metadata requires RGBA8 pixels");
        }

        Map<Integer, BitmapGlyph> glyphs = new LinkedHashMap<>();
        Set<Integer> cells = new HashSet<>();
        for (JsonElement element : requireArray(root, "glyphs")) {
            JsonObject glyph = requireObject(element, "glyph");
            int codePoint = requireInt(glyph, "codePoint");
            if (!Character.isValidCodePoint(codePoint) || glyphs.containsKey(codePoint)) {
                throw new IllegalArgumentException("invalid or duplicate glyph code point");
            }
            JsonObject cellObject = requireObject(glyph, "cell");
            int column = requireInt(cellObject, "column");
            int row = requireInt(cellObject, "row");
            int cellIndex = checkedCell(column, row, width / cellWidth, height / cellHeight);
            if (!cells.add(cellIndex)) {
                throw new IllegalArgumentException("glyph cells overlap");
            }
            JsonObject bearing = requireObject(glyph, "bearing");
            BitmapGlyph definition = new BitmapGlyph(
                    codePoint,
                    uv(column * cellWidth, row * cellHeight,
                            cellWidth, cellHeight, width, height),
                    requireInt(glyph, "advance"),
                    requireInt(bearing, "x"),
                    requireInt(bearing, "y"));
            glyphs.put(codePoint, definition);
        }
        Set<Integer> required = new HashSet<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            required.add(codePoint);
        }
        required.add(0x221e);
        required.add(0xfffd);
        if (!glyphs.keySet().equals(required)) {
            throw new IllegalArgumentException(
                    "font metadata must contain exact required glyph coverage");
        }

        JsonObject fallbackObject = requireObject(root, "fallback");
        int fallbackCodePoint = requireInt(fallbackObject, "codePoint");
        BitmapGlyph fallback = glyphs.get(fallbackCodePoint);
        if (fallback == null) {
            throw new IllegalArgumentException("font fallback glyph is missing");
        }
        JsonObject region = requireObject(fallbackObject, "region");
        UiUvRect fallbackRegion = uv(
                requireInt(region, "x"), requireInt(region, "y"),
                requireInt(region, "width"), requireInt(region, "height"),
                width, height);
        if (!fallback.uv().equals(fallbackRegion)) {
            throw new IllegalArgumentException("font fallback region is inconsistent");
        }
        return new BitmapFont(width, height, glyphs, fallback);
    }

    private UiIconAtlas parseIcons(
            JsonObject root,
            ResourceLocation texture,
            int expectedWidth,
            int expectedHeight) {
        requireInt(root, "version", 1);
        JsonObject atlas = requireObject(root, "atlas");
        int width = requireInt(atlas, "width");
        int height = requireInt(atlas, "height");
        if (width != expectedWidth || height != expectedHeight) {
            throw new IllegalArgumentException("icon metadata atlas dimensions are inconsistent");
        }
        JsonObject cell = requireObject(root, "cell");
        if (requireInt(cell, "width") != 32
                || requireInt(cell, "height") != 32
                || requireInt(cell, "columns") != 4
                || requireInt(cell, "rows") != 2) {
            throw new IllegalArgumentException("icon metadata requires a 4x2 grid of 32x32 cells");
        }
        if (!"RGBA8".equals(requireString(root, "pixelFormat"))) {
            throw new IllegalArgumentException("icon metadata requires RGBA8 pixels");
        }
        JsonObject light = requireObject(root, "faceLight");
        requireInt(light, "top", 100);
        requireInt(light, "north", 82);
        requireInt(light, "east", 68);

        Map<ResourceLocation, UiIconDefinition> definitions = new LinkedHashMap<>();
        List<PixelRegion> regions = new ArrayList<>();
        Set<Integer> assignedCells = new LinkedHashSet<>();
        UiIconDefinition fallback = null;
        int fallbackCount = 0;
        for (JsonElement element : requireArray(root, "icons")) {
            JsonObject icon = requireObject(element, "icon");
            ResourceLocation itemId = parseLocation(requireString(icon, "itemId"), "icon itemId");
            if (definitions.containsKey(itemId)) {
                throw new IllegalArgumentException("duplicate icon id " + itemId);
            }
            JsonObject cellObject = requireObject(icon, "cell");
            int column = requireInt(cellObject, "column");
            int row = requireInt(cellObject, "row");
            int cellIndex = checkedCell(column, row, 4, 2);
            if (!assignedCells.add(cellIndex)) {
                throw new IllegalArgumentException("icon cells overlap");
            }
            JsonObject regionObject = requireObject(icon, "region");
            PixelRegion region = new PixelRegion(
                    requireInt(regionObject, "x"),
                    requireInt(regionObject, "y"),
                    requireInt(regionObject, "width"),
                    requireInt(regionObject, "height"));
            region.requireBounds(width, height);
            if (region.x() != column * 32 || region.y() != row * 32
                    || region.width() != 32 || region.height() != 32) {
                throw new IllegalArgumentException(
                        "icon region must match its declared 32x32 cell bounds");
            }
            for (PixelRegion existing : regions) {
                if (region.overlaps(existing)) {
                    throw new IllegalArgumentException("icon regions overlap");
                }
            }
            regions.add(region);
            UiIconDefinition definition = new UiIconDefinition(
                    itemId, requireString(icon, "displayName"),
                    uv(region.x(), region.y(), region.width(), region.height(), width, height));
            definitions.put(itemId, definition);
            if (requireBoolean(icon, "fallback")) {
                fallback = definition;
                fallbackCount++;
            }
        }
        if (fallbackCount != 1) {
            throw new IllegalArgumentException("icon metadata requires exactly one fallback");
        }
        if (!definitions.keySet().equals(new LinkedHashSet<>(REQUIRED_ICON_IDS))
                || !fallback.itemId().equals(ResourceLocation.parse("gaia:missing"))) {
            throw new IllegalArgumentException("icon metadata does not match canonical required ids");
        }

        JsonArray unassignedArray = requireArray(root, "unassignedCells");
        if (unassignedArray.size() != 2) {
            throw new IllegalArgumentException("icon metadata requires exactly two unassigned cells");
        }
        List<Integer> unassigned = new ArrayList<>();
        for (JsonElement element : unassignedArray) {
            JsonObject unused = requireObject(element, "unassigned cell");
            int cellIndex = checkedCell(
                    requireInt(unused, "column"), requireInt(unused, "row"), 4, 2);
            if (assignedCells.contains(cellIndex) || unassigned.contains(cellIndex)) {
                throw new IllegalArgumentException("unassigned cells overlap assigned cells");
            }
            unassigned.add(cellIndex);
        }
        Set<Integer> allCells = new HashSet<>(assignedCells);
        allCells.addAll(unassigned);
        if (allCells.size() != 8) {
            throw new IllegalArgumentException("icon cell declarations must cover the grid");
        }
        return new UiIconAtlas(texture, width, height, definitions, fallback, unassigned);
    }

    private UiTextureData readImage(ResourceLocation path, int width, int height) {
        try (InputStream input = assetManager.open(path)) {
            byte[] encoded = input.readAllBytes();
            validateRgba8Png(encoded);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) {
                throw new IllegalArgumentException("resource is not a decodable PNG image");
            }
            if (image.getWidth() != width || image.getHeight() != height) {
                throw new IllegalArgumentException(
                        "image must decode to exactly " + width + "x" + height + " RGBA pixels");
            }
            ByteBuffer rgba = ByteBuffer.allocate(width * height * 4);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    rgba.put((byte) (argb >>> 16));
                    rgba.put((byte) (argb >>> 8));
                    rgba.put((byte) argb);
                    rgba.put((byte) (argb >>> 24));
                }
            }
            rgba.flip();
            return new UiTextureData(width, height, rgba);
        } catch (IOException | RuntimeException failure) {
            throw new GaiaUiAssetLoadException(path.toClasspathPath(), failure);
        }
    }

    private static void validateRgba8Png(byte[] encoded) {
        byte[] signature = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        if (encoded.length < signature.length) {
            throw new IllegalArgumentException("PNG signature is truncated");
        }
        for (int index = 0; index < signature.length; index++) {
            if (encoded[index] != signature[index]) {
                throw new IllegalArgumentException("PNG signature is invalid");
            }
        }
        if (encoded.length < 33) {
            throw new IllegalArgumentException("PNG IHDR is truncated");
        }
        int ihdrLength = (encoded[8] & 0xff) << 24
                | (encoded[9] & 0xff) << 16
                | (encoded[10] & 0xff) << 8
                | encoded[11] & 0xff;
        if (ihdrLength != 13
                || encoded[12] != 'I'
                || encoded[13] != 'H'
                || encoded[14] != 'D'
                || encoded[15] != 'R') {
            throw new IllegalArgumentException("PNG must begin with a 13-byte IHDR chunk");
        }
        int bitDepth = Byte.toUnsignedInt(encoded[24]);
        int colorType = Byte.toUnsignedInt(encoded[25]);
        if (bitDepth != 8 || colorType != 6) {
            throw new IllegalArgumentException(
                    "PNG source must use 8-bit RGBA color (IHDR color type 6)");
        }
    }

    private <T> T readJson(ResourceLocation path, Function<JsonObject, T> parser) {
        try {
            JsonObject parsed = StrictJsonDocument.parseObject(
                    assetManager.readUtf8(path), path.toClasspathPath());
            return parser.apply(parsed);
        } catch (RuntimeException failure) {
            if (failure instanceof GaiaUiAssetLoadException uiFailure) {
                throw uiFailure;
            }
            throw new GaiaUiAssetLoadException(path.toClasspathPath(), failure);
        }
    }

    private static ResourceLocation requiredPath(
            JsonObject object, String field, ResourceLocation expected) {
        ResourceLocation actual = parseLocation(requireString(object, field), field);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " must name required path " + expected.toClasspathPath());
        }
        return actual;
    }

    private static ResourceLocation parseLocation(String text, String field) {
        try {
            return ResourceLocation.parse(text);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " is not a canonical resource location", failure);
        }
    }

    private static UiUvRect uv(
            int x, int y, int width, int height, int atlasWidth, int atlasHeight) {
        PixelRegion region = new PixelRegion(x, y, width, height);
        region.requireBounds(atlasWidth, atlasHeight);
        return new UiUvRect(
                (float) x / atlasWidth,
                (float) y / atlasHeight,
                (float) (x + width) / atlasWidth,
                (float) (y + height) / atlasHeight);
    }

    private static int checkedCell(int column, int row, int columns, int rows) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) {
            throw new IllegalArgumentException("cell is outside the atlas grid");
        }
        return row * columns + column;
    }

    private static void requireDimensions(String asset, int width, int height) {
        if (width != ATLAS_WIDTH || height != ATLAS_HEIGHT) {
            throw new IllegalArgumentException(asset + " atlas must be exactly 128x64");
        }
    }

    private static JsonObject requireObject(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        return requireObject(element, field);
    }

    private static JsonObject requireObject(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            int value = element.getAsInt();
            if (element.getAsDouble() != value) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static void requireInt(JsonObject parent, String field, int expected) {
        if (requireInt(parent, field) != expected) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    private static boolean requireBoolean(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private record Manifest(
            ResourceLocation fontImage,
            ResourceLocation fontMetadata,
            int fontWidth,
            int fontHeight,
            ResourceLocation iconImage,
            ResourceLocation iconMetadata,
            int iconWidth,
            int iconHeight) {}

    private record PixelRegion(int x, int y, int width, int height) {
        void requireBounds(int atlasWidth, int atlasHeight) {
            if (x < 0 || y < 0 || width <= 0 || height <= 0
                    || (long) x + width > atlasWidth
                    || (long) y + height > atlasHeight) {
                throw new IllegalArgumentException("icon or glyph region is outside atlas bounds");
            }
        }

        boolean overlaps(PixelRegion other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }
}
