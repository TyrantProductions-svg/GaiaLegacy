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

/** Deterministic build-only production hero derivatives for the Phase 17.5 shell. */
public final class RuntimeHeroAssetGenerator {
    private static final int WIDTH = 1_280;
    private static final int HEIGHT = 720;
    private static final String SOURCE_PATH = "docs/images/gaialegacy-hero.png";
    private static final String SOURCE_SHA256 =
            "66021ac3a9d197c8d9e52cab165019263eccfc688d402fe21391e930f87db262";
    private static final String SOURCE_COMMIT =
            "d13d8fe4d0ac59e2a1a94b84cc0ed698fa6aca33";
    private static final String SOURCE_GIT_BLOB =
            "ff1da87408d26db9fd17d3e429f88407ce75c3e6";
    private static final List<Variant> VARIANTS = List.of(
            new Variant("dawn", "gaia-hero-dawn.png", -72, 108, 101, 90, 8),
            new Variant("highlands", "gaia-hero-highlands.png", 0, 92, 98, 104, 3),
            new Variant("twilight", "gaia-hero-twilight.png", 72, 60, 76, 104, -4));

    public GenerationResult generate(Path outputDirectory, Path heroSource) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(heroSource, "heroSource");
        byte[] sourceBytes = read(heroSource);
        if (!SOURCE_SHA256.equals(TypographySpecimenGenerator.sha256(sourceBytes))) {
            throw new IllegalArgumentException(
                    "Gaia hero source hash does not match the project-owned receipt");
        }
        BufferedImage source = decode(heroSource);
        if (source.getWidth() != 2_560 || source.getHeight() != 1_345) {
            throw new IllegalArgumentException("Gaia hero source dimensions changed");
        }

