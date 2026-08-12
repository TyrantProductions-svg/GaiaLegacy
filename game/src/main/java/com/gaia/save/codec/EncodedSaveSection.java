package com.gaia.save.codec;

import com.gaia.save.format.SaveSectionDescriptor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable encoded bytes and their verified manifest descriptor. */
public record EncodedSaveSection(
        SaveSectionDescriptor descriptor,
        byte[] bytes) {
    public EncodedSaveSection {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (descriptor.uncompressedSize() != bytes.length) {
            throw new IllegalArgumentException(
                    "Encoded section size does not match its descriptor");
        }
        if (!descriptor.sha256().equals(sha256(bytes))) {
            throw new IllegalArgumentException(
                    "Encoded section SHA-256 does not match its descriptor");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
