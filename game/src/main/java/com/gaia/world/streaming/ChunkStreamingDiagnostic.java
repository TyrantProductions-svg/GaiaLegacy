package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One bounded current failure observation, not a historical log. */
public record ChunkStreamingDiagnostic(
        long sequence,
        ChunkKey key,
        ChunkWorkResult.Kind kind,
        String code,
        String message) {
    public static final int MAX_CURRENT_DIAGNOSTICS = 256;
    private static final int MAX_TEXT_BYTES = 512;

    public ChunkStreamingDiagnostic {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        key = ChunkCoordinatePolicy.requireSafe(key);
        Objects.requireNonNull(kind, "kind");
        code = bounded(code, "code");
        message = bounded(message, "message");
    }

    private static String bounded(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()
                || checked.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " is blank or too large");
        }
        return checked;
    }
}
