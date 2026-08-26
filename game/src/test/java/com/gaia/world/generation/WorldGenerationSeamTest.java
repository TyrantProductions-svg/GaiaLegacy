package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.GaiaWorldGenerator;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldGenerationSeamTest {
    private static final byte AIR = 0;
    private static final byte STONE = 3;
    private static final byte OAK_LOG = 4;
    private static final byte OAK_LEAVES = 5;
    private static final GenerationBlockPalette TERRAIN_PALETTE =
            new GenerationBlockPalette(
                    AIR, (byte) 1, (byte) 2, STONE);
    private static final GenerationBlockPalette DECORATION_PALETTE =
            new GenerationBlockPalette(
                    AIR,
                    (byte) 1,
                    (byte) 2,
                    STONE,
                    OAK_LOG,
                    OAK_LEAVES);

    @Test
    void cardinalBorderColumnsMatchHandCheckedWorldOracles() {
        List<ColumnOracle> oracles =
                List.of(
                        new ColumnOracle(111, 60, 37, 33, 32, AIR),
                        new ColumnOracle(112, 60, 39, 35, 32, AIR));

        assertColumnOracles(oracles);
    }

    @Test
    void diagonalCornerColumnsMatchHandCheckedWorldOracles() {
        List<ColumnOracle> oracles =
                List.of(
                        new ColumnOracle(287, -257, 45, 41, 20, STONE),
                        new ColumnOracle(288, -257, 45, 41, 20, STONE),
                        new ColumnOracle(287, -256, 43, 39, 20, STONE),
                        new ColumnOracle(288, -256, 43, 39, 20, STONE));

        assertColumnOracles(oracles);
    }

    @Test
    void oneSignedRegionAnchorClipsOneTreeAcrossFourChunks() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config, DECORATION_PALETTE);
        GenerationStageContract treeContract =
                new GenerationStageContract(
                        ResourceLocation.parse(
                                "gaia:tree_decoration"),
                        1,
                        2);

        StableRegionAnchor anchor =
                StableRegionAnchor.sample(
                        context.sampler(),
                        treeContract,
                        -5L,
                        4L,
                        8);

        assertEquals(-5L, anchor.regionX());
        assertEquals(4L, anchor.regionZ());
        assertEquals(-33L, anchor.worldX());
        assertEquals(33L, anchor.worldZ());
        assertEquals(new ChunkKey(-3, 2), anchor.ownerChunk());

        List<ChunkKey> affected =
                List.of(
                        new ChunkKey(-3, 1),
                        new ChunkKey(-2, 1),
                        new ChunkKey(-3, 2),
                        new ChunkKey(-2, 2));
        Map<ChunkKey, ChunkGenerationData> chunks =
                generate(
                        GaiaWorldGenerator
                                .createVisualRevisionCandidate(),
                        context,
                        affected);

        assertTrue(anchor.ownedBy(new ChunkKey(-3, 2)));
        for (int y = 34; y <= 39; y++) {
            assertEquals(
                    OAK_LOG,
                    blockAt(chunks, -33, y, 33),
                    "single anchor must own one trunk");
        }
        List<WorldBlock> clippedWrites =
                List.of(
                        new WorldBlock(-34, 37, 31),
                        new WorldBlock(-32, 37, 31),
                        new WorldBlock(-35, 37, 32),
                        new WorldBlock(-32, 37, 32));
        assertEquals(
                Set.of(
                        new ChunkKey(-3, 1),
                        new ChunkKey(-2, 1),
                        new ChunkKey(-3, 2),
                        new ChunkKey(-2, 2)),
                clippedWrites.stream()
                        .map(
                                leaf ->
                                        ChunkKey.fromWorld(
                                                leaf.x(), leaf.z()))
                        .collect(java.util.stream.Collectors.toSet()));
        for (WorldBlock leaf : clippedWrites) {
            assertEquals(
                    OAK_LEAVES,
                    blockAt(chunks, leaf.x(), leaf.y(), leaf.z()),
                    "each affected Chunk must receive its clipped canopy write");
        }
        List<WorldBlockValue> exactDecorationWrites =
                blocksWithin(
                        chunks,
                        -35,
                        -31,
                        34,
                        42,
                        31,
                        35,
                        Set.of(OAK_LOG, OAK_LEAVES));
        assertEquals(58, exactDecorationWrites.size());
        assertEquals(
                exactDecorationWrites.size(),
                Set.copyOf(exactDecorationWrites).size(),
                "a canonical decoration write may occur only once");
        assertEquals(
                EXPECTED_TREE_WRITES,
                canonicalWrites(exactDecorationWrites));
    }

    @Test
    void visualPipelineDirectAndStagedGenerationExposeCompleteSeamDelta() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config, DECORATION_PALETTE);
        ChunkKey key = new ChunkKey(1, -3);
        List<WorldGenerationStage> stages = visualStages();
        ChunkGenerationData staged =
                generate(
                                new StagedWorldGenerator(stages),
                                context,
                                List.of(key))
                        .get(key);
        GenerationRegion directRegion =
                new GenerationRegion(
                        key,
                        staged.worldHeight(),
                        context.palette().air(),
                        GenerationRegion.WorldColumnSampler.from(
                                (BiomeProvider) stages.get(0),
                                (HeightProvider) stages.get(1)));
        for (WorldGenerationStage stage : stages) {
            assertEquals(
                    GenerationStageResult.Status.SUCCEEDED,
                    stage.generate(context, directRegion).status());
        }
        ChunkGenerationData direct = directRegion.freeze();

        long originX = ChunkCoordinatePolicy.worldOriginX(key);
        long originZ = ChunkCoordinatePolicy.worldOriginZ(key);
        BiomeProvider biomes = (BiomeProvider) stages.get(0);
        HeightProvider heights = (HeightProvider) stages.get(1);
        GenerationRegion localClampedRegion =
                new GenerationRegion(
                        key,
                        staged.worldHeight(),
                        context.palette().air(),
                        (samplingContext, worldX, worldZ) -> {
                            long clampedX =
                                    Math.max(
                                            originX,
                                            Math.min(
                                                    originX
                                                            + GameConfig.Chunk.SIZE
                                                            - 1L,
                                                    worldX));
                            long clampedZ =
                                    Math.max(
                                            originZ,
                                            Math.min(
                                                    originZ
                                                            + GameConfig.Chunk.SIZE
                                                            - 1L,
                                                    worldZ));
                            BiomeSample biome =
                                    biomes.sample(
                                            samplingContext,
                                            clampedX,
                                            clampedZ);
                            return heights.sampleHeight(
                                    samplingContext,
                                    clampedX,
                                    clampedZ,
                                    biome);
                        });
        for (WorldGenerationStage stage : stages) {
            assertEquals(
                    GenerationStageResult.Status.SUCCEEDED,
                    stage.generate(context, localClampedRegion).status());
        }
        ChunkGenerationData localClamped =
                localClampedRegion.freeze();

        assertEquals(List.of(), blockDeltas(direct, staged));
        assertEquals(
                VISUAL_SEAM_CORRECTION,
                blockDeltas(localClamped, staged),
                "the complete visual content delta must be the literal border-slope seam correction");
        for (BlockDelta correction : VISUAL_SEAM_CORRECTION) {
            assertEquals(
                    correction.oldBlock(),
                    blockAt(
                            Map.of(key, localClamped),
                            Math.toIntExact(correction.x()),
                            correction.y(),
                            Math.toIntExact(correction.z())),
                    "pre-surface byte must match the literal seam oracle");
            assertEquals(
                    correction.newBlock(),
                    blockAt(
                            Map.of(key, staged),
                            Math.toIntExact(correction.x()),
                            correction.y(),
                            Math.toIntExact(correction.z())));
        }
        assertEquals(
                List.of(
                        "013647e46ef2f566dd44081af8f1d44cd4be303661e5a31442cc838f94ff9ae6",
                        "ee78ac05168f882a5dedfed06aa29d389472ffeea9c21a9dff622f1a9b9f2971",
                        "d82442010ea4dbebef50823808866eb5c38104b01cdce2d2f7bda2a7c828ce9c"),
                List.of(
                        columnHash(staged, 25, -33),
                        columnHash(staged, 29, -33),
                        columnHash(staged, 30, -33)));
    }

    @Test
    void safeEdgeHaloDoesNotSilentlyOmitCardinalWorldColumns() {
        GenerationContext context =
                context(WorldGenerationConfig.defaults(), TERRAIN_PALETTE);
        ContinuousBiomeProvider biomes = new ContinuousBiomeProvider();
        BlendedHeightProvider heights = new BlendedHeightProvider();

        for (ChunkKey key :
                List.of(
                        new ChunkKey(
                                ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE,
                                ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE),
                        new ChunkKey(
                                -ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE,
                                -ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE))) {
            GenerationRegion region =
                    new GenerationRegion(
                            key,
                            GameConfig.Chunk.MAX_HEIGHT,
                            context.palette().air(),
                            GenerationRegion.WorldColumnSampler.from(
                                    biomes, heights));
            assertEquals(
                    GenerationStageResult.Status.SUCCEEDED,
                    biomes.generate(context, region).status());
            assertEquals(
                    GenerationStageResult.Status.SUCCEEDED,
                    heights.generate(context, region).status());
            int local = key.x() > 0 ? 15 : 0;
            long worldX = region.worldXLong(local);
            long worldZ = region.worldZLong(local);
            for (long[] direction :
                    new long[][] {
                        {-1L, 0L}, {1L, 0L}, {0L, -1L}, {0L, 1L}
                    }) {
                assertTrue(
                        region.heightAtWorld(
                                        context,
                                        worldX + direction[0],
                                        worldZ + direction[1])
                                .isPresent(),
                        () ->
                                "missing required halo column "
                                        + (worldX + direction[0])
                                        + ","
                                        + (worldZ + direction[1]));
            }
        }
    }

    @Test
    void outsideSafeEdgeAnchorsRetainExactInwardClippedFootprints() {
        GenerationContext context =
                context(
                        WorldGenerationConfig.visualRevisionCandidate(),
                        DECORATION_PALETTE);
        GenerationStageContract contract =
                new GenerationStageContract(
                        ResourceLocation.parse("gaia:tree_decoration"),
                        1,
                        2);
        List<EdgeAnchorOracle> oracles =
                List.of(
                        new EdgeAnchorOracle(
                                268435456L,
                                20L,
                                2147483649L,
                                161L,
                                new ChunkKey(134217728, 10),
                                new ChunkKey(
                                        ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE,
                                        10),
                                Set.of(
                                        new WorldHorizontal(2147483647L, 160L),
                                        new WorldHorizontal(2147483647L, 161L),
                                        new WorldHorizontal(2147483647L, 162L),
                                        new WorldHorizontal(2147483647L, 163L))),
                        new EdgeAnchorOracle(
                                -268435455L,
                                21L,
                                -2147483633L,
                                169L,
                                new ChunkKey(-134217728, 10),
                                new ChunkKey(
                                        -ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE,
                                        10),
                                Set.of(
                                        new WorldHorizontal(-2147483632L, 167L),
                                        new WorldHorizontal(-2147483632L, 168L),
                                        new WorldHorizontal(-2147483632L, 169L),
                                        new WorldHorizontal(-2147483632L, 170L),
                                        new WorldHorizontal(-2147483632L, 171L),
                                        new WorldHorizontal(-2147483631L, 167L),
                                        new WorldHorizontal(-2147483631L, 168L),
                                        new WorldHorizontal(-2147483631L, 169L),
                                        new WorldHorizontal(-2147483631L, 170L),
                                        new WorldHorizontal(-2147483631L, 171L))),
                        new EdgeAnchorOracle(
                                26L,
                                268435456L,
                                211L,
                                2147483649L,
                                new ChunkKey(13, 134217728),
                                new ChunkKey(
                                        13,
                                        ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE),
                                Set.of(
                                        new WorldHorizontal(209L, 2147483647L),
                                        new WorldHorizontal(210L, 2147483647L),
                                        new WorldHorizontal(211L, 2147483647L),
                                        new WorldHorizontal(212L, 2147483647L),
                                        new WorldHorizontal(213L, 2147483647L))),
                        new EdgeAnchorOracle(
                                20L,
                                -268435455L,
                                162L,
                                -2147483634L,
                                new ChunkKey(10, -134217728),
                                new ChunkKey(
                                        10,
                                        -ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE),
                                Set.of(
                                        new WorldHorizontal(160L, -2147483632L),
                                        new WorldHorizontal(161L, -2147483632L),
                                        new WorldHorizontal(162L, -2147483632L),
                                        new WorldHorizontal(163L, -2147483632L),
                                        new WorldHorizontal(164L, -2147483632L))));

        for (EdgeAnchorOracle oracle : oracles) {
            StableRegionAnchor anchor =
                    StableRegionAnchor.sample(
                            context.sampler(),
                            contract,
                            oracle.regionX(),
                            oracle.regionZ(),
                            8);
            assertEquals(oracle.worldX(), anchor.worldX());
            assertEquals(oracle.worldZ(), anchor.worldZ());
            assertEquals(oracle.owner(), anchor.ownerChunk());
            GenerationRegion requested =
                    new GenerationRegion(
                            oracle.requested(),
                            1,
                            AIR);
            int writeCount = 0;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    writeCount +=
                            TreeDecorationProvider.writeWorld(
                                    requested,
                                    anchor.worldX() + dx,
                                    0,
                                    anchor.worldZ() + dz,
                                    OAK_LEAVES,
                                    AIR,
                                    AIR);
                }
            }
            List<WorldHorizontal> clipped =
                    generatedHorizontalWrites(requested, OAK_LEAVES);
            assertEquals(
                    oracle.clippedWrites().size(),
                    writeCount,
                    "each inward write must be emitted exactly once");
            assertEquals(writeCount, clipped.size());
            assertEquals(oracle.clippedWrites(), Set.copyOf(clipped));
        }
    }

    @Test
    void exactTreeWritesAreEmittedOnceWhenAnchorIsVisitedAgain() {
        GenerationContext context =
                context(
                        WorldGenerationConfig.visualRevisionCandidate(),
                        DECORATION_PALETTE);
        TreeDecorationProvider trees =
                new TreeDecorationProvider(
                        new GenerationStageContract(
                                ResourceLocation.parse("gaia:decoration"),
                                1,
                                4),
                        new HybridCaveProvider().entranceQuery());
        List<ChunkKey> affected =
                List.of(
                        new ChunkKey(-3, 1),
                        new ChunkKey(-2, 1),
                        new ChunkKey(-3, 2),
                        new ChunkKey(-2, 2));
        Map<ChunkKey, GenerationRegion> regions =
                maskedTreeRegions(affected);

        int firstWrites =
                regions.values().stream()
                        .mapToInt(region -> trees.generate(context, region))
                        .sum();
        int repeatedWrites =
                regions.values().stream()
                        .mapToInt(region -> trees.generate(context, region))
                        .sum();
        Map<ChunkKey, ChunkGenerationData> chunks =
                regions.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry -> entry.getValue().freeze()));
        List<WorldBlockValue> emitted =
                blocksWithin(
                        chunks,
                        -35,
                        -31,
                        34,
                        42,
                        31,
                        35,
                        Set.of(OAK_LOG, OAK_LEAVES));

        assertEquals(58, firstWrites);
        assertEquals(
                0,
                repeatedWrites,
                "revisiting one global anchor must not emit its trunk again");
        assertEquals(58, emitted.size());
        assertEquals(EXPECTED_TREE_WRITES, canonicalWrites(emitted));
    }

    @Test
    void existingLeafAtTrunkCellIsReplacedAndEmittedOnce() {
        GenerationContext context =
                context(
                        WorldGenerationConfig.visualRevisionCandidate(),
                        DECORATION_PALETTE);
        TreeDecorationProvider trees =
                new TreeDecorationProvider(
                        new GenerationStageContract(
                                ResourceLocation.parse("gaia:decoration"),
                                1,
                                4),
                        new HybridCaveProvider().entranceQuery());
        List<ChunkKey> affected =
                List.of(
                        new ChunkKey(-3, 1),
                        new ChunkKey(-2, 1),
                        new ChunkKey(-3, 2),
                        new ChunkKey(-2, 2));
        Map<ChunkKey, GenerationRegion> regions =
                maskedTreeRegions(affected);
        GenerationRegion overlapRegion =
                regions.get(ChunkKey.fromWorld(-33, 33));
        overlapRegion.writeBlock(
                ChunkKey.localCoordinate(-33),
                34,
                ChunkKey.localCoordinate(33),
                OAK_LEAVES);
        assertEquals(
                OAK_LEAVES,
                overlapRegion.getBlock(
                        ChunkKey.localCoordinate(-33),
                        34,
                        ChunkKey.localCoordinate(33)));

        int firstWrites =
                regions.values().stream()
                        .mapToInt(region -> trees.generate(context, region))
                        .sum();
        int repeatedWrites =
                regions.values().stream()
                        .mapToInt(region -> trees.generate(context, region))
                        .sum();
        Map<ChunkKey, ChunkGenerationData> chunks =
                regions.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey,
                                        entry -> entry.getValue().freeze()));
        List<WorldBlockValue> emitted =
                blocksWithin(
                        chunks,
                        -35,
                        -31,
                        34,
                        42,
                        31,
                        35,
                        Set.of(OAK_LOG, OAK_LEAVES));

        assertEquals(58, firstWrites);
        assertEquals(OAK_LOG, blockAt(chunks, -33, 34, 33));
        assertEquals(
                0,
                repeatedWrites,
                "revisiting the leaf-to-log overlap must emit nothing");
        assertEquals(58, emitted.size());
        assertEquals(EXPECTED_TREE_WRITES, canonicalWrites(emitted));
    }

    @Test
    void versionedCaveEntranceQueryControlsLiteralTreeExclusion() {
        GenerationContext context =
                context(
                        WorldGenerationConfig.visualRevisionCandidate(),
                        DECORATION_PALETTE);
        HybridCaveProvider cavesV1 = new HybridCaveProvider();
        HybridCaveProvider cavesV2 =
                new HybridCaveProvider(
                        cavesV1.contract().withVersion(2));
        HybridCaveProvider.EntranceQuery v1Entrances =
                cavesV1.entranceQuery();
        HybridCaveProvider.EntranceQuery v2Entrances =
                cavesV2.entranceQuery();
        long rootX = -97L;
        long rootZ = -105L;

        assertFalse(v1Entrances.hasEntranceNear(context, rootX, rootZ, 8));
        assertTrue(v2Entrances.hasEntranceNear(context, rootX, rootZ, 8));

        GenerationStageContract decoration =
                new GenerationStageContract(
                        ResourceLocation.parse("gaia:decoration"),
                        1,
                        4);
        TreeDecorationProvider v1Trees =
                new TreeDecorationProvider(decoration, v1Entrances);
        TreeDecorationProvider v2Trees =
                new TreeDecorationProvider(decoration, v2Entrances);

        assertEquals(
                6,
                v1Trees.generate(
                        context,
                        maskedTreeColumnRegion(rootX, rootZ, 37, 42)),
                "v1 has no cave entrance and emits the literal five-block trunk plus top leaf");
        assertEquals(
                0,
                v2Trees.generate(
                        context,
                        maskedTreeColumnRegion(rootX, rootZ, 37, 42)),
                "v2 entrance decision must exclude the same literal tree from decoration");
    }

    private static void assertColumnOracles(
            List<ColumnOracle> oracles) {
        WorldGenerationConfig config =
                WorldGenerationConfig.defaults();
        GenerationContext context = context(config, TERRAIN_PALETTE);
        WorldGenerator generator =
                new StagedWorldGenerator(
                        List.of(
                                new ContinuousBiomeProvider(),
                                new BlendedHeightProvider(),
                                new DefaultStrataDensityProvider(),
                                new NoiseCaveProvider(),
                                new DefaultSurfaceProvider()));
        List<ChunkKey> keys =
                oracles.stream()
                        .map(
                                oracle ->
                                        ChunkKey.fromWorld(
                                                oracle.worldX(),
                                                oracle.worldZ()))
                        .distinct()
                        .toList();
        Map<ChunkKey, ChunkGenerationData> chunks =
                generate(generator, context, keys);

        for (ColumnOracle oracle : oracles) {
            ChunkGenerationData chunk =
                    chunks.get(
                            ChunkKey.fromWorld(
                                    oracle.worldX(),
                                    oracle.worldZ()));
            int localX = ChunkKey.localCoordinate(oracle.worldX());
            int localZ = ChunkKey.localCoordinate(oracle.worldZ());

            assertEquals(
                    oracle.height(),
                    highestSolid(chunk, localX, localZ),
                    "world height mismatch at " + oracle.location());
            assertEquals(
                    STONE,
                    chunk.getBlock(
                            localX, oracle.height(), localZ),
                    "global slope must classify the seam surface at "
                            + oracle.location());
            assertEquals(
                    STONE,
                    chunk.getBlock(
                            localX, oracle.strataY(), localZ),
                    "strata continuity mismatch at "
                            + oracle.location());
            assertEquals(
                    oracle.caveBlock(),
                    chunk.getBlock(
                            localX, oracle.caveY(), localZ),
                    "cave continuity mismatch at "
                            + oracle.location());
        }
    }

    private static int highestSolid(
            ChunkGenerationData chunk, int localX, int localZ) {
        for (int y = chunk.worldHeight() - 1; y >= 0; y--) {
            if (chunk.getBlock(localX, y, localZ) != AIR) {
                return y;
            }
        }
        return -1;
    }

    private static Map<ChunkKey, ChunkGenerationData> generate(
            WorldGenerator generator,
            GenerationContext context,
            List<ChunkKey> keys) {
        Map<ChunkKey, ChunkGenerationData> chunks =
                new HashMap<>();
        for (ChunkKey key : keys) {
            WorldGenerationResult result =
                    generator.generate(context, key);
            assertTrue(
                    result.succeeded(),
                    () -> "Generation failed for " + key);
            chunks.put(key, result.chunkData().orElseThrow());
        }
        return Map.copyOf(chunks);
    }

    private static List<WorldGenerationStage> visualStages() {
        HybridCaveProvider caves = new HybridCaveProvider();
        return List.of(
                new ContinuousBiomeProvider(),
                new BiomeShapedHeightProvider(),
                new DefaultStrataDensityProvider(),
                caves,
                new DefaultSurfaceProvider(),
                new CompositeDecorationProvider(
                        caves.entranceQuery()));
    }

    private static List<BlockDelta> blockDeltas(
            ChunkGenerationData before,
            ChunkGenerationData after) {
        List<BlockDelta> changes = new ArrayList<>();
        long originX =
                (long) before.key().x() * 16L;
        long originZ =
                (long) before.key().z() * 16L;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < before.worldHeight(); y++) {
                    byte oldBlock = before.getBlock(x, y, z);
                    byte newBlock = after.getBlock(x, y, z);
                    if (oldBlock != newBlock) {
                        changes.add(
                                new BlockDelta(
                                        originX + x,
                                        y,
                                        originZ + z,
                                        oldBlock,
                                        newBlock));
                    }
                }
            }
        }
        return List.copyOf(changes);
    }

    private static List<WorldBlockValue> blocksWithin(
            Map<ChunkKey, ChunkGenerationData> chunks,
            int minimumX,
            int maximumX,
            int minimumY,
            int maximumY,
            int minimumZ,
            int maximumZ,
            Set<Byte> included) {
        List<WorldBlockValue> blocks = new ArrayList<>();
        for (int z = minimumZ; z <= maximumZ; z++) {
            for (int x = minimumX; x <= maximumX; x++) {
                for (int y = minimumY; y <= maximumY; y++) {
                    byte block = blockAt(chunks, x, y, z);
                    if (included.contains(block)) {
                        blocks.add(new WorldBlockValue(x, y, z, block));
                    }
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static Map<ChunkKey, GenerationRegion> maskedTreeRegions(
            List<ChunkKey> keys) {
        Map<ChunkKey, GenerationRegion> regions = new HashMap<>();
        for (ChunkKey key : keys) {
            regions.put(
                    key,
                    new GenerationRegion(
                            key,
                            GameConfig.Chunk.MAX_HEIGHT,
                            STONE));
        }
        for (int z = 31; z <= 35; z++) {
            for (int x = -35; x <= -31; x++) {
                ChunkKey key = ChunkKey.fromWorld(x, z);
                GenerationRegion region = regions.get(key);
                for (int y = 34; y <= 42; y++) {
                    region.writeBlock(
                            ChunkKey.localCoordinate(x),
                            y,
                            ChunkKey.localCoordinate(z),
                            AIR);
                }
            }
        }
        return Map.copyOf(regions);
    }

    private static GenerationRegion maskedTreeColumnRegion(
            long worldX,
            long worldZ,
            int minimumY,
            int maximumY) {
        ChunkKey key =
                new ChunkKey(
                        Math.toIntExact(
                                Math.floorDiv(
                                        worldX,
                                        GameConfig.Chunk.SIZE)),
                        Math.toIntExact(
                                Math.floorDiv(
                                        worldZ,
                                        GameConfig.Chunk.SIZE)));
        GenerationRegion region =
                new GenerationRegion(
                        key,
                        GameConfig.Chunk.MAX_HEIGHT,
                        STONE);
        for (int y = minimumY; y <= maximumY; y++) {
            region.writeBlock(
                    region.localX(worldX),
                    y,
                    region.localZ(worldZ),
                    AIR);
        }
        return region;
    }

    private static String canonicalWrites(
            List<WorldBlockValue> writes) {
        return writes.stream()
                .map(
                        write ->
                                write.x()
                                        + ":"
                                        + write.y()
                                        + ":"
                                        + write.z()
                                        + ":"
                                        + Byte.toUnsignedInt(write.block()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String columnHash(
            ChunkGenerationData chunk, int worldX, int worldZ) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            int localX = ChunkKey.localCoordinate(worldX);
            int localZ = ChunkKey.localCoordinate(worldZ);
            for (int y = 0; y < chunk.worldHeight(); y++) {
                digest.update(chunk.getBlock(localX, y, localZ));
            }
            return java.util.HexFormat.of()
                    .formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static List<WorldHorizontal> generatedHorizontalWrites(
            GenerationRegion region, byte block) {
        long originX = region.worldOriginX();
        long originZ = region.worldOriginZ();
        List<WorldHorizontal> writes = new ArrayList<>();
        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                if (region.getBlock(localX, 0, localZ) == block) {
                    writes.add(
                            new WorldHorizontal(
                                    originX + localX,
                                    originZ + localZ));
                }
            }
        }
        return List.copyOf(writes);
    }

    private static byte blockAt(
            Map<ChunkKey, ChunkGenerationData> chunks,
            int worldX,
            int y,
            int worldZ) {
        ChunkGenerationData chunk =
                chunks.get(ChunkKey.fromWorld(worldX, worldZ));
        return chunk.getBlock(
                ChunkKey.localCoordinate(worldX),
                y,
                ChunkKey.localCoordinate(worldZ));
    }

    private static GenerationContext context(
            WorldGenerationConfig config,
            GenerationBlockPalette palette) {
        return new GenerationContext(
                config,
                palette,
                new DeterministicCoordinateSampler(
                        config.seed(), config.algorithmVersion()));
    }

    private record ColumnOracle(
            int worldX,
            int worldZ,
            int height,
            int strataY,
            int caveY,
            byte caveBlock) {
        private String location() {
            return worldX + "," + worldZ;
        }
    }

    private record WorldBlock(int x, int y, int z) {
    }

    private record BlockDelta(
            long x, int y, long z, byte oldBlock, byte newBlock) {
    }

    private record WorldHorizontal(long x, long z) {
    }

    private record EdgeAnchorOracle(
            long regionX,
            long regionZ,
            long worldX,
            long worldZ,
            ChunkKey owner,
            ChunkKey requested,
            Set<WorldHorizontal> clippedWrites) {
    }

    private record WorldBlockValue(
            int x, int y, int z, byte block) {
    }

    private static final List<BlockDelta> VISUAL_SEAM_CORRECTION =
            List.of(
                    new BlockDelta(25L, 32, -33L, (byte) 2, STONE),
                    new BlockDelta(25L, 33, -33L, (byte) 2, STONE),
                    new BlockDelta(25L, 34, -33L, (byte) 2, STONE),
                    new BlockDelta(25L, 35, -33L, (byte) 1, STONE),
                    new BlockDelta(29L, 35, -33L, (byte) 2, STONE),
                    new BlockDelta(29L, 36, -33L, (byte) 2, STONE),
                    new BlockDelta(29L, 37, -33L, (byte) 2, STONE),
                    new BlockDelta(29L, 38, -33L, (byte) 1, STONE),
                    new BlockDelta(30L, 36, -33L, (byte) 2, STONE),
                    new BlockDelta(30L, 37, -33L, (byte) 2, STONE),
                    new BlockDelta(30L, 38, -33L, (byte) 2, STONE),
                    new BlockDelta(30L, 39, -33L, (byte) 1, STONE));

    private static final String EXPECTED_TREE_WRITES =
            "-35:38:31:5;-34:37:31:5;-34:38:31:5;-33:37:31:5;-33:38:31:5;-32:37:31:5;-32:38:31:5;-31:38:31:5;"
                    + "-35:37:32:5;-35:38:32:5;-34:37:32:5;-34:38:32:5;-34:39:32:5;-33:37:32:5;-33:38:32:5;-33:39:32:5;-32:37:32:5;-32:38:32:5;-31:37:32:5;-31:38:32:5;"
                    + "-35:37:33:5;-35:38:33:5;-34:37:33:5;-34:38:33:5;-34:39:33:5;-33:34:33:4;-33:35:33:4;-33:36:33:4;-33:37:33:4;-33:38:33:4;-33:39:33:4;-33:40:33:5;-32:37:33:5;-32:38:33:5;-32:39:33:5;-31:37:33:5;-31:38:33:5;"
                    + "-35:37:34:5;-35:38:34:5;-34:37:34:5;-34:38:34:5;-34:39:34:5;-33:37:34:5;-33:38:34:5;-33:39:34:5;-32:37:34:5;-32:38:34:5;-32:39:34:5;-31:37:34:5;-31:38:34:5;"
                    + "-35:37:35:5;-35:38:35:5;-34:37:35:5;-34:38:35:5;-33:37:35:5;-33:38:35:5;-32:37:35:5;-32:38:35:5";
}
