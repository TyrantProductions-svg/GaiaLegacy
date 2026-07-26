package com.overlord.renderer.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.Texture;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextureTest {
    @Test
    void backendExposesExactlyTheSixTextureOperations()
            throws NoSuchMethodException {
        assertEquals(6, TextureBackend.class.getDeclaredMethods().length);
        assertEquals(
                int.class,
                TextureBackend.class
                        .getDeclaredMethod("createTexture")
                        .getReturnType());
        assertEquals(
                void.class,
                TextureBackend.class
                        .getDeclaredMethod("activateTextureUnit", int.class)
                        .getReturnType());
        assertEquals(
                void.class,
                TextureBackend.class
                        .getDeclaredMethod("bindTexture2d", int.class)
                        .getReturnType());
        assertEquals(
                void.class,
                TextureBackend.class
                        .getDeclaredMethod(
                                "setTextureParameter", int.class, int.class)
                        .getReturnType());
        assertEquals(
                void.class,
                TextureBackend.class
                        .getDeclaredMethod(
                                "uploadRgba8",
                                int.class,
                                int.class,
                                ByteBuffer.class)
                        .getReturnType());
        assertEquals(
                void.class,
                TextureBackend.class
                        .getDeclaredMethod("deleteTexture", int.class)
                        .getReturnType());
    }

    @Test
    void configuresNearestClampedLevelZeroUpload() {
        RecordingTextureBackend backend = new RecordingTextureBackend();
        Texture texture =
                new Texture(
                        MainThreadGuard.captureCurrentThread(),
                        TextureImage.missing(),
                        backend);

        assertEquals(
                Map.of(
                        GL_TEXTURE_MIN_FILTER, GL_NEAREST,
                        GL_TEXTURE_MAG_FILTER, GL_NEAREST,
                        GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE,
                        GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE,
                        GL_TEXTURE_BASE_LEVEL, 0,
                        GL_TEXTURE_MAX_LEVEL, 0),
                backend.parameters());
        assertEquals(List.of(new Upload(2, 2)), backend.uploads());
        texture.bind(3);
        texture.cleanup();
        texture.cleanup();

        assertEquals(List.of(41, 41), backend.boundTextureIds());
        assertEquals(List.of(3), backend.activeTextureUnits());
        assertEquals(List.of(41), backend.deletedTextureIds());
    }

    @Test
    void preservesUploadFailureAndDeletesPartialTextureOnce() {
        RecordingTextureBackend backend = new RecordingTextureBackend();
        IllegalStateException uploadFailure =
                new IllegalStateException("upload failed");
        backend.uploadFailure = uploadFailure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new Texture(
                                        MainThreadGuard.captureCurrentThread(),
                                        TextureImage.missing(),
                                        backend));

        assertSame(uploadFailure, escaped);
        assertEquals(List.of(41), backend.deletedTextureIds());
    }

    @Test
    void preservesUploadFailureWhenPartialCleanupAlsoFails() {
        RecordingTextureBackend backend = new RecordingTextureBackend();
        IllegalStateException uploadFailure =
                new IllegalStateException("upload failed");
        IllegalStateException cleanupFailure =
                new IllegalStateException("cleanup failed");
        backend.uploadFailure = uploadFailure;
        backend.deleteFailure = cleanupFailure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new Texture(
                                        MainThreadGuard.captureCurrentThread(),
                                        TextureImage.missing(),
                                        backend));

        assertSame(uploadFailure, escaped);
        assertEquals(List.of(cleanupFailure), List.of(escaped.getSuppressed()));
        assertEquals(List.of(41), backend.deletedTextureIds());
    }

    private record Upload(int width, int height) {}

    private static final class RecordingTextureBackend implements TextureBackend {
        private final Map<Integer, Integer> parameters = new HashMap<>();
        private final List<Upload> uploads = new ArrayList<>();
        private final List<Integer> activeTextureUnits = new ArrayList<>();
        private final List<Integer> boundTextureIds = new ArrayList<>();
        private final List<Integer> deletedTextureIds = new ArrayList<>();
        private RuntimeException uploadFailure;
        private RuntimeException deleteFailure;

        @Override
        public int createTexture() {
            return 41;
        }

        @Override
        public void activateTextureUnit(int textureUnit) {
            activeTextureUnits.add(textureUnit);
        }

        @Override
        public void bindTexture2d(int textureId) {
            boundTextureIds.add(textureId);
        }

        @Override
        public void setTextureParameter(int parameterName, int value) {
            parameters.put(parameterName, value);
        }

        @Override
        public void uploadRgba8(int width, int height, ByteBuffer pixels) {
            uploads.add(new Upload(width, height));
            if (uploadFailure != null) {
                throw uploadFailure;
            }
        }

        @Override
        public void deleteTexture(int textureId) {
            deletedTextureIds.add(textureId);
            if (deleteFailure != null) {
                throw deleteFailure;
            }
        }

        Map<Integer, Integer> parameters() {
            return Map.copyOf(parameters);
        }

        List<Upload> uploads() {
            return List.copyOf(uploads);
        }

        List<Integer> activeTextureUnits() {
            return List.copyOf(activeTextureUnits);
        }

        List<Integer> boundTextureIds() {
            return List.copyOf(boundTextureIds);
        }

        List<Integer> deletedTextureIds() {
            return List.copyOf(deletedTextureIds);
        }
    }
}
