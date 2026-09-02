package com.gaia.tools.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Generates the approved deterministic Pixelify/Inter runtime typography assets. */
public final class RuntimeTypographyAssetGenerator {
    private static final List<String> PRODUCTION_FACE_IDS = List.of(
            "pixelify-bold-700",
            "pixelify-semibold-600",
            "inter-regular-400",
            "inter-medium-500",
            "inter-semibold-600");

    public GenerationResult generate(Path outputDirectory) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to create typography output", failure);
        }
        ClassLoader loader = getClass().getClassLoader();
        Map<String, TrueTypeFontRasterizer.RasterizedFace> allFaces =
                TypographySpecimenGenerator.rasterizeReviewFaces(
                        loader, FontSourceManifest.load(loader));
        List<TrueTypeFontRasterizer.RasterizedFace> faces = PRODUCTION_FACE_IDS.stream()
                .map(allFaces::get)
                .toList();
        FontAtlasPacker.GeneratedTypographyAtlas atlas = new FontAtlasPacker().pack(
                faces,
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
                2);

        Map<String, byte[]> generated = new TreeMap<>();
        generated.put("ui_font_body.png",
                TypographySpecimenGenerator.png(atlas.page("body-linear")));
        generated.put("ui_font_display.png",
                TypographySpecimenGenerator.png(atlas.page("display-nearest")));
        generated.put("ui_typography.json",
                TypographySpecimenGenerator.json(metadata(atlas, allFaces)));
        generated.forEach((name, bytes) -> {
            try {
                Files.write(outputDirectory.resolve(name), bytes);
            } catch (IOException failure) {
                throw new IllegalArgumentException("unable to write " + name, failure);
            }
        });
        Map<String, String> hashes = new TreeMap<>();
        generated.forEach((name, bytes) -> hashes.put(
                name, TypographySpecimenGenerator.sha256(bytes)));
        return new GenerationResult(List.copyOf(generated.keySet()), hashes);
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output directory argument");
        }
        GenerationResult result = new RuntimeTypographyAssetGenerator()
                .generate(Path.of(arguments[0]));
        System.out.println("Generated runtime typography: " + result.files());
    }

    private static JsonObject metadata(
            FontAtlasPacker.GeneratedTypographyAtlas atlas,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("defaultRole", "BODY");
        JsonArray pages = new JsonArray();
        for (FontAtlasPacker.AtlasPage page : atlas.pages()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", page.id());
            value.addProperty("image", page.id().equals("body-linear")
                    ? "gaia:ui/ui_font_body.png" : "gaia:ui/ui_font_display.png");
            value.addProperty("textureId", page.id().equals("body-linear")
                    ? "FONT_BODY" : "FONT_DISPLAY");
            value.addProperty("width", page.width());
            value.addProperty("height", page.height());
            value.addProperty("sampling", page.samplingMode().name());
            value.addProperty("rgbaSha256", page.sha256());
            pages.add(value);
        }
        root.add("pages", pages);

        JsonArray faceValues = new JsonArray();
        for (String faceId : PRODUCTION_FACE_IDS) {
            TrueTypeFontRasterizer.RasterizedFace face = faces.get(faceId);
            JsonObject value = new JsonObject();
            value.addProperty("id", faceId);
            value.addProperty("page", atlas.facePages().get(faceId));
            value.addProperty("pixelHeight", face.pixelHeight());
            value.addProperty("ascent", face.ascent());
            value.addProperty("descent", face.descent());
            value.addProperty("lineGap", face.lineGap());
            value.addProperty("baseline", face.baseline());
            value.addProperty("lineHeight", face.lineHeight());
            value.addProperty("fallbackCodePoint", 0xfffd);
            JsonArray glyphs = new JsonArray();
            for (TrueTypeFontRasterizer.RasterizedGlyph glyph : face.glyphs()) {
                FontAtlasPacker.GlyphPlacement placement =
                        atlas.placement(faceId, glyph.codePoint());
                JsonObject glyphValue = new JsonObject();
                glyphValue.addProperty("codePoint", glyph.codePoint());
                glyphValue.addProperty("x", placement.x());
                glyphValue.addProperty("y", placement.y());
                glyphValue.addProperty("width", placement.width());
                glyphValue.addProperty("height", placement.height());
                glyphValue.addProperty("advance", placement.advance());
                glyphValue.addProperty("bearingX", placement.bearingX());
                glyphValue.addProperty("bearingY", placement.bearingY());
                glyphs.add(glyphValue);
            }
            value.add("glyphs", glyphs);
            faceValues.add(value);
        }
        root.add("faces", faceValues);

        JsonObject roles = new JsonObject();
        roles.addProperty("DISPLAY_TITLE", "pixelify-bold-700");
        roles.addProperty("HEADING_LARGE", "pixelify-semibold-600");
        roles.addProperty("BODY", "inter-regular-400");
        roles.addProperty("FUNCTIONAL", "inter-semibold-600");
        roles.addProperty("HUD", "inter-medium-500");
        root.add("roles", roles);
        return root;
    }

    public record GenerationResult(List<String> files, Map<String, String> sha256) {
        public GenerationResult {
            files = List.copyOf(files);
            sha256 = Map.copyOf(new LinkedHashMap<>(sha256));
        }
    }
}