        Map<String, byte[]> files = new TreeMap<>();
        JsonArray heroes = new JsonArray();
        for (Variant variant : VARIANTS) {
            BufferedImage hero = coverResize(source, variant.cropOffsetX());
            grade(hero, variant);
            directionalShade(hero);
            RingedPlanetLayer.bakeInto(hero);
            drawOrbitMotif(hero, 1_067, 230, 190);
            drawTopographicDetailMotif(hero, 890, 528);
            byte[] png = TypographySpecimenGenerator.png(hero);
            files.put(variant.file(), png);

            JsonObject entry = new JsonObject();
            entry.addProperty("id", variant.id());
            entry.addProperty("image", "gaia:ui/hero/" + variant.file());
            entry.addProperty("width", WIDTH);
            entry.addProperty("height", HEIGHT);
            entry.addProperty("sampling", "LINEAR");
            entry.addProperty("pngSha256", TypographySpecimenGenerator.sha256(png));
            entry.addProperty("rgba8Bytes", WIDTH * HEIGHT * 4);
            heroes.add(entry);
        }
        files.put("hero-manifest.json", TypographySpecimenGenerator.json(
                manifest(heroes)));
        write(outputDirectory, files);
        return new GenerationResult(List.copyOf(files.keySet()), Map.copyOf(hashes(files)));
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected output directory and Gaia hero source");
        }
        GenerationResult result = new RuntimeHeroAssetGenerator().generate(
                Path.of(arguments[0]), Path.of(arguments[1]));
        System.out.println("Generated runtime heroes: " + result.files());
    }

    private static JsonObject manifest(JsonArray heroes) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject source = new JsonObject();
        source.addProperty("repositoryPath", SOURCE_PATH);
        source.addProperty("sha256", SOURCE_SHA256);
        source.addProperty("gitCommit", SOURCE_COMMIT);
        source.addProperty("gitBlobSha", SOURCE_GIT_BLOB);
        source.addProperty("ownership", "PROJECT_OWNED_GAIALEGACY_RUNTIME_CAPTURE");
        root.add("source", source);
        root.add("heroes", heroes);
        root.addProperty("initialHero", "dawn");
        root.addProperty("runtimeMode", "STATIC_VERTICAL_SLICE");
        root.addProperty("maximumResidentHeroPages", 1);
        JsonObject treatment = new JsonObject();
        treatment.addProperty("direction", "A_PLUS_70_PERCENT_GAIA_30_PERCENT_LEGACY");
        treatment.addProperty("directionalLeftShade", true);
        treatment.addProperty("celestialBody", true);
        JsonObject planet = new JsonObject();
        planet.addProperty("sourcePath", "tools/src/main/java/com/gaia/tools/ui/RingedPlanetLayer.java");
        planet.addProperty("ownership", "PROJECT_OWNED_PARAMETRIC_GEOMETRY");
        planet.addProperty("sphereDiameter", 200);
        planet.addProperty("centerX", 1040);
        planet.addProperty("centerY", 132);
        planet.addProperty("ringMajorAxis", 430);
        planet.addProperty("ringMinorAxis", 112);
        planet.addProperty("tiltDegrees", -15);
        planet.addProperty("supersampling", 4);
        planet.addProperty("occlusion", "BACK_RING_SPHERE_FRONT_RING_THEN_ATMOSPHERE");
        planet.addProperty("baked", true);
        Path planetSource = Path.of(planet.get("sourcePath").getAsString());
        if (!Files.isRegularFile(planetSource)) planetSource = Path.of("..").resolve(planetSource);
        planet.addProperty("sourceSha256", TypographySpecimenGenerator.sha256(read(planetSource)));
        treatment.add("ringedPlanet", planet);
        treatment.addProperty("brokenOrbitAccents", true);
        treatment.addProperty("topographicDetailMotif", true);
        root.add("treatment", treatment);
        return root;
    }

    private static BufferedImage decode(Path source) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new IllegalArgumentException("unable to decode Gaia hero source");
            }
            return image;
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to decode Gaia hero source", failure);
        }
    }

    private static BufferedImage coverResize(BufferedImage source, int requestedOffsetX) {
        int cropWidth = source.getHeight() * WIDTH / HEIGHT;
        int cropHeight = source.getHeight();
        int centered = (source.getWidth() - cropWidth) / 2;
        int cropX = clamp(centered + requestedOffsetX, 0, source.getWidth() - cropWidth);
        BufferedImage output = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            long sourceYFixed = ((2L * y + 1L) * cropHeight << 15) / HEIGHT
                    - (1L << 15);
            int sourceY = clamp((int) (sourceYFixed >> 16), 0, cropHeight - 1);
            int nextY = Math.min(cropHeight - 1, sourceY + 1);
            int fractionY = (int) sourceYFixed & 0xffff;
            for (int x = 0; x < WIDTH; x++) {
                long sourceXFixed = ((2L * x + 1L) * cropWidth << 15) / WIDTH
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
            int topLeft, int topRight, int bottomLeft, int bottomRight,
            int fractionX, int fractionY) {
        int top = (int) (((long) topLeft * (65_536 - fractionX)
                + (long) topRight * fractionX + 32_768) >> 16);
        int bottom = (int) (((long) bottomLeft * (65_536 - fractionX)
                + (long) bottomRight * fractionX + 32_768) >> 16);
        return (int) (((long) top * (65_536 - fractionY)
                + (long) bottom * fractionY + 32_768) >> 16);
    }

    private static void grade(BufferedImage image, Variant variant) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = image.getRGB(x, y);
                int red = clamp((color >> 16 & 0xff) * variant.redPercent() / 100
                        + variant.lift());
                int green = clamp((color >> 8 & 0xff) * variant.greenPercent() / 100
                        + variant.lift());
                int blue = clamp((color & 0xff) * variant.bluePercent() / 100
                        + variant.lift());
                image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
    }

    private static void directionalShade(BufferedImage image) {
        for (int y = 0; y < HEIGHT; y++) {
            double bottom = 0.12 * Math.max(0.0, (y - HEIGHT * 0.55) / (HEIGHT * 0.45));
            for (int x = 0; x < WIDTH; x++) {
                double horizontal = 0.80 - 0.70 * Math.min(1.0, x / (WIDTH * 0.68));
                blend(image, x, y, 0xff06111e,
                        clamp((int) Math.round(255.0 * Math.min(0.90, horizontal + bottom))));
            }
        }
    }

    private static void drawOrbitMotif(BufferedImage image, int centerX, int centerY, int radius) {
        drawArc(image, centerX, centerY, radius, 354, 405, 0xff7ce7ff, 8);
        drawArc(image, centerX, centerY, radius * 3 / 4, 32, 62, 0xff8d6fe8, 6);
    }

    private static void drawTopographicDetailMotif(BufferedImage image, int originX, int originY) {
        for (int contour = 0; contour < 5; contour++) {
            int priorX = originX;
            int priorY = originY + contour * 17;
            for (int step = 1; step <= 38; step++) {
                int x = originX + step * 9;
                int y = originY + contour * 17
                        + (int) Math.round(10 * StrictMath.sin(step * 0.24 + contour * 0.7));
                drawLine(image, priorX, priorY, x, y, 0xff7ce7ff, 12);
                priorX = x;
                priorY = y;
            }
        }
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                int alpha = (row + column) % 3 == 0 ? 19 : 9;
                fillRect(image, 1_184 + column * 11, 648 + row * 11, 6, 6,
                        0xff7ce7ff, alpha);
            }
        }
    }

    private static void drawArc(
            BufferedImage image, int centerX, int centerY, int radius,
            int startDegrees, int endDegrees, int color, int alpha) {
        int priorX = centerX + (int) Math.round(radius
                * StrictMath.cos(StrictMath.toRadians(startDegrees)));
        int priorY = centerY + (int) Math.round(radius
                * StrictMath.sin(StrictMath.toRadians(startDegrees)));
        for (int degree = startDegrees + 1; degree <= endDegrees; degree++) {
            int x = centerX + (int) Math.round(radius
                    * StrictMath.cos(StrictMath.toRadians(degree)));
            int y = centerY + (int) Math.round(radius
                    * StrictMath.sin(StrictMath.toRadians(degree)));
            drawLine(image, priorX, priorY, x, y, color, alpha);
            priorX = x;
            priorY = y;
        }
    }

    private static void drawLine(
            BufferedImage image, int x0, int y0, int x1, int y1, int color, int alpha) {
        int deltaX = Math.abs(x1 - x0);
        int stepX = x0 < x1 ? 1 : -1;
        int deltaY = -Math.abs(y1 - y0);
        int stepY = y0 < y1 ? 1 : -1;
        int error = deltaX + deltaY;
        while (true) {
            blend(image, x0, y0, color, alpha);
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

    private static void fillRect(
            BufferedImage image, int x, int y, int width, int height, int color, int alpha) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                blend(image, x + column, y + row, color, alpha);
            }
        }
    }

    private static void blend(BufferedImage image, int x, int y, int color, int alpha) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return;
        }
        int destination = image.getRGB(x, y);
        int inverse = 255 - alpha;
        int red = ((color >> 16 & 0xff) * alpha
                + (destination >> 16 & 0xff) * inverse + 127) / 255;
        int green = ((color >> 8 & 0xff) * alpha
                + (destination >> 8 & 0xff) * inverse + 127) / 255;
        int blue = ((color & 0xff) * alpha
                + (destination & 0xff) * inverse + 127) / 255;
        image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to read Gaia hero source", failure);
        }
    }

    private static void write(Path outputDirectory, Map<String, byte[]> files) {
        try {
            Files.createDirectories(outputDirectory);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Files.write(outputDirectory.resolve(entry.getKey()), entry.getValue());
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to write runtime hero assets", failure);
        }
    }

    private static Map<String, String> hashes(Map<String, byte[]> files) {
        Map<String, String> result = new TreeMap<>();
        files.forEach((name, bytes) ->
                result.put(name, TypographySpecimenGenerator.sha256(bytes)));
        return result;
    }

    private static int clamp(int value) {
        return clamp(value, 0, 255);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private record Variant(
            String id,
            String file,
            int cropOffsetX,
            int redPercent,
            int greenPercent,
            int bluePercent,
            int lift) {}

    public record GenerationResult(List<String> files, Map<String, String> sha256) {
        public GenerationResult {
            files = List.copyOf(files);
            sha256 = Map.copyOf(sha256);
        }
    }
}
