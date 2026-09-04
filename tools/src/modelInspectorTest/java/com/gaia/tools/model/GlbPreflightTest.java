package com.gaia.tools.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static com.gaia.tools.model.GlbFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class GlbPreflightTest {
    @Test
    void acceptsJsonThenOptionalBinAndReportsExactSnapshotBytes() throws Exception {
        byte[] data = container(chunk(JSON, MINIMAL.getBytes(StandardCharsets.UTF_8)),
                chunk(BIN, new byte[]{1, 2, 3, 4}));
        var checked = GlbPreflight.read(new ByteArrayInputStream(data));
        assertEquals(data.length, checked.byteLength());
        assertEquals(28, checked.jsonByteLength());
        assertEquals(4, checked.binaryByteLength());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(data)), checked.sha256());
        assertArrayEquals(data, checked.openStream().readAllBytes());
    }

    @Test
    void checkedBytesCannotBeChangedByCallerOrByReadingAnotherView() throws Exception {
        byte[] data = glb(MINIMAL);
        byte[] expected = data.clone();
        var checked = GlbPreflight.read(new ByteArrayInputStream(data));
        Arrays.fill(data, (byte) 0);
        byte[] view = checked.openStream().readAllBytes();
        Arrays.fill(view, (byte) 0);
        assertArrayEquals(expected, checked.openStream().readAllBytes());
    }

    @Test
    void rejectsWrongMagicVersionAndDeclaredLengthWithoutRepair() {
        byte[] good = glb(MINIMAL);
        reject(withInt(good, 0, 0), PreflightException.Code.CONTAINER);
        reject(withInt(good, 4, 1), PreflightException.Code.CONTAINER);
        reject(withInt(good, 4, 3), PreflightException.Code.CONTAINER);
        reject(withInt(good, 8, good.length - 4), PreflightException.Code.CONTAINER);
        reject(withInt(good, 8, -1), PreflightException.Code.CONTAINER);
    }

    @Test
    void rejectsTruncationUnalignedAndOverflowingChunkLengths() {
        byte[] good = glb(MINIMAL);
        for (int length : new int[]{0, 4, 11, 12, 19, good.length - 1}) {
            reject(Arrays.copyOf(good, length), PreflightException.Code.CONTAINER);
        }
        reject(withInt(good, 12, 27), PreflightException.Code.CONTAINER);
        reject(withInt(good, 12, -4), PreflightException.Code.CONTAINER);
        reject(withInt(good, 12, 0), PreflightException.Code.CONTAINER);
    }

    @Test
    void rejectsUnknownDuplicateMisorderedOrTrailingChunks() {
        byte[] json = chunk(JSON, MINIMAL.getBytes(StandardCharsets.UTF_8));
        byte[] bin = chunk(BIN, new byte[4]);
        reject(container(bin, json), PreflightException.Code.CONTAINER);
        reject(container(json, json), PreflightException.Code.CONTAINER);
        reject(container(json, bin, bin), PreflightException.Code.CONTAINER);
        reject(container(json, chunk(123, new byte[4])), PreflightException.Code.CONTAINER);
        reject(container(json, new byte[]{0}), PreflightException.Code.CONTAINER);
    }

    @Test
    void jsonSizeIsBoundedBeforeParsing() {
        reject(container(chunk(JSON, new byte[1_048_580])), PreflightException.Code.JSON_SIZE);
    }

    @Test
    void readsAtMostFileLimitPlusOneAndLeavesCallerStreamOpen() {
        class EndlessInput extends InputStream {
            int read;
            boolean closed;
            @Override public int read() { read++; return 0; }
            @Override public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 0);
                read += len;
                return len;
            }
            @Override public void close() { closed = true; }
        }
        EndlessInput input = new EndlessInput();
        var failure = assertThrows(PreflightException.class, () -> GlbPreflight.read(input));
        assertEquals(PreflightException.Code.FILE_SIZE, failure.code());
        assertEquals(16_777_217, input.read);
        assertFalse(input.closed);
    }

    static void reject(byte[] bytes, PreflightException.Code code) {
        var failure = assertThrows(PreflightException.class,
                () -> GlbPreflight.read(new ByteArrayInputStream(bytes)));
        assertEquals(code, failure.code());
    }
}
