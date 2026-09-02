package com.gaia.tools.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Deterministic build-only main-menu concepts for the Gate 17.5B.5 visual gate. */
public final class MainMenuVisualConceptGenerator {
    private static final int WIDTH = 1_280;
    private static final int HEIGHT = 720;
    private static final String HERO_REPOSITORY_PATH = "docs/images/gaialegacy-hero.png";
    private static final String HERO_SHA256 =
            "66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262";
    private static final String HERO_COMMIT =
            "d13d8fe4d0ac59e2a1a94b84cc0ed698fa6aca33";
    private static final String HERO_GIT_BLOB =
            "ff1da87408d26db9fd17d3e429f88407ce75c3e6";
    private static final List<String> MENU_LABELS = List.of(
            "CONTINUE", "NEW WORLD", "WORLD ARCHIVE", "SETTINGS", "CONTROLS", "QUIT");

    public GenerationResult generate(Path outputDirectory, Path heroSource) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(heroSource, "heroSource");
        byte[] heroBytes = read(heroSource);
        String heroHash = TypographySpecimenGenerator.sha256(heroBytes);
        if (!heroHash.equals(HERO_SHA256)) {
            throw new IllegalArgumentException(
                    "Gaia hero source hash does not match the project-owned receipt");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(heroSource.toFile());
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to decode Gaia hero source", failure);
        }
        if (source == null || source.getWidth() != 2_560 || source.getHeight() != 1_345) {
            throw new IllegalArgumentException("Gaia hero source dimensions changed");
        }

        ClassLoader loader = getClass().getClassLoader();
        Map<String, TrueTypeFontRasterizer.RasterizedFace> faces =
                TypographySpecimenGenerator.rasterizeReviewFaces(
                        loader, FontSourceManifest.load(loader));
        BufferedImage hero = coverResize(source, WIDTH, HEIGHT);
        Map<String, byte[]> generated = new TreeMap<>();
        generated.put("concept-a-gaia-panorama.png",
                TypographySpecimenGenerator.png(render(hero, faces, Concept.GAIA_PANORAMA)));
        generated.put("concept-b-orbital-legacy.png",
                TypographySpecimenGenerator.png(render(hero, faces, Concept.ORBITAL_LEGACY)));
        generated.put("concept-c-dark-signal.png",
                TypographySpecimenGenerator.png(render(hero, faces, Concept.DARK_SIGNAL)));

