package com.overlord.voxel;

public enum BlockSize {
    SIZE_2(2, 0.125f),
    SIZE_4(4, 0.25f),
    SIZE_8(8, 0.5f),
    SIZE_16(16, 1.0f);

    private final int pixels;
    private final float units;

    BlockSize(int pixels, float units) {
        this.pixels = pixels;
        this.units = units;
    }

    public int pixels() {
        return pixels;
    }

    public float units() {
        return units;
    }

    public static BlockSize fromSuffix(String suffix) {
        return switch (suffix) {
            case "2" -> SIZE_2;
            case "4" -> SIZE_4;
            case "8" -> SIZE_8;
            case "16" -> SIZE_16;
            default -> throw new IllegalArgumentException("Unknown block size suffix: " + suffix);
        };
    }

    public static BlockSize fromPixels(int pixels) {
        return switch (pixels) {
            case 2 -> SIZE_2;
            case 4 -> SIZE_4;
            case 8 -> SIZE_8;
            case 16 -> SIZE_16;
            default -> throw new IllegalArgumentException("Unknown block size in pixels: " + pixels);
        };
    }
}