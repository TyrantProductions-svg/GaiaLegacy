package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.world.GaiaWorldGenerator;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldGenerationSnapshotTest {
    static final String VERSION_ONE_REGION_HASH =
            "161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8";
    private static final List<ChunkKey> REPRESENTATIVE_KEYS =
            List.of(
                    new ChunkKey(0, 0),
                    new ChunkKey(1, -3),
                    new ChunkKey(0, 2),
                    new ChunkKey(1, 0),
                    new ChunkKey(-1, -1));
    private static final List<String> REPRESENTATIVE_HASHES =
            List.of(
                    "3ffb824a152c4e6f1f3333d1d785bc2645a73a685cfcac6c3b6b964232c8bd73",
                    "56f65cf7d77948b8de20a192dde0a9d31e903b6ec04eb7811afb1ad62e81374a",
                    "fa65749a079e1cb8befef4f05b4d2db04a6f59bea28822628dc5d1321aa693f6",
                    "8dfcb80a424ffe535b740be56adcae0e6d6286d5ec5b5c811e99953deb56e9cf",
                    "743d49d229d22d7400898f43dae920a9195c3065915f529439861568ea5c9e3c");
    static final String VERSION_TWO_CONFIG_FINGERPRINT =
            "56cb2f243319c7cf275ade89f480f9208ce5c1f85334eb225e6b56ed18e3012a";
    static final String VERSION_TWO_REGION_HASH =
            "ec2c76a97f36d34b7360ae9abbb0be60fb8790f275fdaf5227a7daeae9754353";
    private static final List<String> VERSION_TWO_REPRESENTATIVE_HASHES =
            List.of(
                    "be50d65edfef7a20fa20f93e3da65835e05c143600b79d5dcbedad7323debc2e",
                    "857c9a85799b9dcc7ddf4a2f6a5bee3b58c7e49142a17f6fc8abc46e43c97ea0",
                    "fb7ff4753fa1b008a6f2da3add9139e774a500bd43a878f75ad36564e0985b81",
                    "843a1f350723c87b2def6ae1cb9f305da12ea607bb6a6a1ce1de8447f3acf923",
                    "225a5c0b5c00064cf23ffb250b95f153fd8b98e04dfc9b8529958dd88641484a");
    private static final GenerationBlockPalette VERSION_TWO_PALETTE =
            new GenerationBlockPalette(
                    (byte) 0,
                    (byte) 1,
                    (byte) 2,
                    (byte) 3,
                    (byte) 4,
                    (byte) 5);

    @Test
    void fixedDefaultWorldMatchesApprovedSnapshot() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        List<ChunkGenerationData> representative =
                WorldGenerationDeterminismTest.generate(
                        REPRESENTATIVE_KEYS, config);
        for (int index = 0; index < REPRESENTATIVE_KEYS.size(); index++) {
            assertEquals(
                    REPRESENTATIVE_HASHES.get(index),
                    WorldGenerationHasher.hashChunk(
                            config, representative.get(index)),
                    "Snapshot mismatch for "
                            + REPRESENTATIVE_KEYS.get(index));
        }

        String region =
                WorldGenerationHasher.hashRegion(
                        config,
                        WorldGenerationDeterminismTest.generate(
                                WorldGenerationDeterminismTest.defaultKeys(),
                                config));
        assertEquals(VERSION_ONE_REGION_HASH, region);
    }

    @Test
    void approvedVisualRevisionMatchesVersionTwoSnapshot() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        List<ChunkGenerationData> representative =
                generateVisualRevision(REPRESENTATIVE_KEYS, config);
        List<String> representativeHashes =
                representative.stream()
                        .map(data -> WorldGenerationHasher.hashChunk(config, data))
                        .toList();
        String fingerprint =
                sha256(config.canonicalFingerprintInput());
        String region =
                WorldGenerationHasher.hashRegion(
                        config,
                        generateVisualRevision(
                                WorldGenerationDeterminismTest.defaultKeys(),
                                config));

        System.out.println(
                "PHASE4_V2 configFingerprint=" + fingerprint);
        System.out.println(
                "PHASE4_V2 representativeHashes="
                        + representativeHashes);
        System.out.println("PHASE4_V2 aggregateHash=" + region);

        assertEquals(2, config.algorithmVersion());
        assertEquals(VERSION_TWO_CONFIG_FINGERPRINT, fingerprint);
        assertEquals(
                VERSION_TWO_REPRESENTATIVE_HASHES,
                representativeHashes);
        assertEquals(VERSION_TWO_REGION_HASH, region);
    }

    @Test
    void representativeCoordinatesMatchDocumentedProviderFeatures() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context =
                WorldGenerationDeterminismTest.context(config);
        BiomeProvider biomeProvider = new ContinuousBiomeProvider();
        Map<BiomeType, Coordinate> nearest =
                new EnumMap<>(BiomeType.class);
        for (int worldX = -64; worldX <= 64; worldX++) {
            for (int worldZ = -64; worldZ <= 64; worldZ++) {
                BiomeType biome =
                        biomeProvider.sample(context, worldX, worldZ).dominant();
                Coordinate candidate = new Coordinate(worldX, worldZ);
                Coordinate current = nearest.get(biome);
                if (current == null || candidate.compareTo(current) < 0) {
                    nearest.put(biome, candidate);
                }
            }
        }
        assertEquals(
                Map.of(
                        BiomeType.PLAINS, new Coordinate(29, -45),
                        BiomeType.ROLLING_HILLS, new Coordinate(0, 0),
                        BiomeType.ROCKY_HIGHLANDS, new Coordinate(0, 44)),
                nearest);

        HeightProvider heightProvider = new BlendedHeightProvider();
        Coordinate3 cave = null;
        for (ChunkGenerationData data :
                WorldGenerationDeterminismTest.generate(
                        WorldGenerationDeterminismTest.defaultKeys(), config)) {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = data.key().worldOriginX() + localX;
                    int worldZ = data.key().worldOriginZ() + localZ;
                    int surface =
                            heightProvider.sampleHeight(
                                    context,
                                    worldX,
                                    worldZ,
                                    biomeProvider.sample(
                                            context, worldX, worldZ));
                    for (int y = config.cave().bedrockDepth();
                            y <= surface - config.cave().surfaceBuffer();
                            y++) {
                        if (data.getBlock(localX, y, localZ)
                                == WorldGenerationDeterminismTest.PALETTE.air()) {
                            Coordinate3 candidate =
                                    new Coordinate3(worldX, y, worldZ);
                            if (cave == null || candidate.compareTo(cave) < 0) {
                                cave = candidate;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(new Coordinate3(16, 2, 0), cave);
    }

    private record Coordinate(int x, int z)
            implements Comparable<Coordinate> {
        @Override
        public int compareTo(Coordinate other) {
            int distance =
                    Long.compare(
                            (long) x * x + (long) z * z,
                            (long) other.x * other.x
                                    + (long) other.z * other.z);
            if (distance != 0) {
                return distance;
            }
            int byX = Integer.compare(x, other.x);
            return byX != 0 ? byX : Integer.compare(z, other.z);
        }
    }

    private static List<ChunkGenerationData> generateVisualRevision(
            List<ChunkKey> keys, WorldGenerationConfig config) {
        WorldGenerator generator =
                GaiaWorldGenerator.createVisualRevisionCandidate();
        GenerationContext context =
                new GenerationContext(
                        config,
                        VERSION_TWO_PALETTE,
                        new DeterministicCoordinateSampler(
                                config.seed(),
                                config.algorithmVersion()));
        return keys.stream()
                .map(
                        key ->
                                generator.generate(context, key)
                                        .chunkData()
                                        .orElseThrow())
                .toList();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Coordinate3(int x, int y, int z)
            implements Comparable<Coordinate3> {
        @Override
        public int compareTo(Coordinate3 other) {
            int distance =
                    Long.compare(
                            (long) x * x + (long) z * z,
                            (long) other.x * other.x
                                    + (long) other.z * other.z);
            if (distance != 0) {
                return distance;
            }
            int byX = Integer.compare(x, other.x);
            if (byX != 0) {
                return byX;
            }
            int byZ = Integer.compare(z, other.z);
            return byZ != 0 ? byZ : Integer.compare(y, other.y);
        }
    }
}
