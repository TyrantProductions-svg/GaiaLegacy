package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiAssetBundleTest {
    @Test
    void textureCopiesExactlySizedRgbaBytesAndNeverExposesWritableStorage() {
        ByteBuffer source = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        UiTextureData texture = new UiTextureData(2, 1, source);
        source.put(0, (byte) 99);

        assertEquals(2, texture.width());
        assertEquals(1, texture.height());
        assertEquals(1, texture.rgba().get(0));
        assertEquals(8, texture.rgba().remaining());
        assertEquals(UiTextureSampling.NEAREST, texture.sampling());
        assertFalse(texture.rgba().hasArray());
        assertThrows(ReadOnlyBufferException.class, () -> texture.rgba().put(0, (byte) 0));

        ByteBuffer firstView = texture.rgba();
        firstView.get();
        assertEquals(8, texture.rgba().remaining());
    }

    @Test
    void textureCarriesExplicitImmutableSamplingPolicy() {
        UiTextureData linear = new UiTextureData(
                1,
                1,
                ByteBuffer.wrap(new byte[] {1, 2, 3, 4}),
                UiTextureSampling.LINEAR);

        assertEquals(UiTextureSampling.LINEAR, linear.sampling());
        assertThrows(NullPointerException.class, () -> new UiTextureData(
                1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), null));
    }

    @Test
    void textureRejectsInvalidDimensionsWrongByteCountsAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiTextureData(0, 1, ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new UiTextureData(1, -1, ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new UiTextureData(1, 1, ByteBuffer.allocate(3)));
        assertThrows(IllegalArgumentException.class,
                () -> new UiTextureData(Integer.MAX_VALUE, 2, ByteBuffer.allocate(0)));
        assertThrows(NullPointerException.class, () -> new UiTextureData(1, 1, null));
    }

    @Test
    void fontCopiesItsMapAndResolvesUnsupportedCodePointsToTheExplicitMissingGlyph() {
        BitmapGlyph a = glyph('A', new UiUvRect(0.0f, 0.0f, 0.5f, 1.0f));
        BitmapGlyph infinity = glyph(0x221e, new UiUvRect(0.5f, 0.0f, 1.0f, 1.0f));
        BitmapGlyph missing = glyph(0xfffd, new UiUvRect(0.25f, 0.0f, 0.75f, 1.0f));
        Map<Integer, BitmapGlyph> source = new HashMap<>(Map.of((int) 'A', a, 0x221e, infinity));

        BitmapFont font = new BitmapFont(16, 8, source, missing);
        source.clear();

        assertEquals(a, font.glyph('A'));
        assertEquals(infinity, font.glyph(0x221e));
        assertEquals(missing, font.glyph(0x1f603));
        assertEquals(Map.of((int) 'A', a, 0x221e, infinity), font.glyphs());
        assertThrows(UnsupportedOperationException.class,
                () -> font.glyphs().put((int) 'B', glyph('B', a.uv())));
    }

    @Test
    void assetBundleRequiresAllThreeImmutableAssets() {
        UiTextureData texture = new UiTextureData(1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
        BitmapGlyph missing = glyph(0xfffd, new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f));
        BitmapFont font = new BitmapFont(1, 1, Map.of(), missing);

        UiAssetBundle bundle = new UiAssetBundle(texture, texture, font);

        assertEquals(texture, bundle.icons());
        assertEquals(texture, bundle.font());
        assertEquals(font, bundle.glyphs());
        assertThrows(NullPointerException.class, () -> new UiAssetBundle(null, texture, font));
        assertThrows(NullPointerException.class, () -> new UiAssetBundle(texture, null, font));
        assertThrows(NullPointerException.class, () -> new UiAssetBundle(texture, texture, null));
    }

    @Test
    void assetBundleOwnsOneImmutableTypedTextureMapAndTypographyCatalog() {
        UiTextureData nearest = new UiTextureData(
                1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
        UiTextureData linear = new UiTextureData(
                1, 1, ByteBuffer.wrap(new byte[] {5, 6, 7, 8}),
                UiTextureSampling.LINEAR);
        BitmapFont font = new BitmapFont(
                1, 1, Map.of(), glyph(0xfffd, new UiUvRect(0, 0, 1, 1)));
        TypographyCatalog.Face display = new TypographyCatalog.Face(
                font, UiTextureId.FONT_DISPLAY);
        TypographyCatalog.Face body = new TypographyCatalog.Face(
                font, UiTextureId.FONT_BODY);
        TypographyCatalog catalog = new TypographyCatalog(
                Map.of(
                        TypographyRole.DISPLAY_TITLE, display,
                        TypographyRole.HEADING_LARGE, display,
                        TypographyRole.BODY, body,
                        TypographyRole.FUNCTIONAL, body,
                        TypographyRole.HUD, body),
                TypographyRole.BODY);

        UiAssetBundle bundle = new UiAssetBundle(
                Map.of(
                        UiTextureId.ICON_ATLAS, nearest,
                        UiTextureId.FONT_DISPLAY, nearest,
                        UiTextureId.FONT_BODY, linear),
                catalog);

        assertEquals(linear, bundle.texture(UiTextureId.FONT_BODY));
        assertEquals(catalog, bundle.typography());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.textures().put(UiTextureId.FONT_ATLAS, nearest));
        assertThrows(IllegalArgumentException.class,
                () -> bundle.texture(UiTextureId.SOLID));
    }

    private static BitmapGlyph glyph(int codePoint, UiUvRect uv) {
        return new BitmapGlyph(codePoint, uv, 8, 0, 8);
    }
}
