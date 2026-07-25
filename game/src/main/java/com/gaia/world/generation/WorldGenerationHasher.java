package com.gaia.world.generation;

import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class WorldGenerationHasher {
    private static final byte[] CHUNK_DOMAIN =
            "GaiaLegacy.WorldGeneration.Chunk.v1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REGION_DOMAIN =
            "GaiaLegacy.WorldGeneration.Region.v1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Comparator<ChunkGenerationData> KEY_ORDER =
            Comparator.comparingInt(
                            (ChunkGenerationData data) -> data.key().x())
                    .thenComparingInt(data -> data.key().z());

    private WorldGenerationHasher() {
    }

    public static String hashChunk(
            WorldGenerationConfig config,
            ChunkGenerationData data) {
        Objects.requireNonNull(data, "data");
        MessageDigest digest = configuredDigest(CHUNK_DOMAIN, config);
        updateChunk(digest, data);
        return hex(digest.digest());
    }

    public static String hashRegion(
            WorldGenerationConfig config,
            Collection<ChunkGenerationData> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        List<ChunkGenerationData> ordered =
                new ArrayList<>(chunks.size());
        for (ChunkGenerationData data : chunks) {
            ordered.add(Objects.requireNonNull(data, "chunk"));
        }
        ordered.sort(KEY_ORDER);
        Set<ChunkKey> keys = new HashSet<>();
        for (ChunkGenerationData data : ordered) {
            if (!keys.add(data.key())) {
                throw new IllegalArgumentException(
                        "Duplicate generated chunk key: " + data.key());
            }
        }

        MessageDigest digest = configuredDigest(REGION_DOMAIN, config);
        updateInt(digest, ordered.size());
        for (ChunkGenerationData data : ordered) {
            updateChunk(digest, data);
        }
        return hex(digest.digest());
    }

    private static MessageDigest configuredDigest(
            byte[] domain, WorldGenerationConfig config) {
        Objects.requireNonNull(config, "config");
        MessageDigest digest = sha256();
        updateBytes(digest, domain);
        updateBytes(
                digest,
                config.canonicalFingerprintInput()
                        .getBytes(StandardCharsets.UTF_8));
        return digest;
    }

    private static void updateChunk(
            MessageDigest digest, ChunkGenerationData data) {
        updateInt(digest, data.key().x());
        updateInt(digest, data.key().z());
        updateInt(digest, data.worldHeight());
        updateBytes(digest, data.copyBlocks());
    }

    private static void updateBytes(
            MessageDigest digest, byte[] bytes) {
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(
            MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(
                    Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
