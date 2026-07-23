package com.gaia.assets;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.AssetSource;
import com.overlord.assets.ResourceIndex;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderAssets;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.texture.TextureImageLoader;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

public final class GaiaResourceLoader {
    private final AssetManager assetManager;

    public GaiaResourceLoader(AssetManager assetManager) {
        this.assetManager =
                Objects.requireNonNull(assetManager, "assetManager");
    }

    public GaiaAssetCatalog load() {
        AssetLoadReport.Builder diagnostics =
                AssetLoadReport.builder();
        LoadContext context = new LoadContext();

        List<AssetSource> indexSources =
                assetManager.discoverResourceIndexes();
        List<ResourceIndex> indexes =
                parseIndexes(
                        indexSources, diagnostics, context);
        diagnostics.throwIfErrors();

        List<TextureAtlasMetadata> atlases =
                parseAtlases(indexes, diagnostics, context);
        List<MaterialDefinition> materials =
                parseMaterials(indexes, diagnostics, context);
        List<BlockDefinition> blocks =
                parseBlocks(indexes, diagnostics, context);
        diagnostics.throwIfErrors();

        GaiaAssetCatalog catalog =
                resolve(
                        indexes,
                        atlases,
                        materials,
                        blocks,
                        diagnostics,
                        context);
        diagnostics.throwIfErrors();
        return catalog;
    }

    private List<ResourceIndex> parseIndexes(
            List<AssetSource> indexSources,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        List<AssetSource> sorted =
                indexSources.stream().sorted().toList();
        List<ResourceIndex> indexes = new ArrayList<>();
        Map<String, DefinitionSource> namespaces =
                new HashMap<>();

        for (AssetSource indexSource : sorted) {
            String source = indexSource.classpathPath();
            ResourceLocation resource =
                    fromClasspathPath(source);
            try {
                String json;
                try (InputStream input = indexSource.open()) {
                    json =
                            new String(
                                    input.readAllBytes(),
                                    StandardCharsets.UTF_8);
                }
                JsonObject root = requireRoot(json, source);
                StrictJson strict = new StrictJson(root, source);
                strict.requireOnly(
                        "namespace",
                        "blocks",
                        "materials",
                        "atlases");
                String namespace =
                        strict.requireString("namespace");
                ResourceLocation.of(namespace, "probe");
                ResourceIndex index =
                        new ResourceIndex(
                                namespace,
                                requireStringList(
                                        root,
                                        "blocks",
                                        source),
                                requireStringList(
                                        root,
                                        "materials",
                                        source),
                                requireStringList(
                                        root,
                                        "atlases",
                                        source));
                DefinitionSource current =
                        new DefinitionSource(
                                source, resource, namespace);
                if (namespaces.putIfAbsent(
                                namespace, current)
                        != null) {
                    addError(
                            diagnostics,
                            "ASSET_NAMESPACE_DUPLICATE",
                            current,
                            "namespace",
                            "Namespace '"
                                    + namespace
                                    + "' is declared by more than one manifest");
                    continue;
                }
                context.manifests.put(namespace, current);
                indexes.add(index);
            } catch (StrictJson.UnknownFieldException failure) {
                addJsonFailure(
                        diagnostics,
                        "ASSET_JSON_UNKNOWN_FIELD",
                        source,
                        resource,
                        failure);
            } catch (JsonParseException
                    | IllegalArgumentException failure) {
                addJsonFailure(
                        diagnostics,
                        "ASSET_JSON_INVALID",
                        source,
                        resource,
                        failure);
            } catch (IOException failure) {
                diagnostics.add(
                        new AssetDiagnostic(
                                AssetSeverity.ERROR,
                                "ASSET_IO",
                                source,
                                resource,
                                null,
                                Objects.toString(
                                        failure.getMessage(),
                                        failure.getClass()
                                                .getSimpleName()),
                                null));
            }
        }
        return List.copyOf(indexes);
    }

