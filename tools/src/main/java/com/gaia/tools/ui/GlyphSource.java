package com.gaia.tools.ui;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Independently authored Quiet Rune 5x7 glyph source for the GaiaLegacy HUD. */
public final class GlyphSource {
    public static final String SOURCE_NAME = "Quiet Rune 5x7";
    public static final String ALGORITHM_ID = "quiet-rune-5x7";
    public static final int ALGORITHM_VERSION = 1;
    public static final int INFINITY_CODE_POINT = 0x221e;
    public static final int MISSING_CODE_POINT = 0xfffd;

    /*
     * One newly authored five-column, seven-row pattern per printable ASCII
     * code point, U+0020 through U+007E. A slash separates rows. The rasterizer
     * centres these patterns in columns 1..5 of the existing 8x8 cell and
     * leaves row 7 transparent. This grammar is the authoritative font source;
     * no operating-system or third-party bitmap table is transformed here.
     */
    private static final String[] ASCII_PATTERNS = {
        "...../...../...../...../...../...../.....", // space
        "..#../..#../..#../..#../..#../...../..#..", // !
        ".#.#./.#.#./.#.#./...../...../...../.....", // "
        ".#.#./#####/.#.#./.#.#./#####/.#.#./.#.#.", // #
        "..#../.####/#.#../.###./..#.#/####./..#..", // $
        "##..#/##.#./...#./..#../.#.../.#.##/#..##", // %
        ".##../#..#./.#.../.##.#/#..#./#..#./.##.#", // &
        "..#../..#../.#.../...../...../...../.....", // '
        "...#./..#../.#.../.#.../.#.../..#../...#.", // (
        ".#.../..#../...#./...#./...#./..#../.#...", // )
        "...../#.#.#/.###./#####/.###./#.#.#/.....", // *
        "...../..#../..#../#####/..#../..#../.....", // +
        "...../...../...../...../..#../..#../.#...", // ,
        "...../...../...../.###./...../...../.....", // -
        "...../...../...../...../...../...../..#..", // .
        "....#/...#./..#../.#.../#..../...../.....", // /
        ".###./#...#/#..##/#.#.#/##..#/#...#/.###.", // 0
        "..#../.##../#.#../..#../..#../..#../.###.", // 1
        ".###./#...#/....#/..##./.#.../#..../#####", // 2
        "####./....#/...#./..##./....#/#...#/.###.", // 3
        "...#./..##./.#.#./#..#./#####/...#./...#.", // 4
        "#####/#..../####./....#/....#/#...#/.###.", // 5
        ".###./#..../#..../####./#...#/#...#/.###.", // 6
        "#####/....#/...#./..#../.#.../.#.../.#...", // 7
        ".###./#...#/#...#/.###./#...#/#...#/.###.", // 8
        ".###./#...#/#...#/.####/....#/....#/.###.", // 9
        "...../..#../...../...../..#../...../.....", // :
        "...../..#../...../...../..#../..#../.#...", // ;
        "...#./..#../.#.../#..../.#.../..#../...#.", // <
        "...../...../#####/...../#####/...../.....", // =
        ".#.../..#../...#./....#/...#./..#../.#...", // >
        ".###./#...#/....#/...#./..#../...../..#..", // ?
        ".###./#...#/#.###/#.#.#/#.###/#..../.####", // @
        ".###./#...#/#...#/#####/#...#/#...#/#...#", // A
        "####./#...#/#...#/####./#...#/#...#/####.", // B
        ".####/#..../#..../#..../#..../#..../.####", // C
        "####./#...#/#...#/#...#/#...#/#...#/####.", // D
        "#####/#..../#..../####./#..../#..../#####", // E
        "#####/#..../#..../####./#..../#..../#....", // F
        ".####/#..../#..../#..##/#...#/#...#/.###.", // G
        "#...#/#...#/#...#/#####/#...#/#...#/#...#", // H
        ".###./..#../..#../..#../..#../..#../.###.", // I
        "..###/...#./...#./...#./...#./#..#./.##..", // J
        "#...#/#..#./#.#../##.../#.#../#..#./#...#", // K
        "#..../#..../#..../#..../#..../#..../#####", // L
        "#...#/##.##/#.#.#/#.#.#/#...#/#...#/#...#", // M
        "#...#/##..#/#.#.#/#..##/#...#/#...#/#...#", // N
        ".###./#...#/#...#/#...#/#...#/#...#/.###.", // O
        "####./#...#/#...#/####./#..../#..../#....", // P
        ".###./#...#/#...#/#...#/#.#.#/#..##/.####", // Q
        "####./#...#/#...#/####./#.#../#..#./#...#", // R
        ".####/#..../#..../.###./....#/....#/####.", // S
        "#####/..#../..#../..#../..#../..#../..#..", // T
        "#...#/#...#/#...#/#...#/#...#/#...#/.###.", // U
        "#...#/#...#/#...#/#...#/#...#/.#.#./..#..", // V
        "#...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#", // W
        "#...#/#...#/.#.#./..#../.#.#./#...#/#...#", // X
        "#...#/#...#/.#.#./..#../..#../..#../..#..", // Y
        "#####/....#/...#./..#../.#.../#..../#####", // Z
        ".###./.#.../.#.../.#.../.#.../.#.../.###.", // [
        "#..../.#.../..#../...#./....#/...../.....", // backslash
        ".###./...#./...#./...#./...#./...#./.###.", // ]
        "..#../.#.#./#...#/...../...../...../.....", // ^
        "...../...../...../...../...../...../#####", // _
        ".#.../..#../...../...../...../...../.....", // `
        "...../...../.###./....#/.####/#...#/.####", // a
        "#..../#..../####./#...#/#...#/#...#/####.", // b
        "...../...../.####/#..../#..../#..../.####", // c
        "....#/....#/.####/#...#/#...#/#...#/.####", // d
        "...../...../.###./#...#/#####/#..../.####", // e
        "..##./.#..#/.#.../###../.#.../.#.../.#...", // f
        "...../.####/#...#/#...#/.####/....#/.###.", // g
        "#..../#..../####./#...#/#...#/#...#/#...#", // h
        "..#../...../.##../..#../..#../..#../.###.", // i
        "...#./...../..##./...#./...#./#..#./.##..", // j
        "#..../#..../#..#./#.#../##.../#.#../#..#.", // k
        ".##../..#../..#../..#../..#../..#../.###.", // l
        "...../...../##.#./#.#.#/#.#.#/#...#/#...#", // m
        "...../...../####./#...#/#...#/#...#/#...#", // n
        "...../...../.###./#...#/#...#/#...#/.###.", // o
        "...../####./#...#/#...#/####./#..../#....", // p
        "...../.####/#...#/#...#/.####/....#/....#", // q
        "...../...../#.##./##..#/#..../#..../#....", // r
        "...../...../.####/#..../.###./....#/####.", // s
        ".#.../.#.../###../.#.../.#.../.#..#/..##.", // t
        "...../...../#...#/#...#/#...#/#..##/.##.#", // u
        "...../...../#...#/#...#/#...#/.#.#./..#..", // v
        "...../...../#...#/#...#/#.#.#/#.#.#/.#.#.", // w
        "...../...../#...#/.#.#./..#../.#.#./#...#", // x
        "...../#...#/#...#/#...#/.####/....#/.###.", // y
        "...../...../#####/...#./..#../.#.../#####", // z
        "...##/..#../..#../.#.../..#../..#../...##", // {
        "..#../..#../..#../..#../..#../..#../..#..", // |
        "##.../..#../..#../...#./..#../..#../##...", // }
        "...../...../.##.#/#.##./...../...../....."  // ~
    };

