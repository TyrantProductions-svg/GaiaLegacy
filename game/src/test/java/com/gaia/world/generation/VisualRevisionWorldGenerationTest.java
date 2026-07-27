package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.GaiaWorldGenerator;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class VisualRevisionWorldGenerationTest {
    private static final int VERSION_TWO_LOG_COUNT = 220;
    private static final int VERSION_TWO_LEAF_COUNT = 2_089;
    private static final int VERSION_TWO_ENTRANCE_SURFACE_CELLS = 304;
    private static final WorldPoint VERSION_TWO_PLAINS_TREE =
            new WorldPoint(-33, 33);
    private static final WorldPoint VERSION_TWO_HILLS_TREE =
            new WorldPoint(0, 12);
    private static final int VERSION_TWO_ENTRANCE_COMPONENTS = 10;
    private static final int VERSION_TWO_LARGEST_CAVE = 73_558;
    private static final int VERSION_TWO_MAXIMUM_DEPTH = 63;
    private static final int VERSION_TWO_MAXIMUM_CHUNK_SPAN = 23;
    private static final WorldPoint3 VERSION_TWO_DEEPEST_CAVE =
            new WorldPoint3(67, 2, -64);
    private static final WorldPoint3 VERSION_TWO_CROSS_CHUNK_TUNNEL =
            new WorldPoint3(48, 57, -45);
    private static final int[] VERSION_TWO_OUTCROP_COLUMNS =
            {36, 1, 133};
    private static final WorldPoint VERSION_TWO_ROCKY_OUTCROP =
            new WorldPoint(-50, 8);
    private static final GenerationBlockPalette PALETTE =
            new GenerationBlockPalette(
                    (byte) 0,
                    (byte) 1,
                    (byte) 2,
                    (byte) 3,
                    (byte) 4,
                    (byte) 5);

    @Test
    void candidatePipelineProducesGroundedOakTreesAndSurfaceEntrances() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        List<ChunkGenerationData> chunks =
                generate(defaultKeys(), config);

        int logs = 0;
        int leaves = 0;
        int entrances = 0;
        List<WorldPoint> entranceCells = new ArrayList<>();
        List<WorldPoint> treeRoots = new ArrayList<>();
        HeightProvider heights = new BiomeShapedHeightProvider();
        BiomeProvider biomes = new ContinuousBiomeProvider();
        GenerationContext context = context(config);

        for (ChunkGenerationData chunk : chunks) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = chunk.key().worldOriginX() + x;
                    int worldZ = chunk.key().worldOriginZ() + z;
                    int surface =
                            heights.sampleHeight(
                                    context,
                                    worldX,
                                    worldZ,
                                    biomes.sample(
                                            context, worldX, worldZ));
                    if (chunk.getBlock(x, surface, z) == PALETTE.air()) {
                        entrances++;
                        entranceCells.add(
                                new WorldPoint(worldX, worldZ));
                    }
                    if (surface + 1 < chunk.worldHeight()
                            && chunk.getBlock(x, surface + 1, z)
                                    == PALETTE.oakLog()) {
                        treeRoots.add(new WorldPoint(worldX, worldZ));
                        assertTrue(
                                chunk.getBlock(x, surface, z)
                                        != PALETTE.air(),
                                "tree root is floating above cave air");
                    }
                    for (int y = 0; y < chunk.worldHeight(); y++) {
                        byte block = chunk.getBlock(x, y, z);
                        logs += block == PALETTE.oakLog() ? 1 : 0;
                        leaves += block == PALETTE.oakLeaves() ? 1 : 0;
                    }
                }
            }
        }

        assertTrue(logs >= 40, "candidate world contains too few tree logs");
        assertTrue(
                leaves > logs,
                "every tree needs a visible canopy, not bare trunks");
        assertTrue(
                entrances >= 2,
                "candidate world needs at least two explicit entrances");
        for (WorldPoint root : treeRoots) {
            assertTrue(
                    entranceCells.stream()
                            .noneMatch(
                                    entrance ->
                                            root.distanceSquared(entrance)
                                                    <= 16L),
                    "tree root overlaps cave entrance clearance");
        }
        WorldPoint plainsTree =
                firstTree(
                        treeRoots,
                        biomes,
                        context,
                        BiomeType.PLAINS);
        WorldPoint hillsTree =
                firstTree(
                        treeRoots,
                        biomes,
                        context,
                        BiomeType.ROLLING_HILLS);
        System.out.printf(
                "PHASE4_CANDIDATE trees.logs=%d trees.leaves=%d "
                        + "entranceSurfaceCells=%d plainsTree=%s "
                        + "hillsTree=%s%n",
                logs,
                leaves,
                entrances,
                plainsTree,
                hillsTree);
        assertEquals(VERSION_TWO_LOG_COUNT, logs);
        assertEquals(VERSION_TWO_LEAF_COUNT, leaves);
        assertEquals(VERSION_TWO_ENTRANCE_SURFACE_CELLS, entrances);
        assertEquals(VERSION_TWO_PLAINS_TREE, plainsTree);
        assertEquals(VERSION_TWO_HILLS_TREE, hillsTree);
    }

    @Test
    void everyTreeTopLogIsCoveredByALeaf() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        int trees = 0;

        for (ChunkGenerationData chunk :
                generate(defaultKeys(), config)) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = chunk.key().worldOriginX() + x;
                    int worldZ = chunk.key().worldOriginZ() + z;
                    int surface =
                            heights.sampleHeight(
                                    context,
                                    worldX,
                                    worldZ,
                                    biomes.sample(
                                            context, worldX, worldZ));
                    int firstLog = surface + 1;
                    if (firstLog >= chunk.worldHeight()
                            || chunk.getBlock(x, firstLog, z)
                                    != PALETTE.oakLog()) {
                        continue;
                    }
                    int topLog = firstLog;
                    while (topLog + 1 < chunk.worldHeight()
                            && chunk.getBlock(x, topLog + 1, z)
                                    == PALETTE.oakLog()) {
                        topLog++;
                    }
                    assertTrue(
                            topLog + 1 < chunk.worldHeight(),
                            "tree top exceeds world height at "
                                    + worldX
                                    + ","
                                    + worldZ);
                    assertEquals(
                            PALETTE.oakLeaves(),
                            chunk.getBlock(x, topLog + 1, z),
                            "visible top log at "
                                    + worldX
                                    + ","
                                    + worldZ);
                    trees++;
                }
            }
        }
        assertTrue(trees > 0, "fixed region must contain trees");
    }

    @Test
    void candidateHeightProfilesHaveDistinctRelief() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        HeightProvider heights = new BiomeShapedHeightProvider();

        int plainsRange =
                range(heights, context, new BiomeSample(1.0, 0.0, 0.0));
        int hillsRange =
                range(heights, context, new BiomeSample(0.0, 1.0, 0.0));
        int highlandsRange =
                range(heights, context, new BiomeSample(0.0, 0.0, 1.0));

        assertTrue(plainsRange <= 8);
        assertTrue(hillsRange >= plainsRange + 6);
        assertTrue(highlandsRange >= hillsRange + 6);
    }

    @Test
    void candidateRollingHillsSpawnReserveKeepsGrassSurface() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        ChunkGenerationData origin =
                generate(List.of(new ChunkKey(0, 0)), config)
                        .get(0);
        int surface =
                heights.sampleHeight(
                        context,
                        0,
                        0,
                        biomes.sample(context, 0, 0));

        assertEquals(PALETTE.grass(), origin.getBlock(0, surface, 0));
    }

    @Test
    void candidateHashDoesNotDependOnChunkOrder() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        List<ChunkKey> forward = defaultKeys();
        List<ChunkKey> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        List<ChunkKey> shuffled = new ArrayList<>(forward);
        Collections.rotate(shuffled, 37);

        String forwardHash =
                WorldGenerationHasher.hashRegion(
                        config, generate(forward, config));
        String reverseHash =
                WorldGenerationHasher.hashRegion(
                        config, generate(reverse, config));
        String shuffledHash =
                WorldGenerationHasher.hashRegion(
                        config, generate(shuffled, config));
        String concurrentHash =
                WorldGenerationHasher.hashRegion(
                        config, generateConcurrent(shuffled, config));
        assertEquals(forwardHash, reverseHash);
        assertEquals(forwardHash, shuffledHash);
        assertEquals(forwardHash, concurrentHash);
        System.out.println(
                "PHASE4_CANDIDATE aggregateHash=" + forwardHash);
        System.out.println(
                "PHASE4_CANDIDATE configFingerprint="
                        + sha256(config.canonicalFingerprintInput()));
    }

    @Test
    void candidateCavesHaveReachableDeepCrossChunkEntrances() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        CaveStats stats =
                analyzeCaves(generate(defaultKeys(), config), config);

        assertTrue(stats.entranceComponents() >= 2);
        assertTrue(stats.largestReachableComponent() >= 500);
        assertTrue(stats.maximumEntranceDepth() >= 10);
        assertTrue(stats.maximumChunksReached() >= 2);
        System.out.printf(
                "PHASE4_CANDIDATE entranceComponents=%d "
                        + "largestReachable=%d maximumDepth=%d "
                        + "maximumChunks=%d entrances=%s deepest=%s "
                        + "boundaryAir=%s%n",
                stats.entranceComponents(),
                stats.largestReachableComponent(),
                stats.maximumEntranceDepth(),
                stats.maximumChunksReached(),
                stats.entrances(),
                stats.deepest(),
                stats.boundaryAir());
        assertEquals(
                VERSION_TWO_ENTRANCE_COMPONENTS,
                stats.entranceComponents());
        assertEquals(
                VERSION_TWO_LARGEST_CAVE,
                stats.largestReachableComponent());
        assertEquals(
                VERSION_TWO_MAXIMUM_DEPTH,
                stats.maximumEntranceDepth());
        assertEquals(
                VERSION_TWO_MAXIMUM_CHUNK_SPAN,
                stats.maximumChunksReached());
        assertEquals(VERSION_TWO_DEEPEST_CAVE, stats.deepest());
        assertEquals(
                VERSION_TWO_CROSS_CHUNK_TUNNEL,
                stats.boundaryAir());
    }

    @Test
    void tunnelSurfaceProtectionAllowsOnlyExplicitEntranceSteps() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        int surface =
                heights.sampleHeight(
                        context,
                        32,
                        32,
                        biomes.sample(context, 32, 32));

        assertTrue(
                HybridCaveProvider.tunnelCellAllowed(
                        context, 32, surface, 32, 0));
        assertTrue(
                !HybridCaveProvider.tunnelCellAllowed(
                        context, 32, surface, 32, 20));
        assertTrue(
                HybridCaveProvider.tunnelCellAllowed(
                        context,
                        32,
                        surface - config.cave().surfaceBuffer(),
                        32,
                        20));
    }

    @Test
    void candidateGenerationHandlesRepresentableWorldEdges() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        int maximumChunk =
                Math.floorDiv(Integer.MAX_VALUE, 16);
        int minimumChunk =
                Math.floorDiv(Integer.MIN_VALUE, 16);

        List<ChunkGenerationData> chunks =
                generate(
                        List.of(
                                new ChunkKey(maximumChunk, 0),
                                new ChunkKey(minimumChunk, 0)),
                        config);

        assertEquals(2, chunks.size());
        assertEquals(
                new ChunkKey(maximumChunk, 0),
                chunks.get(0).key());
        assertEquals(
                new ChunkKey(minimumChunk, 0),
                chunks.get(1).key());
    }

    @Test
    void candidateOutcropsAreSparseAndBiomeWeighted() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        List<ChunkGenerationData> chunks =
                generate(defaultKeys(), config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();
        int[] outcropColumns = new int[3];
        WorldPoint[] firstOutcrop = new WorldPoint[3];
        Set<WorldPoint> outcropCells = new HashSet<>();

        for (ChunkGenerationData chunk : chunks) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = chunk.key().worldOriginX() + x;
                    int worldZ = chunk.key().worldOriginZ() + z;
                    BiomeSample biome =
                            biomes.sample(context, worldX, worldZ);
                    int surface =
                            heights.sampleHeight(
                                    context,
                                    worldX,
                                    worldZ,
                                    biome);
                    boolean outcrop = false;
                    for (int y = surface + 1;
                            y
                                    <= Math.min(
                                            surface + 6,
                                            chunk.worldHeight() - 1);
                            y++) {
                        outcrop |=
                                chunk.getBlock(x, y, z)
                                        == PALETTE.stone();
                    }
                    if (outcrop) {
                        outcropCells.add(
                                new WorldPoint(worldX, worldZ));
                        int dominant = biome.dominant().ordinal();
                        outcropColumns[dominant]++;
                        if (firstOutcrop[dominant] == null) {
                            firstOutcrop[dominant] =
                                    new WorldPoint(worldX, worldZ);
                        }
                    }
                }
            }
        }

        int total =
                outcropColumns[0]
                        + outcropColumns[1]
                        + outcropColumns[2];
        int largest = largestHorizontalComponent(outcropCells);
        
        // Print values to file for debugging
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/gaia_outcrops.txt"),
                    "outcropColumns = " + java.util.Arrays.toString(outcropColumns) + "\n"
                            + "total = " + total + "\n"
                            + "largestHorizontalComponent = " + largest + "\n"
                            + "firstOutcrop = " + java.util.Arrays.toString(firstOutcrop) + "\n");
        } catch (Exception e) {
            // ignore
        }
        
        assertTrue(total <= 200, "outcrops did not fall by at least 75%: total=" + total);
        assertTrue(
                outcropColumns[0] <= 50,
                "plains should be almost clear: "
                        + java.util.Arrays.toString(outcropColumns));
        assertTrue(
                outcropColumns[2] > outcropColumns[0],
                "rocky highlands should own the visible outcrops");
        assertTrue(
                largest <= 15,
                "outcrop footprints must remain within two to five columns: " + largest);
        System.out.println(
                "PHASE4_CANDIDATE outcropColumns="
                        + java.util.Arrays.toString(outcropColumns)
                        + " coordinates="
                        + java.util.Arrays.toString(firstOutcrop));
        assertTrue(
                java.util.Arrays.equals(
                        VERSION_TWO_OUTCROP_COLUMNS,
                        outcropColumns));
        assertEquals(
                VERSION_TWO_ROCKY_OUTCROP,
                firstOutcrop[BiomeType.ROCKY_HIGHLANDS.ordinal()]);
    }

    private static int range(
            HeightProvider heights,
            GenerationContext context,
            BiomeSample biome) {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int z = -64; z < 80; z += 2) {
            for (int x = -64; x < 80; x += 2) {
                int value =
                        heights.sampleHeight(
                                context, x, z, biome);
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        return maximum - minimum;
    }

    private static WorldPoint firstTree(
            List<WorldPoint> roots,
            BiomeProvider biomes,
            GenerationContext context,
            BiomeType type) {
        return roots.stream()
                .filter(
                        root ->
                                biomes.sample(
                                                        context,
                                                        root.x(),
                                                        root.z())
                                                .dominant()
                                        == type)
                .min(
                        Comparator.comparingLong(
                                        (WorldPoint root) ->
                                                root.distanceSquared(
                                                        new WorldPoint(
                                                                0, 0)))
                                .thenComparingInt(WorldPoint::x)
                                .thenComparingInt(WorldPoint::z))
                .orElse(null);
    }

    private static List<ChunkGenerationData> generate(
            List<ChunkKey> keys,
            WorldGenerationConfig config) {
        WorldGenerator generator =
                GaiaWorldGenerator.createVisualRevisionCandidate();
        GenerationContext context = context(config);
        List<ChunkGenerationData> chunks = new ArrayList<>();
        for (ChunkKey key : keys) {
            chunks.add(
                    generator.generate(context, key)
                            .chunkData()
                            .orElseThrow());
        }
        return chunks;
    }

    private static List<ChunkGenerationData> generateConcurrent(
            List<ChunkKey> keys,
            WorldGenerationConfig config) {
        WorldGenerator generator =
                GaiaWorldGenerator.createVisualRevisionCandidate();
        GenerationContext context = context(config);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ChunkGenerationData>> tasks =
                    keys.stream()
                            .map(
                                    key ->
                                            (Callable<ChunkGenerationData>)
                                                    () ->
                                                            generator.generate(
                                                                            context,
                                                                            key)
                                                                    .chunkData()
                                                                    .orElseThrow())
                            .toList();
            List<ChunkGenerationData> chunks = new ArrayList<>();
            for (Future<ChunkGenerationData> result :
                    executor.invokeAll(tasks)) {
                chunks.add(result.get());
            }
            return chunks;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            executor.shutdownNow();
        }
    }

    private static int largestHorizontalComponent(
            Set<WorldPoint> cells) {
        Set<WorldPoint> remaining = new HashSet<>(cells);
        int largest = 0;
        while (!remaining.isEmpty()) {
            WorldPoint start = remaining.iterator().next();
            remaining.remove(start);
            List<WorldPoint> queue = new ArrayList<>();
            queue.add(start);
            int size = 0;
            for (int index = 0; index < queue.size(); index++) {
                WorldPoint point = queue.get(index);
                size++;
                for (WorldPoint neighbor :
                        List.of(
                                new WorldPoint(point.x() - 1, point.z()),
                                new WorldPoint(point.x() + 1, point.z()),
                                new WorldPoint(point.x(), point.z() - 1),
                                new WorldPoint(point.x(), point.z() + 1))) {
                    if (remaining.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            largest = Math.max(largest, size);
        }
        return largest;
    }

    private static GenerationContext context(
            WorldGenerationConfig config) {
        return new GenerationContext(
                config,
                PALETTE,
                new DeterministicCoordinateSampler(
                        config.seed(), config.algorithmVersion()));
    }

    private static List<ChunkKey> defaultKeys() {
        List<ChunkKey> keys = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                keys.add(new ChunkKey(x, z));
            }
        }
        return keys;
    }

    private static CaveStats analyzeCaves(
            List<ChunkGenerationData> chunks,
            WorldGenerationConfig config) {
        int width = 9 * 16;
        int origin = -4 * 16;
        int worldHeight = chunks.get(0).worldHeight();
        byte[] underground =
                new byte[width * width * worldHeight];
        int[] surfaces = new int[width * width];
        GenerationContext context = context(config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BiomeShapedHeightProvider();

        for (ChunkGenerationData chunk : chunks) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = chunk.key().worldOriginX() + x;
                    int worldZ = chunk.key().worldOriginZ() + z;
                    int flatX = worldX - origin;
                    int flatZ = worldZ - origin;
                    int surface =
                            heights.sampleHeight(
                                    context,
                                    worldX,
                                    worldZ,
                                    biomes.sample(
                                            context, worldX, worldZ));
                    surfaces[flatX + width * flatZ] = surface;
                    for (int y = 0; y <= surface; y++) {
                        if (chunk.getBlock(x, y, z)
                                == PALETTE.air()) {
                            underground[
                                            flatX
                                                    + width
                                                            * (flatZ
                                                                    + width
                                                                            * y)] =
                                    1;
                        }
                    }
                }
            }
        }

        byte[] visited = new byte[underground.length];
        int[] queue = new int[underground.length];
        int entranceComponents = 0;
        int largest = 0;
        int maximumDepth = 0;
        int maximumChunks = 0;
        List<WorldPoint3> entranceCoordinates = new ArrayList<>();
        WorldPoint3 deepest = null;
        WorldPoint3 boundaryAir = null;
        for (int start = 0; start < underground.length; start++) {
            if (underground[start] == 0 || visited[start] != 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = 1;
            int size = 0;
            int depth = 0;
            boolean entrance = false;
            WorldPoint3 componentEntrance = null;
            WorldPoint3 componentDeepest = null;
            WorldPoint3 componentBoundary = null;
            Set<ChunkKey> reached = new HashSet<>();
            while (head < tail) {
                int index = queue[head++];
                int y = index / (width * width);
                int remainder = index - y * width * width;
                int z = remainder / width;
                int x = remainder - z * width;
                int surface = surfaces[x + width * z];
                if (y == surface && componentEntrance == null) {
                    componentEntrance =
                            new WorldPoint3(
                                    origin + x, y, origin + z);
                }
                entrance |= componentEntrance != null;
                int localDepth = surface - y;
                if (localDepth > depth) {
                    depth = localDepth;
                    componentDeepest =
                            new WorldPoint3(
                                    origin + x, y, origin + z);
                }
                int worldX = origin + x;
                int worldZ = origin + z;
                if (componentBoundary == null
                        && (Math.floorMod(worldX, 16) == 0
                                || Math.floorMod(worldZ, 16) == 0)) {
                    componentBoundary =
                            new WorldPoint3(worldX, y, worldZ);
                }
                reached.add(
                        ChunkKey.fromWorld(
                                origin + x, origin + z));
                size++;
                tail =
                        enqueue(
                                x - 1,
                                y,
                                z,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
                tail =
                        enqueue(
                                x + 1,
                                y,
                                z,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
                tail =
                        enqueue(
                                x,
                                y,
                                z - 1,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
                tail =
                        enqueue(
                                x,
                                y,
                                z + 1,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
                tail =
                        enqueue(
                                x,
                                y - 1,
                                z,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
                tail =
                        enqueue(
                                x,
                                y + 1,
                                z,
                                width,
                                worldHeight,
                                underground,
                                visited,
                                queue,
                                tail);
            }
            if (entrance) {
                entranceComponents++;
                largest = Math.max(largest, size);
                maximumDepth = Math.max(maximumDepth, depth);
                maximumChunks =
                        Math.max(maximumChunks, reached.size());
                entranceCoordinates.add(componentEntrance);
                if (depth >= maximumDepth) {
                    deepest = componentDeepest;
                }
                if (componentBoundary != null) {
                    boundaryAir = componentBoundary;
                }
            }
        }
        return new CaveStats(
                entranceComponents,
                largest,
                maximumDepth,
                maximumChunks,
                List.copyOf(entranceCoordinates),
                deepest,
                boundaryAir);
    }

    private static int enqueue(
            int x,
            int y,
            int z,
            int width,
            int worldHeight,
            byte[] underground,
            byte[] visited,
            int[] queue,
            int tail) {
        if (x < 0
                || x >= width
                || z < 0
                || z >= width
                || y < 0
                || y >= worldHeight) {
            return tail;
        }
        int index = x + width * (z + width * y);
        if (underground[index] != 0 && visited[index] == 0) {
            visited[index] = 1;
            queue[tail++] = index;
        }
        return tail;
    }

    private record CaveStats(
            int entranceComponents,
            int largestReachableComponent,
            int maximumEntranceDepth,
            int maximumChunksReached,
            List<WorldPoint3> entrances,
            WorldPoint3 deepest,
            WorldPoint3 boundaryAir) {}

    private record WorldPoint(int x, int z) {
        private long distanceSquared(WorldPoint other) {
            long dx = (long) x - other.x;
            long dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }
    }

    private record WorldPoint3(int x, int y, int z) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}