    private List<TextureAtlasMetadata> parseAtlases(
            List<ResourceIndex> indexes,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        List<DefinitionRequest> requests =
                definitionRequests(
                        indexes,
                        ResourceIndex::atlases,
                        "atlases",
                        diagnostics,
                        context);
        Map<ResourceLocation, TextureAtlasMetadata> byId =
                new LinkedHashMap<>();
        Map<ResourceLocation, DefinitionSource> regionOwners =
                new HashMap<>();

        for (DefinitionRequest request : requests) {
            TextureAtlasMetadata atlas =
                    readDefinition(
                            request,
                            diagnostics,
                            root ->
                                    parseAtlas(
                                            root,
                                            request,
                                            diagnostics));
            if (atlas == null) {
                continue;
            }

            boolean duplicateAtlas =
                    byId.containsKey(atlas.id());
            if (duplicateAtlas) {
                addError(
                        diagnostics,
                        "ASSET_ATLAS_ID_DUPLICATE",
                        request.sourceContext(),
                        "id",
                        "Duplicate atlas id " + atlas.id());
            }

            List<ResourceLocation> regionIds =
                    atlas.regions().keySet().stream()
                            .sorted()
                            .toList();
            for (ResourceLocation regionId : regionIds) {
                DefinitionSource existing =
                        regionOwners.putIfAbsent(
                                regionId,
                                request.sourceContext());
                if (existing != null) {
                    addError(
                            diagnostics,
                            "ASSET_ATLAS_REGION_ID_DUPLICATE",
                            request.sourceContext(),
                            "regions." + regionId,
                            "Duplicate atlas region id "
                                    + regionId);
                }
            }

            if (!duplicateAtlas) {
                byId.put(atlas.id(), atlas);
                context.atlasSources.put(
                        atlas.id(), request.sourceContext());
            }
        }
        return List.copyOf(byId.values());
    }

    private TextureAtlasMetadata parseAtlas(
            JsonObject root,
            DefinitionRequest request,
            AssetLoadReport.Builder diagnostics) {
        String source = request.source();
        StrictJson strict = new StrictJson(root, source);
        strict.requireOnly(
                "id",
                "texture",
                "width",
                "height",
                "regions");

        ResourceLocation id =
                ResourceLocation.parse(
                        strict.requireString("id"));
        if (!ownedByManifest(
                id,
                request,
                "id",
                diagnostics)) {
            return null;
        }
        ResourceLocation texture =
                ResourceLocation.parse(
                        strict.requireString("texture"));
        int width = strict.requireInt("width");
        int height = strict.requireInt("height");
        if (width <= 0 || height <= 0) {
            throw new JsonParseException(
                    source
                            + " fields 'width' and 'height' must be positive");
        }

        JsonObject regionsObject =
                strict.requireObject("regions");
        Map<ResourceLocation, TextureRegion> regions =
                new LinkedHashMap<>();
        for (String regionText :
                regionsObject.keySet().stream()
                        .sorted()
                        .toList()) {
            ResourceLocation regionId =
                    ResourceLocation.parse(regionText);
            if (!ownedByManifest(
                    regionId,
                    request,
                    "regions." + regionId,
                    diagnostics)) {
                continue;
            }

            JsonElement regionElement =
                    regionsObject.get(regionText);
            if (regionElement == null
                    || regionElement.isJsonNull()
                    || !regionElement.isJsonObject()) {
                throw new JsonParseException(
                        source
                                + " field 'regions."
                                + regionId
                                + "' must be an object");
            }
            StrictJson regionJson =
                    new StrictJson(
                            regionElement.getAsJsonObject(),
                            source
                                    + " field 'regions."
                                    + regionId
                                    + "'");
            regionJson.requireOnly(
                    "x", "y", "width", "height");
            try {
                regions.put(
                        regionId,
                        new TextureRegion(
                                regionId,
                                regionJson.requireInt("x"),
                                regionJson.requireInt("y"),
                                regionJson.requireInt("width"),
                                regionJson.requireInt("height"),
                                width,
                                height));
            } catch (IllegalArgumentException failure) {
                addError(
                        diagnostics,
                        "ASSET_ATLAS_REGION_BOUNDS",
                        request.sourceContext(),
                        "regions." + regionId,
                        Objects.toString(
                                failure.getMessage(),
                                "Invalid atlas region bounds"));
            }
        }

        return new TextureAtlasMetadata(
                id, texture, width, height, regions);
    }

