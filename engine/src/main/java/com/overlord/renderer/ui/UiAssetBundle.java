package com.overlord.renderer.ui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable CPU-side textures and one logical typography catalog. */
public final class UiAssetBundle {
    private final Map<UiTextureId, UiTextureData> textures;
    private final TypographyCatalog typography;

    public UiAssetBundle(
            Map<UiTextureId, UiTextureData> textures,
            TypographyCatalog typography) {
        Objects.requireNonNull(textures, "textures");
        this.typography = Objects.requireNonNull(typography, "typography");
        EnumMap<UiTextureId, UiTextureData> copy = new EnumMap<>(UiTextureId.class);
        textures.forEach((id, texture) -> {
            Objects.requireNonNull(id, "texture id");
            Objects.requireNonNull(texture, "texture data");
            if (id == UiTextureId.SOLID) {
                throw new IllegalArgumentException("SOLID has no texture resource");
            }
            copy.put(id, texture);
        });
        if (!copy.containsKey(UiTextureId.ICON_ATLAS)) {
            throw new IllegalArgumentException("UI icon texture is required");
        }
        for (TypographyCatalog.Face face : typography.roles().values()) {
            if (!copy.containsKey(face.texture())) {
                throw new IllegalArgumentException(
                        "typography face texture is missing: " + face.texture());
            }
        }
        this.textures = Collections.unmodifiableMap(copy);
    }

    /** Legacy single-font construction retained for existing focused callers. */
    public UiAssetBundle(UiTextureData icons, UiTextureData font, BitmapFont glyphs) {
        this(
                Map.of(
                        UiTextureId.ICON_ATLAS, Objects.requireNonNull(icons, "icons"),
                        UiTextureId.FONT_ATLAS, Objects.requireNonNull(font, "font")),
                legacyTypography(Objects.requireNonNull(glyphs, "glyphs")));
    }

    public Map<UiTextureId, UiTextureData> textures() {
        return textures;
    }

    public UiTextureData texture(UiTextureId id) {
        UiTextureData texture = textures.get(Objects.requireNonNull(id, "id"));
        if (texture == null) {
            throw new IllegalArgumentException("UI texture is not installed: " + id);
        }
        return texture;
    }

    public TypographyCatalog typography() {
        return typography;
    }

    public UiTextureData icons() {
        return texture(UiTextureId.ICON_ATLAS);
    }

    public UiTextureData font() {
        return textures.containsKey(UiTextureId.FONT_ATLAS)
                ? texture(UiTextureId.FONT_ATLAS)
                : texture(UiTextureId.FONT_BODY);
    }

    public BitmapFont glyphs() {
        return typography.resolve(typography.defaultRole()).font();
    }

    private static TypographyCatalog legacyTypography(BitmapFont font) {
        TypographyCatalog.Face face = new TypographyCatalog.Face(
                font, UiTextureId.FONT_ATLAS);
        EnumMap<TypographyRole, TypographyCatalog.Face> roles =
                new EnumMap<>(TypographyRole.class);
        for (TypographyRole role : TypographyRole.values()) {
            roles.put(role, face);
        }
        return new TypographyCatalog(roles, TypographyRole.BODY);
    }
}
