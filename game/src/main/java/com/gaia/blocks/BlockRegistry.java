package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.BlockRenderResolver;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
}
