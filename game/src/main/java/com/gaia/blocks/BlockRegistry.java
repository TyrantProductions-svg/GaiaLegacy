package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.BlockRenderResolver;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BlockRegistry implements BlockRenderResolver {
    private final Map<Integer, BlockDefinition> definitionsById;
    private final Map<ResourceLocation, BlockDefinition> definitionsByName;
    private final Map<ResourceLocation, ItemFormDefinition> itemsById;
    private final Map<ResourceLocation, Set<ItemCapability>> itemCapabilitiesById;
    private final Map<ResourceLocation, ItemVisualReference> itemVisualsById;
    private final Map<ResourceLocation, ResourceLocation> detailUnitsByBlock;
    private final Map<ResourceLocation, BlockDefinition> blocksByDetailUnit;
    private final Map<Integer, BlockRenderInfo> renderInfoById;
    private final BlockRenderInfo airRenderInfo;

    private BlockRegistry(
            Map<Integer, BlockDefinition> definitionsById,
            Map<ResourceLocation, BlockDefinition> definitionsByName,
            Map<ResourceLocation, ItemFormDefinition> itemsById,
            Map<ResourceLocation, Set<ItemCapability>> itemCapabilitiesById,
            Map<ResourceLocation, ItemVisualReference> itemVisualsById,
            Map<ResourceLocation, ResourceLocation> detailUnitsByBlock,
            Map<ResourceLocation, BlockDefinition> blocksByDetailUnit,
            Map<Integer, BlockRenderInfo> renderInfoById) {
        this.definitionsById = Map.copyOf(definitionsById);
        this.definitionsByName = Map.copyOf(definitionsByName);
        this.itemsById = Map.copyOf(itemsById);
        this.itemCapabilitiesById = Map.copyOf(itemCapabilitiesById);
        this.itemVisualsById = Map.copyOf(itemVisualsById);
        this.detailUnitsByBlock = Map.copyOf(detailUnitsByBlock);
        this.blocksByDetailUnit = Map.copyOf(blocksByDetailUnit);
        this.renderInfoById = Map.copyOf(renderInfoById);
        this.airRenderInfo = this.renderInfoById.get(0);
    }

    public static BlockRegistry create(
            Collection<BlockDefinition> definitions,
            Map<Integer, BlockRenderInfo> renderInfos) {
        return create(definitions, Set.of(), renderInfos);
    }

    public static BlockRegistry create(
            Collection<BlockDefinition> definitions,
            Collection<StandaloneItemDefinition> standaloneItems,
            Map<Integer, BlockRenderInfo> renderInfos) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(standaloneItems, "standaloneItems");
        Objects.requireNonNull(renderInfos, "renderInfos");

        Map<Integer, BlockDefinition> byId = new HashMap<>();
        Map<ResourceLocation, BlockDefinition> byName =
                new HashMap<>();
        Map<ResourceLocation, ItemFormDefinition> byItemId = new HashMap<>();
        Map<ResourceLocation, Set<ItemCapability>> capabilitiesByItemId =
                new HashMap<>();
        Map<ResourceLocation, ItemVisualReference> visualsByItemId =
                new HashMap<>();
        Set<ResourceLocation> standaloneItemIds = new java.util.HashSet<>();
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
            ItemFormDefinition item = definition.item();
            if (item != null && byItemId.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException(
                        "Duplicate item form id: " + item.id());
            }
        }
        for (StandaloneItemDefinition standaloneItem : standaloneItems) {
            Objects.requireNonNull(standaloneItem, "standalone item");
            ItemFormDefinition form = standaloneItem.form();
            if (byItemId.putIfAbsent(form.id(), form) != null) {
                throw new IllegalArgumentException(
                        "Duplicate item form id: " + form.id());
            }
            capabilitiesByItemId.put(
                    form.id(), standaloneItem.capabilities());
            visualsByItemId.put(form.id(), standaloneItem.visual());
            standaloneItemIds.add(form.id());
        }
        Map<ResourceLocation, ResourceLocation> detailUnitsByBlock =
                new HashMap<>();
        Map<ResourceLocation, BlockDefinition> blocksByDetailUnit =
                new HashMap<>();
        for (BlockDefinition definition : definitions) {
            DetailSupportDefinition support = definition.detailSupport();
            if (support == null) {
                continue;
            }
            ResourceLocation unitItem = support.unitItem();
            ItemFormDefinition unitForm = byItemId.get(unitItem);
            if (unitForm == null) {
                throw new IllegalArgumentException(
                        "Unknown detail-unit item: " + unitItem);
            }
            if (!standaloneItemIds.contains(unitItem)) {
                throw new IllegalArgumentException(
                        "Detail-unit item must be standalone: " + unitItem);
            }
            if (unitForm.maxStackSize() != 64) {
                throw new IllegalArgumentException(
                        "Detail-unit item requires max stack 64: " + unitItem);
            }
            BlockDefinition previous =
                    blocksByDetailUnit.putIfAbsent(unitItem, definition);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate detail-unit mapping: "
                                + unitItem
                                + " for "
                                + previous.name()
                                + " and "
                                + definition.name());
            }
            detailUnitsByBlock.put(definition.name(), unitItem);
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
                byId,
                byName,
                byItemId,
                capabilitiesByItemId,
                visualsByItemId,
                detailUnitsByBlock,
                blocksByDetailUnit,
                copiedRenderInfos);
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

    /**
     * Resolves item rules from the data-driven block registry. This is an
     * index over existing block definitions, not a second item registry.
     */
    public Optional<ItemFormDefinition> itemForm(ResourceLocation itemId) {
        return Optional.ofNullable(
                itemsById.get(Objects.requireNonNull(itemId, "itemId")));
    }

    public Set<ItemCapability> itemCapabilities(ResourceLocation itemId) {
        return itemCapabilitiesById.getOrDefault(
                Objects.requireNonNull(itemId, "itemId"), Set.of());
    }

    public Optional<ItemVisualReference> itemVisual(ResourceLocation itemId) {
        return Optional.ofNullable(
                itemVisualsById.get(
                        Objects.requireNonNull(itemId, "itemId")));
    }

    public Optional<ResourceLocation> detailUnitForBlock(ResourceLocation blockId) {
        return Optional.ofNullable(
                detailUnitsByBlock.get(
                        Objects.requireNonNull(blockId, "blockId")));
    }

    public Optional<BlockDefinition> blockForDetailUnit(ResourceLocation itemId) {
        return Optional.ofNullable(
                blocksByDetailUnit.get(
                        Objects.requireNonNull(itemId, "itemId")));
    }

    public Optional<BlockDefinition> find(ResourceLocation name) {
        return Optional.ofNullable(
                definitionsByName.get(Objects.requireNonNull(name, "name")));
    }

    /** Resolves the owning block without creating a parallel item registry. */
    public Optional<BlockDefinition> blockForItem(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        return definitionsByName.values().stream()
                .filter(definition ->
                        definition.item() != null
                                && definition.item().id().equals(itemId))
                .findFirst();
    }

    @Override
    public BlockRenderInfo resolve(int unsignedBlockId) {
        return renderInfoById.getOrDefault(
                unsignedBlockId, airRenderInfo);
    }
}
