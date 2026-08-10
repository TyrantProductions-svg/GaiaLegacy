package com.overlord.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class AudioBusSettingsTest {
    @ParameterizedTest
    @CsvSource({"1.0,1.0,1.0,1.0", "0.5,0.4,0.25,0.05", "0.0,1.0,1.0,0.0"})
    void effectiveMusicGainIsMasterTimesMusicTimesCue(
            float master, float music, float cue, float expected) {
        AudioBusSettings settings = new AudioBusSettings(master, music, 1.0f);

        assertEquals(expected, settings.effectiveMusicGain(cue), 1.0e-6f);
    }

    @Test
    void musicAndSfxBusesRemainIndependent() {
        AudioBusSettings settings = new AudioBusSettings(0.5f, 0.4f, 0.8f);

        assertEquals(0.1f, settings.effectiveMusicGain(0.5f), 1.0e-6f);
        assertEquals(0.2f, settings.effectiveSfxGain(0.5f), 1.0e-6f);
    }

    @ParameterizedTest
    @MethodSource("invalidGains")
    void busSettingsRejectNonFiniteAndOutOfRangeGains(float invalid) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioBusSettings(invalid, 1.0f, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioBusSettings(1.0f, invalid, 1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioBusSettings(1.0f, 1.0f, invalid));
    }

    @ParameterizedTest
    @MethodSource("invalidGains")
    void effectiveGainRejectsInvalidCueGain(float invalid) {
        AudioBusSettings settings = new AudioBusSettings(1.0f, 1.0f, 1.0f);

        assertThrows(IllegalArgumentException.class, () -> settings.effectiveMusicGain(invalid));
        assertThrows(IllegalArgumentException.class, () -> settings.effectiveSfxGain(invalid));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void soundEventRejectsInvalidIdentifiers(String invalid) {
        assertThrows(RuntimeException.class, () -> new SoundEvent(invalid));
    }

    @Test
    void soundCuePreservesValidatedEventResourceCategoryAndBaseGain() {
        SoundEvent event = new SoundEvent("block.break");
        ResourceLocation resource = ResourceLocation.parse("gaia:audio/sfx/block_break.ogg");

        SoundCue cue = new SoundCue(event, resource, "blocks", 0.75f);

        assertEquals(event, cue.event());
        assertEquals(resource, cue.resource());
        assertEquals("blocks", cue.category());
        assertEquals(0.75f, cue.baseGain(), 0.0f);
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void soundCueRejectsInvalidCategoryIdentifiers(String invalid) {
        SoundEvent event = new SoundEvent("block.break");
        ResourceLocation resource = ResourceLocation.parse("gaia:audio/sfx/block_break.ogg");

        assertThrows(
                RuntimeException.class,
                () -> new SoundCue(event, resource, invalid, 1.0f));
    }

    @ParameterizedTest
    @MethodSource("invalidGains")
    void soundCueRejectsInvalidBaseGain(float invalid) {
        SoundEvent event = new SoundEvent("block.break");
        ResourceLocation resource = ResourceLocation.parse("gaia:audio/sfx/block_break.ogg");

        assertThrows(
                IllegalArgumentException.class,
                () -> new SoundCue(event, resource, "blocks", invalid));
    }

    @Test
    void soundCueRejectsMissingEventOrResource() {
        SoundEvent event = new SoundEvent("block.break");
        ResourceLocation resource = ResourceLocation.parse("gaia:audio/sfx/block_break.ogg");

        assertThrows(NullPointerException.class, () -> new SoundCue(null, resource, "blocks", 1.0f));
        assertThrows(NullPointerException.class, () -> new SoundCue(event, null, "blocks", 1.0f));
    }

    @Test
    void musicHandleRequiresPositiveStableIdentifier() {
        assertEquals(7L, new MusicHandle(7L).value());
        assertThrows(IllegalArgumentException.class, () -> new MusicHandle(0L));
        assertThrows(IllegalArgumentException.class, () -> new MusicHandle(-1L));
    }

    @Test
    void audioDiagnosticRejectsBlankFieldsAndBoundsMessage() {
        AudioDiagnostic diagnostic =
                new AudioDiagnostic("AUDIO_BACKEND_INIT_FAILED", "OpenAL device unavailable");

        assertEquals("AUDIO_BACKEND_INIT_FAILED", diagnostic.code());
        assertEquals("OpenAL device unavailable", diagnostic.message());
        assertThrows(NullPointerException.class, () -> new AudioDiagnostic(null, "message"));
        assertThrows(IllegalArgumentException.class, () -> new AudioDiagnostic(" ", "message"));
        assertThrows(NullPointerException.class, () -> new AudioDiagnostic("CODE", null));
        assertThrows(IllegalArgumentException.class, () -> new AudioDiagnostic("CODE", " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioDiagnostic("CODE", "x".repeat(257)));
    }

    private static Stream<Arguments> invalidGains() {
        return Stream.of(
                Arguments.of(-0.01f),
                Arguments.of(1.01f),
                Arguments.of(Float.NaN),
                Arguments.of(Float.POSITIVE_INFINITY),
                Arguments.of(Float.NEGATIVE_INFINITY));
    }

    private static Stream<Arguments> invalidIdentifiers() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("Block Break"),
                Arguments.of("../break"),
                Arguments.of("block:break"));
    }
}
