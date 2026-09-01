package com.gaia.tools.ui;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

public final class BlockIconGenerator {
    private static final int ATLAS_WIDTH = 128;
    private static final int ATLAS_HEIGHT = 64;
    private static final int CELL_SIZE = 32;
    private static final int COLUMNS = 4;
    private static final List<IconRequest> REQUIRED = List.of(
            new IconRequest(ResourceLocation.parse("gaia:grass"), "Grass"),
            new IconRequest(ResourceLocation.parse("gaia:dirt"), "Dirt"),
            new IconRequest(ResourceLocation.parse("gaia:stone"), "Stone"),
            new IconRequest(ResourceLocation.parse("gaia:oak_log"), "Oak Log"),
            new IconRequest(ResourceLocation.parse("gaia:oak_leaves"), "Oak Leaves"));
    private static final IconRequest CHISEL =
            new IconRequest(ResourceLocation.parse("gaia:chisel"), "Chisel");

    public GeneratedIcons generate(AssetManager assetManager) throws IOException {
        Objects.requireNonNull(assetManager, "assetManager");
        GaiaAssetCatalog catalog = new GaiaResourceLoader(assetManager).load();
        TextureAtlasMetadata atlas = catalog.blockAtlas();
        BufferedImage source;
        try (InputStream input = assetManager.open(atlas.texture())) {
            source = ImageIO.read(input);
        }
        if (source == null) {
            throw new IOException(atlas.texture().toClasspathPath() + " is not a decodable PNG");
        }
        if (source.getWidth() != atlas.width() || source.getHeight() != atlas.height()) {
            throw new IOException("block atlas image dimensions do not match metadata");
        }

        byte[] rgba = blankAtlas();
        for (int index = 0; index < REQUIRED.size(); index++) {
            IconRequest request = REQUIRED.get(index);
            BlockDefinition block = catalog.blockRegistry().blockForItem(request.itemId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "required item has no canonical block form: " + request.itemId()));
            if (!block.item().id().equals(request.itemId())) {
                throw new IllegalArgumentException(
                        "canonical item mapping mismatch for " + request.itemId());
            }
            drawBlockIcon(
                    rgba, index, source,
                    catalog.blockRegistry().resolve(block.id()).region(BlockFace.UP),
                    catalog.blockRegistry().resolve(block.id()).region(BlockFace.NORTH),
                    catalog.blockRegistry().resolve(block.id()).region(BlockFace.EAST));
        }
        drawMissing(rgba, REQUIRED.size());
        ItemVisualReference chiselVisual = catalog.blockRegistry().itemVisual(CHISEL.itemId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "required standalone item has no explicit visual: " + CHISEL.itemId()));
        if (chiselVisual.type() != ItemVisualType.ATLAS_REGION
                || !atlas.id().equals(chiselVisual.atlas())) {
            throw new IllegalArgumentException(
                    "required standalone item has an unsupported visual: " + CHISEL.itemId());
        }
        drawAtlasIcon(
                rgba,
                REQUIRED.size() + 1,
                source,
                atlas.requireRegion(chiselVisual.region()));
        return new GeneratedIcons(encodePng(rgba), encodeJson());
    }

    private static byte[] blankAtlas() {
        byte[] rgba = new byte[ATLAS_WIDTH * ATLAS_HEIGHT * 4];
        for (int offset = 0; offset < rgba.length; offset += 4) {
            rgba[offset] = (byte) 0xff;
            rgba[offset + 1] = (byte) 0xff;
            rgba[offset + 2] = (byte) 0xff;
        }
        return rgba;
    }

    private static void drawBlockIcon(
            byte[] target,
            int cell,
            BufferedImage source,
            TextureRegion top,
            TextureRegion north,
            TextureRegion east) {
        int originX = cell % COLUMNS * CELL_SIZE;
        int originY = cell / COLUMNS * CELL_SIZE;
        for (int y = 0; y < CELL_SIZE; y++) {
            for (int x = 0; x < CELL_SIZE; x++) {
                double dx = x - 2.0;
                double dy = y - 9.0;
                double topU = (dx / 14.0 - dy / 7.0) / 2.0;
                double topV = (dx / 14.0 + dy / 7.0) / 2.0;
                if (inside(topU, topV)) {
                    sample(target, originX + x, originY + y,
                            source, top, topU, topV, 100);
                }

                double northU = (x - 2.0) / 14.0;
                double northV = (y - 9.0 - 7.0 * northU) / 14.0;
                if (inside(northU, northV)) {
                    sample(target, originX + x, originY + y,
                            source, north, northU, northV, 82);
                }

                double eastU = (x - 16.0) / 14.0;
                double eastV = (y - 16.0 + 7.0 * eastU) / 14.0;
                if (inside(eastU, eastV)) {
                    sample(target, originX + x, originY + y,
                            source, east, eastU, eastV, 68);
                }
            }
        }
    }

    private static boolean inside(double u, double v) {
        return u >= 0.0 && u < 1.0 && v >= 0.0 && v < 1.0;
    }

    private static void sample(
            byte[] target,
            int targetX,
            int targetY,
            BufferedImage source,
            TextureRegion region,
            double u,
            double v,
            int lightPercent) {
        int sourceX = region.x() + Math.min(region.width() - 1, (int) (u * region.width()));
        int sourceY = region.y() + Math.min(region.height() - 1, (int) (v * region.height()));
        int argb = source.getRGB(sourceX, sourceY);
        int alpha = argb >>> 24;
        if (alpha == 0) {
            put(target, targetX, targetY, 255, 255, 255, 0);
            return;
        }
        put(target, targetX, targetY,
                ((argb >>> 16) & 0xff) * lightPercent / 100,
                ((argb >>> 8) & 0xff) * lightPercent / 100,
                (argb & 0xff) * lightPercent / 100,
                alpha);
    }

    private static void drawMissing(byte[] target, int cell) {
        int originX = cell % COLUMNS * CELL_SIZE;
        int originY = cell / COLUMNS * CELL_SIZE;
        for (int y = 2; y < 30; y++) {
            for (int x = 2; x < 30; x++) {
                double dx = (x - 15.5) / 14.0;
                double dy = (y - 15.5) / 14.0;
                if (dx * dx + dy * dy <= 1.0) {
                    put(target, originX + x, originY + y, 7, 16, 25, 230);
                }
            }
        }
        int[][] edges = {
            {16, 5, 26, 10}, {26, 10, 16, 15}, {16, 15, 6, 10}, {6, 10, 16, 5},
            {6, 10, 6, 21}, {6, 21, 16, 27}, {16, 27, 26, 21}, {26, 21, 26, 10},
            {16, 15, 16, 27}
        };
        for (int[] edge : edges) {
            line(target, originX + edge[0], originY + edge[1],
                    originX + edge[2], originY + edge[3], 234, 246, 244, 255);
        }
        line(target, originX + 13, originY + 11, originX + 16, originY + 9,
                155, 131, 207, 255);
        line(target, originX + 16, originY + 9, originX + 19, originY + 11,
                155, 131, 207, 255);
        line(target, originX + 19, originY + 11, originX + 16, originY + 15,
                155, 131, 207, 255);
        put(target, originX + 16, originY + 19, 155, 131, 207, 255);
    }

    private static void drawAtlasIcon(
            byte[] target,
            int cell,
            BufferedImage source,
            TextureRegion region) {
        int originX = cell % COLUMNS * CELL_SIZE;
        int originY = cell / COLUMNS * CELL_SIZE;
        for (int y = 0; y < CELL_SIZE; y++) {
            int sourceY = region.y() + y * region.height() / CELL_SIZE;
            for (int x = 0; x < CELL_SIZE; x++) {
                int sourceX = region.x() + x * region.width() / CELL_SIZE;
                int argb = source.getRGB(sourceX, sourceY);
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    put(target, originX + x, originY + y, 255, 255, 255, 0);
                } else {
                    put(target, originX + x, originY + y,
                            argb >>> 16 & 0xff,
                            argb >>> 8 & 0xff,
                            argb & 0xff,
                            alpha);
                }
            }
        }
    }

    private static void line(
            byte[] target, int x0, int y0, int x1, int y1,
            int red, int green, int blue, int alpha) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            put(target, x0, y0, red, green, blue, alpha);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int twice = 2 * error;
            if (twice >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static void put(
            byte[] target, int x, int y, int red, int green, int blue, int alpha) {
        int offset = (y * ATLAS_WIDTH + x) * 4;
        target[offset] = (byte) red;
        target[offset + 1] = (byte) green;
        target[offset + 2] = (byte) blue;
        target[offset + 3] = (byte) alpha;
    }

    private static byte[] encodeJson() {
        StringBuilder json = new StringBuilder(2_048);
        json.append("{\n")
                .append("  \"version\": 1,\n")
                .append("  \"atlas\": {\"width\": 128, \"height\": 64},\n")
                .append("  \"cell\": {\"width\": 32, \"height\": 32, ")
                .append("\"columns\": 4, \"rows\": 2},\n")
                .append("  \"pixelFormat\": \"RGBA8\",\n")
                .append("  \"faceLight\": {\"top\": 100, \"north\": 82, \"east\": 68},\n")
                .append("  \"icons\": [\n");
        for (int index = 0; index < REQUIRED.size(); index++) {
            appendIcon(json, REQUIRED.get(index).itemId().toString(),
                    REQUIRED.get(index).displayName(), index, false, true);
        }
        appendIcon(json, "gaia:missing", "Missing", REQUIRED.size(), true, true);
        appendIcon(json, CHISEL.itemId().toString(), CHISEL.displayName(),
                REQUIRED.size() + 1, false, false);
        json.append("  ],\n")
                .append("  \"unassignedCells\": [\n")
                .append("    {\"column\": 3, \"row\": 1}\n")
                .append("  ]\n")
                .append("}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendIcon(
            StringBuilder json,
            String itemId,
            String displayName,
            int cell,
            boolean fallback,
            boolean comma) {
        int column = cell % COLUMNS;
        int row = cell / COLUMNS;
        int x = column * CELL_SIZE;
        int y = row * CELL_SIZE;
        json.append("    {\"itemId\": \"").append(itemId)
                .append("\", \"displayName\": \"").append(displayName)
                .append("\", \"cell\": {\"column\": ").append(column)
                .append(", \"row\": ").append(row)
                .append("}, \"region\": {\"x\": ").append(x)
                .append(", \"y\": ").append(y)
                .append(", \"width\": 32, \"height\": 32}, \"fallback\": ")
                .append(fallback).append("}")
                .append(comma ? ",\n" : "\n");
    }

    private static byte[] encodePng(byte[] rgba) {
        ByteArrayOutputStream png = new ByteArrayOutputStream(rgba.length);
        png.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        ByteArrayOutputStream header = new ByteArrayOutputStream(13);
        writeInt(header, ATLAS_WIDTH);
        writeInt(header, ATLAS_HEIGHT);
        header.write(8);
        header.write(6);
        header.write(0);
        header.write(0);
        header.write(0);
        writeChunk(png, "IHDR", header.toByteArray());

        byte[] scanlines = new byte[ATLAS_HEIGHT * (1 + ATLAS_WIDTH * 4)];
        int sourceOffset = 0;
        int outputOffset = 0;
        for (int row = 0; row < ATLAS_HEIGHT; row++) {
            scanlines[outputOffset++] = 0;
            System.arraycopy(rgba, sourceOffset, scanlines, outputOffset, ATLAS_WIDTH * 4);
            sourceOffset += ATLAS_WIDTH * 4;
            outputOffset += ATLAS_WIDTH * 4;
        }
        writeChunk(png, "IDAT", uncompressedZlib(scanlines));
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] uncompressedZlib(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length + 11);
        output.write(0x78);
        output.write(0x01);
        output.write(0x01);
        output.write(input.length & 0xff);
        output.write(input.length >>> 8 & 0xff);
        int complement = (~input.length) & 0xffff;
        output.write(complement & 0xff);
        output.write(complement >>> 8 & 0xff);
        output.writeBytes(input);
        Adler32 adler = new Adler32();
        adler.update(input);
        writeInt(output, (int) adler.getValue());
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(png, data.length);
        png.writeBytes(typeBytes);
        png.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(png, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24 & 0xff);
        output.write(value >>> 16 & 0xff);
        output.write(value >>> 8 & 0xff);
        output.write(value & 0xff);
    }

    private record IconRequest(ResourceLocation itemId, String displayName) {}

    public record GeneratedIcons(byte[] png, byte[] json) {
        public GeneratedIcons {
            png = png.clone();
            json = json.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }

        @Override
        public byte[] json() {
            return json.clone();
        }
    }
}
