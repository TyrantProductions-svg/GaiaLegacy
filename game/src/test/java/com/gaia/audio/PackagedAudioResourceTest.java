package com.gaia.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PackagedAudioResourceTest {
    private static final byte[] OGG_CAPTURE_PATTERN =
            "OggS".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "{0} is a packaged Ogg resource")
    @MethodSource("musicResources")
    void productionAssetManagerLoadsNonEmptyOggMusic(
            String description, ResourceLocation location) throws IOException {
        AssetManager assets = new AssetManager(getClass().getClassLoader());

        try (InputStream input = assets.open(location)) {
            assertArrayEquals(
                    OGG_CAPTURE_PATTERN,
                    input.readNBytes(OGG_CAPTURE_PATTERN.length),
                    location + " must begin with the OggS capture pattern");
            assertNotEquals(-1, input.read(), location + " must contain data after its OggS header");
        }
    }

    private static Stream<Arguments> musicResources() {
        return Stream.of(
                Arguments.of(
                        "Gaia",
                        ResourceLocation.parse("gaia:audio/music/gaia.ogg")),
                Arguments.of(
                        "Legacy",
                        ResourceLocation.parse("gaia:audio/music/legacy.ogg")));
    }
}
