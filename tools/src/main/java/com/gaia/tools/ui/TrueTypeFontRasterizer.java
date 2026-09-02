package com.gaia.tools.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Deterministic build-time TrueType rasterization backed by LWJGL STB. */
public final class TrueTypeFontRasterizer {
    public RasterizedFace rasterize(
            ClassLoader loader,
            FontSourceManifest.Entry source,
            int pixelHeight,
            int oversample,
            List<Integer> codePoints) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(source, "source");
        validateRequest(pixelHeight, oversample, codePoints);

        ByteBuffer fontBytes = loadDirect(loader, source.sourcePath());
        try (STBTTFontinfo font = STBTTFontinfo.malloc()) {
            if (!STBTruetype.stbtt_InitFont(font, fontBytes)) {
                throw new IllegalArgumentException("invalid TrueType source " + source.id());
            }
            float targetScale = STBTruetype.stbtt_ScaleForPixelHeight(font, pixelHeight);
            Metrics metrics = metrics(font, targetScale);
            List<RasterizedGlyph> glyphs = codePoints.stream()
                    .map(codePoint -> rasterizeGlyph(font, targetScale, oversample, codePoint))
                    .toList();
            return new RasterizedFace(
                    source.id(),
                    pixelHeight,
                    oversample,
                    metrics.ascent(),
                    metrics.descent(),
                    metrics.lineGap(),
                    metrics.ascent(),
                    metrics.lineHeight(),
                    glyphs);
        } finally {
            MemoryUtil.memFree(fontBytes);
        }
    }

    private static void validateRequest(
            int pixelHeight, int oversample, List<Integer> codePoints) {
        if (pixelHeight <= 0 || pixelHeight > 512) {
            throw new IllegalArgumentException("pixelHeight must be in [1,512]");
        }
        if (oversample <= 0 || oversample > 8) {
            throw new IllegalArgumentException("oversample must be in [1,8]");
        }
        Objects.requireNonNull(codePoints, "codePoints");
        Set<Integer> seen = new HashSet<>();
        for (Integer codePoint : codePoints) {
            if (codePoint == null || !Character.isValidCodePoint(codePoint)
                    || !seen.add(codePoint)) {
                throw new IllegalArgumentException(
                        "codePoints must be unique valid Unicode scalar values");
            }
        }
        if (codePoints.isEmpty()) {
            throw new IllegalArgumentException("codePoints must not be empty");
        }
    }

    private static Metrics metrics(STBTTFontinfo font, float scale) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascentBuffer = stack.mallocInt(1);
            IntBuffer descentBuffer = stack.mallocInt(1);
            IntBuffer lineGapBuffer = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(
                    font, ascentBuffer, descentBuffer, lineGapBuffer);
            int ascent = (int) Math.ceil(ascentBuffer.get(0) * scale);
            int descent = (int) Math.floor(descentBuffer.get(0) * scale);
            int lineGap = Math.max(0, (int) Math.ceil(lineGapBuffer.get(0) * scale));
            int lineHeight = Math.max(
                    ascent - descent,
                    (int) Math.ceil((ascentBuffer.get(0)
                            - descentBuffer.get(0) + lineGapBuffer.get(0)) * scale));
            return new Metrics(ascent, descent, lineGap, lineHeight);
        }
    }

    private static RasterizedGlyph rasterizeGlyph(
            STBTTFontinfo font, float targetScale, int oversample, int codePoint) {
        int rasterCodePoint = codePoint;
        if (STBTruetype.stbtt_FindGlyphIndex(font, rasterCodePoint) == 0) {
            rasterCodePoint = '?';
        }
        if (STBTruetype.stbtt_FindGlyphIndex(font, rasterCodePoint) == 0) {
            throw new IllegalArgumentException("font has no visible fallback glyph");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advanceBuffer = stack.mallocInt(1);
            IntBuffer leftBearingBuffer = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointHMetrics(
                    font, rasterCodePoint, advanceBuffer, leftBearingBuffer);
            int advance = Math.round(advanceBuffer.get(0) * targetScale);

            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer xOffsetBuffer = stack.mallocInt(1);
            IntBuffer yOffsetBuffer = stack.mallocInt(1);
            ByteBuffer bitmap = STBTruetype.stbtt_GetCodepointBitmap(
                    font,
                    0.0f,
                    targetScale * oversample,
                    rasterCodePoint,
                    widthBuffer,
                    heightBuffer,
                    xOffsetBuffer,
                    yOffsetBuffer);
            int sourceWidth = widthBuffer.get(0);
            int sourceHeight = heightBuffer.get(0);
            int width = ceilDiv(sourceWidth, oversample);
            int height = ceilDiv(sourceHeight, oversample);
            byte[] alpha;
            if (bitmap == null || sourceWidth == 0 || sourceHeight == 0) {
                alpha = new byte[0];
                width = 0;
                height = 0;
            } else {
                try {
                    alpha = downsample(bitmap, sourceWidth, sourceHeight, oversample);
                } finally {
                    STBTruetype.stbtt_FreeBitmap(bitmap);
                }
            }
            int bearingX = Math.floorDiv(xOffsetBuffer.get(0), oversample);
            int bearingY = -Math.floorDiv(yOffsetBuffer.get(0), oversample);
            return new RasterizedGlyph(
                    codePoint, width, height, advance, bearingX, bearingY, alpha);
        }
    }

    private static byte[] downsample(
            ByteBuffer source, int sourceWidth, int sourceHeight, int oversample) {
        int width = ceilDiv(sourceWidth, oversample);
        int height = ceilDiv(sourceHeight, oversample);
        byte[] result = new byte[Math.multiplyExact(width, height)];
        int divisor = Math.multiplyExact(oversample, oversample);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sum = 0;
                for (int sampleY = 0; sampleY < oversample; sampleY++) {
                    int sourceY = y * oversample + sampleY;
                    if (sourceY >= sourceHeight) {
                        continue;
                    }
                    for (int sampleX = 0; sampleX < oversample; sampleX++) {
                        int sourceX = x * oversample + sampleX;
                        if (sourceX < sourceWidth) {
                            sum += source.get(sourceY * sourceWidth + sourceX) & 0xff;
                        }
                    }
                }
                result[y * width + x] = (byte) ((sum + divisor / 2) / divisor);
            }
        }
        return result;
    }

    private static int ceilDiv(int value, int divisor) {
        return value == 0 ? 0 : Math.floorDiv(value - 1, divisor) + 1;
    }

    private static ByteBuffer loadDirect(ClassLoader loader, String path) {
        byte[] bytes;
        try (InputStream input = loader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing TrueType resource " + path);
            }
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to read TrueType resource " + path, failure);
        }
        ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
        direct.put(bytes).flip();
        return direct;
    }

    private record Metrics(int ascent, int descent, int lineGap, int lineHeight) {}

    public record RasterizedFace(
            String sourceId,
            int pixelHeight,
            int oversample,
            int ascent,
            int descent,
            int lineGap,
            int baseline,
            int lineHeight,
            List<RasterizedGlyph> glyphs) {
        public RasterizedFace {
            Objects.requireNonNull(sourceId, "sourceId");
            glyphs = List.copyOf(glyphs);
        }

        public RasterizedGlyph glyph(int codePoint) {
            return glyphs.stream()
                    .filter(glyph -> glyph.codePoint() == codePoint)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "face " + sourceId + " does not contain U+"
                                    + Integer.toHexString(codePoint).toUpperCase()));
        }
    }

    public record RasterizedGlyph(
            int codePoint,
            int width,
            int height,
            int advance,
            int bearingX,
            int bearingY,
            byte[] alpha) {
        public RasterizedGlyph {
            alpha = alpha.clone();
            if (alpha.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("glyph alpha size does not match dimensions");
            }
        }

        @Override
        public byte[] alpha() {
            return alpha.clone();
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            return object instanceof RasterizedGlyph other
                    && codePoint == other.codePoint
                    && width == other.width
                    && height == other.height
                    && advance == other.advance
                    && bearingX == other.bearingX
                    && bearingY == other.bearingY
                    && Arrays.equals(alpha, other.alpha);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    codePoint, width, height, advance, bearingX, bearingY);
            return 31 * result + Arrays.hashCode(alpha);
        }
    }
}
