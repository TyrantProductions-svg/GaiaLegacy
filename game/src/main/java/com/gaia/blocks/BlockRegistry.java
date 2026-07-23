package com.gaia.blocks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.BlockRenderResolver;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class BlockRegistry implements BlockRenderResolver {
    private final Map<Integer, BlockDefinition> definitionsById;
    private final Map<ResourceLocation, BlockDefinition> definitionsByName;
    private final Map<Integer, BlockRenderInfo> renderInfoById;
    private final BlockRenderInfo airRenderInfo;

    private BlockRegistry(
            Map<Integer, BlockDefinition> definitionsById,
            Map<ResourceLocation, BlockDefinition> definitionsByName,
            Map<Integer, BlockRenderInfo> renderInfoById) {
        this.definitionsById = Map.copyOf(definitionsById);
        this.definitionsByName = Map.copyOf(definitionsByName);
        this.renderInfoById = Map.copyOf(renderInfoById);
        this.airRenderInfo = this.renderInfoById.get(0);
    }

    public static BlockRegistry create(
            Collection<BlockDefinition> definitions,
            Map<Integer, BlockRenderInfo> renderInfos) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(renderInfos, "renderInfos");

        Map<Integer, BlockDefinition> byId = new HashMap<>();
        Map<ResourceLocation, BlockDefinition> byName =
                new HashMap<>();
        for (BlockDefinition definition : definitions) {
            Objects.requireNonNull(definition, "block definition");
            if (byId.putIfAbsent(
                            definition.id(), definition)
                    != null) {
                throw new IllegalArgumentException(
                        "Duplicate block id: " + definition.id());
            }
            if (byName.putIfAbsent(
                            definition.name(), definition)
                    != null) {
                throw new IllegalArgumentException(
                        "Duplicate block name: "
                                + definition.name());
            }
        }
        if (!byId.containsKey(0)) {
            throw new IllegalArgumentException(
                    "Block registry requires id 0 air");
        }

        Map<Integer, BlockRenderInfo> copiedRenderInfos =
                new HashMap<>();
        for (Map.Entry<Integer, BlockRenderInfo> entry
                : renderInfos.entrySet()) {
            Integer id =
                    Objects.requireNonNull(
                            entry.getKey(), "render info id");
            BlockRenderInfo renderInfo =
                    Objects.requireNonNull(
                            entry.getValue(), "block render info");
            if (!byId.containsKey(id)) {
                throw new IllegalArgumentException(
                        "Render info has no block definition: " + id);
            }
            copiedRenderInfos.put(id, renderInfo);
        }
        for (Integer id : byId.keySet()) {
            if (!copiedRenderInfos.containsKey(id)) {
                throw new IllegalArgumentException(
                        "Missing render info for block id: " + id);
            }
        }

        return new BlockRegistry(
                byId, byName, copiedRenderInfos);
    }

    public BlockDefinition require(ResourceLocation name) {
        Objects.requireNonNull(name, "name");
        BlockDefinition definition = definitionsByName.get(name);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Unknown block name: " + name);
        }
        return definition;
    }

    public BlockDefinition require(int unsignedId) {
        BlockDefinition definition = definitionsById.get(unsignedId);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Unknown block id: " + unsignedId);
        }
        return definition;
    }

    public BlockDefinition require(byte storedId) {
        return require(Byte.toUnsignedInt(storedId));
    }

    public byte requireStoredId(ResourceLocation name) {
        return (byte) require(name).id();
    }

    @Override
    public BlockRenderInfo resolve(int unsignedBlockId) {
        return renderInfoById.getOrDefault(
                unsignedBlockId, airRenderInfo);
    }

    // Migration bridge for callers removed in Tasks 8 and 10.

    private static final Map<Byte, Block> legacyBlocks =
            new HashMap<>();
    private static byte nextModId = 100;
    private static int jsonBlockCount = 0;

    public static final Block AIR =
            registerCore(
                    BlockProperties.builder()
                            .id((byte) 0)
                            .name("air")
                            .tolerance(0)
                            .structuralIntegrity(0)
                            .hardness(0)
                            .transparent(true)
                            .build());

    public static final Block GRASS =
            registerCore(
                    BlockProperties.builder()
                            .id((byte) 1)
                            .name("grass")
                            .tolerance(3.0f)
                            .structuralIntegrity(50.0f)
                            .hardness(0.6f)
                            .flammable(false)
                            .build());

    public static final Block DIRT =
            registerCore(
                    BlockProperties.builder()
                            .id((byte) 2)
                            .name("dirt")
                            .tolerance(2.5f)
                            .structuralIntegrity(40.0f)
                            .hardness(0.5f)
                            .flammable(false)
                            .build());

    public static final Block STONE =
            registerCore(
                    BlockProperties.builder()
                            .id((byte) 3)
                            .name("stone")
                            .tolerance(6.0f)
                            .structuralIntegrity(80.0f)
                            .hardness(1.5f)
                            .flammable(false)
                            .build());

    private static Block registerCore(BlockProperties props) {
        Block block = new Block(props);
        legacyBlocks.put(props.getId(), block);
        return block;
    }

    public static void init() {
        System.out.println(
                "[BlockRegistry] Registered "
                        + legacyBlocks.size()
                        + " core blocks");
    }

    public static void loadAllFromResources() {
        try {
            URL resourceUrl =
                    BlockRegistry.class
                            .getClassLoader()
                            .getResource("blocks");
            if (resourceUrl == null) {
                System.out.println(
                        "[BlockRegistry] No blocks/ resource folder found");
                return;
            }

            Path blocksDir;
            if (resourceUrl.getProtocol().equals("file")) {
                blocksDir = Paths.get(resourceUrl.toURI());
            } else {
                System.out.println(
                        "[BlockRegistry] Cannot load blocks from JAR resources at runtime");
                return;
            }

            if (!Files.isDirectory(blocksDir)) {
                System.out.println(
                        "[BlockRegistry] blocks/ is not a directory");
                return;
            }

            Gson gson =
                    new GsonBuilder().setPrettyPrinting().create();

            try (Stream<Path> paths = Files.walk(blocksDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(
                                path ->
                                        path.toString()
                                                .endsWith(".json"))
                        .forEach(
                                path -> {
                                    try {
                                        String json =
                                                Files.readString(path);
                                        BlockProperties props =
                                                gson.fromJson(
                                                        json,
                                                        BlockProperties
                                                                .class);
                                        registerFromProperties(props);
                                        jsonBlockCount++;
                                    } catch (Exception exception) {
                                        System.err.println(
                                                "[BlockRegistry] Failed to load "
                                                        + path
                                                                .getFileName()
                                                        + ": "
                                                        + exception
                                                                .getMessage());
                                    }
                                });
            }

            System.out.println(
                    "[BlockRegistry] Loaded "
                            + legacyBlocks.size()
                            + " blocks total ("
                            + jsonBlockCount
                            + " from JSON)");
        } catch (Exception exception) {
            System.err.println(
                    "[BlockRegistry] Failed to load blocks from resources: "
                            + exception.getMessage());
        }
    }

    private static void registerFromProperties(
            BlockProperties props) {
        byte id = props.getId();
        if (id == 0) {
            id = nextModId++;
            props =
                    BlockProperties.builder()
                            .id(id)
                            .name(props.getName())
                            .tolerance(props.getTolerance())
                            .structuralIntegrity(
                                    props.getStructuralIntegrity())
                            .hardness(props.getHardness())
                            .transparent(props.isTransparent())
                            .lightLevel(props.getLightLevel())
                            .flammable(props.isFlammable())
                            .gravity(props.hasGravity())
                            .blastResistance(
                                    props.getBlastResistance())
                            .build();
        }

        Block block = new Block(props);
        legacyBlocks.put(id, block);
        System.out.println(
                "[BlockRegistry] Registered block: "
                        + props.getName()
                        + " (id="
                        + id
                        + ")");
    }
}
