package com.gaia.tools.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import static com.gaia.tools.model.PreflightException.Code.*;

/** Restrictive offline HAND_TOOL_V0 input boundary, not a semantic validator. */
public final class GlbPreflight {
    private static final int MAX_FILE_BYTES = 16_777_216;
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int JSON = 0x4E4F534A;
    private static final int BIN = 0x004E4942;

    private GlbPreflight() { }

    /** Takes one bounded snapshot. Does not close the caller-owned stream. */
    public static CheckedGlb read(InputStream input) throws IOException {
        byte[] bytes = Objects.requireNonNull(input, "input").readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) { throw new PreflightException(FILE_SIZE); }
        if (bytes.length < 24) { throw new PreflightException(CONTAINER); }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x46546C67 || data.getInt() != 2
                || unsigned(data.getInt()) != bytes.length) {
            throw new PreflightException(CONTAINER);
        }
        int jsonLength = 0;
        int binLength = 0;
        int chunks = 0;
        while (data.hasRemaining()) {
            if (data.remaining() < 8) { throw new PreflightException(CONTAINER); }
            long length = unsigned(data.getInt());
            int type = data.getInt();
            if ((length & 3) != 0 || length > data.remaining()) {
                throw new PreflightException(CONTAINER);
            }
            if (chunks == 0 && type == JSON && length > 0) {
                if (length > MAX_JSON_BYTES) { throw new PreflightException(JSON_SIZE); }
                jsonLength = (int) length;
            } else if (chunks == 1 && type == BIN) {
                binLength = (int) length;
            } else {
                throw new PreflightException(CONTAINER);
            }
            data.position(data.position() + (int) length);
            chunks++;
        }
        GlbJsonPreflight.check(bytes, 20, jsonLength);
        return new CheckedGlb(bytes, jsonLength, binLength);
    }

    private static long unsigned(int value) { return Integer.toUnsignedLong(value); }

    /** Only the preflight can construct this immutable snapshot. */
    public static final class CheckedGlb {
        private final byte[] bytes;
        private final int jsonBytes;
        private final int binaryBytes;
        private final String sha256;

        private CheckedGlb(byte[] ownedBytes, int jsonBytes, int binaryBytes) {
            this.bytes = ownedBytes;
            this.jsonBytes = jsonBytes;
            this.binaryBytes = binaryBytes;
            try {
                sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(ownedBytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("Required SHA-256 is unavailable", impossible);
            }
        }

        public int byteLength() { return bytes.length; }
        public int jsonByteLength() { return jsonBytes; }
        public int binaryByteLength() { return binaryBytes; }
        public String sha256() { return sha256; }
        InputStream openStream() { return new ByteArrayInputStream(bytes); }
        InputStream openJsonStream() { return new ByteArrayInputStream(bytes, 20, jsonBytes); }
        ByteBuffer binaryData() {
            return binaryBytes == 0 ? null : ByteBuffer.wrap(bytes, 28 + jsonBytes, binaryBytes)
                    .slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
