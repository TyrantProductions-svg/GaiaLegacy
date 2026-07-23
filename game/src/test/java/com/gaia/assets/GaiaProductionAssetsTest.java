package com.gaia.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class GaiaProductionAssetsTest {
    private static final String ATLAS_PATH =
            "assets/gaia/textures/atlas.png";

    @Test
    void loadsProductionResourcesWithStableIdsAndUvs() {
        ClassLoader loader =
                GaiaProductionAssetsTest.class.getClassLoader();
        GaiaAssetCatalog catalog =
                new GaiaResourceLoader(new AssetManager(loader)).load();

        assertEquals(
                0,
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:air"))
                        .id());
        assertEquals(
                1,
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:grass"))
                        .id());
        assertEquals(
                2,
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:dirt"))
                        .id());
        assertEquals(
                3,
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:stone"))
                        .id());

        TextureAtlasMetadata atlas = catalog.blockAtlas();
        assertEquals(
                0,
                atlas.requireRegion(
                                ResourceLocation.parse("gaia:grass_top"))
                        .x());
        assertEquals(
                16,
                atlas.requireRegion(
                                ResourceLocation.parse("gaia:grass_side"))
                        .x());
        assertEquals(
                32,
                atlas.requireRegion(ResourceLocation.parse("gaia:dirt"))
                        .x());
        assertEquals(
                48,
                atlas.requireRegion(ResourceLocation.parse("gaia:stone"))
                        .x());
        assertEquals(
                80,
                atlas.requireRegion(ResourceLocation.parse("gaia:missing"))
                        .x());
        assertTrue(catalog.report().diagnostics().isEmpty());
    }

    @Test
    void preservesExistingTilesAndProvidesOpaqueMissingTexture()
            throws Exception {
        ClassLoader loader =
                GaiaProductionAssetsTest.class.getClassLoader();
        BufferedImage atlas;
        try (InputStream input = loader.getResourceAsStream(ATLAS_PATH)) {
            assertNotNull(input);
            atlas = ImageIO.read(input);
        }

        assertNotNull(atlas);
        assertEquals(128, atlas.getWidth());
        assertEquals(64, atlas.getHeight());
        assertEquals(
                "1c8979cf8e6b41bc6d3e67ab5b7efff6"
                        + "fbc2816472a120d54ca2bd2bae785a61",
                hashArgbRegion(atlas, 0, 0));
        assertEquals(
                "87baa8f45b3b71e6ecf61fa23dce1047"
                        + "758d58ba381dbe7e8b47a858696e217f",
                hashArgbRegion(atlas, 16, 0));
        assertEquals(
                "055bbfb1d895874d53eab4db98a32e290"
                        + "d2b27681d728ec58cc5320c112e6de3",
                hashArgbRegion(atlas, 32, 0));
        assertEquals(
                "799040087946283e315d980e13372844b"
                        + "1ff9f1629fbba0bcf9ae24ea13be299",
                hashArgbRegion(atlas, 48, 0));

        boolean containsOpaqueBlack = false;
        boolean containsOpaquePurple = false;
        for (int y = 0; y < 16; y++) {
            for (int x = 80; x < 96; x++) {
                int argb = atlas.getRGB(x, y);
                assertTrue(
                        argb == 0xff000000 || argb == 0xffb000b0);
                containsOpaqueBlack |= argb == 0xff000000;
                containsOpaquePurple |= argb == 0xffb000b0;
            }
        }

        assertTrue(containsOpaqueBlack);
        assertTrue(containsOpaquePurple);
    }

    private static String hashArgbRegion(
            BufferedImage image, int startX, int startY)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = startY; y < startY + 16; y++) {
            for (int x = startX; x < startX + 16; x++) {
                int argb = image.getRGB(x, y);
                digest.update((byte) ((argb >>> 24) & 0xff));
                digest.update((byte) ((argb >>> 16) & 0xff));
                digest.update((byte) ((argb >>> 8) & 0xff));
                digest.update((byte) (argb & 0xff));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
