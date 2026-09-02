package com.gaia.tools.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Generates the equal-layout Quiet Rune / Pixelify typography review specimen. */
public final class TypographySpecimenGenerator {
    private static final int WIDTH = 1_280;
    private static final int HEIGHT = 720;
    private static final int OVERSAMPLE = 3;
    private static final int PADDING = 2;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<Integer> CODE_POINTS = codePoints();
    private static final List<TextRun> TEXT_RUNS = List.of(
            new TextRun(Role.TITLE, "GAIA LEGACY", 86, 70, 0xff7ce7ff),
            new TextRun(Role.SUBTITLE, "COSMIC INTERFACE TYPOGRAPHY", 90, 128, 0xff91a6bd),
            new TextRun(Role.HEADING, "WORLD ARCHIVE", 90, 204, 0xfff2f7ff),
            new TextRun(Role.BODY,
                    "Shape the world at quarter scale.", 90, 260, 0xffc7d4e2),
            new TextRun(Role.BODY,
                    "Materials remain exact across every edit.", 90, 292, 0xffc7d4e2),
            new TextRun(Role.HUD,
                    "DETAIL PRECISION  [2,1,3]  STONE  48", 690, 224, 0xff7ce7ff),
            new TextRun(Role.NUMBERS,
                    "0123456789  17:45  62.27%  x64", 690, 282, 0xfff2f7ff),
            new TextRun(Role.BUTTON, "CONTINUE", 122, 405, 0xff07111f),
            new TextRun(Role.STATUS,
                    "READY   UNKNOWN   FAILED   RECOVER", 690, 365, 0xffffcc67),
            new TextRun(Role.CAPTION,
                    "Same content, coordinates, colors, and role sizes in every panel.",
                    90, 626, 0xff8398ae));

