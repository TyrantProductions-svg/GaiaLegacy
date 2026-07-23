package com.gaia.assets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadException;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.RenderType;
import com.overlord.voxel.BlockFace;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GaiaResourceLoaderTest {
    private static final String INDEX_LIST =
            "META-INF/gaialegacy/resource-indexes.list";
    private static final String MANIFEST =
            "assets/test/resource-index.json";
    private static final String AIR =
            "assets/test/blocks/air.json";
    private static final String SOLID =
            "assets/test/blocks/solid.json";
    private static final String MISSING_MATERIAL =
            "assets/test/materials/missing.json";
    private static final String OPAQUE_MATERIAL =
            "assets/test/materials/opaque.json";
    private static final String BLOCK_ATLAS =
            "assets/test/atlases/blocks.json";
    private static final String ATLAS_IMAGE =
            "assets/test/textures/atlas.png";
    private static final ResourceLocation TEST_MISSING =
            ResourceLocation.parse("test:missing");
    private static final byte[] PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                                    + "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir Path temp;
    private final AtomicInteger jarNumber = new AtomicInteger();

    @Test
    void loadsCompleteJarFixtureWithoutFileProtocolAssumptions()
            throws Exception {
        GaiaAssetCatalog catalog = load(validEntries());

        assertAll(
                () ->
                        assertEquals(
                                1,
                                Byte.toUnsignedInt(
                                        catalog.blockRegistry()
                                                .requireStoredId(
                                                        ResourceLocation.parse(
                                                                "test:solid")))),
                () ->
                        assertEquals(
                                RenderType.OPAQUE,
                                catalog.materials()
                                        .get(
                                                ResourceLocation.parse(
                                                        "test:opaque"))
                                        .renderType()),
                () -> assertTrue(catalog.report().errors().isEmpty()),
                () -> assertTrue(catalog.report().warnings().isEmpty()),
                () -> assertEquals(1, catalog.renderAssets().blockAtlas().width()),
                () ->
                        assertFalse(
                                catalog.blockRegistry()
                                        .resolve(0)
                                        .renderable()),
                () ->
                        assertEquals(
                                ResourceLocation.parse("test:solid"),
                                catalog.blockRegistry()
                                        .require(1)
                                        .item()
                                        .id()),
                () ->
                        assertThrows(
                                UnsupportedOperationException.class,
                                () ->
                                        catalog.materials()
                                                .put(
                                                        ResourceLocation.parse(
                                                                "test:new"),
                                                        catalog.materials()
                                                                .get(
                                                                        ResourceLocation
                                                                                .parse(
                                                                                        "test:opaque")))));
    }

    @Test
    void reportsInvalidJsonWithExactContext() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(entries, SOLID, "{\"id\":1");

        assertFatal(
                entries,
                "ASSET_JSON_INVALID",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                null);
    }

    @Test
    void reportsUnknownJsonFieldWithExactContext() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson("test:solid", 1, "test:opaque", texturesAll("test:missing"))
                        .replace(
                                "\"blastResistance\":2.0,",
                                "\"blastResistance\":2.0,\"surprise\":true,"));

        assertFatal(
                entries,
                "ASSET_JSON_UNKNOWN_FIELD",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                null);
    }

    @Test
    void rejectsDuplicateManifestNamespacesBeforeOpeningDefinitions()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                INDEX_LIST,
                MANIFEST + "\nassets/zother/resource-index.json\n");
        putJson(
                entries,
                "assets/zother/resource-index.json",
                manifestJson("test", List.of("blocks/not-present.json"), List.of(), List.of()));

        assertFatal(
                entries,
                "ASSET_NAMESPACE_DUPLICATE",
                "assets/zother/resource-index.json",
                ResourceLocation.parse("zother:resource-index.json"),
                "namespace");
    }

    @Test
    void rejectsDefinitionIdsOutsideOwningManifestNamespace()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson("other:solid", 1, "test:opaque", texturesAll("test:missing")));

        assertFatal(
                entries,
                "ASSET_NAMESPACE_MISMATCH",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                "name");
    }

    @Test
    void reportsDuplicateNumericBlockIds() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson("test:solid", 0, "test:opaque", texturesAll("test:missing")));

        assertFatal(
                entries,
                "ASSET_BLOCK_ID_DUPLICATE",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                "id");
    }

    @Test
    void reportsDuplicateLogicalBlockNames() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson("test:air", 1, "test:opaque", texturesAll("test:missing")));

        assertFatal(
                entries,
                "ASSET_BLOCK_NAME_DUPLICATE",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                "name");
    }

    @Test
    void aggregatesChainedBlockIdAndNameCollisions()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of(
                                "blocks/air.json",
                                "blocks/a.json",
                                "blocks/b.json",
                                "blocks/c.json"),
                        List.of(
                                "materials/missing.json",
                                "materials/opaque.json"),
                        List.of("atlases/blocks.json")));
        putJson(
                entries,
                "assets/test/blocks/a.json",
                solidJson(
                        "test:a",
                        1,
                        "test:opaque",
                        texturesAll("test:missing")));
        putJson(
                entries,
                "assets/test/blocks/b.json",
                solidJson(
                        "test:b",
                        1,
                        "test:opaque",
                        texturesAll("test:missing")));
        putJson(
                entries,
                "assets/test/blocks/c.json",
                solidJson(
                        "test:b",
                        2,
                        "test:opaque",
                        texturesAll("test:missing")));

        AssetLoadException failure = failure(entries);

        assertEquals(
                List.of(
                        "ASSET_BLOCK_ID_DUPLICATE",
                        "ASSET_BLOCK_NAME_DUPLICATE"),
                failure.report().errors().stream()
                        .map(AssetDiagnostic::code)
                        .toList());
        assertEquals(
                "assets/test/blocks/c.json",
                failure.report().errors().get(1).source());
    }

    @Test
    void reportsDuplicateMaterialIds() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                OPAQUE_MATERIAL,
                materialJson("test:missing", "test:blocks", "opaque", "test:missing"));

        assertFatal(
                entries,
                "ASSET_MATERIAL_ID_DUPLICATE",
                OPAQUE_MATERIAL,
                ResourceLocation.parse("test:materials/opaque.json"),
                "id");
    }

    @Test
    void reportsDuplicateAtlasIds() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of("materials/missing.json", "materials/opaque.json"),
                        List.of("atlases/blocks.json", "atlases/duplicate.json")));
        putJson(
                entries,
                "assets/test/atlases/duplicate.json",
                atlasJson("test:blocks", "test:textures/atlas.png", Map.of("test:other", region())));

        assertFatal(
                entries,
                "ASSET_ATLAS_ID_DUPLICATE",
                "assets/test/atlases/duplicate.json",
                ResourceLocation.parse("test:atlases/duplicate.json"),
                "id");
    }

    @Test
    void reportsDuplicateAtlasRegionIdsAcrossAtlases()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of("materials/missing.json", "materials/opaque.json"),
                        List.of("atlases/blocks.json", "atlases/decor.json")));
        putJson(
                entries,
                "assets/test/atlases/decor.json",
                atlasJson("test:decor", "test:textures/atlas.png", Map.of("test:missing", region())));

        assertFatal(
                entries,
                "ASSET_ATLAS_REGION_ID_DUPLICATE",
                "assets/test/atlases/decor.json",
                ResourceLocation.parse("test:atlases/decor.json"),
                "regions.test:missing");
    }

    @Test
    void tracksRegionsFromDuplicateAtlasDefinitionsForLaterCollisions()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of(
                                "materials/missing.json",
                                "materials/opaque.json"),
                        List.of(
                                "atlases/a.json",
                                "atlases/b.json",
                                "atlases/blocks.json",
                                "atlases/c.json")));
        putJson(
                entries,
                "assets/test/atlases/a.json",
                atlasJson(
                        "test:first",
                        "test:textures/atlas.png",
                        Map.of("test:a", region())));
        putJson(
                entries,
                "assets/test/atlases/b.json",
                atlasJson(
                        "test:first",
                        "test:textures/atlas.png",
                        Map.of("test:chained", region())));
        putJson(
                entries,
                "assets/test/atlases/c.json",
                atlasJson(
                        "test:third",
                        "test:textures/atlas.png",
                        Map.of("test:chained", region())));

        AssetLoadException failure = failure(entries);

        assertEquals(
                List.of(
                        "ASSET_ATLAS_ID_DUPLICATE",
                        "ASSET_ATLAS_REGION_ID_DUPLICATE"),
                failure.report().errors().stream()
                        .map(AssetDiagnostic::code)
                        .toList());
        assertEquals(
                "assets/test/atlases/c.json",
                failure.report().errors().get(1).source());
    }

    @Test
    void reportsAtlasRegionBoundsWithExactField() throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                BLOCK_ATLAS,
                atlasJson(
                        "test:blocks",
                        "test:textures/atlas.png",
                        Map.of(
                                "test:missing",
                                "{\"x\":1,\"y\":0,\"width\":1,\"height\":1}")));

        assertFatal(
                entries,
                "ASSET_ATLAS_REGION_BOUNDS",
                BLOCK_ATLAS,
                ResourceLocation.parse("test:atlases/blocks.json"),
                "regions.test:missing");
    }

    @Test
    void reportsMultipleBlockAtlasesFromLoadedMaterials()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of(
                                "materials/cutout.json",
                                "materials/missing.json",
                                "materials/opaque.json"),
                        List.of("atlases/blocks.json", "atlases/decor.json")));
        putJson(
                entries,
                "assets/test/materials/cutout.json",
                materialJson("test:cutout", "test:decor", "cutout", "test:decor_missing"));
        putJson(
                entries,
                "assets/test/atlases/decor.json",
                atlasJson(
                        "test:decor",
                        "test:textures/atlas.png",
                        Map.of("test:decor_missing", region())));

        assertFatal(
                entries,
                "ASSET_MULTIPLE_BLOCK_ATLASES",
                "assets/test/materials/cutout.json",
                ResourceLocation.parse("test:materials/cutout.json"),
                "atlas");
    }

    @Test
    void aggregatesAtlasSelectionErrorsWithMissingAir()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/solid.json"),
                        List.of(
                                "materials/cutout.json",
                                "materials/missing.json",
                                "materials/opaque.json"),
                        List.of(
                                "atlases/blocks.json",
                                "atlases/decor.json")));
        putJson(
                entries,
                "assets/test/materials/cutout.json",
                materialJson(
                        "test:cutout",
                        "test:decor",
                        "cutout",
                        "test:decor_missing"));
        putJson(
                entries,
                "assets/test/atlases/decor.json",
                atlasJson(
                        "test:decor",
                        "test:textures/atlas.png",
                        Map.of("test:decor_missing", region())));

        AssetLoadException failure = failure(entries);

        assertEquals(
                List.of(
                        "ASSET_MULTIPLE_BLOCK_ATLASES",
                        "ASSET_DEFINITION_NOT_FOUND"),
                failure.report().errors().stream()
                        .map(AssetDiagnostic::code)
                        .toList());
        assertEquals(
                "blocks.id",
                failure.report().errors().get(1).field());
    }

    @Test
    void reportsManifestDefinitionThatCannotBeFound()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove(SOLID);

        assertFatal(
                entries,
                "ASSET_DEFINITION_NOT_FOUND",
                SOLID,
                ResourceLocation.parse("test:blocks/solid.json"),
                null);
    }

    @Test
    void missingRegionWarnsAndUsesDeclaredFallbackRegion()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "test:opaque",
                        texturesAll("test:not_present")));

        GaiaAssetCatalog catalog = load(entries);
        AssetDiagnostic up =
                warning(catalog, "ASSET_MISSING_REGION", "textures.up");

        assertAll(
                () ->
                        assertEquals(
                                "ASSET_MISSING_REGION",
                                catalog.report().warnings().get(0).code()),
                () ->
                        assertEquals(
                                List.of(
                                        "textures.north",
                                        "textures.south",
                                        "textures.up",
                                        "textures.down",
                                        "textures.west",
                                        "textures.east"),
                                catalog.report().warnings().stream()
                                        .filter(
                                                diagnostic ->
                                                        diagnostic.code()
                                                                .equals(
                                                                        "ASSET_MISSING_REGION"))
                                        .map(AssetDiagnostic::field)
                                        .toList()),
                () -> assertEquals(AssetSeverity.WARNING, up.severity()),
                () -> assertEquals(SOLID, up.source()),
                () ->
                        assertEquals(
                                ResourceLocation.parse(
                                        "test:blocks/solid.json"),
                                up.resource()),
                () -> assertEquals(TEST_MISSING, up.fallback()),
                () ->
                        assertSame(
                                catalog.blockAtlas()
                                        .requireRegion(TEST_MISSING),
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .region(BlockFace.UP)));
    }

    @Test
    void missingMaterialWarnsAndUsesDeclaredFallbackMaterial()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "test:not_present",
                        texturesAll("test:missing")));

        GaiaAssetCatalog catalog = load(entries);
        AssetDiagnostic warning =
                warning(catalog, "ASSET_MISSING_MATERIAL", "material");

        assertAll(
                () -> assertEquals(SOLID, warning.source()),
                () ->
                        assertEquals(
                                ResourceLocation.parse(
                                        "test:blocks/solid.json"),
                                warning.resource()),
                () -> assertEquals(TEST_MISSING, warning.fallback()),
                () ->
                        assertSame(
                                catalog.materials().get(TEST_MISSING),
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .material()));
    }

    @Test
    void expandsFaceAliasesInDocumentedPrecedence()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        Map<String, String> regions = new HashMap<>();
        for (String name :
                List.of("all", "sides", "top", "bottom", "north", "up", "missing")) {
            regions.put("test:" + name, region());
        }
        putJson(
                entries,
                BLOCK_ATLAS,
                atlasJson("test:blocks", "test:textures/atlas.png", regions));
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "test:opaque",
                        "{\"all\":\"test:all\","
                                + "\"sides\":\"test:sides\","
                                + "\"top\":\"test:top\","
                                + "\"bottom\":\"test:bottom\","
                                + "\"north\":\"test:north\","
                                + "\"up\":\"test:up\"}"));

        GaiaAssetCatalog catalog = load(entries);

        assertAll(
                () -> assertRegion(catalog, BlockFace.NORTH, "test:north"),
                () -> assertRegion(catalog, BlockFace.SOUTH, "test:sides"),
                () -> assertRegion(catalog, BlockFace.EAST, "test:sides"),
                () -> assertRegion(catalog, BlockFace.WEST, "test:sides"),
                () -> assertRegion(catalog, BlockFace.UP, "test:up"),
                () -> assertRegion(catalog, BlockFace.DOWN, "test:bottom"));
    }

    @Test
    void parsesAllRenderTypesAndEveryItemField()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of(
                                "blocks/air.json",
                                "blocks/cutout.json",
                                "blocks/solid.json",
                                "blocks/transparent.json"),
                        List.of(
                                "materials/cutout.json",
                                "materials/missing.json",
                                "materials/opaque.json",
                                "materials/transparent.json"),
                        List.of("atlases/blocks.json")));
        putJson(
                entries,
                "assets/test/materials/cutout.json",
                materialJson("test:cutout", "test:blocks", "cutout", "test:missing"));
        putJson(
                entries,
                "assets/test/materials/transparent.json",
                materialJson(
                        "test:transparent",
                        "test:blocks",
                        "transparent",
                        "test:missing"));
        putJson(
                entries,
                SOLID,
                solidJson(
                                "test:solid",
                                1,
                                "test:opaque",
                                texturesAll("test:missing"))
                        .replace(
                                "\"item\":{\"maxStackSize\":64,\"mouthHoldable\":true,\"twoHanded\":false}",
                                "\"item\":{\"id\":\"test:solid_item\",\"maxStackSize\":16,"
                                        + "\"mouthHoldable\":true,\"twoHanded\":true}"));
        putJson(
                entries,
                "assets/test/blocks/cutout.json",
                solidJson("test:cutout_block", 2, "test:cutout", texturesAll("test:missing")));
        putJson(
                entries,
                "assets/test/blocks/transparent.json",
                solidJson(
                        "test:transparent_block",
                        3,
                        "test:transparent",
                        texturesAll("test:missing")));

        GaiaAssetCatalog catalog = load(entries);

        assertAll(
                () ->
                        assertEquals(
                                RenderType.OPAQUE,
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .material()
                                        .renderType()),
                () ->
                        assertEquals(
                                RenderType.CUTOUT,
                                catalog.blockRegistry()
                                        .resolve(2)
                                        .material()
                                        .renderType()),
                () ->
                        assertEquals(
                                RenderType.TRANSPARENT,
                                catalog.blockRegistry()
                                        .resolve(3)
                                        .material()
                                        .renderType()),
                () ->
                        assertEquals(
                                ResourceLocation.parse(
                                        "test:solid_item"),
                                catalog.blockRegistry()
                                        .require(1)
                                        .item()
                                        .id()),
                () ->
                        assertEquals(
                                16,
                                catalog.blockRegistry()
                                        .require(1)
                                        .item()
                                        .maxStackSize()),
                () ->
                        assertTrue(
                                catalog.blockRegistry()
                                        .require(1)
                                        .item()
                                        .mouthHoldable()),
                () ->
                        assertTrue(
                                catalog.blockRegistry()
                                        .require(1)
                                        .item()
                                        .twoHanded()));
    }

    @Test
    void permitsReferencesIntoAnotherDiscoveredNamespace()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                INDEX_LIST,
                MANIFEST + "\nassets/shared/resource-index.json\n");
        putJson(
                entries,
                "assets/shared/resource-index.json",
                manifestJson(
                        "shared",
                        List.of(),
                        List.of("materials/opaque.json"),
                        List.of()));
        putJson(
                entries,
                "assets/shared/materials/opaque.json",
                materialJson(
                        "shared:opaque",
                        "test:blocks",
                        "opaque",
                        "test:missing"));
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "shared:opaque",
                        texturesAll("test:missing")));

        GaiaAssetCatalog catalog = load(entries);

        assertSame(
                catalog.materials()
                        .get(ResourceLocation.parse("shared:opaque")),
                catalog.blockRegistry().resolve(1).material());
    }

    @Test
    void missingAndCorruptAtlasImagesUseCpuFallback()
            throws Exception {
        Map<String, byte[]> missing = validEntries();
        missing.remove(ATLAS_IMAGE);
        GaiaAssetCatalog missingCatalog = load(missing);

        Map<String, byte[]> corrupt = validEntries();
        corrupt.put(ATLAS_IMAGE, new byte[] {1, 2, 3, 4});
        GaiaAssetCatalog corruptCatalog = load(corrupt);

        assertAll(
                () -> assertTextureFallback(missingCatalog),
                () -> assertTextureFallback(corruptCatalog));
    }

    @Test
    void decodedAtlasImageMustMatchDeclaredDimensions()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        String atlas =
                new String(
                        entries.get(BLOCK_ATLAS),
                        StandardCharsets.UTF_8);
        putJson(
                entries,
                BLOCK_ATLAS,
                atlas.replace(
                        "\"width\":1,\"height\":1,\"regions\":",
                        "\"width\":2,\"height\":1,\"regions\":"));

        AssetLoadException failure = failure(entries);
        AssetDiagnostic diagnostic =
                failure.report().errors().get(0);

        assertAll(
                () -> assertEquals(1, failure.report().errors().size()),
                () ->
                        assertEquals(
                                "ASSET_ATLAS_IMAGE_SIZE_MISMATCH",
                                diagnostic.code()),
                () -> assertEquals(BLOCK_ATLAS, diagnostic.source()),
                () ->
                        assertEquals(
                                ResourceLocation.parse(
                                        "test:textures/atlas.png"),
                                diagnostic.resource()),
                () -> assertEquals("texture", diagnostic.field()),
                () -> assertTrue(diagnostic.message().contains("2x1")),
                () -> assertTrue(diagnostic.message().contains("1x1")),
                () -> assertEquals(null, diagnostic.fallback()));
    }

    @Test
    void ambiguousAtlasImageOwnershipRemainsFatal()
            throws Exception {
        Path basePath = temp.resolve("ambiguous-base.jar");
        Path overlayPath = temp.resolve("ambiguous-overlay.jar");
        try (TestAssetJar base =
                        TestAssetJar.create(basePath, validEntries());
                TestAssetJar overlay =
                        TestAssetJar.create(
                                overlayPath,
                                Map.of(ATLAS_IMAGE, PNG));
                URLClassLoader combined =
                        new URLClassLoader(
                                new URL[] {
                                    basePath.toUri().toURL(),
                                    overlayPath.toUri().toURL()
                                },
                                ClassLoader.getPlatformClassLoader())) {
            AssetLoadException failure =
                    assertThrows(
                            AssetLoadException.class,
                            () ->
                                    new GaiaResourceLoader(
                                                    new AssetManager(
                                                            combined))
                                            .load());

            assertEquals(
                    "ASSET_AMBIGUOUS",
                    failure.report().errors().get(0).code());
        }
    }

    @Test
    void constructsGuardedFallbacksWhenJsonFallbacksAreAbsent()
            throws Exception {
        Map<String, byte[]> entries = validEntries();
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of("materials/opaque.json"),
                        List.of("atlases/blocks.json")));
        putJson(
                entries,
                BLOCK_ATLAS,
                atlasJson(
                        "test:blocks",
                        "test:textures/atlas.png",
                        Map.of("test:actual", region())));
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "test:not_present",
                        texturesAll("test:not_present")));

        GaiaAssetCatalog catalog = load(entries);

        assertAll(
                () ->
                        assertEquals(
                                TEST_MISSING,
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .material()
                                        .id()),
                () ->
                        assertEquals(
                                2,
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .region(BlockFace.UP)
                                        .atlasWidth()),
                () ->
                        assertEquals(
                                TEST_MISSING,
                                catalog.blockRegistry()
                                        .resolve(1)
                                        .region(BlockFace.UP)
                                        .id()),
                () ->
                        assertTrue(
                                catalog.report()
                                        .warnings()
                                        .stream()
                                        .anyMatch(
                                                warning ->
                                                        warning.code()
                                                                .equals(
                                                                        "ASSET_MISSING_MATERIAL"))));
    }

    private GaiaAssetCatalog load(Map<String, byte[]> entries)
            throws Exception {
        Path jar =
                temp.resolve(
                        "fixture-"
                                + jarNumber.incrementAndGet()
                                + ".jar");
        try (TestAssetJar testJar =
                TestAssetJar.create(jar, entries)) {
            return new GaiaResourceLoader(
                            new AssetManager(testJar.classLoader()))
                    .load();
        }
    }

    private void assertFatal(
            Map<String, byte[]> entries,
            String code,
            String source,
            ResourceLocation resource,
            String field)
            throws Exception {
        AssetLoadException failure = failure(entries);
        assertEquals(
                1,
                failure.report().errors().size(),
                "single-fault fixture emitted unexpected errors");
        AssetDiagnostic diagnostic =
                failure.report().errors().stream()
                        .filter(candidate -> candidate.code().equals(code))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Missing diagnostic "
                                                        + code
                                                        + " in "
                                                        + failure.report()
                                                                .errors()));

        assertAll(
                () -> assertEquals(AssetSeverity.ERROR, diagnostic.severity()),
                () -> assertEquals(source, diagnostic.source()),
                () -> assertEquals(resource, diagnostic.resource()),
                () -> assertEquals(field, diagnostic.field()),
                () -> assertEquals(null, diagnostic.fallback()));
    }

    private AssetLoadException failure(
            Map<String, byte[]> entries) {
        return assertThrows(
                AssetLoadException.class,
                () -> load(entries));
    }

    private static AssetDiagnostic warning(
            GaiaAssetCatalog catalog, String code, String field) {
        return catalog.report().warnings().stream()
                .filter(
                        diagnostic ->
                                diagnostic.code().equals(code)
                                        && field.equals(
                                                diagnostic.field()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertRegion(
            GaiaAssetCatalog catalog,
            BlockFace face,
            String expected) {
        assertEquals(
                ResourceLocation.parse(expected),
                catalog.blockRegistry()
                        .resolve(1)
                        .region(face)
                        .id());
    }

    private static void assertTextureFallback(
            GaiaAssetCatalog catalog) {
        assertAll(
                () ->
                        assertEquals(
                                2,
                                catalog.renderAssets()
                                        .blockAtlas()
                                        .width()),
                () ->
                        assertEquals(
                                2,
                                catalog.renderAssets()
                                        .blockAtlas()
                                        .height()),
                () ->
                        assertEquals(
                                "ASSET_TEXTURE_FALLBACK",
                                catalog.report()
                                        .warnings()
                                        .get(0)
                                        .code()));
    }

    private static Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new HashMap<>();
        putJson(entries, INDEX_LIST, MANIFEST + "\n");
        putJson(
                entries,
                MANIFEST,
                manifestJson(
                        "test",
                        List.of("blocks/air.json", "blocks/solid.json"),
                        List.of(
                                "materials/missing.json",
                                "materials/opaque.json"),
                        List.of("atlases/blocks.json")));
        putJson(entries, AIR, airJson());
        putJson(
                entries,
                SOLID,
                solidJson(
                        "test:solid",
                        1,
                        "test:opaque",
                        texturesAll("test:missing")));
        putJson(
                entries,
                MISSING_MATERIAL,
                materialJson(
                        "test:missing",
                        "test:blocks",
                        "opaque",
                        "test:missing"));
        putJson(
                entries,
                OPAQUE_MATERIAL,
                materialJson(
                        "test:opaque",
                        "test:blocks",
                        "opaque",
                        "test:missing"));
        putJson(
                entries,
                BLOCK_ATLAS,
                atlasJson(
                        "test:blocks",
                        "test:textures/atlas.png",
                        Map.of("test:missing", region())));
        entries.put(ATLAS_IMAGE, PNG);
        return entries;
    }

    private static String manifestJson(
            String namespace,
            List<String> blocks,
            List<String> materials,
            List<String> atlases) {
        return "{\"namespace\":\""
                + namespace
                + "\",\"blocks\":"
                + strings(blocks)
                + ",\"materials\":"
                + strings(materials)
                + ",\"atlases\":"
                + strings(atlases)
                + "}";
    }

    private static String airJson() {
        return "{\"id\":0,"
                + "\"name\":\"test:air\","
                + "\"material\":\"test:opaque\","
                + "\"textures\":{\"all\":\"test:missing\"},"
                + "\"hardness\":0,"
                + "\"structuralIntegrity\":0,"
                + "\"tolerance\":0,"
                + "\"gravity\":false,"
                + "\"flammable\":false,"
                + "\"blastResistance\":0}";
    }

    private static String solidJson(
            String name,
            int id,
            String material,
            String textures) {
        return "{\"id\":"
                + id
                + ",\"name\":\""
                + name
                + "\",\"material\":\""
                + material
                + "\",\"textures\":"
                + textures
                + ",\"hardness\":1.5,"
                + "\"structuralIntegrity\":50.0,"
                + "\"tolerance\":2.0,"
                + "\"gravity\":false,"
                + "\"flammable\":false,"
                + "\"blastResistance\":2.0,"
                + "\"item\":{\"maxStackSize\":64,"
                + "\"mouthHoldable\":true,"
                + "\"twoHanded\":false}}";
    }

    private static String materialJson(
            String id,
            String atlas,
            String renderType,
            String missingRegion) {
        return "{\"id\":\""
                + id
                + "\",\"atlas\":\""
                + atlas
                + "\",\"renderType\":\""
                + renderType
                + "\",\"alphaCutoff\":0.5,"
                + "\"missingRegion\":\""
                + missingRegion
                + "\"}";
    }

    private static String atlasJson(
            String id,
            String texture,
            Map<String, String> regions) {
        List<String> ordered = new ArrayList<>(regions.keySet());
        ordered.sort(String::compareTo);
        StringBuilder encodedRegions = new StringBuilder("{");
        for (String regionId : ordered) {
            if (encodedRegions.length() > 1) {
                encodedRegions.append(',');
            }
            encodedRegions
                    .append('"')
                    .append(regionId)
                    .append("\":")
                    .append(regions.get(regionId));
        }
        encodedRegions.append('}');
        return "{\"id\":\""
                + id
                + "\",\"texture\":\""
                + texture
                + "\",\"width\":1,\"height\":1,\"regions\":"
                + encodedRegions
                + "}";
    }

    private static String region() {
        return "{\"x\":0,\"y\":0,\"width\":1,\"height\":1}";
    }

    private static String texturesAll(String region) {
        return "{\"all\":\"" + region + "\"}";
    }

    private static String strings(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (String value : values) {
            if (result.length() > 1) {
                result.append(',');
            }
            result.append('"').append(value).append('"');
        }
        return result.append(']').toString();
    }

    private static void putJson(
            Map<String, byte[]> entries,
            String path,
            String json) {
        entries.put(
                path,
                json.getBytes(StandardCharsets.UTF_8));
    }
}