        Map<String, String> derivativeHashes = hashes(generated);
        generated.put("measurement.json", TypographySpecimenGenerator.json(
                measurement(source, generated, derivativeHashes)));
        write(outputDirectory, generated);
        return new GenerationResult(
                List.copyOf(generated.keySet()), Map.copyOf(hashes(generated)));
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected output directory and Gaia hero source");
        }
        GenerationResult result = new MainMenuVisualConceptGenerator().generate(
                Path.of(arguments[0]), Path.of(arguments[1]));
        System.out.println("Generated Gate 17.5B.5 concepts: " + result.files());
    }

    private static BufferedImage render(
            BufferedImage hero,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            Concept concept) {
        BufferedImage image = grade(hero, concept);
        switch (concept) {
            case GAIA_PANORAMA -> {
                directionalShade(image, 0.84, 0.08, 0.14);
                drawTopographicMotif(image, 890, 470, 0x147ce7ff);
                drawWordmark(image, faces, 84, 64, 1.0, 0xff83edff, 0xfff3f8ff);
                drawMenu(image, faces, 100, 288, 0xfff1f7ff, 0xff7ce7ff);
                drawFooter(image, faces, 100, 676, "GAIA // FRONTIER CHANNEL 01");
            }
            case ORBITAL_LEGACY -> {
                directionalShade(image, 0.76, 0.18, 0.24);
                drawOrbitMotif(image, 956, 318, 244, 0x287ce7ff, 0x187b5ee5);
                drawWordmark(image, faces, 84, 70, 1.0, 0xff8deeff, 0xfff5f7ff);
                drawMenu(image, faces, 96, 306, 0xffeef5fb, 0xff7ce7ff);
                drawFooter(image, faces, 96, 676, "GAIA // ORBITAL LEGACY SIGNAL");
            }
            case DARK_SIGNAL -> {
                directionalShade(image, 0.91, 0.48, 0.42);
                drawTopographicMotif(image, 690, 405, 0x127ce7ff);
                drawOrbitMotif(image, 1030, 242, 180, 0x147ce7ff, 0x127b5ee5);
                drawSignalTicks(image, 735, 548, 0x237ce7ff);
                drawWordmark(image, faces, 94, 82, 1.35, 0xff8fefff, 0xfff5f8fc);
                drawMenu(image, faces, 112, 346, 0xffe9f1f8, 0xff7ce7ff);
                drawFooter(image, faces, 112, 676, "GAIA // SIGNAL ACQUIRED");
            }
        }
        return image;
    }

    private static BufferedImage grade(BufferedImage source, Concept concept) {
        BufferedImage result = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = source.getRGB(x, y);
                int red = color >> 16 & 0xff;
                int green = color >> 8 & 0xff;
                int blue = color & 0xff;
                switch (concept) {
                    case GAIA_PANORAMA -> {
                        int warmth = radialStrength(x, y, 1_030, 118, 620);
                        red = clamp(red * 106 / 100 + warmth * 22 / 255);
                        green = clamp(green * 99 / 100 + warmth * 8 / 255);
                        blue = clamp(blue * 88 / 100);
                    }
                    case ORBITAL_LEGACY -> {
                        red = clamp(red * 52 / 100);
                        green = clamp(green * 72 / 100 + 5);
                        blue = clamp(blue * 102 / 100 + 12);
                    }
                    case DARK_SIGNAL -> {
                        int luminance = (red * 54 + green * 183 + blue * 19) / 256;
                        red = clamp(luminance * 16 / 100 + red * 16 / 100);
                        green = clamp(luminance * 24 / 100 + green * 14 / 100);
                        blue = clamp(luminance * 34 / 100 + blue * 18 / 100 + 5);
                    }
                }
                result.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
        return result;
    }

    private static void directionalShade(
            BufferedImage image, double leftAlpha, double rightAlpha, double bottomAlpha) {
        for (int y = 0; y < HEIGHT; y++) {
            double vertical = bottomAlpha * Math.max(0.0, (y - HEIGHT * 0.48) / (HEIGHT * 0.52));
            for (int x = 0; x < WIDTH; x++) {
                double horizontal = leftAlpha
                        + (rightAlpha - leftAlpha) * Math.min(1.0, x / (WIDTH * 0.72));
                int alpha = clamp((int) Math.round(255.0 * Math.min(0.94, horizontal + vertical)));
                TypographySpecimenGenerator.blend(image, x, y, 0xff06111e, alpha);
            }
        }
    }

    private static void drawWordmark(
            BufferedImage image,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            int x,
            int y,
            double scale,
            int primary,
            int secondary) {
        int emblemSize = (int) Math.round(76 * scale);
        drawGaiaEmblem(image, x, y, emblemSize, primary);
        int textX = x + emblemSize + (int) Math.round(22 * scale);
        TypographySpecimenGenerator.drawTrueTypeText(
                image, faces.get("pixelify-bold-700"), "GAIA", textX, y + 2, primary);
        TypographySpecimenGenerator.drawTrueTypeText(
                image, faces.get("inter-semibold-600"), "L E G A C Y", textX + 3, y + 51,
                secondary);
        drawLine(image, textX + 2, y + 78, textX + 178, y + 78, 0x667ce7ff, 1);
    }

    private static void drawGaiaEmblem(
            BufferedImage image, int x, int y, int size, int color) {
        int centerX = x + size / 2;
        int centerY = y + size / 2;
        int radius = size / 2 - 3;
        drawArc(image, centerX, centerY, radius, 198, 332, color, 2);
        drawArc(image, centerX, centerY, radius, 348, 510, color, 2);
        int horizon = centerY + size / 9;
        drawLine(image, x + size / 7, horizon, x + size * 6 / 7, horizon, color, 1);
        drawLine(image, x + size / 7, horizon,
                x + size * 2 / 5, centerY - size / 12, color, 2);
        drawLine(image, x + size * 2 / 5, centerY - size / 12,
                x + size * 3 / 5, horizon - size / 6, color, 2);
        drawLine(image, x + size * 3 / 5, horizon - size / 6,
                x + size * 6 / 7, horizon, color, 2);
        int cell = Math.max(2, size / 16);
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                TypographySpecimenGenerator.fillRect(
                        image,
                        x + size * 11 / 16 + column * (cell + 2),
                        y + size * 11 / 16 + row * (cell + 2),
                        cell, cell, color);
            }
        }
    }

    private static void drawMenu(
            BufferedImage image,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            int x,
            int y,
            int normal,
            int selected) {
        TrueTypeFontRasterizer.RasterizedFace font = faces.get("inter-semibold-600");
        for (int index = 0; index < MENU_LABELS.size(); index++) {
            int top = y + index * 44;
            if (index == 0) {
                TypographySpecimenGenerator.fillRect(image, x - 18, top + 3, 3, 18, selected);
                TypographySpecimenGenerator.fillRect(image, x - 8, top + 26, 110, 1, 0x557ce7ff);
            }
            TypographySpecimenGenerator.drawTrueTypeText(
                    image, font, MENU_LABELS.get(index), x, top, index == 0 ? selected : normal);
        }
    }

    private static void drawFooter(
            BufferedImage image,
            Map<String, TrueTypeFontRasterizer.RasterizedFace> faces,
            int x,
            int y,
            String status) {
        TypographySpecimenGenerator.drawTrueTypeText(
                image, faces.get("inter-medium-500"), status, x, y, 0xff8ca0b4);
        TypographySpecimenGenerator.drawTrueTypeText(
                image, faces.get("inter-regular-400"), "v0.2 // MILESTONE 2", 1_055, y,
                0xff8ca0b4);
    }

    private static void drawTopographicMotif(
            BufferedImage image, int originX, int originY, int color) {
        for (int contour = 0; contour < 6; contour++) {
            int priorX = originX;
            int priorY = originY + contour * 18;
            for (int step = 1; step <= 48; step++) {
                int x = originX + step * 9;
                int y = originY + contour * 18
                        + (int) Math.round(12 * StrictMath.sin(step * 0.22 + contour * 0.65));
                drawLine(image, priorX, priorY, x, y, color, 1);
                priorX = x;
                priorY = y;
            }
        }
    }

    private static void drawOrbitMotif(
            BufferedImage image,
            int centerX,
            int centerY,
            int radius,
            int cyan,
            int violet) {
        drawArc(image, centerX, centerY, radius, 192, 326, cyan, 1);
        drawArc(image, centerX, centerY, radius, 343, 425, cyan, 1);
        drawArc(image, centerX, centerY, radius * 3 / 4, 18, 164, violet, 1);
        drawArc(image, centerX, centerY, radius * 3 / 4, 190, 286, violet, 1);
        TypographySpecimenGenerator.fillRect(image,
                centerX + radius * 3 / 4 - 2, centerY - 2, 5, 5, 0x707ce7ff);
    }

    private static void drawSignalTicks(
            BufferedImage image, int x, int y, int color) {
        for (int index = 0; index < 24; index++) {
            int height = index % 4 == 0 ? 14 : index % 2 == 0 ? 8 : 4;
            drawLine(image, x + index * 18, y - height, x + index * 18, y, color, 1);
        }
        drawLine(image, x, y, x + 23 * 18, y, color, 1);
    }

    private static void drawArc(
            BufferedImage image,
            int centerX,
            int centerY,
            int radius,
            int startDegrees,
            int endDegrees,
            int color,
            int thickness) {
        int priorX = centerX + (int) Math.round(radius
                * StrictMath.cos(StrictMath.toRadians(startDegrees)));
        int priorY = centerY + (int) Math.round(radius
                * StrictMath.sin(StrictMath.toRadians(startDegrees)));
        for (int degree = startDegrees + 1; degree <= endDegrees; degree++) {
            int x = centerX + (int) Math.round(radius
                    * StrictMath.cos(StrictMath.toRadians(degree)));
            int y = centerY + (int) Math.round(radius
                    * StrictMath.sin(StrictMath.toRadians(degree)));
            drawLine(image, priorX, priorY, x, y, color, thickness);
            priorX = x;
            priorY = y;
        }
    }

    private static void drawLine(
            BufferedImage image,
            int x0,
            int y0,
            int x1,
            int y1,
            int color,
            int thickness) {
        int deltaX = Math.abs(x1 - x0);
        int stepX = x0 < x1 ? 1 : -1;
        int deltaY = -Math.abs(y1 - y0);
        int stepY = y0 < y1 ? 1 : -1;
        int error = deltaX + deltaY;
        while (true) {
            for (int y = -thickness / 2; y <= thickness / 2; y++) {
                for (int x = -thickness / 2; x <= thickness / 2; x++) {
                    TypographySpecimenGenerator.blend(image, x0 + x, y0 + y, color, 255);
                }
            }
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int twice = error * 2;
            if (twice >= deltaY) {
                error += deltaY;
                x0 += stepX;
            }
            if (twice <= deltaX) {
                error += deltaX;
                y0 += stepY;
            }
        }
    }

    private static BufferedImage coverResize(
            BufferedImage source, int targetWidth, int targetHeight) {
        int cropWidth = source.getHeight() * targetWidth / targetHeight;
        int cropHeight = source.getHeight();
        int cropX = (source.getWidth() - cropWidth) / 2;
        BufferedImage output = new BufferedImage(
                targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < targetHeight; y++) {
            long sourceYFixed = ((2L * y + 1L) * cropHeight << 15) / targetHeight
                    - (1L << 15);
            int sourceY = clamp((int) (sourceYFixed >> 16), 0, cropHeight - 1);
            int nextY = Math.min(cropHeight - 1, sourceY + 1);
            int fractionY = (int) sourceYFixed & 0xffff;
            for (int x = 0; x < targetWidth; x++) {
                long sourceXFixed = ((2L * x + 1L) * cropWidth << 15) / targetWidth
                        - (1L << 15) + ((long) cropX << 16);
                int sourceX = clamp((int) (sourceXFixed >> 16), cropX,
                        cropX + cropWidth - 1);
                int nextX = Math.min(cropX + cropWidth - 1, sourceX + 1);
                int fractionX = (int) sourceXFixed & 0xffff;
                int topLeft = source.getRGB(sourceX, sourceY);
                int topRight = source.getRGB(nextX, sourceY);
                int bottomLeft = source.getRGB(sourceX, nextY);
                int bottomRight = source.getRGB(nextX, nextY);
                int red = bilinear(topLeft >> 16 & 0xff, topRight >> 16 & 0xff,
                        bottomLeft >> 16 & 0xff, bottomRight >> 16 & 0xff,
                        fractionX, fractionY);
                int green = bilinear(topLeft >> 8 & 0xff, topRight >> 8 & 0xff,
                        bottomLeft >> 8 & 0xff, bottomRight >> 8 & 0xff,
                        fractionX, fractionY);
                int blue = bilinear(topLeft & 0xff, topRight & 0xff,
                        bottomLeft & 0xff, bottomRight & 0xff,
                        fractionX, fractionY);
                output.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
        return output;
    }

    private static int bilinear(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            int fractionX,
            int fractionY) {
        int top = (int) (((long) topLeft * (65_536 - fractionX)
                + (long) topRight * fractionX + 32_768) >> 16);
        int bottom = (int) (((long) bottomLeft * (65_536 - fractionX)
                + (long) bottomRight * fractionX + 32_768) >> 16);
        return (int) (((long) top * (65_536 - fractionY)
                + (long) bottom * fractionY + 32_768) >> 16);
    }

    private static JsonObject measurement(
            BufferedImage source,
            Map<String, byte[]> generated,
            Map<String, String> hashes) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("typographySystem", "Pixelify Sans + Inter");
        root.addProperty("fontAtlasPages", 2);
        JsonObject background = new JsonObject();
        background.addProperty("repositoryPath", HERO_REPOSITORY_PATH);
        background.addProperty("sourceWidth", source.getWidth());
        background.addProperty("sourceHeight", source.getHeight());
        background.addProperty("sourceSha256", HERO_SHA256);
        background.addProperty("gitCommit", HERO_COMMIT);
        background.addProperty("gitBlobSha", HERO_GIT_BLOB);
        background.addProperty("ownership", "PROJECT_OWNED_GAIALEGACY_RUNTIME_CAPTURE");
        root.add("backgroundSource", background);

        JsonArray labels = new JsonArray();
        MENU_LABELS.forEach(labels::add);
        root.add("menuLabels", labels);
        JsonObject wordmark = new JsonObject();
        wordmark.addProperty("status", "PROJECT_OWNED_PLACEHOLDER");
        wordmark.addProperty("treatment",
                "broken planetary ring + terrain horizon + 2x2 DETAIL cells + GAIA/LEGACY lockup");
        wordmark.addProperty("productionPath",
                "project vector source -> deterministic raster -> existing Gaia UI resource authority");
        root.add("wordmark", wordmark);

        JsonArray concepts = new JsonArray();
        addConcept(concepts, "A", "Gaia Panorama", "concept-a-gaia-panorama.png",
                "warm world-first panorama; directional left gradient; sparse contour trace",
                generated, hashes);
        addConcept(concepts, "B", "Orbital Legacy", "concept-b-orbital-legacy.png",
                "cool atmospheric terrain; broken orbital arcs; sparse legacy signal",
                generated, hashes);
        addConcept(concepts, "C", "Dark Signal", "concept-c-dark-signal.png",
                "dark world-presence; enlarged emblem; contour and signal ticks",
                generated, hashes);
        root.add("concepts", concepts);

        JsonObject memory = new JsonObject();
        memory.addProperty("conceptWidth", WIDTH);
        memory.addProperty("conceptHeight", HEIGHT);
        memory.addProperty("rgba8BytesPerTexture", WIDTH * HEIGHT * 4);
        memory.addProperty("twoTextureCrossfadeBytes", WIDTH * HEIGHT * 4 * 2);
        memory.addProperty("sourceRgba8Bytes", source.getWidth() * source.getHeight() * 4);
        root.add("memory", memory);

        JsonObject runtime = new JsonObject();
        runtime.addProperty("recommendedStrategy",
                "3-4 deterministic Gaia captures; only current and next resident during crossfade");
        runtime.addProperty("rendererAuthority", "EXISTING_UI_RENDERER_ONLY");
        runtime.addProperty("requiredRendererChange",
                "narrow hero texture-page binding in UiTextureId/UiAssetBundle/UiRenderer");
        runtime.addProperty("shaderChangeRequired", false);
        runtime.addProperty("secondRendererRequired", false);
        runtime.addProperty("worldOrSimulationRequired", false);
        root.add("runtimeProposal", runtime);
        return root;
    }

    private static void addConcept(
            JsonArray concepts,
            String id,
            String name,
            String file,
            String treatment,
            Map<String, byte[]> generated,
            Map<String, String> hashes) {
        JsonObject concept = new JsonObject();
        concept.addProperty("id", id);
        concept.addProperty("name", name);
        concept.addProperty("file", file);
        concept.addProperty("treatment", treatment);
        concept.addProperty("pngBytes", generated.get(file).length);
        concept.addProperty("sha256", hashes.get(file));
        concepts.add(concept);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to read Gaia hero source", failure);
        }
    }

    private static void write(Path directory, Map<String, byte[]> generated) {
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, byte[]> entry : generated.entrySet()) {
                Files.write(directory.resolve(entry.getKey()), entry.getValue());
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to write main-menu concepts", failure);
        }
    }

    private static Map<String, String> hashes(Map<String, byte[]> generated) {
        Map<String, String> hashes = new TreeMap<>();
        generated.forEach((name, bytes) ->
                hashes.put(name, TypographySpecimenGenerator.sha256(bytes)));
        return hashes;
    }

    private static int radialStrength(
            int x, int y, int centerX, int centerY, int radius) {
        long deltaX = x - centerX;
        long deltaY = y - centerY;
        long squared = deltaX * deltaX + deltaY * deltaY;
        long radiusSquared = (long) radius * radius;
        if (squared >= radiusSquared) {
            return 0;
        }
        return (int) ((radiusSquared - squared) * 255 / radiusSquared);
    }

    private static int clamp(int value) {
        return clamp(value, 0, 255);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public record GenerationResult(List<String> files, Map<String, String> sha256) {
        public GenerationResult {
            files = List.copyOf(files);
            sha256 = Map.copyOf(sha256);
        }
    }

    private enum Concept {
        GAIA_PANORAMA,
        ORBITAL_LEGACY,
        DARK_SIGNAL
    }
}
