package com.overlord.renderer.ui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable role-to-face catalog consumed by the one {@link TextRenderer}. */
public final class TypographyCatalog {
    private final Map<TypographyRole, Face> roles;
    private final TypographyRole defaultRole;

    public TypographyCatalog(Map<TypographyRole, Face> roles, TypographyRole defaultRole) {
        Objects.requireNonNull(roles, "roles");
        this.defaultRole = Objects.requireNonNull(defaultRole, "defaultRole");
        EnumMap<TypographyRole, Face> copy = new EnumMap<>(TypographyRole.class);
        roles.forEach((role, face) -> copy.put(
                Objects.requireNonNull(role, "typography role"),
                Objects.requireNonNull(face, "typography face")));
        if (!copy.keySet().equals(java.util.EnumSet.allOf(TypographyRole.class))) {
            throw new IllegalArgumentException("typography catalog must resolve every role");
        }
        if (!copy.containsKey(defaultRole)) {
            throw new IllegalArgumentException("default typography role must be resolved");
        }
        this.roles = Collections.unmodifiableMap(copy);
    }

    public Map<TypographyRole, Face> roles() {
        return roles;
    }

    public TypographyRole defaultRole() {
        return defaultRole;
    }

    public Face resolve(TypographyRole role) {
        return roles.get(Objects.requireNonNull(role, "role"));
    }

    public record Face(BitmapFont font, UiTextureId texture) {
        public Face {
            Objects.requireNonNull(font, "font");
            Objects.requireNonNull(texture, "texture");
            if (texture != UiTextureId.FONT_ATLAS
                    && texture != UiTextureId.FONT_DISPLAY
                    && texture != UiTextureId.FONT_BODY) {
                throw new IllegalArgumentException("typography face must use a font texture");
            }
        }
    }
}
