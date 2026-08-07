package com.overlord.renderer.feedback;

import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable complete six-face atlas description for one world-item cube. */
public record WorldItemFaceRegions(Map<BlockFace, TextureRegion> regions) {
    public WorldItemFaceRegions {
        Objects.requireNonNull(regions, "regions");
        EnumMap<BlockFace, TextureRegion> copied = new EnumMap<>(BlockFace.class);
        for (Map.Entry<BlockFace, TextureRegion> entry : regions.entrySet()) {
            copied.put(
                    Objects.requireNonNull(entry.getKey(), "face"),
                    Objects.requireNonNull(entry.getValue(), "region"));
        }
        for (BlockFace face : BlockFace.values()) {
            if (!copied.containsKey(face)) {
                throw new IllegalArgumentException(
                        "world-item visuals require all six faces: " + face);
            }
        }
        regions = Collections.unmodifiableMap(copied);
    }

    public TextureRegion region(BlockFace face) {
        return regions.get(Objects.requireNonNull(face, "face"));
    }

    public static WorldItemFaceRegions uniform(TextureRegion region) {
        Objects.requireNonNull(region, "region");
        EnumMap<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, region);
        }
        return new WorldItemFaceRegions(regions);
    }
}
