package com.overlord.renderer.feedback;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

public record DamageAtlasLayout(TextureImage image, int stageCount) {
    public static final int TILE_SIZE = 16;

    public DamageAtlasLayout {
        Objects.requireNonNull(image, "image");
        if (stageCount < 8 || stageCount > 10) {
            throw new IllegalArgumentException("stageCount must be between 8 and 10");
        }
        if (image.width() != stageCount * TILE_SIZE || image.height() != TILE_SIZE) {
            throw new IllegalArgumentException(
                    "damage atlas must be " + (stageCount * TILE_SIZE) + "x" + TILE_SIZE);
        }
    }

    public TextureRegion region(int stage) {
        if (stage < 0 || stage >= stageCount) {
            throw new IllegalArgumentException("damage stage is outside the atlas: " + stage);
        }
        return new TextureRegion(
                ResourceLocation.of("overlord", "feedback/damage-stage-" + stage),
                stage * TILE_SIZE,
                0,
                TILE_SIZE,
                TILE_SIZE,
                image.width(),
                image.height());
    }
}
