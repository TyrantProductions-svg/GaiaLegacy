package com.gaia.tools.ui;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable build-time packing for one logical typography system and 1-N pages. */
public final class FontAtlasPacker {
    private static final int MAX_DIMENSION = 4_096;

    public GeneratedTypographyAtlas pack(
            List<TrueTypeFontRasterizer.RasterizedFace> inputFaces,
            List<PageDefinition> inputDefinitions,
            int padding) {
        Objects.requireNonNull(inputFaces, "inputFaces");
        Objects.requireNonNull(inputDefinitions, "inputDefinitions");
        if (padding < 0 || padding > 16) {
            throw new IllegalArgumentException("padding must be in [0,16]");
        }

        Map<String, TrueTypeFontRasterizer.RasterizedFace> faces = faceMap(inputFaces);
        List<PageDefinition> definitions = inputDefinitions.stream()
                .sorted(Comparator.comparing(PageDefinition::id))
                .toList();
        Map<String, String> facePages = validateDefinitions(faces.keySet(), definitions);

        List<AtlasPage> pages = new ArrayList<>();
        List<GlyphPlacement> placements = new ArrayList<>();
        for (PageDefinition definition : definitions) {
            List<GlyphRef> glyphs = new ArrayList<>();
            for (String faceId : definition.faceSourceIds()) {
                TrueTypeFontRasterizer.RasterizedFace face = faces.get(faceId);
                for (TrueTypeFontRasterizer.RasterizedGlyph glyph : face.glyphs()) {
                    glyphs.add(new GlyphRef(faceId, glyph));
                }
            }
            PackedPage packed = packPage(glyphs, padding);
            byte[] rgba = transparentWhite(packed.width(), packed.height());
            for (PlacedGlyph placed : packed.placed()) {
                copyInk(rgba, packed.width(), placed, padding);
                TrueTypeFontRasterizer.RasterizedGlyph glyph = placed.ref().glyph();
                placements.add(new GlyphPlacement(
                        placed.ref().faceId(),
                        glyph.codePoint(),
                        definition.id(),
                        placed.x() + padding,
                        placed.y() + padding,
                        glyph.width(),
                        glyph.height(),
                        placed.x(),
                        placed.y(),
                        placed.width(),
                        placed.height(),
                        glyph.advance(),
                        glyph.bearingX(),
                        glyph.bearingY()));
            }
            for (GlyphRef glyph : glyphs) {
                if (glyph.glyph().width() == 0) {
                    placements.add(new GlyphPlacement(
                            glyph.faceId(), glyph.glyph().codePoint(), definition.id(),
                            0, 0, 0, 0, 0, 0, 0, 0,
                            glyph.glyph().advance(), glyph.glyph().bearingX(),
                            glyph.glyph().bearingY()));
                }
            }
            pages.add(new AtlasPage(
                    definition.id(), definition.samplingMode(), packed.width(),
                    packed.height(), rgba, sha256(rgba)));
        }
        placements.sort(Comparator.comparing(GlyphPlacement::faceSourceId)
                .thenComparingInt(GlyphPlacement::codePoint));
        return new GeneratedTypographyAtlas(pages, placements, padding, facePages);
    }

    private static Map<String, TrueTypeFontRasterizer.RasterizedFace> faceMap(
            List<TrueTypeFontRasterizer.RasterizedFace> inputFaces) {
        Map<String, TrueTypeFontRasterizer.RasterizedFace> faces = new HashMap<>();
        for (TrueTypeFontRasterizer.RasterizedFace face : inputFaces) {
            Objects.requireNonNull(face, "face");
            if (faces.put(face.sourceId(), face) != null) {
                throw new IllegalArgumentException("duplicate rasterized face " + face.sourceId());
            }
        }
        if (faces.isEmpty()) {
            throw new IllegalArgumentException("at least one rasterized face is required");
        }
        return faces;
    }

    private static Map<String, String> validateDefinitions(
            Set<String> expectedFaces, List<PageDefinition> definitions) {
        Set<String> pageIds = new HashSet<>();
        Map<String, String> ownership = new LinkedHashMap<>();
        for (PageDefinition definition : definitions) {
            if (!pageIds.add(definition.id())) {
                throw new IllegalArgumentException("duplicate atlas page " + definition.id());
            }
            for (String faceId : definition.faceSourceIds()) {
                if (!expectedFaces.contains(faceId)) {
                    throw new IllegalArgumentException("unknown rasterized face " + faceId);
                }
                if (ownership.put(faceId, definition.id()) != null) {
                    throw new IllegalArgumentException("face assigned to multiple atlas pages " + faceId);
                }
            }
        }
        if (!ownership.keySet().equals(expectedFaces)) {
            throw new IllegalArgumentException("every rasterized face must own exactly one page");
        }
        return ownership.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }

