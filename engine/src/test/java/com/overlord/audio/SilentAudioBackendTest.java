package com.overlord.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import org.junit.jupiter.api.Test;

class SilentAudioBackendTest {
    private static final ResourceLocation GAIA =
            ResourceLocation.parse("gaia:audio/music/gaia.ogg");
    private static final ResourceLocation LEGACY =
            ResourceLocation.parse("gaia:audio/music/legacy.ogg");

    @Test
    void assignsDeterministicDistinctHandlesAndTracksLogicalPlayback() {
        SilentAudioBackend backend = new SilentAudioBackend();

        MusicHandle gaia = backend.startMusic(GAIA, true);
        MusicHandle legacy = backend.startMusic(LEGACY, false);

        assertEquals(1L, gaia.value());
        assertEquals(2L, legacy.value());
        assertNotEquals(gaia, legacy);
        assertTrue(backend.isMusicPlaying(gaia));
        assertTrue(backend.isMusicPlaying(legacy));

        backend.stopMusic(gaia);
        assertFalse(backend.isMusicPlaying(gaia));
        assertTrue(backend.isMusicPlaying(legacy));
        backend.close();
    }

    @Test
    void equalDiagnosticValuesDoNotGrantHandleOwnershipAcrossBackends() {
        SilentAudioBackend first = new SilentAudioBackend();
        SilentAudioBackend second = new SilentAudioBackend();
        MusicHandle firstHandle = first.startMusic(GAIA, true);
        MusicHandle secondHandle = second.startMusic(LEGACY, true);
        MusicHandle forged = new MusicHandle(firstHandle.value());

        assertEquals(firstHandle.value(), secondHandle.value());
        assertNotEquals(firstHandle, secondHandle);
        assertNotEquals(firstHandle, forged);
        assertThrows(IllegalArgumentException.class, () -> first.isMusicPlaying(secondHandle));
        assertThrows(IllegalArgumentException.class, () -> second.isMusicPlaying(firstHandle));
        assertThrows(IllegalArgumentException.class, () -> first.isMusicPlaying(forged));
        assertTrue(first.isMusicPlaying(firstHandle));
        assertTrue(second.isMusicPlaying(secondHandle));

        first.close();
        second.close();
    }

    @Test
    void stoppedForeignHandleCannotControlLaterSameValueHandle() {
        SilentAudioBackend earlier = new SilentAudioBackend();
        MusicHandle stopped = earlier.startMusic(GAIA, true);
        earlier.stopMusic(stopped);
        assertFalse(earlier.isMusicPlaying(stopped));

        SilentAudioBackend later = new SilentAudioBackend();
        MusicHandle active = later.startMusic(LEGACY, true);
        assertEquals(stopped.value(), active.value());
        assertNotEquals(stopped, active);

        assertThrows(IllegalArgumentException.class, () -> later.setMusicGain(stopped, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> later.stopMusic(stopped));
        assertTrue(later.isMusicPlaying(active));

        earlier.close();
        later.close();
    }

    @Test
    void gainAndUpdateAreValidatedDeterministicNoOps() {
        SilentAudioBackend backend = new SilentAudioBackend();
        MusicHandle handle = backend.startMusic(GAIA, true);

        assertDoesNotThrow(() -> backend.setMusicGain(handle, 0.25f));
        assertDoesNotThrow(backend::update);
        assertTrue(backend.isMusicPlaying(handle));
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.setMusicGain(handle, Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.setMusicGain(handle, -0.01f));
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.setMusicGain(handle, 1.01f));
        backend.close();
    }

    @Test
    void unknownAndMissingHandlesAreRejected() {
        SilentAudioBackend backend = new SilentAudioBackend();
        MusicHandle unknown = new MusicHandle(99L);

        assertThrows(IllegalArgumentException.class, () -> backend.setMusicGain(unknown, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> backend.isMusicPlaying(unknown));
        assertThrows(IllegalArgumentException.class, () -> backend.stopMusic(unknown));
        assertThrows(NullPointerException.class, () -> backend.isMusicPlaying(null));
        backend.close();
    }

    @Test
    void closeIsIdempotentAndPreventsResurrection() {
        SilentAudioBackend backend = new SilentAudioBackend();
        MusicHandle handle = backend.startMusic(GAIA, true);

        backend.close();
        assertDoesNotThrow(backend::close);

        assertThrows(IllegalStateException.class, () -> backend.startMusic(GAIA, true));
        assertThrows(
                IllegalStateException.class,
                () -> backend.setMusicGain(handle, 1.0f));
        assertThrows(IllegalStateException.class, () -> backend.isMusicPlaying(handle));
        assertThrows(IllegalStateException.class, () -> backend.stopMusic(handle));
        assertThrows(IllegalStateException.class, backend::update);
    }

    @Test
    void repeatedStartStopKeepsOnlyActiveStateAndRejectsStoppedMutation() {
        SilentAudioBackend backend = new SilentAudioBackend();
        MusicHandle last = null;

        for (int index = 0; index < 10_000; index++) {
            last = backend.startMusic(GAIA, true);
            backend.stopMusic(last);
        }

        assertEquals(0, backend.activeHandleCount());
        assertFalse(backend.isMusicPlaying(last));
        MusicHandle stopped = last;
        assertThrows(IllegalArgumentException.class, () -> backend.setMusicGain(stopped, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> backend.stopMusic(stopped));
        assertEquals(0, backend.activeHandleCount());
        backend.close();
    }
}
