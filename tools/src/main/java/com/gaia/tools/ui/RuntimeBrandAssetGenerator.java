package com.gaia.tools.ui;

import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Project-owned canonical vector/path source and build-only 4x coverage rasterizer. */
public final class RuntimeBrandAssetGenerator {
    private static final int SIZE = 256;
    private static final int SUPERSAMPLE = 4;
    private static final int INK = 0x91dce8;
    private static final String SOURCE =
            "tools/src/main/java/com/gaia/tools/ui/RuntimeBrandAssetGenerator.java";

    public void generate(Path directory) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Path2D.Double horizon = new Path2D.Double();
        horizon.moveTo(53, 143);
        horizon.curveTo(65, 142, 72, 138, 81, 135);
        horizon.lineTo(104, 101);
        horizon.lineTo(123, 125);
        horizon.lineTo(137, 115);
        horizon.lineTo(159, 139);
        horizon.curveTo(176, 140, 190, 143, 201, 143);
        Shape terrain = new BasicStroke(5.0f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND).createStrokedShape(horizon);
        Path2D.Double land = new Path2D.Double();
        land.moveTo(68, 162);
        land.curveTo(91, 172, 110, 174, 133, 173);
        Shape horizonBase = new BasicStroke(3.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND).createStrokedShape(land);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int coverage = 0;
                for (int sy = 0; sy < SUPERSAMPLE; sy++) {
                    for (int sx = 0; sx < SUPERSAMPLE; sx++) {
                        double px = x + (sx + 0.5) / SUPERSAMPLE;
                        double py = y + (sy + 0.5) / SUPERSAMPLE;
                        double dx = px - 128, dy = py - 128;
                        double radius = StrictMath.sqrt(dx * dx + dy * dy);
                        double angle = StrictMath.toDegrees(StrictMath.atan2(dy, dx));
                        boolean ring = radius >= 90 && radius <= 96
                                && !(angle > -108 && angle < -70)
                                && !(angle > 31 && angle < 51);
                        boolean cells = false;
                        for (int cy = 0; cy < 2; cy++) {
                            for (int cx = 0; cx < 2; cx++) {
                                double left = 151 + cx * 13, top = 159 + cy * 13;
                                cells |= px >= left && px < left + 9
                                        && py >= top && py < top + 9;
                            }
                        }
                        if (ring || cells || terrain.contains(px, py)
                                || horizonBase.contains(px, py)) coverage++;
                    }
                }
                int alpha = (coverage * 255 + 8) / 16;
                // Preserve ink RGB even at zero alpha: straight-alpha LINEAR has no fringe.
                image.setRGB(x, y, alpha << 24 | INK);
            }
        }
        byte[] png = TypographySpecimenGenerator.png(image);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("version", 1);
        receipt.addProperty("image", "gaia:ui/brand/gaia-emblem.png");
        receipt.addProperty("width", SIZE);
        receipt.addProperty("height", SIZE);
        receipt.addProperty("sampling", "LINEAR");
        receipt.addProperty("alphaMode", "STRAIGHT_INK_RGB_PADDING");
        receipt.addProperty("paddingPixels", 16);
        receipt.addProperty("supersampling", SUPERSAMPLE);
        receipt.addProperty("rgba8Bytes", SIZE * SIZE * 4);
        receipt.addProperty("ownership", "PROJECT_OWNED_GAIALEGACY_VECTOR_PATH");
        receipt.addProperty("sourcePath", SOURCE);
        receipt.addProperty("pngSha256", TypographySpecimenGenerator.sha256(png));
        try {
            Path source = Path.of(SOURCE);
            if (!Files.isRegularFile(source)) source = Path.of("..").resolve(SOURCE);
            receipt.addProperty("sourceSha256",
                    TypographySpecimenGenerator.sha256(Files.readAllBytes(source)));
            Files.createDirectories(directory);
            Files.write(directory.resolve("gaia-emblem.png"), png);
            Files.write(directory.resolve("brand-manifest.json"),
                    TypographySpecimenGenerator.json(receipt));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to generate Gaia brand asset", failure);
        }
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) throw new IllegalArgumentException("expected output directory");
        new RuntimeBrandAssetGenerator().generate(Path.of(arguments[0]));
    }
}
