package com.overlord.audio.vorbis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class StbVorbisDecoderTest {
    @Test
    void compressedCopyCompactsRemainingBytesWithoutTakingCallerOwnership() {
        ByteBuffer caller = ByteBuffer.allocateDirect(10);
        for (int index = 0; index < caller.capacity(); index++) {
            caller.put(index, (byte) (index + 20));
        }
        caller.position(2);
        caller.limit(7);

        ByteBuffer owned = StbVorbisDecoder.copyCompressed(caller);
        try {
            assertNotSame(caller, owned);
            assertTrue(owned.isDirect());
            assertEquals(0, owned.position());
            assertEquals(5, owned.remaining());
            for (int index = 0; index < owned.remaining(); index++) {
                assertEquals((byte) (index + 22), owned.get(index));
            }
            owned.put(0, (byte) 99);
            assertEquals((byte) 22, caller.get(2));
            assertEquals(2, caller.position());
            assertEquals(7, caller.limit());
        } finally {
            MemoryUtil.memFree(owned);
        }
    }

    @Test
    void corruptCompressedBytesFailWithTypedBoundedDiagnostic() {
        ByteBuffer corrupt = MemoryUtil.memAlloc(32);
        corrupt.putInt(0x4f676753).putInt(0x01020304).flip();

        VorbisDecoder.DecodeException failure;
        try {
            failure =
                    assertThrows(
                            VorbisDecoder.DecodeException.class,
                            () -> StbVorbisDecoder.open(corrupt));
        } finally {
            MemoryUtil.memFree(corrupt);
        }

        assertTrue(failure.getMessage().startsWith("VORBIS_OPEN_FAILED"));
        assertTrue(failure.getMessage().length() <= 256);
    }

    @Test
    void nonDirectCompressedBytesAreRejectedBeforeNativeOpen() {
        ByteBuffer heapBytes = ByteBuffer.wrap(new byte[] {0x4f, 0x67, 0x67, 0x53});

        assertThrows(IllegalArgumentException.class, () -> StbVorbisDecoder.open(heapBytes));
    }
}
