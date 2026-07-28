package com.gaia.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderAssets;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.voxel.BlockFace;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GaiaProductionAssetsTest {
    private static final String DAMAGE_ATLAS_SHA256 =
            "10866639349013a1abf50472f32b2b06071bcdfabf26f4e5eaf9a304cb3b2fcb";
    private static final String ATLAS_PATH =
            "assets/gaia/textures/atlas.png";
    private static final String DAMAGE_ATLAS_PATH =
            "assets/gaia/textures/effects/block_damage.png";

    @Test
    void packagesTenStageDamageAtlasWithoutChangingBlockAtlas() throws Exception {
        ClassLoader loader = GaiaProductionAssetsTest.class.getClassLoader();
        BufferedImage damage;
        try (InputStream input = loader.getResourceAsStream(DAMAGE_ATLAS_PATH)) {
            assertNotNull(input);
            damage = ImageIO.read(input);
        }

        assertNotNull(damage);
        assertEquals(160, damage.getWidth());
        assertEquals(16, damage.getHeight());
        for (int stage = 0; stage < 10; stage++) {
            boolean containsVisiblePixel = false;
            for (int y = 0; y < 16; y++) {
                for (int x = stage * 16; x < (stage + 1) * 16; x++) {
                    containsVisiblePixel |= ((damage.getRGB(x, y) >>> 24) & 0xff) != 0;
                }
            }
            assertTrue(containsVisiblePixel, "damage stage " + stage + " is empty");
            if (stage > 0) {
                assertPreviousDamageStageIsCumulative(damage, stage);
            }
        }

        BufferedImage blockAtlas;
        try (InputStream input = loader.getResourceAsStream(ATLAS_PATH)) {
            assertNotNull(input);
            blockAtlas = ImageIO.read(input);
        }
        assertEquals(
                "e5b2b34d81dcc396efff2c071f7f6bd3"
                        + "b90e03b2278f8ce80c3fe98a314739f6",
                hashArgbRegion(blockAtlas, 0, 0, 128, 64));
    }

    @Test
    void generatedDamageAtlasIsDeterministicAndMatchesProductionResource(
            @TempDir Path temporaryDirectory) throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path generator =
                repositoryRoot.resolve(
                        "tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java");
        Path first = temporaryDirectory.resolve("first.png");
        Path second = temporaryDirectory.resolve("second.png");

        runGenerator(repositoryRoot, generator, first);
        runGenerator(repositoryRoot, generator, second);

        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);
        byte[] productionBytes;
        try (InputStream input =
                GaiaProductionAssetsTest.class
                        .getClassLoader()
                        .getResourceAsStream(DAMAGE_ATLAS_PATH)) {
            assertNotNull(input);
            productionBytes = input.readAllBytes();
        }
        assertArrayEquals(firstBytes, secondBytes);
        assertArrayEquals(firstBytes, productionBytes);
        assertEquals(DAMAGE_ATLAS_SHA256, hashBytes(firstBytes));

        BufferedImage generated = ImageIO.read(first.toFile());
        assertNotNull(generated);
        assertEquals(160, generated.getWidth());
        assertEquals(16, generated.getHeight());
    }

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
        var oakLog =
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:oak_log"));
        var oakLeaves =
                catalog.blockRegistry()
                        .require(ResourceLocation.parse("gaia:oak_leaves"));
        assertEquals(4, oakLog.id());
        assertEquals(5, oakLeaves.id());
        assertEquals(
                ResourceLocation.parse("gaia:oak_log_top"),
                oakLog.textures().get(BlockFace.UP));
        assertEquals(
                ResourceLocation.parse("gaia:oak_log_top"),
                oakLog.textures().get(BlockFace.DOWN));
        for (BlockFace side :
                new BlockFace[] {
                    BlockFace.NORTH,
                    BlockFace.SOUTH,
                    BlockFace.EAST,
                    BlockFace.WEST
                }) {
            assertEquals(
                    ResourceLocation.parse("gaia:oak_log_side"),
                    oakLog.textures().get(side));
        }
        for (BlockFace face : BlockFace.values()) {
            assertEquals(
                    ResourceLocation.parse("gaia:oak_leaves"),
                    oakLeaves.textures().get(face));
        }
        assertEquals(
                ResourceLocation.parse("gaia:oak_log"),
                oakLog.item().id());
        assertEquals(
                ResourceLocation.parse("gaia:oak_leaves"),
                oakLeaves.item().id());
        assertEquals(ResourceLocation.parse("gaia:opaque"), oakLog.material());
        assertEquals(
                ResourceLocation.parse("gaia:opaque"),
                oakLeaves.material());
        assertEquals(
                ResourceLocation.parse("gaia:opaque"),
                catalog.renderAssets().worldMaterial().id());
        assertEquals(
                RenderAssets.DEFAULT_WORLD_VERTEX_SHADER,
                catalog.renderAssets().worldVertexShader());
        assertEquals(
                160,
                catalog.renderAssets().feedback().damageAtlas().image().width());
        assertEquals(
                16,
                catalog.renderAssets().feedback().damageAtlas().image().height());
        assertEquals(
                ResourceLocation.parse("overlord:shaders/feedback/block_damage.vert"),
                catalog.renderAssets().feedback().damageVertexShader());
        assertTrue(oakLog.flammable());
        assertTrue(oakLeaves.flammable());

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
        assertRegion(
                atlas, "gaia:oak_log_side", 80, 48);
        assertRegion(
                atlas, "gaia:oak_log_top", 96, 48);
        assertRegion(
                atlas, "gaia:oak_leaves", 112, 48);
        assertEquals(15, atlas.regions().size());
        assertRegion(atlas, "gaia:dark_stone", 64, 0);
        assertRegion(atlas, "gaia:magma", 96, 0);
        assertRegion(atlas, "gaia:magma_variant_1", 112, 0);
        assertRegion(atlas, "gaia:snow_top", 32, 16);
        assertRegion(atlas, "gaia:snow_side", 48, 16);
        assertRegion(atlas, "gaia:magma_variant_2", 96, 16);
        assertRegion(atlas, "gaia:magma_variant_3", 112, 16);
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
                "a51b9fa9361792d81af5bc7d14b024ef"
                        + "3824707d2dbf5f2ccef11eef2f8ce4b0",
                hashArgbRegion(atlas, 0, 0));
        assertEquals(
                "b7f9d6e708397056dc893c3c2cdd41fe"
                        + "5291bacbd9fa326b61c6fd1fe6f10607",
                hashArgbRegion(atlas, 16, 0));
        assertEquals(
                "9ac3bc6ebaa124641000967dcafc0f8c"
                        + "32a53e49ed66dfac8c6cb438a9d8336b",
                hashArgbRegion(atlas, 32, 0));
        assertEquals(
                "d3f7b4cb0549923c7ef487e771b014f9"
                        + "be7f5d4469d4fb034d79fb9bbc0422bb",
                hashArgbRegion(atlas, 48, 0));
        assertEquals(
                "e5b2b34d81dcc396efff2c071f7f6bd3"
                        + "b90e03b2278f8ce80c3fe98a314739f6",
                hashArgbRegion(atlas, 0, 0, 128, 64));
        assertEquals(
                "c162bb0cf28de1fa5a331a49da56ad1c"
                        + "7d66028a7247cdda98ad56f86730f6f8",
                hashArgbRegion(atlas, 80, 0));
        assertEquals(
                "cc034eb5ea7ab5ce7ac2800e3c2acbeb"
                        + "1986f11ff5794e76dddca0a575dfd136",
                hashArgbRegion(atlas, 0, 16));
        assertEquals(
                "99f6a3c9a95ef6ec632be21e2b3f1bfb"
                        + "b554f1849297524fcddf17fb5503a436",
                hashArgbRegion(atlas, 80, 48));
        assertEquals(
                "dcf44c64dfb2e4a6fa2ddd78fbfb3480"
                        + "1b71c09954a4b6994e43f4e48d3f0c5c",
                hashArgbRegion(atlas, 96, 48));
        assertEquals(
                "530eca6f4d209a46d8e783111556124d"
                        + "2ad258f9badf47bdee6dfb8e18c1acc7",
                hashArgbRegion(atlas, 112, 48));

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

        assertTileIsFullyOpaque(atlas, 80, 48);
        assertTileIsFullyOpaque(atlas, 96, 48);
        assertTileIsFullyOpaque(atlas, 112, 48);
    }

    private static void assertRegion(
            TextureAtlasMetadata atlas,
            String id,
            int expectedX,
            int expectedY) {
        var region =
                atlas.requireRegion(ResourceLocation.parse(id));
        assertEquals(expectedX, region.x());
        assertEquals(expectedY, region.y());
        assertEquals(16, region.width());
        assertEquals(16, region.height());
    }

    private static void assertTileIsFullyOpaque(
            BufferedImage atlas, int startX, int startY) {
        for (int y = startY; y < startY + 16; y++) {
            for (int x = startX; x < startX + 16; x++) {
                assertEquals(255, (atlas.getRGB(x, y) >>> 24) & 0xff);
            }
        }
    }

    private static void assertPreviousDamageStageIsCumulative(
            BufferedImage damage, int stage) {
        int previousOffset = (stage - 1) * 16;
        int currentOffset = stage * 16;
        for (int y = 0; y < 16; y++) {
            for (int localX = 0; localX < 16; localX++) {
                int previous = damage.getRGB(previousOffset + localX, y);
                if (((previous >>> 24) & 0xff) != 0) {
                    assertEquals(previous, damage.getRGB(currentOffset + localX, y));
                }
            }
        }
    }

    private static String hashArgbRegion(
            BufferedImage image, int startX, int startY)
            throws Exception {
        return hashArgbRegion(image, startX, startY, 16, 16);
    }

    private static String hashBytes(byte[] bytes) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current;
    }

    private static void runGenerator(
            Path repositoryRoot, Path generator, Path output) throws Exception {
        String executable =
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process =
                new ProcessBuilder(java.toString(), generator.toString(), output.toString())
                        .directory(repositoryRoot.toFile())
                        .redirectErrorStream(true)
                        .start();
        String outputText =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), outputText);
    }

    private static String hashArgbRegion(
            BufferedImage image,
            int startX,
            int startY,
            int width,
            int height)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = startY; y < startY + height; y++) {
            for (int x = startX; x < startX + width; x++) {
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