    private List<MaterialDefinition> parseMaterials(
            List<ResourceIndex> indexes,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        List<DefinitionRequest> requests =
                definitionRequests(
                        indexes,
                        ResourceIndex::materials,
                        "materials",
                        diagnostics,
                        context);
        Map<ResourceLocation, MaterialDefinition> byId =
                new LinkedHashMap<>();

        for (DefinitionRequest request : requests) {
            MaterialDefinition material =
                    readDefinition(
                            request,
                            diagnostics,
                            root ->
                                    parseMaterial(
                                            root,
                                            request,
                                            diagnostics));
            if (material == null) {
                continue;
            }
            if (byId.putIfAbsent(
                            material.id(), material)
                    != null) {
                addError(
                        diagnostics,
                        "ASSET_MATERIAL_ID_DUPLICATE",
                        request.sourceContext(),
                        "id",
                        "Duplicate material id "
                                + material.id());
            } else {
                context.materialSources.put(
                        material.id(),
                        request.sourceContext());
            }
        }
        return List.copyOf(byId.values());
    }

    private MaterialDefinition parseMaterial(
            JsonObject root,
            DefinitionRequest request,
            AssetLoadReport.Builder diagnostics) {
        StrictJson strict =
                new StrictJson(root, request.source());
        strict.requireOnly(
                "id",
                "atlas",
                "renderType",
                "alphaCutoff",
                "missingRegion");

        ResourceLocation id =
                ResourceLocation.parse(
                        strict.requireString("id"));
        if (!ownedByManifest(
                id,
                request,
                "id",
                diagnostics)) {
            return null;
        }
        return new MaterialDefinition(
                id,
                ResourceLocation.parse(
                        strict.requireString("atlas")),
                RenderType.parse(
                        strict.requireString("renderType")),
                strict.requireFloat("alphaCutoff"),
                ResourceLocation.parse(
                        strict.requireString(
                                "missingRegion")));
    }

    private List<BlockDefinition> parseBlocks(
            List<ResourceIndex> indexes,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        List<DefinitionRequest> requests =
                definitionRequests(
                        indexes,
                        ResourceIndex::blocks,
                        "blocks",
                        diagnostics,
                        context);
        Map<Integer, DefinitionSource> seenIds =
                new LinkedHashMap<>();
        Map<ResourceLocation, DefinitionSource> seenNames =
                new LinkedHashMap<>();
        List<BlockDefinition> blocks = new ArrayList<>();

        for (DefinitionRequest request : requests) {
            BlockDefinition block =
                    readDefinition(
                            request,
                            diagnostics,
                            root ->
                                    parseBlock(
                                            root,
                                            request,
                                            diagnostics));
            if (block == null) {
                continue;
            }
            DefinitionSource current =
                    request.sourceContext();
            boolean duplicate = false;
            if (seenIds.putIfAbsent(
                            block.id(), current)
                    != null) {
                addError(
                        diagnostics,
                        "ASSET_BLOCK_ID_DUPLICATE",
                        current,
                        "id",
                        "Duplicate block id " + block.id());
                duplicate = true;
            }
            if (seenNames.putIfAbsent(
                            block.name(), current)
                    != null) {
                addError(
                        diagnostics,
                        "ASSET_BLOCK_NAME_DUPLICATE",
                        current,
                        "name",
                        "Duplicate block name "
                                + block.name());
                duplicate = true;
            }
            if (!duplicate) {
                blocks.add(block);
                context.blockSources.put(
                        block.name(),
                        current);
            }
        }
        return List.copyOf(blocks);
    }

