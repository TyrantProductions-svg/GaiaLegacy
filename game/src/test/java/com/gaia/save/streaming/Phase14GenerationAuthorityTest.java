package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationHasher;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Focused RED for canonical metadata selecting its exact generation authority. */
class Phase14GenerationAuthorityTest {
    private static final SaveGameId SAVE_ID = SaveGameId.parse(
            "123e4567-e89b-12d3-a456-426614174125");
    private static final ChunkKey KEY = new ChunkKey(2, -1);

    @Test
    void canonicalMetadataSelectsItsExactGeneratorImplementation() {
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();
        WorldGenerationConfig visual =
                WorldGenerationConfig.visualRevisionCandidate();
        String expectedDefault = generatedHash(
                defaults, GaiaWorldGenerator.createDefault(), KEY);
        String expectedVisual = generatedHash(
                visual, GaiaWorldGenerator.createVisualRevisionCandidate(), KEY);
        String wrongVisualAuthority = generatedHash(
                visual, GaiaWorldGenerator.createDefault(), KEY);

        assertNotEquals(expectedVisual, wrongVisualAuthority,
                "the selected key must distinguish visual from default authority");
        assertEquals(
                expectedDefault,
                Phase14SaveMigrator.reproducedBaseHash(metadata(defaults), KEY));
        assertEquals(
                expectedVisual,
                Phase14SaveMigrator.reproducedBaseHash(metadata(visual), KEY));
    }

    private static String generatedHash(
            WorldGenerationConfig config,
            WorldGenerator generator,
            ChunkKey key) {
        GenerationContext context = new GenerationContext(
                config,
                new GenerationBlockPalette(
                        (byte) 0,
                        (byte) 1,
                        (byte) 2,
                        (byte) 3,
                        (byte) 4,
                        (byte) 5),
                new DeterministicCoordinateSampler(
                        config.seed(), config.algorithmVersion()));
        var generated = generator.generate(context, key);
        if (!generated.succeeded()) {
            throw new AssertionError("focused generation failed for " + key);
        }
        return WorldGenerationHasher.hashChunk(
                config, generated.chunkData().orElseThrow());
    }

    private static SaveGameSnapshot.StaticMetadata metadata(
            WorldGenerationConfig config) {
        return new SaveGameSnapshot.StaticMetadata(
                SaveFormatVersion.CURRENT,
                "0.3.0-phase15-test",
                SAVE_ID,
                "Phase 14 generation authority",
                java.time.Instant.parse("2026-08-24T00:00:00Z"),
                config.seed(),
                "gaia-v" + config.algorithmVersion(),
                sha256(config.canonicalFingerprintInput()),
                config.chunkRadius(),
                GameConfig.Chunk.MAX_HEIGHT,
                Optional.empty());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
