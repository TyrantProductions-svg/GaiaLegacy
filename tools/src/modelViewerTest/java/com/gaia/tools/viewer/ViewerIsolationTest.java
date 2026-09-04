package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewerIsolationTest {
    @Test
    void runtimeClasspathUsesEngineAndInspectorButNeverGameOrToolsMainOutput() {
        String classpath = System.getProperty("viewer.runtimeClasspath", "");
        String normalized = classpath.replace('\\', '/').toLowerCase();
        assertTrue(normalized.contains("engine"));
        assertTrue(normalized.contains("modelinspector"));
        assertFalse(normalized.contains("game/build"));
        assertFalse(normalized.contains("tools/build/classes/java/main"));
        assertFalse(normalized.contains("tools/build/resources/main"));
    }

    @Test
    void viewerProductionSourceCannotParseGlbJsonAccessorsOrImagesDirectly() throws Exception {
        Path root = Path.of(System.getProperty("viewer.projectRoot"));
        Path source = root.resolve("tools/src/modelViewer/java");
        List<String> forbidden = List.of("de.javagl", "com.fasterxml.jackson", "javax.imageio",
                "GlbPreflight", "GlbAdmission", "BufferAccess");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String token : forbidden) {
                    assertFalse(text.contains(token), file + " must not use " + token);
                }
            }
        }
    }

    @Test
    void packagedViewerResourcesAndMainClassArePresent() throws Exception {
        assertNotNull(ModelViewerMain.class.getResource("/assets/gaia/model-viewer/preview.vert"));
        assertNotNull(ModelViewerMain.class.getResource("/assets/gaia/model-viewer/preview.frag"));
        assertTrue(ViewerApplication.class.getDeclaredMethod("launch", Path.class,
                com.gaia.tools.model.GaiaGlbValidator.Result.class, ViewerCpuModel.class) != null);
    }
}