    private static PackedPage packPage(List<GlyphRef> glyphs, int padding) {
        List<GlyphRef> ink = glyphs.stream()
                .filter(glyph -> glyph.glyph().width() > 0)
                .sorted(Comparator
                        .<GlyphRef>comparingInt(glyph -> glyph.glyph().height())
                        .reversed()
                        .thenComparing(
                                Comparator.<GlyphRef>comparingInt(
                                        glyph -> glyph.glyph().width()).reversed())
                        .thenComparing(GlyphRef::faceId)
                        .thenComparingInt(glyph -> glyph.glyph().codePoint()))
                .toList();
        int maxWidth = ink.stream()
                .mapToInt(glyph -> glyph.glyph().width() + 2 * padding)
                .max().orElse(1);
        int firstWidth = nextPowerOfTwo(maxWidth);
        PackedPage best = null;
        for (int width = firstWidth; width <= MAX_DIMENSION; width *= 2) {
            PackedPage candidate = packAtWidth(ink, padding, width);
            if (candidate.height() > MAX_DIMENSION) {
                continue;
            }
            if (best == null || compareCandidate(candidate, best) < 0) {
                best = candidate;
            }
            if (width == MAX_DIMENSION) {
                break;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("typography atlas exceeds 4096 pixels");
        }
        return best;
    }

    private static PackedPage packAtWidth(List<GlyphRef> ink, int padding, int width) {
        List<PlacedGlyph> placed = new ArrayList<>();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (GlyphRef glyph : ink) {
            int glyphWidth = Math.addExact(glyph.glyph().width(), 2 * padding);
            int glyphHeight = Math.addExact(glyph.glyph().height(), 2 * padding);
            if (x > 0 && x + glyphWidth > width) {
                y = Math.addExact(y, rowHeight);
                x = 0;
                rowHeight = 0;
            }
            placed.add(new PlacedGlyph(glyph, x, y, glyphWidth, glyphHeight));
            x = Math.addExact(x, glyphWidth);
            rowHeight = Math.max(rowHeight, glyphHeight);
        }
        int usedHeight = Math.addExact(y, rowHeight);
        return new PackedPage(width, nextPowerOfTwo(Math.max(1, usedHeight)), placed);
    }

    private static int compareCandidate(PackedPage left, PackedPage right) {
        long leftArea = (long) left.width() * left.height();
        long rightArea = (long) right.width() * right.height();
        int area = Long.compare(leftArea, rightArea);
        if (area != 0) {
            return area;
        }
        int extent = Integer.compare(
                Math.max(left.width(), left.height()),
                Math.max(right.width(), right.height()));
        return extent != 0 ? extent : Integer.compare(left.width(), right.width());
    }

    private static byte[] transparentWhite(int width, int height) {
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        for (int pixel = 0; pixel < width * height; pixel++) {
            rgba[pixel * 4] = (byte) 0xff;
            rgba[pixel * 4 + 1] = (byte) 0xff;
            rgba[pixel * 4 + 2] = (byte) 0xff;
        }
        return rgba;
    }

    private static void copyInk(byte[] rgba, int atlasWidth, PlacedGlyph placed, int padding) {
        byte[] alpha = placed.ref().glyph().alpha();
        int glyphWidth = placed.ref().glyph().width();
        int glyphHeight = placed.ref().glyph().height();
        for (int y = 0; y < glyphHeight; y++) {
            for (int x = 0; x < glyphWidth; x++) {
                int destination = ((placed.y() + padding + y) * atlasWidth
                        + placed.x() + padding + x) * 4;
                rgba[destination + 3] = alpha[y * glyphWidth + x];
            }
        }
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 0 || value > MAX_DIMENSION) {
            throw new IllegalArgumentException("atlas dimension is out of bounds");
        }
        int highest = Integer.highestOneBit(value);
        return value == highest ? value : Math.multiplyExact(highest, 2);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum SamplingMode {
        NEAREST,
        LINEAR
    }

    public record PageDefinition(
            String id, List<String> faceSourceIds, SamplingMode samplingMode) {
        public PageDefinition {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("page id must not be blank");
            }
            Objects.requireNonNull(faceSourceIds, "faceSourceIds");
            faceSourceIds = faceSourceIds.stream().sorted().toList();
            if (faceSourceIds.isEmpty()
                    || new HashSet<>(faceSourceIds).size() != faceSourceIds.size()) {
                throw new IllegalArgumentException("page face ids must be nonempty and unique");
            }
            Objects.requireNonNull(samplingMode, "samplingMode");
        }
    }

    public record GeneratedTypographyAtlas(
            List<AtlasPage> pages,
            List<GlyphPlacement> placements,
            int padding,
            Map<String, String> facePages) {
        public GeneratedTypographyAtlas {
            pages = List.copyOf(pages);
            placements = List.copyOf(placements);
            facePages = Map.copyOf(facePages);
        }

        public AtlasPage page(String id) {
            return pages.stream().filter(page -> page.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown atlas page " + id));
        }

        public GlyphPlacement placement(String faceSourceId, int codePoint) {
            return placements.stream()
                    .filter(placement -> placement.faceSourceId().equals(faceSourceId)
                            && placement.codePoint() == codePoint)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown glyph placement"));
        }
    }

    public record AtlasPage(
            String id,
            SamplingMode samplingMode,
            int width,
            int height,
            byte[] rgba,
            String sha256) {
        public AtlasPage {
            rgba = rgba.clone();
            if (rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
                throw new IllegalArgumentException("page bytes do not match dimensions");
            }
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }

        public int byteCount() {
            return rgba.length;
        }

        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof AtlasPage other
                    && id.equals(other.id)
                    && samplingMode == other.samplingMode
                    && width == other.width
                    && height == other.height
                    && sha256.equals(other.sha256)
                    && Arrays.equals(rgba, other.rgba);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(id, samplingMode, width, height, sha256)
                    + Arrays.hashCode(rgba);
        }
    }

    public record GlyphPlacement(
            String faceSourceId,
            int codePoint,
            String pageId,
            int x,
            int y,
            int width,
            int height,
            int paddedX,
            int paddedY,
            int paddedWidth,
            int paddedHeight,
            int advance,
            int bearingX,
            int bearingY) {}

    private record GlyphRef(
            String faceId, TrueTypeFontRasterizer.RasterizedGlyph glyph) {}

    private record PlacedGlyph(
            GlyphRef ref, int x, int y, int width, int height) {}

    private record PackedPage(int width, int height, List<PlacedGlyph> placed) {}
}
