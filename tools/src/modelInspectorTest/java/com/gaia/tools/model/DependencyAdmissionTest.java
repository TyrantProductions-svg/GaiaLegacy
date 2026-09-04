package com.gaia.tools.model;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class DependencyAdmissionTest {
    private static final List<String> DEPENDENCY_CLASSES = List.of(
            "de.javagl.jgltf.model.io.v2.GltfAssetV2", "de.javagl.jgltf.impl.v1.GlTF",
            "de.javagl.jgltf.impl.v2.GlTF", "com.fasterxml.jackson.core.JsonFactory",
            "com.fasterxml.jackson.databind.ObjectMapper", "com.fasterxml.jackson.annotation.JsonProperty");

    @Test
    void isolatedInspectorCanLoadTheAdmittedNonResolvingReaderAndV2Model() throws Exception {
        assertNotNull(Class.forName("de.javagl.jgltf.model.io.GltfAssetReader")
                .getMethod("readWithoutReferences", java.io.InputStream.class));
        assertNotNull(Class.forName("de.javagl.jgltf.impl.v2.GlTF"));
    }

    @Test
    void headlessInspectorCannotAccidentallyLoadGameOrGraphicsAuthorities() {
        for (String name : new String[]{"com.gaia.GaiaMain",
                "com.overlord.renderer.Mesh", "org.lwjgl.glfw.GLFW",
                "org.lwjgl.opengl.GL", "com.google.gson.Gson"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
        }
    }

    @Test
    void resolvedRuntimeArtifactsMatchTheReviewedSha256Receipt() throws Exception {
        Properties receipt = new Properties();
        try (var input = getClass().getResourceAsStream("/model-inspector/dependencies.properties")) {
            assertNotNull(input, "Dependency admission receipt is missing");
            receipt.load(input);
        }
        assertEquals(6, receipt.size());
        for (String name : DEPENDENCY_CLASSES) {
            Path jar = jarFor(name);
            assertEquals(receipt.getProperty(jar.getFileName().toString()),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))),
                    jar.getFileName().toString());
        }
    }

    @Test
    void dependenciesHaveJava17CompatibleBaseAndSelectedMultiReleaseBytecode() throws Exception {
        for (String name : DEPENDENCY_CLASSES) {
            try (JarFile jar = new JarFile(jarFor(name).toFile())) {
                var entries = jar.entries();
                int examined = 0;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String path = entry.getName();
                    if (!path.endsWith(".class")) { continue; }
                    if (path.startsWith("META-INF/versions/")
                            && Integer.parseInt(path.split("/")[2]) > 17) { continue; }
                    try (var input = new DataInputStream(jar.getInputStream(entry))) {
                        assertEquals(0xCAFEBABE, input.readInt());
                        input.readUnsignedShort();
                        assertTrue(input.readUnsignedShort() <= 61, name + ": " + path);
                        examined++;
                    }
                }
                assertTrue(examined > 0, name);
            }
        }
    }

    @Test
    void completeJgltfLicenseIsPackagedAndJacksonEmbeddedNoticesRemainIntact() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/licenses/jgltf-LICENSE.txt")) {
            assertNotNull(input, "JglTF upstream jars omit the top-level license; package it explicitly");
            assertEquals("53628709bbc440617513f9d4f0dde16b286034eba56ad8b41b58a1b6f26a0d2b",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }
        for (String name : DEPENDENCY_CLASSES.subList(3, 6)) {
            try (JarFile jar = new JarFile(jarFor(name).toFile())) {
                assertNotNull(jar.getEntry("META-INF/LICENSE"));
                assertNotNull(jar.getEntry("META-INF/NOTICE"));
                if (name.endsWith("JsonFactory")) {
                    for (String notice : List.of("FastDoubleParser-LICENSE",
                            "FastDoubleParser-ThirdParty-LICENSE", "Schubfach-LICENSE")) {
                        assertNotNull(jar.getEntry("META-INF/" + notice), notice);
                    }
                }
            }
        }
    }

    private static Path jarFor(String name) throws Exception {
        return Path.of(Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
