package com.gaia.save.format;

/** Version of the manifest and core save-format contract. */
public record SaveFormatVersion(int value) {
    public static final SaveFormatVersion CURRENT = new SaveFormatVersion(1);

    public SaveFormatVersion {
        if (value <= 0) {
            throw new IllegalArgumentException("Save format version must be positive");
        }
    }
}