    private BlockDefinition parseBlock(
            JsonObject root,
            DefinitionRequest request,
            AssetLoadReport.Builder diagnostics) {
        String source = request.source();
        StrictJson strict = new StrictJson(root, source);
        strict.requireOnly(
                "id",
                "name",
                "material",
                "textures",
                "hardness",
                "structuralIntegrity",
                "tolerance",
                "gravity",
                "flammable",
                "blastResistance",
                "item");

        int id = strict.requireInt("id");
        ResourceLocation name =
                ResourceLocation.parse(
                        strict.requireString("name"));
        if (!ownedByManifest(
                name,
                request,
                "name",
                diagnostics)) {
            return null;
        }
        ResourceLocation material =
                ResourceLocation.parse(
                        strict.requireString("material"));
        Map<BlockFace, ResourceLocation> textures =
                parseTextures(
                        strict.requireObject("textures"),
                        source);
        JsonObject itemObject = strict.optionalObject("item");
        if (id != 0 && itemObject == null) {
            throw new JsonParseException(
                    source
                            + " field 'item' is required for non-air blocks");
        }
        ItemFormDefinition item =
                itemObject == null
                        ? null
                        : parseItem(
                                itemObject, name, source);

        return new BlockDefinition(
                id,
                name,
                material,
                textures,
                strict.requireFloat("hardness"),
                strict.requireFloat(
                        "structuralIntegrity"),
                strict.requireFloat("tolerance"),
                strict.requireBoolean("gravity"),
                strict.requireBoolean("flammable"),
                strict.requireFloat("blastResistance"),
                item);
    }

    private static ItemFormDefinition parseItem(
            JsonObject itemObject,
            ResourceLocation blockName,
            String source) {
        StrictJson item =
                new StrictJson(
                        itemObject,
                        source + " field 'item'");
        item.requireOnly(
                "id",
                "maxStackSize",
                "mouthHoldable",
                "twoHanded");
        ResourceLocation itemId =
                itemObject.has("id")
                        ? ResourceLocation.parse(
                                item.requireString("id"))
                        : blockName;
        return new ItemFormDefinition(
                itemId,
                item.requireInt("maxStackSize"),
                item.requireBoolean("mouthHoldable"),
                item.requireBoolean("twoHanded"));
    }

    private static Map<BlockFace, ResourceLocation> parseTextures(
            JsonObject texturesObject, String source) {
        StrictJson textures =
                new StrictJson(
                        texturesObject,
                        source + " field 'textures'");
        textures.requireOnly(
                "all",
                "sides",
                "top",
                "bottom",
                "north",
                "south",
                "east",
                "west",
                "up",
                "down");

        EnumMap<BlockFace, ResourceLocation> faces =
                new EnumMap<>(BlockFace.class);
        applyAll(faces, texturesObject, textures, "all");
        applySides(
                faces, texturesObject, textures, "sides");
        apply(
                faces,
                BlockFace.UP,
                texturesObject,
                textures,
                "top");
        apply(
                faces,
                BlockFace.DOWN,
                texturesObject,
                textures,
                "bottom");
        apply(
                faces,
                BlockFace.NORTH,
                texturesObject,
                textures,
                "north");
        apply(
                faces,
                BlockFace.SOUTH,
                texturesObject,
                textures,
                "south");
        apply(
                faces,
                BlockFace.EAST,
                texturesObject,
                textures,
                "east");
        apply(
                faces,
                BlockFace.WEST,
                texturesObject,
                textures,
                "west");
        apply(
                faces,
                BlockFace.UP,
                texturesObject,
                textures,
                "up");
        apply(
                faces,
                BlockFace.DOWN,
                texturesObject,
                textures,
                "down");
        return faces;
    }

