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
}