package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.audio.AudioAssetSource;
import com.overlord.audio.vorbis.StbVorbisDecoder;
import com.overlord.audio.vorbis.VorbisDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.system.MemoryUtil;

class GaiaMusicAssetDecodeTest {
    private static final int MAXIMUM_TEST_FRAMES = 2_048;

    @Test
    void audioAssetSourceRemainsLambdaCompatibleWithDefaultNoOpRelease() {
        ByteBuffer callerOwned = ByteBuffer.allocateDirect(4);
        AudioAssetSource source = ignored -> callerOwned;

        assertSame(callerOwned, source.read(ResourceLocation.parse("gaia:test.ogg")));
        assertDoesNotThrow(() -> source.release(callerOwned));
    }

    @ParameterizedTest(name = "{0} decodes through the production asset path")
    @MethodSource("musicResources")
    void productionMusicIsStereo44100AndProvidesOneBoundedPcmBlock(
            ResourceLocation location) throws IOException {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        ByteBuffer compressed = readDirect(assets, location);
        VorbisDecoder decoder = null;

        try {
            decoder = StbVorbisDecoder.open(compressed);
            MemoryUtil.memFree(compressed);
            compressed = null;
            assertEquals(2, decoder.channels());
            assertEquals(44_100, decoder.sampleRate());
            ShortBuffer pcm = MemoryUtil.memAllocShort(MAXIMUM_TEST_FRAMES * 2);
            try {
                int frames = decoder.readFrames(pcm, MAXIMUM_TEST_FRAMES);
                assertTrue(frames > 0, "production music must decode non-empty PCM");
                assertTrue(frames <= MAXIMUM_TEST_FRAMES, "decoder exceeded caller frame bound");
                assertEquals(frames * decoder.channels(), pcm.position());
            } finally {
                MemoryUtil.memFree(pcm);
            }

            decoder.close();
            decoder.close();
            VorbisDecoder closedDecoder = decoder;
            assertThrows(
                    IllegalStateException.class,
                    () -> closedDecoder.readFrames(ShortBuffer.allocate(4), 1));
            assertThrows(IllegalStateException.class, closedDecoder::channels);
            assertThrows(IllegalStateException.class, closedDecoder::sampleRate);
        } finally {
            if (decoder != null) {
                decoder.close();
            }
            if (compressed != null) {
                MemoryUtil.memFree(compressed);
            }
        }
    }

    private static ByteBuffer readDirect(AssetManager assets, ResourceLocation location)
            throws IOException {
        byte[] bytes;
        try (InputStream input = assets.open(location)) {
            bytes = input.readAllBytes();
        }
        ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
        direct.put(bytes).flip();
        return direct;
    }

    private static Stream<ResourceLocation> musicResources() {
        return Stream.of(
                ResourceLocation.parse("gaia:audio/music/gaia.ogg"),
                ResourceLocation.parse("gaia:audio/music/legacy.ogg"));
    }
}