    private static void applyAll(
            EnumMap<BlockFace, ResourceLocation> faces,
            JsonObject object,
            StrictJson textures,
            String field) {
        if (!object.has(field)) {
            return;
        }
        ResourceLocation region =
                ResourceLocation.parse(
                        textures.requireString(field));
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
    }

    private static void applySides(
            EnumMap<BlockFace, ResourceLocation> faces,
            JsonObject object,
            StrictJson textures,
            String field) {
        if (!object.has(field)) {
            return;
        }
        ResourceLocation region =
                ResourceLocation.parse(
                        textures.requireString(field));
        faces.put(BlockFace.NORTH, region);
        faces.put(BlockFace.SOUTH, region);
        faces.put(BlockFace.EAST, region);
        faces.put(BlockFace.WEST, region);
    }

    private static void apply(
            EnumMap<BlockFace, ResourceLocation> faces,
            BlockFace face,
            JsonObject object,
            StrictJson textures,
            String field) {
        if (object.has(field)) {
            faces.put(
                    face,
                    ResourceLocation.parse(
                            textures.requireString(field)));
        }
    }

    private GaiaAssetCatalog resolve(
            List<ResourceIndex> indexes,
            List<TextureAtlasMetadata> atlases,
            List<MaterialDefinition> materials,
            List<BlockDefinition> blocks,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        Map<ResourceLocation, TextureAtlasMetadata> atlasById =
                new TreeMap<>();
        for (TextureAtlasMetadata atlas : atlases) {
            atlasById.putIfAbsent(atlas.id(), atlas);
        }
        Map<ResourceLocation, MaterialDefinition> materialById =
                new TreeMap<>();
        for (MaterialDefinition material : materials) {
            materialById.putIfAbsent(
                    material.id(), material);
        }

        ResourceLocation selectedAtlasId =
                selectBlockAtlas(
                        indexes,
                        materialById,
                        atlasById,
                        diagnostics,
                        context);
        if (blocks.stream()
                .noneMatch(block -> block.id() == 0)) {
            DefinitionSource source =
                    firstSource(indexes, context);
            addError(
                    diagnostics,
                    "ASSET_DEFINITION_NOT_FOUND",
                    source,
                    "blocks.id",
                    "Block definitions must include id 0 air");
        }
        diagnostics.throwIfErrors();
        TextureAtlasMetadata selectedAtlas =
                atlasById.get(selectedAtlasId);

        String fallbackNamespace =
                context.atlasSources
                        .get(selectedAtlasId)
                        .namespace();
        FallbackValues fallback =
                fallbackValues(
                        fallbackNamespace,
                        materialById,
                        selectedAtlas);

        Map<Integer, BlockRenderInfo> renderInfoById =
                new TreeMap<>();
        List<BlockDefinition> sortedBlocks =
                blocks.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                BlockDefinition::id)
                                        .thenComparing(
                                                BlockDefinition::name))
                        .toList();
        for (BlockDefinition block : sortedBlocks) {
            DefinitionSource source =
                    context.blockSources.get(block.name());
            MaterialDefinition material =
                    materialById.get(block.material());
            TextureAtlasMetadata materialAtlas;
            if (material == null) {
                addWarning(
                        diagnostics,
                        "ASSET_MISSING_MATERIAL",
                        source,
                        "material",
                        "Block references missing material "
                                + block.material(),
                        fallback.material().id());
                material = fallback.material();
                materialAtlas = fallback.atlas();
            } else {
                materialAtlas =
                        atlasById.get(material.atlas());
            }

            TextureRegion missingRegion =
                    materialAtlas.regions().get(
                            material.missingRegion());
            if (missingRegion == null) {
                missingRegion = fallback.region();
            }

            BlockRenderInfo renderInfo;
            if (block.id() == 0) {
                renderInfo =
                        BlockRenderInfo.nonRenderable(
                                material, missingRegion);
            } else {
                EnumMap<BlockFace, TextureRegion> faces =
                        new EnumMap<>(BlockFace.class);
                for (BlockFace face : BlockFace.values()) {
                    ResourceLocation requested =
                            block.textures().get(face);
                    TextureRegion region =
                            requested == null
                                    ? null
                                    : materialAtlas.regions()
                                            .get(requested);
                    if (region == null) {
                        String field =
                                "textures."
                                        + face.name()
                                                .toLowerCase(
                                                        Locale.ROOT);
                        addWarning(
                                diagnostics,
                                "ASSET_MISSING_REGION",
                                source,
                                field,
                                requested == null
                                        ? "Block face has no texture region"
                                        : "Block face references missing region "
                                                + requested,
                                missingRegion.id());
                        region = missingRegion;
                    }
                    faces.put(face, region);
                }
                renderInfo =
                        new BlockRenderInfo(
                                material, faces, true);
            }
            renderInfoById.put(block.id(), renderInfo);
        }

        BlockRegistry registry =
                BlockRegistry.create(
                        sortedBlocks, renderInfoById);
        TextureImage image =
                new TextureImageLoader()
                        .load(
                                assetManager,
                                selectedAtlas.texture(),
                                diagnostics::add);
        RenderAssets renderAssets = new RenderAssets(image);

        return new GaiaAssetCatalog(
                registry,
                materialById,
                atlasById,
                selectedAtlas,
                renderAssets,
                diagnostics.build());
    }

    private ResourceLocation selectBlockAtlas(
            List<ResourceIndex> indexes,
            Map<ResourceLocation, MaterialDefinition> materials,
            Map<ResourceLocation, TextureAtlasMetadata> atlases,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        if (materials.isEmpty()) {
            addError(
                    diagnostics,
                    "ASSET_DEFINITION_NOT_FOUND",
                    firstSource(indexes, context),
                    "materials",
                    "No material definitions were loaded");
            return null;
        }

        TreeSet<ResourceLocation> referencedAtlases =
                new TreeSet<>();
        for (MaterialDefinition material :
                materials.values()) {
            referencedAtlases.add(material.atlas());
        }
        ResourceLocation selected =
                referencedAtlases.first();
        if (referencedAtlases.size() > 1) {
            for (MaterialDefinition material :
                    materials.values()) {
                if (!material.atlas().equals(selected)) {
                    addError(
                            diagnostics,
                            "ASSET_MULTIPLE_BLOCK_ATLASES",
                            context.materialSources.get(
                                    material.id()),
                            "atlas",
                            "Material "
                                    + material.id()
                                    + " references "
                                    + material.atlas()
                                    + " instead of the single block atlas "
                                    + selected);
                }
            }
        }

        if (!atlases.containsKey(selected)) {
            MaterialDefinition owner =
                    materials.values().stream()
                            .filter(
                                    material ->
                                            material.atlas()
                                                    .equals(
                                                            selected))
                            .findFirst()
                            .orElseThrow();
            addError(
                    diagnostics,
                    "ASSET_DEFINITION_NOT_FOUND",
                    context.materialSources.get(owner.id()),
                    "atlas",
                    "Material references missing atlas "
                            + selected);
        }
        return selected;
    }

    private static FallbackValues fallbackValues(
            String namespace,
            Map<ResourceLocation, MaterialDefinition> materials,
            TextureAtlasMetadata selectedAtlas) {
        ResourceLocation missingId =
                ResourceLocation.of(namespace, "missing");
        MaterialDefinition material =
                materials.get(missingId);
        TextureRegion region =
                selectedAtlas.regions().get(missingId);
        if (material != null && region != null) {
            return new FallbackValues(
                    material, selectedAtlas, region);
        }

        TextureRegion guardedRegion =
                new TextureRegion(
                        missingId, 0, 0, 2, 2, 2, 2);
        TextureAtlasMetadata guardedAtlas =
                new TextureAtlasMetadata(
                        ResourceLocation.of(
                                namespace,
                                "guarded_missing"),
                        ResourceLocation.of(
                                namespace,
                                "textures/guarded_missing.png"),
                        2,
                        2,
                        Map.of(missingId, guardedRegion));
        MaterialDefinition guardedMaterial =
                new MaterialDefinition(
                        missingId,
                        guardedAtlas.id(),
                        RenderType.OPAQUE,
                        0.5f,
                        missingId);
        return new FallbackValues(
                guardedMaterial,
                guardedAtlas,
                guardedRegion);
    }

    private List<DefinitionRequest> definitionRequests(
            List<ResourceIndex> indexes,
            Function<ResourceIndex, List<String>> selector,
            String field,
            AssetLoadReport.Builder diagnostics,
            LoadContext context) {
        List<DefinitionRequest> requests =
                new ArrayList<>();
        for (ResourceIndex index : indexes) {
            DefinitionSource manifest =
                    context.manifests.get(index.namespace());
            for (String relativePath : selector.apply(index)) {
                try {
                    ResourceLocation location =
                            ResourceLocation.of(
                                    index.namespace(),
                                    relativePath);
                    requests.add(
                            new DefinitionRequest(
                                    index.namespace(),
                                    location,
                                    new DefinitionSource(
                                            location.toClasspathPath(),
                                            location,
                                            index.namespace())));
                } catch (IllegalArgumentException failure) {
                    addError(
                            diagnostics,
                            "ASSET_JSON_INVALID",
                            manifest,
                            field,
                            manifest.source()
                                    + " field '"
                                    + field
                                    + "' has unsafe definition path '"
                                    + relativePath
                                    + "'");
                }
            }
        }
        requests.sort(
                Comparator.comparing(
                        DefinitionRequest::source));
        return List.copyOf(requests);
    }

    private <T> T readDefinition(
            DefinitionRequest request,
            AssetLoadReport.Builder diagnostics,
            Function<JsonObject, T> parser) {
        try {
            JsonObject root =
                    requireRoot(
                            assetManager.readUtf8(
                                    request.location()),
                            request.source());
            return parser.apply(root);
        } catch (AssetLoadException failure) {
            addDefinitionAccessFailure(
                    diagnostics, request, failure);
        } catch (StrictJson.UnknownFieldException failure) {
            addJsonFailure(
                    diagnostics,
                    "ASSET_JSON_UNKNOWN_FIELD",
                    request.source(),
                    request.location(),
                    failure);
        } catch (JsonParseException
                | IllegalArgumentException failure) {
            addJsonFailure(
                    diagnostics,
                    "ASSET_JSON_INVALID",
                    request.source(),
                    request.location(),
                    failure);
        }
        return null;
    }

    private static void addDefinitionAccessFailure(
            AssetLoadReport.Builder diagnostics,
            DefinitionRequest request,
            AssetLoadException failure) {
        for (AssetDiagnostic diagnostic :
                failure.report().diagnostics()) {
            if (diagnostic.code().equals(
                    "ASSET_NOT_FOUND")) {
                diagnostics.add(
                        new AssetDiagnostic(
                                AssetSeverity.ERROR,
                                "ASSET_DEFINITION_NOT_FOUND",
                                request.source(),
                                request.location(),
                                null,
                                "Indexed definition is not present",
                                null));
            } else {
                diagnostics.add(diagnostic);
            }
        }
    }

    private static JsonObject requireRoot(
            String json, String source) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new JsonParseException(
                    source + " root must be an object");
        }
        return root.getAsJsonObject();
    }

    private static List<String> requireStringList(
            JsonObject object,
            String field,
            String source) {
        JsonElement value = object.get(field);
        if (value == null
                || value.isJsonNull()
                || !value.isJsonArray()) {
            throw new JsonParseException(
                    source
                            + " field '"
                            + field
                            + "' must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (element == null
                    || element.isJsonNull()
                    || !element.isJsonPrimitive()) {
                throw new JsonParseException(
                        source
                                + " field '"
                                + field
                                + "["
                                + index
                                + "]' must be a string");
            }
            JsonPrimitive primitive =
                    element.getAsJsonPrimitive();
            if (!primitive.isString()) {
                throw new JsonParseException(
                        source
                                + " field '"
                                + field
                                + "["
                                + index
                                + "]' must be a string");
            }
            result.add(primitive.getAsString());
        }
        return List.copyOf(result);
    }

    private static boolean ownedByManifest(
            ResourceLocation id,
            DefinitionRequest request,
            String field,
            AssetLoadReport.Builder diagnostics) {
        if (id.namespace().equals(
                request.namespace())) {
            return true;
        }
        addError(
                diagnostics,
                "ASSET_NAMESPACE_MISMATCH",
                request.sourceContext(),
                field,
                "Definition id "
                        + id
                        + " does not belong to manifest namespace "
                        + request.namespace());
        return false;
    }

    private static ResourceLocation fromClasspathPath(
            String path) {
        String relative =
                path.substring("assets/".length());
        int slash = relative.indexOf('/');
        return ResourceLocation.of(
                relative.substring(0, slash),
                relative.substring(slash + 1));
    }

    private static DefinitionSource firstSource(
            List<ResourceIndex> indexes,
            LoadContext context) {
        return indexes.stream()
                .map(
                        index ->
                                context.manifests.get(
                                        index.namespace()))
                .filter(Objects::nonNull)
                .min(
                        Comparator.comparing(
                                DefinitionSource::source))
                .orElse(
                        new DefinitionSource(
                                AssetManager.INDEX_LIST_PATH,
                                null,
                                "gaia"));
    }

    private static void addJsonFailure(
            AssetLoadReport.Builder diagnostics,
            String code,
            String source,
            ResourceLocation resource,
            RuntimeException failure) {
        diagnostics.add(
                new AssetDiagnostic(
                        AssetSeverity.ERROR,
                        code,
                        source,
                        resource,
                        null,
                        Objects.toString(
                                failure.getMessage(),
                                failure.getClass()
                                        .getSimpleName()),
                        null));
    }

    private static void addError(
            AssetLoadReport.Builder diagnostics,
            String code,
            DefinitionSource source,
            String field,
            String message) {
        diagnostics.add(
                new AssetDiagnostic(
                        AssetSeverity.ERROR,
                        code,
                        source.source(),
                        source.resource(),
                        field,
                        message,
                        null));
    }

    private static void addWarning(
            AssetLoadReport.Builder diagnostics,
            String code,
            DefinitionSource source,
            String field,
            String message,
            ResourceLocation fallback) {
        diagnostics.add(
                new AssetDiagnostic(
                        AssetSeverity.WARNING,
                        code,
                        source.source(),
                        source.resource(),
                        field,
                        message,
                        fallback));
    }

    private record DefinitionSource(
            String source,
            ResourceLocation resource,
            String namespace) {}

    private record DefinitionRequest(
            String namespace,
            ResourceLocation location,
            DefinitionSource sourceContext) {
        String source() {
            return sourceContext.source();
        }
    }

    private record FallbackValues(
            MaterialDefinition material,
            TextureAtlasMetadata atlas,
            TextureRegion region) {}

    private static final class LoadContext {
        private final Map<String, DefinitionSource> manifests =
                new HashMap<>();
        private final Map<ResourceLocation, DefinitionSource>
                atlasSources = new HashMap<>();
        private final Map<ResourceLocation, DefinitionSource>
                materialSources = new HashMap<>();
        private final Map<ResourceLocation, DefinitionSource>
                blockSources = new HashMap<>();
    }
}
