package com.gaia.tools.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypographySourceProvenanceTest {
    private static final Map<String, String> EXPECTED_SOURCES = expectedSources();
    private static final Map<String, String> EXPECTED_URLS = expectedUrls();

    @Test
    void closedManifestBindsEveryApprovedFontToTheActualSourceBytesAndLicense()
            throws Exception {
        FontSourceManifest manifest = FontSourceManifest.load(getClass().getClassLoader());

        assertEquals(List.copyOf(EXPECTED_SOURCES.keySet()),
                manifest.entries().stream().map(FontSourceManifest.Entry::id).toList());
        for (FontSourceManifest.Entry entry : manifest.entries()) {
            assertEquals(EXPECTED_SOURCES.get(entry.id()), entry.sourcePath());
            assertEquals(EXPECTED_URLS.get(entry.id()), entry.sourceUrl());
            assertEquals(64, entry.sourceSha256().length());
            assertTrue(entry.sourceSha256().matches("[0-9a-f]{64}"));
            assertEquals(entry.sourceSha256(), sha256(resource(entry.sourcePath())));
            assertTrue(resource(entry.licensePath()).length > 1_000,
                    () -> "license text is unexpectedly short for " + entry.id());
            assertTrue(entry.upstream().startsWith("https://github.com/"));
            assertTrue(entry.weight() == 400 || entry.weight() == 500
                    || entry.weight() == 600 || entry.weight() == 700);
            assertNotEquals(entry.upstreamCommitOrTag(), entry.sourceSha256(),
                    "Git version identity must not masquerade as source SHA-256");
        }
    }

    private byte[] resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertTrue(input != null, () -> "missing test resource " + path);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, String> expectedSources() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("pixelify-semibold-600",
                "ui-source/fonts/pixelify/PixelifySans-SemiBold.ttf");
        paths.put("pixelify-bold-700",
                "ui-source/fonts/pixelify/PixelifySans-Bold.ttf");
        paths.put("inter-regular-400",
                "ui-source/fonts/inter/Inter-Regular.ttf");
        paths.put("inter-medium-500",
                "ui-source/fonts/inter/Inter-Medium.ttf");
        paths.put("inter-semibold-600",
                "ui-source/fonts/inter/Inter-SemiBold.ttf");
        paths.put("plex-regular-400",
                "ui-source/fonts/ibm-plex-sans/IBMPlexSans-Regular.ttf");
        paths.put("plex-medium-500",
                "ui-source/fonts/ibm-plex-sans/IBMPlexSans-Medium.ttf");
        paths.put("plex-semibold-600",
                "ui-source/fonts/ibm-plex-sans/IBMPlexSans-SemiBold.ttf");
        return Collections.unmodifiableMap(paths);
    }

    private static Map<String, String> expectedUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        String pixelify = "https://raw.githubusercontent.com/eifetx/Pixelify-Sans/"
                + "39df74aba80df8157546034b878e8be1eb565ced/fonts/ttf/";
        urls.put("pixelify-semibold-600", pixelify + "PixelifySans-SemiBold.ttf");
        urls.put("pixelify-bold-700", pixelify + "PixelifySans-Bold.ttf");
        String inter = "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip#";
        urls.put("inter-regular-400", inter + "extras/ttf/Inter-Regular.ttf");
        urls.put("inter-medium-500", inter + "extras/ttf/Inter-Medium.ttf");
        urls.put("inter-semibold-600", inter + "extras/ttf/Inter-SemiBold.ttf");
        String plex = "https://raw.githubusercontent.com/IBM/plex/"
                + "1da12f02587b630c07e92692d21492d722f53614/packages/plex-sans/fonts/complete/ttf/";
        urls.put("plex-regular-400", plex + "IBMPlexSans-Regular.ttf");
        urls.put("plex-medium-500", plex + "IBMPlexSans-Medium.ttf");
        urls.put("plex-semibold-600", plex + "IBMPlexSans-SemiBold.ttf");
        return Collections.unmodifiableMap(urls);
    }
}
