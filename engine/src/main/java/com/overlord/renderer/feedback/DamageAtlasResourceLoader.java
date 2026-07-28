package com.overlord.renderer.feedback;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetManager;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.texture.TextureImageLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.BufferUtils;

public final class DamageAtlasResourceLoader {
    public static final int STAGE_COUNT = 10;
    public static final int WIDTH = STAGE_COUNT * DamageAtlasLayout.TILE_SIZE;
    public static final int HEIGHT = DamageAtlasLayout.TILE_SIZE;
    private static final ResourceLocation FALLBACK =
            ResourceLocation.of("overlord", "feedback/damage-atlas-fallback");

    public DamageAtlasLayout load(
            AssetManager assets,
            ResourceLocation location,
            Consumer<AssetDiagnostic> diagnostics) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(diagnostics, "diagnostics");

        List<AssetDiagnostic> decodeDiagnostics = new ArrayList<>();
        TextureImage image =
                new TextureImageLoader().load(assets, location, decodeDiagnostics::add);
        if (!decodeDiagnostics.isEmpty()) {
            AssetDiagnostic cause = decodeDiagnostics.get(0);
            diagnostics.accept(
                    new AssetDiagnostic(
                            AssetSeverity.WARNING,
                            "DAMAGE_ATLAS_FALLBACK",
                            location.toClasspathPath(),
                            location,
                            "texture",
                            "Damage atlas was missing or could not be decoded; cause: "
                                    + cause.code()
                                    + ": "
                                    + cause.message(),
                            FALLBACK));
            return new DamageAtlasLayout(fallbackImage(), STAGE_COUNT);
        }
        if (image.width() != WIDTH || image.height() != HEIGHT) {
            diagnostics.accept(
                    new AssetDiagnostic(
                            AssetSeverity.WARNING,
                            "DAMAGE_ATLAS_INVALID_DIMENSIONS",
                            location.toClasspathPath(),
                            location,
                            "texture",
                            "Damage atlas must be exactly 160x16 but was "
                                    + image.width()
                                    + "x"
                                    + image.height(),
                            FALLBACK));
            return new DamageAtlasLayout(fallbackImage(), STAGE_COUNT);
        }
        return new DamageAtlasLayout(image, STAGE_COUNT);
    }

    public static TextureImage fallbackImage() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int localX = x % DamageAtlasLayout.TILE_SIZE;
                boolean crack = localX == y || localX == HEIGHT - 1 - y;
                boolean magenta = crack && ((localX + y) & 2) == 0;
                pixels.put((byte) (magenta ? 176 : 0));
                pixels.put((byte) 0);
                pixels.put((byte) (magenta ? 176 : 0));
                pixels.put((byte) (crack ? 255 : 0));
            }
        }
        pixels.flip();
        return new TextureImage(WIDTH, HEIGHT, pixels);
    }
}
