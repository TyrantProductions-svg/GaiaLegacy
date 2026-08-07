package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldItemFaceRegionsTest {
    @Test
    void requiresAndDefensivelyCopiesAllSixFaces() {
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("gaia:stone"), 0, 0, 16, 16, 16, 16);
        EnumMap<BlockFace, TextureRegion> source = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            source.put(face, region);
        }

        WorldItemFaceRegions faces = new WorldItemFaceRegions(source);
        source.clear();

        for (BlockFace face : BlockFace.values()) {
            assertEquals(region, faces.region(face));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> faces.regions().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemFaceRegions(Map.of(BlockFace.UP, region)));
    }
}