    public GenerationResult generate(Path outputDirectory) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to create specimen output", failure);
        }

        ClassLoader loader = getClass().getClassLoader();
        FontSourceManifest manifest = FontSourceManifest.load(loader);
        Map<String, TrueTypeFontRasterizer.RasterizedFace> faces = rasterizeReviewFaces(
                loader, manifest);
        FontAtlasPacker packer = new FontAtlasPacker();
        List<TrueTypeFontRasterizer.RasterizedFace> productionFaces = List.of(
                faces.get("pixelify-bold-700"),
                faces.get("pixelify-semibold-600"),
                faces.get("inter-regular-400"),
                faces.get("inter-medium-500"),
                faces.get("inter-semibold-600"));
        FontAtlasPacker.GeneratedTypographyAtlas composite = packer.pack(
                productionFaces,
                List.of(new FontAtlasPacker.PageDefinition(
                        "composite-linear",
                        productionFaces.stream().map(
                                TrueTypeFontRasterizer.RasterizedFace::sourceId).toList(),
                        FontAtlasPacker.SamplingMode.LINEAR)),
                PADDING);
        FontAtlasPacker.GeneratedTypographyAtlas split = packer.pack(
                productionFaces,
                List.of(
                        new FontAtlasPacker.PageDefinition(
                                "body-linear",
                                List.of("inter-regular-400", "inter-medium-500",
                                        "inter-semibold-600"),
                                FontAtlasPacker.SamplingMode.LINEAR),
                        new FontAtlasPacker.PageDefinition(
                                "display-nearest",
                                List.of("pixelify-bold-700", "pixelify-semibold-600"),
                                FontAtlasPacker.SamplingMode.NEAREST)),
                PADDING);
        enforceReviewMemoryBound(composite);
        enforceReviewMemoryBound(split);

        QuietRuneFace quietRune = loadQuietRune(loader);
        Map<String, byte[]> generated = new TreeMap<>();
        generated.put("quiet-rune-baseline.png",
                png(renderSpecimen(Variant.QUIET_RUNE, faces, quietRune)));
        generated.put("pixelify-inter.png",
                png(renderSpecimen(Variant.INTER, faces, quietRune)));
        generated.put("pixelify-plex.png",
                png(renderSpecimen(Variant.PLEX, faces, quietRune)));

        FontAtlasPacker.AtlasPage compositePage = composite.pages().get(0);
        generated.put("atlas-composite.png", png(compositePage));
        generated.put("atlas-composite.json",
                json(atlasMetadata("composite", composite, List.of(compositePage))));
        for (FontAtlasPacker.AtlasPage page : split.pages()) {
            String stem = page.id().equals("body-linear")
                    ? "atlas-split-body" : "atlas-split-display";
            generated.put(stem + ".png", png(page));
            generated.put(stem + ".json",
                    json(atlasMetadata("split", split, List.of(page))));
        }

        Map<String, String> derivativeHashes = hashes(generated);
        JsonObject measurement = measurement(
                manifest, faces, composite, split, derivativeHashes);
        generated.put("measurement.json", json(measurement));
        write(outputDirectory, generated);
        return new GenerationResult(
                List.copyOf(generated.keySet()), Map.copyOf(hashes(generated)));
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output directory argument");
        }
        GenerationResult result = new TypographySpecimenGenerator()
                .generate(Path.of(arguments[0]));
        System.out.println("Generated Phase 17.5 typography specimen: " + result.files());
    }

    static Map<String, TrueTypeFontRasterizer.RasterizedFace> rasterizeReviewFaces(
            ClassLoader loader, FontSourceManifest manifest) {
        Map<String, Integer> sizes = Map.of(
                "pixelify-bold-700", 42,
                "pixelify-semibold-600", 28,
                "inter-regular-400", 18,
                "inter-medium-500", 16,
                "inter-semibold-600", 18,
                "plex-regular-400", 18,
                "plex-medium-500", 16,
                "plex-semibold-600", 18);
        TrueTypeFontRasterizer rasterizer = new TrueTypeFontRasterizer();
        Map<String, TrueTypeFontRasterizer.RasterizedFace> faces = new LinkedHashMap<>();
        for (FontSourceManifest.Entry entry : manifest.entries()) {
            faces.put(entry.id(), rasterizer.rasterize(
                    loader, entry, sizes.get(entry.id()), OVERSAMPLE, CODE_POINTS));
        }
        return Map.copyOf(faces);
    }

    private static BufferedImage renderSpecimen(
            Variant variant,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            QuietRuneFace quietRune) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        fillRect(image, 0, 0, WIDTH, HEIGHT, 0xff07111f);
        fillRect(image, 42, 34, WIDTH - 84, HEIGHT - 68, 0xff0b1929);
        outline(image, 42, 34, WIDTH - 84, HEIGHT - 68, 0xff1d3e59, 2);
        fillRect(image, 64, 182, 560, 382, 0xff0f2236);
        fillRect(image, 660, 182, 556, 382, 0xff0f2236);
        outline(image, 64, 182, 560, 382, 0xff1c3b56, 1);
        outline(image, 660, 182, 556, 382, 0xff1c3b56, 1);
        fillRect(image, 90, 390, 232, 56, 0xff7ce7ff);
        outline(image, 90, 390, 232, 56, 0xffd5f8ff, 2);
        fillRect(image, 690, 340, 454, 58, 0xff152b43);
        fillRect(image, 690, 432, 454, 72, 0xff151f38);
        outline(image, 690, 432, 454, 72, 0xff7b5ee5, 1);
        fillRect(image, 690, 432, 5, 72, 0xff7b5ee5);
        fillRect(image, 690, 532, 132, 4, 0xffffcc67);

        for (TextRun run : TEXT_RUNS) {
            if (variant == Variant.QUIET_RUNE) {
                drawQuietText(image, quietRune, run.text(), run.x(), run.y(),
                        quietScale(run.role()), run.argb());
            } else {
                TrueTypeFontRasterizer.RasterizedFace face = faceFor(variant, run.role(), faces);
                drawTrueTypeText(image, face, run.text(), run.x(), run.y(), run.argb());
            }
        }
        return image;
    }

    private static TrueTypeFontRasterizer.RasterizedFace faceFor(
            Variant variant, Role role,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces) {
        if (role == Role.TITLE) {
            return faces.get("pixelify-bold-700");
        }
        if (role == Role.HEADING) {
            return faces.get("pixelify-semibold-600");
        }
        String family = variant == Variant.INTER ? "inter" : "plex";
        String weight = switch (role) {
            case SUBTITLE, HUD -> "medium-500";
            case BUTTON, STATUS -> "semibold-600";
            default -> "regular-400";
        };
        return faces.get(family + "-" + weight);
    }

    private static int quietScale(Role role) {
        return switch (role) {
            case TITLE -> 5;
            case HEADING -> 3;
            case CAPTION -> 1;
            default -> 2;
        };
    }

    static void drawTrueTypeText(
            BufferedImage image,
            TrueTypeFontRasterizer.RasterizedFace face,
            String text,
            int x,
            int top,
            int color) {
        int cursor = x;
        int baseline = top + face.baseline();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            TrueTypeFontRasterizer.RasterizedGlyph glyph;
            try {
                glyph = face.glyph(codePoint);
            } catch (IllegalArgumentException absent) {
                glyph = face.glyph(0xfffd);
            }
            byte[] alpha = glyph.alpha();
            int left = cursor + glyph.bearingX();
            int glyphTop = baseline - glyph.bearingY();
            for (int y = 0; y < glyph.height(); y++) {
                for (int glyphX = 0; glyphX < glyph.width(); glyphX++) {
                    blend(image, left + glyphX, glyphTop + y, color,
                            alpha[y * glyph.width() + glyphX] & 0xff);
                }
            }
            cursor += glyph.advance();
        }
    }

    private static void drawQuietText(
            BufferedImage image, QuietRuneFace face, String text,
            int x, int top, int scale, int color) {
        int cursor = x;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            QuietGlyph glyph = face.glyphs().getOrDefault(
                    codePoint, face.glyphs().get(0xfffd));
            for (int sourceY = 0; sourceY < 8; sourceY++) {
                for (int sourceX = 0; sourceX < 8; sourceX++) {
                    int alpha = face.image().getRGB(
                            glyph.column() * 8 + sourceX,
                            glyph.row() * 8 + sourceY) >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    for (int y = 0; y < scale; y++) {
                        for (int scaledX = 0; scaledX < scale; scaledX++) {
                            blend(image,
                                    cursor + sourceX * scale + scaledX,
                                    top + sourceY * scale + y,
                                    color, alpha);
                        }
                    }
                }
            }
            cursor += glyph.advance() * scale;
        }
    }

    static void blend(
            BufferedImage image, int x, int y, int color, int glyphAlpha) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return;
        }
        int colorAlpha = color >>> 24;
        int alpha = (glyphAlpha * colorAlpha + 127) / 255;
        if (alpha == 0) {
            return;
        }
        int background = image.getRGB(x, y);
        int inverse = 255 - alpha;
        int red = (((color >> 16) & 0xff) * alpha
                + ((background >> 16) & 0xff) * inverse + 127) / 255;
        int green = (((color >> 8) & 0xff) * alpha
                + ((background >> 8) & 0xff) * inverse + 127) / 255;
        int blue = ((color & 0xff) * alpha
                + (background & 0xff) * inverse + 127) / 255;
        image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
    }

    static void fillRect(
            BufferedImage image, int x, int y, int width, int height, int color) {
        for (int row = Math.max(0, y); row < Math.min(image.getHeight(), y + height); row++) {
            for (int column = Math.max(0, x);
                    column < Math.min(image.getWidth(), x + width); column++) {
                image.setRGB(column, row, color);
            }
        }
    }

    static void outline(
            BufferedImage image, int x, int y, int width, int height, int color, int thickness) {
        fillRect(image, x, y, width, thickness, color);
        fillRect(image, x, y + height - thickness, width, thickness, color);
        fillRect(image, x, y, thickness, height, color);
        fillRect(image, x + width - thickness, y, thickness, height, color);
    }

    private static QuietRuneFace loadQuietRune(ClassLoader loader) {
        byte[] png = resource(loader, "assets/gaia/ui/ui_font.png");
        byte[] json = resource(loader, "assets/gaia/ui/ui_font.json");
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            JsonObject root = JsonParser.parseString(
                    new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<Integer, QuietGlyph> glyphs = new HashMap<>();
            for (var element : root.getAsJsonArray("glyphs")) {
                JsonObject glyph = element.getAsJsonObject();
                JsonObject cell = glyph.getAsJsonObject("cell");
                glyphs.put(glyph.get("codePoint").getAsInt(), new QuietGlyph(
                        cell.get("column").getAsInt(), cell.get("row").getAsInt(),
                        glyph.get("advance").getAsInt()));
            }
            return new QuietRuneFace(image, Map.copyOf(glyphs));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("unable to load Quiet Rune baseline", failure);
        }
    }

    private static JsonObject atlasMetadata(
            String option,
            FontAtlasPacker.GeneratedTypographyAtlas atlas,
            List<FontAtlasPacker.AtlasPage> pages) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("option", option);
        root.addProperty("padding", atlas.padding());
        JsonArray pageArray = new JsonArray();
        for (FontAtlasPacker.AtlasPage page : pages) {
            JsonObject value = new JsonObject();
            value.addProperty("id", page.id());
            value.addProperty("width", page.width());
            value.addProperty("height", page.height());
            value.addProperty("bytes", page.byteCount());
            value.addProperty("sampling", page.samplingMode().name());
            value.addProperty("rgbaSha256", page.sha256());
            pageArray.add(value);
        }
        root.add("pages", pageArray);
        JsonArray placements = new JsonArray();
        for (FontAtlasPacker.GlyphPlacement placement : atlas.placements()) {
            if (pages.stream().noneMatch(page -> page.id().equals(placement.pageId()))) {
                continue;
            }
            JsonObject value = new JsonObject();
            value.addProperty("face", placement.faceSourceId());
            value.addProperty("codePoint", placement.codePoint());
            value.addProperty("page", placement.pageId());
            value.addProperty("x", placement.x());
            value.addProperty("y", placement.y());
            value.addProperty("width", placement.width());
            value.addProperty("height", placement.height());
            value.addProperty("advance", placement.advance());
            value.addProperty("bearingX", placement.bearingX());
            value.addProperty("bearingY", placement.bearingY());
            placements.add(value);
        }
        root.add("glyphs", placements);
        return root;
    }

    private static JsonObject measurement(
            FontSourceManifest manifest,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            FontAtlasPacker.GeneratedTypographyAtlas composite,
            FontAtlasPacker.GeneratedTypographyAtlas split,
            Map<String, String> derivativeHashes) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonObject canvas = new JsonObject();
        canvas.addProperty("width", WIDTH);
        canvas.addProperty("height", HEIGHT);
        root.add("canvas", canvas);
        root.addProperty("baseline", "quiet-rune-5x7");
        byte[] layoutBytes = json(layoutJson());
        root.addProperty("layoutSha256", sha256(layoutBytes));
        root.add("textRuns", layoutJson().getAsJsonArray("textRuns"));

        JsonArray rasterFaces = new JsonArray();
        faces.values().stream().sorted(Comparator.comparing(
                TrueTypeFontRasterizer.RasterizedFace::sourceId)).forEach(face -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("sourceId", face.sourceId());
                    value.addProperty("pixelHeight", face.pixelHeight());
                    value.addProperty("oversample", face.oversample());
                    value.addProperty("ascent", face.ascent());
                    value.addProperty("descent", face.descent());
                    value.addProperty("lineGap", face.lineGap());
                    value.addProperty("baseline", face.baseline());
                    value.addProperty("lineHeight", face.lineHeight());
                    rasterFaces.add(value);
                });
        root.add("rasterFaces", rasterFaces);

        JsonArray options = new JsonArray();
        options.add(optionMeasurement("composite-linear", composite, 1));
        options.add(optionMeasurement("split-display-body", split,
                estimatedPageRuns(split.facePages())));
        root.add("atlasOptions", options);

        JsonArray sources = new JsonArray();
        for (FontSourceManifest.Entry entry : manifest.entries()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", entry.id());
            value.addProperty("sourceUrl", entry.sourceUrl());
            value.addProperty("upstreamCommitOrTag", entry.upstreamCommitOrTag());
            value.addProperty("gitBlobSha", entry.gitBlobSha());
            value.addProperty("sourceSha256", entry.sourceSha256());
            value.addProperty("licenseSha256", entry.licenseSha256());
            sources.add(value);
        }
        root.add("sourceFiles", sources);

        JsonArray derivatives = new JsonArray();
        derivativeHashes.forEach((path, hash) -> {
            JsonObject value = new JsonObject();
            value.addProperty("path", path);
            value.addProperty("sha256", hash);
            derivatives.add(value);
        });
        root.add("derivatives", derivatives);
        root.addProperty("notes",
                "Texture-bind and draw-command runs preserve specimen text order; glyph quads are unchanged.");
        return root;
    }

    private static JsonObject optionMeasurement(
            String id, FontAtlasPacker.GeneratedTypographyAtlas atlas, int bindRuns) {
        JsonObject option = new JsonObject();
        option.addProperty("id", id);
        option.addProperty("pageCount", atlas.pages().size());
        option.addProperty("totalBytes", atlas.pages().stream()
                .mapToInt(FontAtlasPacker.AtlasPage::byteCount).sum());
        option.addProperty("estimatedTextureBindRuns", bindRuns);
        option.addProperty("estimatedUiDrawCommandRuns", bindRuns);
        option.addProperty("glyphQuads", glyphQuadCount());
        JsonArray pages = new JsonArray();
        for (FontAtlasPacker.AtlasPage page : atlas.pages()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", page.id());
            value.addProperty("width", page.width());
            value.addProperty("height", page.height());
            value.addProperty("bytes", page.byteCount());
            value.addProperty("sampling", page.samplingMode().name());
            value.addProperty("rgbaSha256", page.sha256());
            pages.add(value);
        }
        option.add("pages", pages);
        return option;
    }

    private static int estimatedPageRuns(Map<String, String> facePages) {
        String prior = null;
        int runs = 0;
        for (TextRun run : TEXT_RUNS) {
            String face = faceIdForInter(run.role());
            String page = facePages.get(face);
            if (!Objects.equals(page, prior)) {
                runs++;
                prior = page;
            }
        }
        return runs;
    }

    private static String faceIdForInter(Role role) {
        if (role == Role.TITLE) {
            return "pixelify-bold-700";
        }
        if (role == Role.HEADING) {
            return "pixelify-semibold-600";
        }
        return switch (role) {
            case SUBTITLE, HUD -> "inter-medium-500";
            case BUTTON, STATUS -> "inter-semibold-600";
            default -> "inter-regular-400";
        };
    }

    private static int glyphQuadCount() {
        return TEXT_RUNS.stream().mapToInt(run ->
                (int) run.text().codePoints().filter(codePoint -> codePoint != ' ').count()).sum();
    }

    private static JsonObject layoutJson() {
        JsonObject root = new JsonObject();
        root.addProperty("width", WIDTH);
        root.addProperty("height", HEIGHT);
        JsonArray runs = new JsonArray();
        for (TextRun run : TEXT_RUNS) {
            JsonObject value = new JsonObject();
            value.addProperty("role", run.role().name());
            value.addProperty("text", run.text());
            value.addProperty("x", run.x());
            value.addProperty("y", run.y());
            value.addProperty("argb", String.format("%08x", run.argb()));
            runs.add(value);
        }
        root.add("textRuns", runs);
        return root;
    }

    static byte[] png(FontAtlasPacker.AtlasPage page) {
        BufferedImage image = new BufferedImage(
                page.width(), page.height(), BufferedImage.TYPE_INT_ARGB);
        byte[] rgba = page.rgba();
        for (int y = 0; y < page.height(); y++) {
            for (int x = 0; x < page.width(); x++) {
                int offset = (y * page.width() + x) * 4;
                int argb = (rgba[offset + 3] & 0xff) << 24
                        | (rgba[offset] & 0xff) << 16
                        | (rgba[offset + 1] & 0xff) << 8
                        | rgba[offset + 2] & 0xff;
                image.setRGB(x, y, argb);
            }
        }
        return png(image);
    }

    static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to encode PNG", failure);
        }
    }

    static byte[] json(JsonObject value) {
        return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(ClassLoader loader, String path) {
        try (InputStream input = loader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing resource " + path);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to read resource " + path, failure);
        }
    }

    private static void write(Path directory, Map<String, byte[]> generated) {
        generated.forEach((name, bytes) -> {
            try {
                Files.write(directory.resolve(name), bytes);
            } catch (IOException failure) {
                throw new IllegalArgumentException("unable to write " + name, failure);
            }
        });
    }

    private static Map<String, String> hashes(Map<String, byte[]> generated) {
        Map<String, String> hashes = new TreeMap<>();
        generated.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        return hashes;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void enforceReviewMemoryBound(
            FontAtlasPacker.GeneratedTypographyAtlas atlas) {
        int bytes = atlas.pages().stream().mapToInt(
                FontAtlasPacker.AtlasPage::byteCount).sum();
        if (bytes > 2 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "typography atlas option exceeds the 2 MiB design-review target");
        }
    }

    private static List<Integer> codePoints() {
        List<Integer> result = new ArrayList<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            result.add(codePoint);
        }
        result.add(0x221e);
        result.add(0xfffd);
        return List.copyOf(result);
    }

    public record GenerationResult(List<String> files, Map<String, String> sha256) {
        public GenerationResult {
            files = List.copyOf(files);
            sha256 = Map.copyOf(sha256);
        }
    }

    private enum Variant {
        QUIET_RUNE,
        INTER,
        PLEX
    }

    private enum Role {
        TITLE,
        SUBTITLE,
        HEADING,
        BODY,
        HUD,
        NUMBERS,
        BUTTON,
        STATUS,
        CAPTION
    }

    private record TextRun(Role role, String text, int x, int y, int argb) {}

    private record QuietRuneFace(BufferedImage image, Map<Integer, QuietGlyph> glyphs) {}

    private record QuietGlyph(int column, int row, int advance) {}
}
