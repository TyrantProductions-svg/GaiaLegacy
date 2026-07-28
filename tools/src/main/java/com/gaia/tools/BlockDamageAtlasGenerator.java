package com.gaia.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

public final class BlockDamageAtlasGenerator {
    private static final long SEED = 0x474149413942L;
    private static final int STAGES = 10;
    private static final int TILE_SIZE = 16;
    private static final int CRACK = 0xd8000000;

    private BlockDamageAtlasGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected exactly one output path");
        }
        Path output = Path.of(args[0]);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(generate(), "png", output.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
    }

    static BufferedImage generate() {
        List<List<Segment>> groups = segmentGroups();
        BufferedImage atlas =
                new BufferedImage(STAGES * TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int stage = 0; stage < STAGES; stage++) {
            int tileOffset = stage * TILE_SIZE;
            for (int group = 0; group <= stage; group++) {
                for (Segment segment : groups.get(group)) {
                    drawLine(atlas, tileOffset, segment);
                }
            }
        }
        return atlas;
    }

    private static List<List<Segment>> segmentGroups() {
        Random random = new Random(SEED);
        List<List<Segment>> groups = new ArrayList<>();
        int anchorX = 8;
        int anchorY = 8;
        for (int group = 0; group < STAGES; group++) {
            List<Segment> segments = new ArrayList<>();
            int branchCount = group == 0 ? 3 : 2;
            for (int branch = 0; branch < branchCount; branch++) {
                double angle = (group * 2 + branch) * Math.PI / 7.0 + random.nextDouble() * 0.35;
                int length = 3 + random.nextInt(5);
                int endX = clamp(anchorX + (int) Math.round(Math.cos(angle) * length));
                int endY = clamp(anchorY + (int) Math.round(Math.sin(angle) * length));
                segments.add(new Segment(anchorX, anchorY, endX, endY));
                if (branch == 0) {
                    anchorX = endX;
                    anchorY = endY;
                }
            }
            groups.add(List.copyOf(segments));
        }
        return List.copyOf(groups);
    }

    private static void drawLine(BufferedImage image, int offsetX, Segment segment) {
        int x = segment.x0();
        int y = segment.y0();
        int deltaX = Math.abs(segment.x1() - x);
        int stepX = x < segment.x1() ? 1 : -1;
        int deltaY = -Math.abs(segment.y1() - y);
        int stepY = y < segment.y1() ? 1 : -1;
        int error = deltaX + deltaY;
        while (true) {
            image.setRGB(offsetX + x, y, CRACK);
            if (x == segment.x1() && y == segment.y1()) {
                return;
            }
            int doubledError = error * 2;
            if (doubledError >= deltaY) {
                error += deltaY;
                x += stepX;
            }
            if (doubledError <= deltaX) {
                error += deltaX;
                y += stepY;
            }
        }
    }

    private static int clamp(int coordinate) {
        return Math.max(1, Math.min(TILE_SIZE - 2, coordinate));
    }

    private record Segment(int x0, int y0, int x1, int y1) {}
}