    private static final String INFINITY_PATTERN =
            "...../.##../#..#./.#.#./#..#./.##../.....";
    private static final String MISSING_PATTERN =
            "#####/#...#/#.#.#/#...#/##.##/#...#/#####";
    private static final String PRINTABLE_ASCII_FINGERPRINT = computePrintableAsciiFingerprint();

    private GlyphSource() {}

    public static List<Glyph> projectGlyphs() {
        List<Glyph> glyphs = new ArrayList<>(97);
        for (int index = 0; index < ASCII_PATTERNS.length; index++) {
            glyphs.add(glyph(32 + index, index, ASCII_PATTERNS[index]));
        }
        glyphs.add(glyph(INFINITY_CODE_POINT, 95, INFINITY_PATTERN));
        glyphs.add(glyph(MISSING_CODE_POINT, 96, MISSING_PATTERN));
        return List.copyOf(glyphs);
    }

    public static String printableAsciiFingerprint() {
        return PRINTABLE_ASCII_FINGERPRINT;
    }

    private static Glyph glyph(int codePoint, int cellIndex, String pattern) {
        String[] sourceRows = pattern.split("/", -1);
        if (sourceRows.length != 7) {
            throw new IllegalArgumentException("a Quiet Rune glyph must contain seven rows");
        }
        byte[] rows = new byte[8];
        for (int row = 0; row < sourceRows.length; row++) {
            if (sourceRows[row].length() != 5) {
                throw new IllegalArgumentException(
                        "a Quiet Rune glyph row must contain five columns");
            }
            for (int column = 0; column < sourceRows[row].length(); column++) {
                char pixel = sourceRows[row].charAt(column);
                if (pixel == '#') {
                    rows[row] |= (byte) (0x40 >>> column);
                } else if (pixel != '.') {
                    throw new IllegalArgumentException(
                            "a Quiet Rune pixel must be '#' or '.'");
                }
            }
        }
        return new Glyph(codePoint, cellIndex, 8, 0, 8, rows);
    }

    private static String computePrintableAsciiFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < ASCII_PATTERNS.length; index++) {
                digest.update(glyph(32 + index, index, ASCII_PATTERNS[index]).rows());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Glyph(
            int codePoint,
            int cellIndex,
            int advance,
            int bearingX,
            int bearingY,
            byte[] rows) {
        public Glyph {
            rows = rows.clone();
        }

        @Override
        public byte[] rows() {
            return rows.clone();
        }
    }
}
