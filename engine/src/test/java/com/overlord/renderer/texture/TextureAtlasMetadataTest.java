package com.overlord.renderer.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextureAtlasMetadataTest {
    private static final ResourceLocation ATLAS =
            ResourceLocation.parse("gaia:block_atlas");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.parse("gaia:textures/block_atlas.png");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");

    @Test
    void convertsPixelBoundsToNormalizedUvs() {
        TextureRegion region =
                new TextureRegion(
                        ResourceLocation.parse("gaia:grass_side"),
                        16,
                        0,
                        16,
                        16,
                        128,
                        64);

        assertEquals(0.125f, region.uMin(), 1.0e-6f);
        assertEquals(0.25f, region.uMax(), 1.0e-6f);
        assertEquals(0.0f, region.vMin(), 1.0e-6f);
        assertEquals(0.25f, region.vMax(), 1.0e-6f);
    }

    @Test
    void rejectsInvalidOrOverflowingRegionBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureRegion(
                                ResourceLocation.parse("gaia:bad"),
                                120,
                                0,
                                16,
                                16,
                                128,
                                64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureRegion(
                                ResourceLocation.parse("gaia:overflow"),
                                Integer.MAX_VALUE,
                                0,
                                2,
                                1,
                                Integer.MAX_VALUE,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureRegion(
                                ResourceLocation.parse("gaia:zero"),
                                0,
                                0,
                                0,
                                1,
                                1,
                                1));
    }

    @Test
    void parsesEverySupportedRenderTypeAndRejectsUnknownValues() {
        assertEquals(RenderType.OPAQUE, RenderType.parse("opaque"));
        assertEquals(RenderType.CUTOUT, RenderType.parse("cutout"));
        assertEquals(
                RenderType.TRANSPARENT,
                RenderType.parse("transparent"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RenderType.parse("compute"));
    }

    @Test
    void validatesMaterialAlphaCutoff() {
        assertThrows(
                IllegalArgumentException.class,
                () -> material(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> material(Float.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> material(-0.01f));
        assertThrows(
                IllegalArgumentException.class,
                () -> material(1.01f));

        assertEquals(0.0f, material(0.0f).alphaCutoff());
        assertEquals(1.0f, material(1.0f).alphaCutoff());
    }

    @Test
    void copiesAtlasRegionsAndRequiresMatchingDimensions() {
        TextureRegion missing = region(MISSING);
        Map<ResourceLocation, TextureRegion> mutable = new HashMap<>();
        mutable.put(MISSING, missing);
        TextureAtlasMetadata metadata =
                new TextureAtlasMetadata(
                        ATLAS, TEXTURE, 16, 16, mutable);

        mutable.clear();

        assertEquals(missing, metadata.requireRegion(MISSING));
        assertEquals(1, metadata.regions().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> metadata.regions().clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        metadata.requireRegion(
                                ResourceLocation.parse("gaia:unknown")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TextureAtlasMetadata(
                                ATLAS,
                                TEXTURE,
                                32,
                                16,
                                Map.of(MISSING, missing)));
    }

    @Test
    void renderableBlocksRequireAllSixFacesAndCopyMappings() {
        MaterialDefinition material = material(0.5f);
        TextureRegion missing = region(MISSING);
        EnumMap<BlockFace, TextureRegion> faces =
                new EnumMap<>(BlockFace.class);
        faces.put(BlockFace.NORTH, missing);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BlockRenderInfo(material, faces, true));

        for (BlockFace face : BlockFace.values()) {
            faces.put(face, missing);
        }
        faces.put(BlockFace.EAST, null);
        assertThrows(
                NullPointerException.class,
                () -> new BlockRenderInfo(material, faces, true));
        faces.put(BlockFace.EAST, missing);

        BlockRenderInfo info =
                new BlockRenderInfo(material, faces, true);
        faces.clear();

        assertArrayEquals(
                new BlockFace[] {
                    BlockFace.NORTH,
                    BlockFace.SOUTH,
                    BlockFace.UP,
                    BlockFace.DOWN,
                    BlockFace.WEST,
                    BlockFace.EAST
                },
                BlockFace.values());
        for (BlockFace face : BlockFace.values()) {
            assertEquals(missing, info.region(face));
        }
        assertThrows(
                UnsupportedOperationException.class,
                () -> info.regions().clear());
    }

    @Test
    void nonRenderableBlocksFillAllFacesWithFallback() {
        TextureRegion missing = region(MISSING);
        BlockRenderInfo info =
                BlockRenderInfo.nonRenderable(material(0.5f), missing);

        assertFalse(info.renderable());
        assertEquals(6, info.regions().size());
        for (BlockFace face : BlockFace.values()) {
            assertEquals(missing, info.region(face));
        }
    }

    private static MaterialDefinition material(float alphaCutoff) {
        return new MaterialDefinition(
                ResourceLocation.parse("gaia:terrain"),
                ATLAS,
                RenderType.CUTOUT,
                alphaCutoff,
                MISSING);
    }

    private static TextureRegion region(ResourceLocation id) {
        return new TextureRegion(id, 0, 0, 16, 16, 16, 16);
    }
}
