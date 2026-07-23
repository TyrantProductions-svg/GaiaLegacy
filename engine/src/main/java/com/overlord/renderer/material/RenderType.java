package com.overlord.renderer.material;

import java.util.Locale;

public enum RenderType {
    OPAQUE,
    CUTOUT,
    TRANSPARENT;

    public static RenderType parse(String text) {
        return valueOf(text.toUpperCase(Locale.ROOT));
    }
}
