package com.gaia.tools.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

public final class BitmapFontGenerator {
    private static final int ATLAS_WIDTH = 128;
    private static final int ATLAS_HEIGHT = 64;
    private static final int CELL_WIDTH = 8;
    private static final int CELL_HEIGHT = 8;
    private static final int COLUMNS = ATLAS_WIDTH / CELL_WIDTH;
    private static final int CELL_COUNT = COLUMNS * (ATLAS_HEIGHT / CELL_HEIGHT);
    private static final Set<Integer> REQUIRED_CODE_POINTS = requiredCodePoints();

    public GeneratedFont generate(Collection<GlyphSource.Glyph> source) {
        List<GlyphSource.Glyph> glyphs = validateAndSort(source);
        byte[] rgba = blankAtlas();
        for (GlyphSource.Glyph glyph : glyphs) {
            draw(rgba, glyph);
        }
        return new GeneratedFont(encodePng(rgba), encodeJson(glyphs));
    }

    private static List<GlyphSource.Glyph> validateAndSort(
            Collection<GlyphSource.Glyph> source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        List<GlyphSource.Glyph> glyphs = new ArrayList<>(source);
        glyphs.sort(Comparator.comparingInt(GlyphSource.Glyph::codePoint));
        Set<Integer> codePoints = new HashSet<>();
        Set<Integer> cells = new HashSet<>();
        for (GlyphSource.Glyph glyph : glyphs) {
            if (glyph == null) {
                throw new NullPointerException("glyph");
            }
            if (!Character.isValidCodePoint(glyph.codePoint())) {
                throw new IllegalArgumentException("invalid glyph code point");
            }
            if (!codePoints.add(glyph.codePoint())) {
                throw new IllegalArgumentException("duplicate glyph code point");
            }
            if (glyph.cellIndex() < 0 || glyph.cellIndex() >= CELL_COUNT
                    || !cells.add(glyph.cellIndex())) {
                throw new IllegalArgumentException("invalid or duplicate glyph cell");
            }
            if (glyph.rows().length != CELL_HEIGHT) {
                throw new IllegalArgumentException("glyph must have eight rows");
            }
        }
        if (!codePoints.equals(REQUIRED_CODE_POINTS)) {
            throw new IllegalArgumentException(
                    "font source must contain exactly ASCII 32..126, U+221E, and U+FFFD");
        }
        return List.copyOf(glyphs);
    }

    private static Set<Integer> requiredCodePoints() {
        Set<Integer> required = new HashSet<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            required.add(codePoint);
        }
        required.add(GlyphSource.INFINITY_CODE_POINT);
        required.add(GlyphSource.MISSING_CODE_POINT);
        return Set.copyOf(required);
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

    private static void draw(byte[] rgba, GlyphSource.Glyph glyph) {
        int originX = glyph.cellIndex() % COLUMNS * CELL_WIDTH;
        int originY = glyph.cellIndex() / COLUMNS * CELL_HEIGHT;
        byte[] rows = glyph.rows();
        for (int y = 0; y < CELL_HEIGHT; y++) {
            int rowBits = Byte.toUnsignedInt(rows[y]);
            for (int x = 0; x < CELL_WIDTH; x++) {
                if ((rowBits & (0x80 >>> x)) != 0) {
                    int offset = ((originY + y) * ATLAS_WIDTH + originX + x) * 4;
                    rgba[offset + 3] = (byte) 0xff;
                }
            }
        }
    }

    private static byte[] encodeJson(List<GlyphSource.Glyph> glyphs) {
        StringBuilder json = new StringBuilder(16_384);
        json.append("{\n")
                .append("  \"source\": {\n")
                .append("    \"name\": \"").append(GlyphSource.SOURCE_NAME).append("\",\n")
                .append("    \"algorithm\": \"").append(GlyphSource.ALGORITHM_ID)
                .append("\",\n")
                .append("    \"version\": ").append(GlyphSource.ALGORITHM_VERSION)
                .append(",\n")
                .append("    \"printableAsciiSha256\": \"")
                .append(GlyphSource.printableAsciiFingerprint()).append("\"\n")
                .append("  },\n")
                .append("  \"atlas\": {\n")
                .append("    \"width\": 128,\n")
                .append("    \"height\": 64\n")
                .append("  },\n")
                .append("  \"cell\": {\"width\": 8, \"height\": 8},\n")
                .append("  \"pixelFormat\": \"RGBA8\",\n")
                .append("  \"glyphColor\": {\"red\": 255, \"green\": 255, ")
                .append("\"blue\": 255, \"alpha\": 255},\n")
                .append("  \"tintable\": true,\n")
                .append("  \"glyphs\": [\n");
        GlyphSource.Glyph fallback = null;
        for (int index = 0; index < glyphs.size(); index++) {
            GlyphSource.Glyph glyph = glyphs.get(index);
            int column = glyph.cellIndex() % COLUMNS;
            int row = glyph.cellIndex() / COLUMNS;
            json.append("    {\"codePoint\": ").append(glyph.codePoint())
                    .append(", \"cell\": {\"column\": ").append(column)
                    .append(", \"row\": ").append(row)
                    .append("}, \"advance\": ").append(glyph.advance())
                    .append(", \"bearing\": {\"x\": ").append(glyph.bearingX())
                    .append(", \"y\": ").append(glyph.bearingY()).append("}}")
                    .append(index + 1 == glyphs.size() ? "\n" : ",\n");
            if (glyph.codePoint() == GlyphSource.MISSING_CODE_POINT) {
                fallback = glyph;
            }
        }
        int fallbackX = fallback.cellIndex() % COLUMNS * CELL_WIDTH;
        int fallbackY = fallback.cellIndex() / COLUMNS * CELL_HEIGHT;
        json.append("  ],\n")
                .append("  \"fallback\": {\"codePoint\": 65533, \"region\": {")
                .append("\"x\": ").append(fallbackX)
                .append(", \"y\": ").append(fallbackY)
                .append(", \"width\": 8, \"height\": 8}}\n")
                .append("}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodePng(byte[] rgba) {
        ByteArrayOutputStream png = new ByteArrayOutputStream(ATLAS_WIDTH * ATLAS_HEIGHT * 4);
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

    public record GeneratedFont(byte[] png, byte[] json) {
        public GeneratedFont {
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
