# Resource and Material Asset System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic, JAR-safe, data-driven block/material/atlas pipeline while preserving GaiaLegacy's existing block IDs, terrain appearance, lifecycle, and OpenGL thread ownership.

**Architecture:** The engine supplies immutable namespaced asset primitives, classloader-stream discovery, CPU-side texture data, atlas/material values, and a narrow block-render resolver. The game module strictly parses Gaia JSON into an immutable `GaiaAssetCatalog`, injects numeric block IDs into world generation, injects render data into meshing, and gives CPU texture pixels to the existing main-thread Renderer for GPU upload.

**Tech Stack:** Java 17 source compatibility, Gradle Wrapper, Gson 2.10.1 in `game`, LWJGL/STB 3.3.3 in `engine`, JUnit Jupiter 6.1.1, OpenGL 4.1 / GLSL 410.

## Global Constraints

- Work only on `feat/resource-material-system`; never modify, merge, or push `main`, and never force-push.
- Preserve Java 17 source and target compatibility; JDK 21 may run Gradle, but no platform-specific JDK path may enter the repository.
- Use the checked-in Gradle Wrapper for every build and test command.
- Keep all OpenGL calls and GPU creation, upload, bind, and destruction on the Phase 1 OpenGL-context main thread.
- Keep GLSL at `#version 410 core`; do not add OpenGL 4.3+, compute shaders, or platform APIs.
- Do not change terrain shape, grass/dirt/stone appearance, player movement, input ownership, fixed 1/60 update, or lifecycle order.
- The engine must not depend on `game` or Gson.
- Use constructor injection or explicit context; do not expand `ServiceLocator`.
- Do not copy code or assets from Terasology, Create, or other projects.
- Do not add transparent sorting, blending queues, item gameplay, module hot reload, or resource override precedence in Phase 2.
- Preserve fixed unsigned IDs `air=0`, `grass=1`, `dirt=2`, and `stone=3`.
- Do not commit generated output, `.class`, `bin/`, crash dumps, IDE files, or local caches.

---

## File Structure

### Engine assets

- Create `engine/src/main/java/com/overlord/assets/ResourceLocation.java` — validated namespaced identifier and classpath mapping.
- Create `engine/src/main/java/com/overlord/assets/AssetSource.java` — one discovered classpath URL with a stable source label.
- Create `engine/src/main/java/com/overlord/assets/AssetSeverity.java` — warning/error severity.
- Create `engine/src/main/java/com/overlord/assets/AssetDiagnostic.java` — structured source/resource/field/fallback diagnostic.
- Create `engine/src/main/java/com/overlord/assets/AssetLoadReport.java` — immutable diagnostics plus a builder used during loading.
- Create `engine/src/main/java/com/overlord/assets/AssetLoadException.java` — aggregate fatal load exception.
- Create `engine/src/main/java/com/overlord/assets/AssetManager.java` — index discovery and unique stream-based resource access.
- Create `engine/src/main/java/com/overlord/assets/ResourceIndex.java` — namespace manifest value.

### Engine render data

- Create `engine/src/main/java/com/overlord/renderer/material/RenderType.java`.
- Create `engine/src/main/java/com/overlord/renderer/material/MaterialDefinition.java`.
- Create `engine/src/main/java/com/overlord/renderer/texture/TextureRegion.java`.
- Create `engine/src/main/java/com/overlord/renderer/texture/TextureAtlasMetadata.java`.
- Create `engine/src/main/java/com/overlord/renderer/texture/TextureImage.java`.
- Create `engine/src/main/java/com/overlord/renderer/texture/TextureImageLoader.java`.
- Create `engine/src/main/java/com/overlord/renderer/RenderAssets.java`.
- Create `engine/src/main/java/com/overlord/voxel/BlockFace.java`.
- Create `engine/src/main/java/com/overlord/voxel/BlockRenderInfo.java`.
- Create `engine/src/main/java/com/overlord/voxel/BlockRenderResolver.java`.
- Modify `engine/src/main/java/com/overlord/renderer/Texture.java` — upload supplied CPU pixels instead of opening a hard-coded resource.
- Modify `engine/src/main/java/com/overlord/renderer/Renderer.java` — receive `RenderAssets`.
- Modify `engine/src/main/java/com/overlord/core/Engine.java` — receive `RenderAssets` while retaining a safe engine-only default.
- Modify `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java` — resolve six face regions without block constants.
- Modify `engine/src/main/java/com/overlord/config/GameConfig.java` — remove atlas-layout constants after all callers use metadata.

### Gaia definitions and loading

- Create `game/src/main/java/com/gaia/blocks/ItemFormDefinition.java`.
- Create `game/src/main/java/com/gaia/blocks/BlockDefinition.java`.
- Replace `game/src/main/java/com/gaia/blocks/BlockRegistry.java` — immutable maps by unsigned numeric ID and `ResourceLocation`, also implementing `BlockRenderResolver`.
- Delete `game/src/main/java/com/gaia/blocks/Block.java` after caller migration in Task 10.
- Delete `game/src/main/java/com/gaia/blocks/BlockProperties.java` after caller migration in Task 10.
- Create `game/src/main/java/com/gaia/assets/StrictJson.java` — checked Gson tree access with unknown-field rejection.
- Create `game/src/main/java/com/gaia/assets/GaiaAssetCatalog.java`.
- Create `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`.
- Modify `game/src/main/java/com/gaia/GameBootstrap.java`.
- Modify `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`.
- Modify `game/src/main/java/com/gaia/world/WorldLoader.java`.

### Resources and documentation

- Create `game/src/main/resources/META-INF/gaialegacy/resource-indexes.list`.
- Create `game/src/main/resources/assets/gaia/resource-index.json`.
- Create `game/src/main/resources/assets/gaia/blocks/air.json`.
- Move/replace `game/src/main/resources/blocks/grass.json` as `game/src/main/resources/assets/gaia/blocks/grass.json`.
- Move/replace `game/src/main/resources/blocks/dirt.json` as `game/src/main/resources/assets/gaia/blocks/dirt.json`.
- Create `game/src/main/resources/assets/gaia/blocks/stone.json`.
- Create `game/src/main/resources/assets/gaia/materials/opaque.json`.
- Create `game/src/main/resources/assets/gaia/materials/missing.json`.
- Create `game/src/main/resources/assets/gaia/atlases/blocks.json`.
- Move/edit `game/src/main/resources/textures/atlas.png` as `game/src/main/resources/assets/gaia/textures/atlas.png`.
- Modify `game/build.gradle` — verify packaged resource entries.
- Create `THIRD_PARTY_NOTICES.md`.
- Create `docs/references.md`.
- Create `docs/agent-handoffs/phase-02-handoff.md` at completion.

---

### Task 1: Validated Namespaced Resource Locations

**Files:**
- Create: `engine/src/main/java/com/overlord/assets/ResourceLocation.java`
- Test: `engine/src/test/java/com/overlord/assets/ResourceLocationTest.java`

**Interfaces:**
- Consumes: Java `record`, `Pattern`, and `Objects`.
- Produces: `ResourceLocation.parse(String)`, `ResourceLocation.of(String, String)`, `namespace()`, `path()`, `toClasspathPath()`, and stable `toString()`.

- [ ] **Step 1: Write the failing identifier tests**

```java
package com.overlord.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ResourceLocationTest {
    @Test
    void parsesAndMapsACompleteIdentifier() {
        ResourceLocation location =
                ResourceLocation.parse("gaia:textures/atlas.png");

        assertEquals("gaia", location.namespace());
        assertEquals("textures/atlas.png", location.path());
        assertEquals(
                "assets/gaia/textures/atlas.png",
                location.toClasspathPath());
        assertEquals("gaia:textures/atlas.png", location.toString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grass",
                ":grass",
                "gaia:",
                "Gaia:grass",
                "gaia:Grass",
                "gaia:/grass",
                "gaia:grass/",
                "gaia:blocks//grass",
                "gaia:blocks/../grass",
                "gaia:blocks/./grass",
                "gaia:\\blocks\\grass",
                "gaia:C:/grass"
            })
    void rejectsIncompleteOrUnsafeIdentifiers(String text) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourceLocation.parse(text));
    }
}
```

- [ ] **Step 2: Run the identifier test and confirm RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.assets.ResourceLocationTest
```

Expected: compilation fails because `ResourceLocation` does not exist.

- [ ] **Step 3: Implement `ResourceLocation`**

```java
package com.overlord.assets;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourceLocation(String namespace, String path)
        implements Comparable<ResourceLocation> {
    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    public ResourceLocation {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid resource namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "Invalid resource path: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Resource path cannot traverse directories: " + path);
            }
        }
    }

    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String text) {
        Objects.requireNonNull(text, "text");
        int separator = text.indexOf(':');
        if (separator <= 0
                || separator == text.length() - 1
                || separator != text.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "Resource location must be namespace:path: " + text);
        }
        return of(
                text.substring(0, separator),
                text.substring(separator + 1));
    }

    public String toClasspathPath() {
        return "assets/" + namespace + "/" + path;
    }

    @Override
    public int compareTo(ResourceLocation other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
```

- [ ] **Step 4: Run the focused and engine test suites**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.assets.ResourceLocationTest
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/assets/ResourceLocation.java engine/src/test/java/com/overlord/assets/ResourceLocationTest.java
git commit -m "feat(assets): add namespaced resource locations"
```

---

### Task 2: Structured Diagnostics and JAR-Safe Asset Discovery

**Files:**
- Create: `engine/src/main/java/com/overlord/assets/AssetSource.java`
- Create: `engine/src/main/java/com/overlord/assets/AssetSeverity.java`
- Create: `engine/src/main/java/com/overlord/assets/AssetDiagnostic.java`
- Create: `engine/src/main/java/com/overlord/assets/AssetLoadReport.java`
- Create: `engine/src/main/java/com/overlord/assets/AssetLoadException.java`
- Create: `engine/src/main/java/com/overlord/assets/AssetManager.java`
- Create: `engine/src/main/java/com/overlord/assets/ResourceIndex.java`
- Test: `engine/src/test/java/com/overlord/assets/AssetManagerTest.java`

**Interfaces:**
- Consumes: `ResourceLocation`.
- Produces:
  - `new AssetManager(ClassLoader)`
  - `List<AssetSource> discoverResourceIndexes()`
  - `InputStream open(ResourceLocation)`
  - `String readUtf8(ResourceLocation)`
  - `AssetLoadReport.Builder.add(AssetDiagnostic)`
  - `AssetLoadReport.Builder.addAll(AssetLoadReport)`
  - `AssetLoadReport.Builder.throwIfErrors()`
  - `ResourceIndex(String, List<String>, List<String>, List<String>)`

- [ ] **Step 1: Write failing directory, JAR, missing, and ambiguity tests**

Create a test helper that writes exact entry bytes to a temporary JAR:

```java
private static Path jar(Path path, Map<String, String> entries)
        throws IOException {
    try (JarOutputStream output =
            new JarOutputStream(Files.newOutputStream(path))) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            output.putNextEntry(new JarEntry(entry.getKey()));
            output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
    return path;
}
```

Test these exact outcomes in
`engine/src/test/java/com/overlord/assets/AssetManagerTest.java`:

```java
@Test
void discoversIndexAndReadsAssetFromJar() throws Exception {
    Path jar =
            jar(
                    temp.resolve("assets.jar"),
                    Map.of(
                            "META-INF/gaialegacy/resource-indexes.list",
                            "# Gaia\nassets/gaia/resource-index.json\n",
                            "assets/gaia/resource-index.json",
                            "{\"namespace\":\"gaia\"}",
                            "assets/gaia/blocks/grass.json",
                            "{\"id\":1}"));
    try (URLClassLoader loader =
            new URLClassLoader(
                    new URL[] {jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
        AssetManager manager = new AssetManager(loader);
        List<AssetSource> indexes = manager.discoverResourceIndexes();

        assertEquals(1, indexes.size());
        assertEquals(
                "assets/gaia/resource-index.json",
                indexes.get(0).classpathPath());
        assertEquals(
                "{\"id\":1}",
                manager.readUtf8(
                        ResourceLocation.parse("gaia:blocks/grass.json")));
    }
}

@Test
void rejectsMissingAndAmbiguousResources() throws Exception {
    AssetManager empty =
            new AssetManager(ClassLoader.getPlatformClassLoader());
    AssetLoadException missing =
            assertThrows(
                    AssetLoadException.class,
                    () -> empty.open(
                            ResourceLocation.parse("gaia:missing.json")));
    assertEquals("ASSET_NOT_FOUND",
            missing.report().errors().get(0).code());

    Path first =
            jar(
                    temp.resolve("first.jar"),
                    Map.of("assets/gaia/shared.json", "first"));
    Path second =
            jar(
                    temp.resolve("second.jar"),
                    Map.of("assets/gaia/shared.json", "second"));
    try (URLClassLoader loader =
            new URLClassLoader(
                    new URL[] {
                        first.toUri().toURL(),
                        second.toUri().toURL()
                    },
                    ClassLoader.getPlatformClassLoader())) {
        AssetLoadException ambiguous =
                assertThrows(
                        AssetLoadException.class,
                        () -> new AssetManager(loader).open(
                                ResourceLocation.parse("gaia:shared.json")));
        assertEquals(
                "ASSET_AMBIGUOUS",
                ambiguous.report().errors().get(0).code());
    }
}
```

Add this directory-classpath test:

```java
@Test
void discoversIndexFromDirectoryClasspath() throws Exception {
    Path root = Files.createDirectories(temp.resolve("classes"));
    Path list =
            root.resolve(
                    "META-INF/gaialegacy/resource-indexes.list");
    Files.createDirectories(list.getParent());
    Files.writeString(
            list,
            "assets/gaia/resource-index.json\n",
            StandardCharsets.UTF_8);
    Path manifest =
            root.resolve("assets/gaia/resource-index.json");
    Files.createDirectories(manifest.getParent());
    Files.writeString(
            manifest,
            "{\"namespace\":\"gaia\"}",
            StandardCharsets.UTF_8);

    try (URLClassLoader loader =
            new URLClassLoader(
                    new URL[] {root.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
        List<AssetSource> sources =
                new AssetManager(loader)
                        .discoverResourceIndexes();
        assertEquals(1, sources.size());
        assertEquals(
                "assets/gaia/resource-index.json",
                sources.get(0).classpathPath());
    }
}
```

For deterministic discovery, create two JARs with distinct manifest paths,
pass their URLs to `URLClassLoader` in reverse lexical order, and assert:

```java
assertEquals(
        sources.stream().sorted().toList(),
        sources);
```

- [ ] **Step 2: Run the asset manager test and confirm RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.assets.AssetManagerTest
```

Expected: compilation fails because the asset access and diagnostic classes do
not exist.

- [ ] **Step 3: Implement the diagnostic values**

Use these exact records and builder contract:

```java
public enum AssetSeverity {
    WARNING,
    ERROR
}
```

```java
public record AssetDiagnostic(
        AssetSeverity severity,
        String code,
        String source,
        ResourceLocation resource,
        String field,
        String message,
        ResourceLocation fallback) {
    public AssetDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
    }
}
```

```java
public final class AssetLoadReport {
    private final List<AssetDiagnostic> diagnostics;

    private AssetLoadReport(List<AssetDiagnostic> diagnostics) {
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<AssetDiagnostic> diagnostics() {
        return diagnostics;
    }

    public List<AssetDiagnostic> warnings() {
        return diagnostics.stream()
                .filter(d -> d.severity() == AssetSeverity.WARNING)
                .toList();
    }

    public List<AssetDiagnostic> errors() {
        return diagnostics.stream()
                .filter(d -> d.severity() == AssetSeverity.ERROR)
                .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<AssetDiagnostic> diagnostics =
                new ArrayList<>();

        public void add(AssetDiagnostic diagnostic) {
            diagnostics.add(Objects.requireNonNull(
                    diagnostic, "diagnostic"));
        }

        public void addAll(AssetLoadReport report) {
            diagnostics.addAll(
                    Objects.requireNonNull(report, "report")
                            .diagnostics());
        }

        public AssetLoadReport build() {
            return new AssetLoadReport(diagnostics);
        }

        public void throwIfErrors() {
            AssetLoadReport report = build();
            if (!report.errors().isEmpty()) {
                throw new AssetLoadException(report);
            }
        }
    }
}
```

`AssetLoadException` stores the report and builds a message containing every
error code and source:

```java
public final class AssetLoadException extends RuntimeException {
    private final AssetLoadReport report;

    public AssetLoadException(AssetLoadReport report) {
        super(
                report.errors().stream()
                        .map(d -> d.code() + " at " + d.source()
                                + ": " + d.message())
                        .collect(Collectors.joining(
                                System.lineSeparator(),
                                "Asset loading failed:"
                                        + System.lineSeparator(),
                                "")));
        this.report = Objects.requireNonNull(report, "report");
    }

    public AssetLoadReport report() {
        return report;
    }
}
```

- [ ] **Step 4: Implement discovery and stream access**

`AssetSource` and `ResourceIndex` are immutable:

```java
public record AssetSource(String classpathPath, URL url)
        implements Comparable<AssetSource> {
    public AssetSource {
        Objects.requireNonNull(classpathPath, "classpathPath");
        Objects.requireNonNull(url, "url");
    }

    public InputStream open() throws IOException {
        return url.openStream();
    }

    @Override
    public int compareTo(AssetSource other) {
        int pathOrder = classpathPath.compareTo(other.classpathPath);
        return pathOrder != 0
                ? pathOrder
                : url.toExternalForm().compareTo(
                        other.url.toExternalForm());
    }
}
```

```java
public record ResourceIndex(
        String namespace,
        List<String> blocks,
        List<String> materials,
        List<String> atlases) {
    public ResourceIndex {
        Objects.requireNonNull(namespace, "namespace");
        blocks = List.copyOf(blocks);
        materials = List.copyOf(materials);
        atlases = List.copyOf(atlases);
    }
}
```

Implement `AssetManager` with:

```java
public static final String INDEX_LIST_PATH =
        "META-INF/gaialegacy/resource-indexes.list";

public List<AssetSource> discoverResourceIndexes() {
    try {
        SortedSet<String> manifestPaths = new TreeSet<>();
        Enumeration<URL> lists =
                classLoader.getResources(INDEX_LIST_PATH);
        while (lists.hasMoreElements()) {
            URL listUrl = lists.nextElement();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    listUrl.openStream(),
                                    StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#"))
                        .peek(AssetManager::validateManifestPath)
                        .forEach(manifestPaths::add);
            }
        }

        SortedSet<AssetSource> result = new TreeSet<>();
        for (String path : manifestPaths) {
            Enumeration<URL> manifests =
                    classLoader.getResources(path);
            if (!manifests.hasMoreElements()) {
                throw failure(
                        "ASSET_INDEX_NOT_FOUND",
                        path,
                        null,
                        "index",
                        "Discovery entry has no matching manifest");
            }
            while (manifests.hasMoreElements()) {
                result.add(new AssetSource(
                        path, manifests.nextElement()));
            }
        }
        return List.copyOf(result);
    } catch (IOException failure) {
        throw failure(
                "ASSET_IO",
                INDEX_LIST_PATH,
                null,
                "index",
                failure.getMessage());
    }
}

public InputStream open(ResourceLocation location) {
    String path = location.toClasspathPath();
    try {
        List<URL> matches =
                Collections.list(classLoader.getResources(path));
        if (matches.isEmpty()) {
            throw failure(
                    "ASSET_NOT_FOUND",
                    path,
                    location,
                    null,
                    "Resource is not present on the classpath");
        }
        if (matches.size() != 1) {
            throw failure(
                    "ASSET_AMBIGUOUS",
                    path,
                    location,
                    null,
                    "Resource has " + matches.size()
                            + " classpath owners");
        }
        return matches.get(0).openStream();
    } catch (IOException failure) {
        throw failure(
                "ASSET_IO",
                path,
                location,
                null,
                failure.getMessage());
    }
}

public String readUtf8(ResourceLocation location) {
    try (InputStream input = open(location)) {
        return new String(
                input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
        throw failure(
                "ASSET_IO",
                location.toClasspathPath(),
                location,
                null,
                failure.getMessage());
    }
}
```

`validateManifestPath` accepts only
`assets/<valid-namespace>/<safe-relative-path>` using the same segment rules as
`ResourceLocation`. The private `failure` helper creates one ERROR diagnostic
and returns `new AssetLoadException(builder.build())`.

- [ ] **Step 5: Run focused and full engine tests**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.assets.AssetManagerTest
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/assets engine/src/test/java/com/overlord/assets
git commit -m "feat(assets): discover indexed resources from classpath streams"
```

---

### Task 3: Immutable Material, Atlas, Texture, and Block Render Values

**Files:**
- Create: `engine/src/main/java/com/overlord/renderer/material/RenderType.java`
- Create: `engine/src/main/java/com/overlord/renderer/material/MaterialDefinition.java`
- Create: `engine/src/main/java/com/overlord/renderer/texture/TextureRegion.java`
- Create: `engine/src/main/java/com/overlord/renderer/texture/TextureAtlasMetadata.java`
- Create: `engine/src/main/java/com/overlord/renderer/texture/TextureImage.java`
- Create: `engine/src/main/java/com/overlord/renderer/texture/TextureImageLoader.java`
- Create: `engine/src/main/java/com/overlord/renderer/RenderAssets.java`
- Create: `engine/src/main/java/com/overlord/voxel/BlockFace.java`
- Create: `engine/src/main/java/com/overlord/voxel/BlockRenderInfo.java`
- Create: `engine/src/main/java/com/overlord/voxel/BlockRenderResolver.java`
- Test: `engine/src/test/java/com/overlord/renderer/texture/TextureAtlasMetadataTest.java`
- Test: `engine/src/test/java/com/overlord/renderer/texture/TextureImageLoaderTest.java`

**Interfaces:**
- Consumes: `ResourceLocation`, `AssetManager`, `AssetDiagnostic`.
- Produces:
  - `RenderType.parse(String)`
  - `TextureRegion.uMin()`, `uMax()`, `vMin()`, `vMax()`
  - validated `TextureAtlasMetadata`
  - `TextureImageLoader.load(AssetManager, ResourceLocation, Consumer<AssetDiagnostic>)`
  - `RenderAssets.missing()`
  - `BlockRenderResolver.resolve(int unsignedBlockId)`

- [ ] **Step 1: Write failing atlas and CPU texture fallback tests**

Test exact UV values and bounds:

```java
@Test
void convertsPixelBoundsToNormalizedUvs() {
    TextureRegion region =
            new TextureRegion(
                    ResourceLocation.parse("gaia:grass_side"),
                    16, 0, 16, 16, 128, 64);

    assertEquals(0.125f, region.uMin(), 1.0e-6f);
    assertEquals(0.25f, region.uMax(), 1.0e-6f);
    assertEquals(0.0f, region.vMin(), 1.0e-6f);
    assertEquals(0.25f, region.vMax(), 1.0e-6f);
}

@Test
void rejectsRegionOutsideAtlas() {
    assertThrows(
            IllegalArgumentException.class,
            () -> new TextureRegion(
                    ResourceLocation.parse("gaia:bad"),
                    120, 0, 16, 16, 128, 64));
}
```

Also assert all supported render types parse:

```java
assertEquals(RenderType.OPAQUE, RenderType.parse("opaque"));
assertEquals(RenderType.CUTOUT, RenderType.parse("cutout"));
assertEquals(
        RenderType.TRANSPARENT,
        RenderType.parse("transparent"));
assertThrows(
        IllegalArgumentException.class,
        () -> RenderType.parse("compute"));
```

Test fallback without OpenGL:

```java
@Test
void createsPurpleBlackFallbackWhenImageIsMissing() {
    AssetManager assets =
            new AssetManager(ClassLoader.getPlatformClassLoader());
    List<AssetDiagnostic> diagnostics = new ArrayList<>();

    TextureImage image =
            new TextureImageLoader()
                    .load(
                            assets,
                            ResourceLocation.parse(
                                    "gaia:textures/missing.png"),
                            diagnostics::add);

    assertEquals(2, image.width());
    assertEquals(2, image.height());
    assertEquals("ASSET_TEXTURE_FALLBACK",
            diagnostics.get(0).code());
    assertEquals(16, image.rgbaPixels().remaining());
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.texture.*"
```

Expected: compilation fails because render data and texture image classes do not
exist.

- [ ] **Step 3: Implement material, atlas, and block render values**

Use:

```java
public enum RenderType {
    OPAQUE,
    CUTOUT,
    TRANSPARENT;

    public static RenderType parse(String text) {
        return valueOf(text.toUpperCase(Locale.ROOT));
    }
}
```

```java
public record MaterialDefinition(
        ResourceLocation id,
        ResourceLocation atlas,
        RenderType renderType,
        float alphaCutoff,
        ResourceLocation missingRegion) {
    public MaterialDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(renderType, "renderType");
        Objects.requireNonNull(missingRegion, "missingRegion");
        if (!Float.isFinite(alphaCutoff)
                || alphaCutoff < 0.0f
                || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException(
                    "alphaCutoff must be finite and within 0..1");
        }
    }
}
```

`TextureRegion` validates positive atlas/region dimensions, non-negative
coordinates, and `x + width <= atlasWidth`,
`y + height <= atlasHeight`. Its four UV methods divide by the stored atlas
dimensions.

`TextureAtlasMetadata` is:

```java
public record TextureAtlasMetadata(
        ResourceLocation id,
        ResourceLocation texture,
        int width,
        int height,
        Map<ResourceLocation, TextureRegion> regions) {
    public TextureAtlasMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(texture, "texture");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must be positive");
        }
        regions = Map.copyOf(regions);
        for (TextureRegion region : regions.values()) {
            if (region.atlasWidth() != width
                    || region.atlasHeight() != height) {
                throw new IllegalArgumentException(
                        "Region dimensions do not match atlas " + id);
            }
        }
    }

    public TextureRegion requireRegion(ResourceLocation region) {
        TextureRegion value = regions.get(region);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown texture region: " + region);
        }
        return value;
    }
}
```

Define `BlockFace` in this order:

```java
public enum BlockFace {
    NORTH,
    SOUTH,
    UP,
    DOWN,
    WEST,
    EAST
}
```

Define:

```java
@FunctionalInterface
public interface BlockRenderResolver {
    BlockRenderInfo resolve(int unsignedBlockId);
}
```

`BlockRenderInfo` copies an `EnumMap<BlockFace, TextureRegion>`, requires all
six faces when `renderable` is true, and exposes
`TextureRegion region(BlockFace)`. Its
`nonRenderable(MaterialDefinition, TextureRegion)` factory fills all faces
with the supplied fallback while returning `renderable=false`.

- [ ] **Step 4: Implement CPU texture loading and procedural fallback**

`TextureImage` validates a direct RGBA buffer with exactly
`width * height * 4` remaining bytes and stores a read-only slice.

`TextureImageLoader.load`:

1. opens the asset through `AssetManager`;
2. copies encoded bytes into a direct `BufferUtils` buffer;
3. decodes RGBA with `STBImage.stbi_load_from_memory`;
4. copies decoded bytes into an owned direct buffer;
5. always frees the STB buffer;
6. catches `AssetLoadException` only when every contained error code is
   `ASSET_NOT_FOUND`; ambiguous ownership and other asset-access errors remain
   fatal;
7. on missing or decode failure, emits this warning and returns
   `TextureImage.missing()`:

```java
new AssetDiagnostic(
        AssetSeverity.WARNING,
        "ASSET_TEXTURE_FALLBACK",
        location.toClasspathPath(),
        location,
        "texture",
        "Texture was missing or could not be decoded",
        ResourceLocation.of(location.namespace(), "missing"))
```

The fallback bytes are exactly:

```java
byte[] rgba = {
    (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF,
    0x00, 0x00, 0x00, (byte) 0xFF,
    0x00, 0x00, 0x00, (byte) 0xFF,
    (byte) 0xB0, 0x00, (byte) 0xB0, (byte) 0xFF
};
```

`RenderAssets` contains one `TextureImage blockAtlas` and its `missing()`
factory returns `TextureImage.missing()`.

- [ ] **Step 5: Run focused and complete engine tests**

Run:

```powershell
.\gradlew.bat :engine:test --tests "com.overlord.renderer.texture.*"
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`; no GLFW window opens.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/renderer engine/src/main/java/com/overlord/voxel/BlockFace.java engine/src/main/java/com/overlord/voxel/BlockRenderInfo.java engine/src/main/java/com/overlord/voxel/BlockRenderResolver.java engine/src/test/java/com/overlord/renderer
git commit -m "feat(rendering): add material and atlas metadata values"
```

---

### Task 4: Immutable Block Definitions and Dual-Key Registry

**Files:**
- Create: `game/src/main/java/com/gaia/blocks/ItemFormDefinition.java`
- Create: `game/src/main/java/com/gaia/blocks/BlockDefinition.java`
- Replace: `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- Test: `game/src/test/java/com/gaia/blocks/BlockRegistryTest.java`

**Interfaces:**
- Consumes: `ResourceLocation`, `BlockFace`, `BlockRenderInfo`,
  `BlockRenderResolver`.
- Produces:
  - `BlockRegistry.create(Collection<BlockDefinition>, Map<Integer, BlockRenderInfo>)`
  - `BlockDefinition require(ResourceLocation)`
  - `BlockDefinition require(int unsignedId)`
  - `byte requireStoredId(ResourceLocation)`
  - `BlockRenderInfo resolve(int unsignedBlockId)`

- [ ] **Step 1: Write failing fixed-ID, duplicate, and unsigned tests**

```java
@Test
void indexesDefinitionsByNameAndUnsignedId() {
    BlockDefinition air = definition(0, "gaia:air");
    BlockDefinition high = definition(200, "gaia:high");
    BlockRegistry registry =
            BlockRegistry.create(
                    List.of(air, high),
                    Map.of(
                            0, renderInfo(false),
                            200, renderInfo(true)));

    assertEquals(
            ResourceLocation.parse("gaia:high"),
            registry.require((byte) 200).name());
    assertEquals((byte) 200,
            registry.requireStoredId(
                    ResourceLocation.parse("gaia:high")));
    assertTrue(registry.resolve(200).renderable());
}

@Test
void rejectsDuplicateNumericAndLogicalIds() {
    assertThrows(
            IllegalArgumentException.class,
            () -> BlockRegistry.create(
                    List.of(
                            definition(1, "gaia:first"),
                            definition(1, "gaia:second")),
                    Map.of()));
    assertThrows(
            IllegalArgumentException.class,
            () -> BlockRegistry.create(
                    List.of(
                            definition(1, "gaia:same"),
                            definition(2, "gaia:same")),
                    Map.of()));
}
```

Use these test helpers so every definition is complete:

```java
private static final ResourceLocation MISSING =
        ResourceLocation.parse("gaia:missing");
private static final MaterialDefinition MATERIAL =
        new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                MISSING);
private static final TextureRegion REGION =
        new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

private static BlockDefinition definition(int id, String name) {
    EnumMap<BlockFace, ResourceLocation> textures =
            new EnumMap<>(BlockFace.class);
    for (BlockFace face : BlockFace.values()) {
        textures.put(face, MISSING);
    }
    return new BlockDefinition(
            id,
            ResourceLocation.parse(name),
            MATERIAL.id(),
            textures,
            1.0f,
            1.0f,
            1.0f,
            false,
            false,
            1.0f,
            id == 0
                    ? null
                    : new ItemFormDefinition(
                            ResourceLocation.parse(name),
                            64,
                            false,
                            false));
}

private static BlockRenderInfo renderInfo(boolean renderable) {
    EnumMap<BlockFace, TextureRegion> faces =
            new EnumMap<>(BlockFace.class);
    for (BlockFace face : BlockFace.values()) {
        faces.put(face, REGION);
    }
    return renderable
            ? new BlockRenderInfo(true, MATERIAL, faces)
            : BlockRenderInfo.nonRenderable(MATERIAL, REGION);
}
```

- [ ] **Step 2: Run the registry test and confirm RED**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.blocks.BlockRegistryTest
```

Expected: compilation fails because the new definitions and registry API do not
exist.

- [ ] **Step 3: Implement item and block definition records**

```java
public record ItemFormDefinition(
        ResourceLocation id,
        int maxStackSize,
        boolean mouthHoldable,
        boolean twoHanded) {
    public ItemFormDefinition {
        Objects.requireNonNull(id, "id");
        if (maxStackSize < 1 || maxStackSize > 64) {
            throw new IllegalArgumentException(
                    "maxStackSize must be within 1..64");
        }
    }
}
```

```java
public record BlockDefinition(
        int id,
        ResourceLocation name,
        ResourceLocation material,
        Map<BlockFace, ResourceLocation> textures,
        float hardness,
        float structuralIntegrity,
        float tolerance,
        boolean gravity,
        boolean flammable,
        float blastResistance,
        ItemFormDefinition item) {
    public BlockDefinition {
        if (id < 0 || id > 255) {
            throw new IllegalArgumentException(
                    "Block id must be within 0..255");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(material, "material");
        textures = Map.copyOf(textures);
        requireFiniteNonNegative("hardness", hardness);
        requireFiniteNonNegative(
                "structuralIntegrity", structuralIntegrity);
        requireFiniteNonNegative("tolerance", tolerance);
        requireFiniteNonNegative(
                "blastResistance", blastResistance);
    }

    public boolean renderable() {
        return id != 0;
    }

    private static void requireFiniteNonNegative(
            String field, float value) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(
                    field + " must be finite and non-negative");
        }
    }
}
```

- [ ] **Step 4: Add the immutable registry while preserving a bounded migration bridge**

`BlockRegistry` is final, contains unmodifiable instance maps by `Integer` and
`ResourceLocation`, and implements `BlockRenderResolver`.

`create` enforces:

```java
for (BlockDefinition definition : definitions) {
    if (byId.putIfAbsent(definition.id(), definition) != null) {
        throw new IllegalArgumentException(
                "Duplicate block id: " + definition.id());
    }
    if (byName.putIfAbsent(
            definition.name(), definition) != null) {
        throw new IllegalArgumentException(
                "Duplicate block name: " + definition.name());
    }
}
if (!byId.containsKey(0)) {
    throw new IllegalArgumentException(
            "Block registry requires id 0 air");
}
```

`require(byte)` delegates through `Byte.toUnsignedInt`. Unknown IDs throw a
message containing the unsigned value. `resolve` returns the ID-zero render
info for an unknown world byte so corrupt/unrecognized world data is treated as
empty instead of selecting a concrete Gaia block.

Keep the existing static `AIR`, `GRASS`, `DIRT`, `STONE`, `init()`, and
`loadAllFromResources()` compatibility section temporarily so the pre-migration
Bootstrap and world callers still compile at this commit. Rename its backing
map to `legacyBlocks` so it cannot collide with the new instance maps. Add this
exact comment above that section:

```java
// Migration bridge for callers removed in Tasks 8 and 10.
```

No new production caller may use the bridge. Task 8 removes the three terrain
constant callers; Task 10 removes the Bootstrap calls, the entire bridge, and
the obsolete `Block` and `BlockProperties` classes.

- [ ] **Step 5: Run the registry and complete game tests**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.blocks.BlockRegistryTest
.\gradlew.bat :game:test
```

Expected: both commands report `BUILD SUCCESSFUL`. The explicit migration bridge
keeps the untouched Bootstrap and world callers compiling until Tasks 8 and 10.

- [ ] **Step 6: Commit**

```powershell
git add game/src/main/java/com/gaia/blocks game/src/test/java/com/gaia/blocks/BlockRegistryTest.java
git commit -m "refactor(blocks): replace hard-coded block registry"
```

---

### Task 5: Strict Gaia JSON Loading and Cross-Resource Validation

**Files:**
- Create: `game/src/main/java/com/gaia/assets/StrictJson.java`
- Create: `game/src/main/java/com/gaia/assets/GaiaAssetCatalog.java`
- Create: `game/src/main/java/com/gaia/assets/GaiaResourceLoader.java`
- Test: `game/src/test/java/com/gaia/assets/GaiaResourceLoaderTest.java`
- Test helper: `game/src/test/java/com/gaia/assets/TestAssetJar.java`

**Interfaces:**
- Consumes: `AssetManager`, `ResourceIndex`, block/material/atlas values,
  `TextureImageLoader`.
- Produces:

```java
public record GaiaAssetCatalog(
        BlockRegistry blockRegistry,
        Map<ResourceLocation, MaterialDefinition> materials,
        Map<ResourceLocation, TextureAtlasMetadata> atlases,
        TextureAtlasMetadata blockAtlas,
        RenderAssets renderAssets,
        AssetLoadReport report) {}
```

and:

```java
public GaiaResourceLoader(AssetManager assetManager)
public GaiaAssetCatalog load()
```

- [ ] **Step 1: Write failing valid-JAR and invalid-resource tests**

`TestAssetJar` is:

```java
final class TestAssetJar implements AutoCloseable {
    private final URLClassLoader classLoader;

    private TestAssetJar(URLClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    static TestAssetJar create(
            Path jarPath, Map<String, byte[]> entries)
            throws IOException {
        try (JarOutputStream output =
                new JarOutputStream(
                        Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry
                    : new TreeMap<>(entries).entrySet()) {
                output.putNextEntry(
                        new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {jarPath.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader());
        return new TestAssetJar(loader);
    }

    ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public void close() throws IOException {
        classLoader.close();
    }
}
```

Use this complete 1x1 PNG test asset:

```java
private static final byte[] PNG =
        Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                + "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
```

The fixture atlas metadata uses width 1, height 1, and one
`test:missing` region at `x=0, y=0, width=1, height=1`.
The valid test JAR contains:

```text
META-INF/gaialegacy/resource-indexes.list
assets/test/resource-index.json
assets/test/blocks/air.json
assets/test/blocks/solid.json
assets/test/materials/opaque.json
assets/test/materials/missing.json
assets/test/atlases/blocks.json
assets/test/textures/atlas.png
```

Assert:

```java
GaiaAssetCatalog catalog =
        new GaiaResourceLoader(
                new AssetManager(testJar.classLoader()))
                .load();

assertEquals(
        1,
        Byte.toUnsignedInt(
                catalog.blockRegistry()
                        .requireStoredId(
                                ResourceLocation.parse(
                                        "test:solid"))));
assertEquals(
        RenderType.OPAQUE,
        catalog.materials()
                .get(ResourceLocation.parse("test:opaque"))
                .renderType());
assertTrue(catalog.report().errors().isEmpty());
```

Add individual tests asserting diagnostic codes for:

```text
ASSET_JSON_INVALID
ASSET_JSON_UNKNOWN_FIELD
ASSET_NAMESPACE_DUPLICATE
ASSET_NAMESPACE_MISMATCH
ASSET_BLOCK_ID_DUPLICATE
ASSET_BLOCK_NAME_DUPLICATE
ASSET_MATERIAL_ID_DUPLICATE
ASSET_ATLAS_ID_DUPLICATE
ASSET_ATLAS_REGION_ID_DUPLICATE
ASSET_ATLAS_REGION_BOUNDS
ASSET_MULTIPLE_BLOCK_ATLASES
ASSET_DEFINITION_NOT_FOUND
```

Add recoverable tests:

```java
assertEquals(
        "ASSET_MISSING_REGION",
        catalog.report().warnings().get(0).code());
assertSame(
        catalog.blockAtlas().requireRegion(
                ResourceLocation.parse("test:missing")),
        catalog.blockRegistry().resolve(1).region(BlockFace.UP));
```

and the equivalent `ASSET_MISSING_MATERIAL` assertion.

- [ ] **Step 2: Run the loader test and confirm RED**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.assets.GaiaResourceLoaderTest
```

Expected: compilation fails because `StrictJson`, `GaiaAssetCatalog`, and
`GaiaResourceLoader` do not exist.

- [ ] **Step 3: Implement strict JSON access**

`StrictJson` wraps a `JsonObject` plus source string. It provides:

```java
void requireOnly(String... allowed)
String requireString(String field)
int requireInt(String field)
float requireFloat(String field)
boolean requireBoolean(String field)
JsonObject requireObject(String field)
JsonObject optionalObject(String field)
```

`requireOnly` is:

```java
Set<String> allowedNames = Set.of(allowed);
for (String actual : object.keySet()) {
    if (!allowedNames.contains(actual)) {
        throw new UnknownFieldException(
                source + " has unknown field '" + actual + "'");
    }
}
```

Define nested
`static final class UnknownFieldException extends JsonParseException` with a
single `String message` constructor.

Every getter rejects absent fields, JSON null, and the wrong primitive/object
type with a message containing `source` and `field`. `requireInt` additionally
checks that `value.getAsBigDecimal().stripTrailingZeros().scale() <= 0`.
`requireFloat` rejects non-finite results.

- [ ] **Step 4: Implement manifest and definition parsing**

`GaiaResourceLoader.load()` performs these exact passes:

```java
AssetLoadReport.Builder diagnostics = AssetLoadReport.builder();
List<AssetSource> indexSources =
        assetManager.discoverResourceIndexes();
List<ResourceIndex> indexes =
        parseIndexes(indexSources, diagnostics);
diagnostics.throwIfErrors();

List<TextureAtlasMetadata> atlases =
        parseAtlases(indexes, diagnostics);
List<MaterialDefinition> materials =
        parseMaterials(indexes, diagnostics);
List<BlockDefinition> blocks =
        parseBlocks(indexes, diagnostics);
diagnostics.throwIfErrors();

GaiaAssetCatalog catalog =
        resolve(
                indexes,
                atlases,
                materials,
                blocks,
                diagnostics);
diagnostics.throwIfErrors();
return catalog;
```

Each manifest or definition is parsed independently. Wrap stream access and
Gson tree parsing as:

```java
try {
    JsonElement root =
            JsonParser.parseString(
                    assetManager.readUtf8(location));
    if (!root.isJsonObject()) {
        throw new JsonParseException(
                source + " root must be an object");
    }
    parseDefinition(root.getAsJsonObject(), source);
} catch (AssetLoadException failure) {
    diagnostics.addAll(failure.report());
} catch (StrictJson.UnknownFieldException failure) {
    diagnostics.add(
            new AssetDiagnostic(
                    AssetSeverity.ERROR,
                    "ASSET_JSON_UNKNOWN_FIELD",
                    source,
                    location,
                    null,
                    failure.getMessage(),
                    null));
} catch (JsonParseException | IllegalArgumentException failure) {
    diagnostics.add(
            new AssetDiagnostic(
                    AssetSeverity.ERROR,
                    "ASSET_JSON_INVALID",
                    source,
                    location,
                    null,
                    failure.getMessage(),
                    null));
}
```

Continue through the sorted definition list so one startup attempt reports all
independent fatal files, then call `diagnostics.throwIfErrors()` at the pass
boundary.

Manifest parsing accepts only `namespace`, `blocks`, `materials`, and
`atlases`. It validates the namespace with
`ResourceLocation.of(namespace, "probe")`, requires all three arrays, preserves
their string entries, and rejects duplicate namespaces before definitions are
opened.

Definition paths are converted with:

```java
ResourceLocation.of(index.namespace(), relativePath)
```

so absolute paths, backslashes, empty segments, and traversal fail before
`AssetManager.open`.

Atlas parsing accepts exactly `id`, `texture`, `width`, `height`, and
`regions`. Each region accepts exactly `x`, `y`, `width`, and `height`; create
`TextureRegion` with the parent atlas dimensions. Convert constructor failures
into `ASSET_ATLAS_REGION_BOUNDS` diagnostics containing the definition source
and `regions.<id>`.

Material parsing accepts exactly `id`, `atlas`, `renderType`, `alphaCutoff`,
and `missingRegion`.

Block parsing accepts exactly the fields approved in the design:

```text
id, name, material, textures, hardness, structuralIntegrity,
tolerance, gravity, flammable, blastResistance, item
```

Item parsing accepts exactly:

```text
id, maxStackSize, mouthHoldable, twoHanded
```

If an item object omits `id`, use the block name. Air may omit `item`.

Every block, material, atlas, and atlas-region definition ID must use the
namespace declared by its owning manifest. A mismatched definition produces
`ASSET_NAMESPACE_MISMATCH`. References may target another discovered namespace,
which permits a future module to reuse a declared Gaia material without taking
ownership of the `gaia` namespace.

Face expansion uses an `EnumMap<BlockFace, ResourceLocation>` and applies:

```java
applyAll(faces, textures, "all");
applySides(faces, textures, "sides");
apply(faces, BlockFace.UP, textures, "top");
apply(faces, BlockFace.DOWN, textures, "bottom");
apply(faces, BlockFace.NORTH, textures, "north");
apply(faces, BlockFace.SOUTH, textures, "south");
apply(faces, BlockFace.EAST, textures, "east");
apply(faces, BlockFace.WEST, textures, "west");
apply(faces, BlockFace.UP, textures, "up");
apply(faces, BlockFace.DOWN, textures, "down");
```

Thus explicit `up`/`down` override `top`/`bottom`.

- [ ] **Step 5: Implement reference resolution and guarded missing assets**

Create maps with `putIfAbsent`; translate duplicate failures into the specified
diagnostic codes.

Phase 2 Renderer owns one block atlas. Require every loaded material to name the
same atlas; otherwise emit fatal `ASSET_MULTIPLE_BLOCK_ATLASES`. Require that
atlas to exist before resolving block faces.

Reserve `gaia:missing` only for production Gaia resources. For test namespaces,
the missing material and region are `<namespace>:missing`. Resolve the fallback
namespace from the manifest owning the selected block atlas.

For each block:

1. ID zero produces `BlockRenderInfo.nonRenderable`.
2. Unknown material emits `ASSET_MISSING_MATERIAL` and selects the missing
   material.
3. Every missing face emits `ASSET_MISSING_REGION` with
   `field=textures.<face>` and selects the missing region.
4. Existing faces resolve through the atlas named by the selected material.
5. Build `BlockRegistry.create(blocks, renderInfoById)`.

If the JSON missing material/region cannot be resolved, construct guarded
in-memory values:

```java
ResourceLocation missingId =
        ResourceLocation.of(namespace, "missing");
TextureRegion guardedRegion =
        new TextureRegion(
                missingId, 0, 0, 2, 2, 2, 2);
TextureAtlasMetadata guardedAtlas =
        new TextureAtlasMetadata(
                ResourceLocation.of(namespace, "guarded_missing"),
                ResourceLocation.of(
                        namespace, "textures/guarded_missing.png"),
                2, 2,
                Map.of(missingId, guardedRegion));
MaterialDefinition guardedMaterial =
        new MaterialDefinition(
                missingId,
                guardedAtlas.id(),
                RenderType.OPAQUE,
                0.5f,
                missingId);
```

The selected atlas image is loaded during CPU asset loading:

```java
TextureImage image =
        new TextureImageLoader()
                .load(
                        assetManager,
                        selectedAtlas.texture(),
                        diagnostics::add);
RenderAssets renderAssets = new RenderAssets(image);
```

Return immutable maps and `diagnostics.build()` in `GaiaAssetCatalog`.

- [ ] **Step 6: Run focused tests, then both module suites**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.assets.GaiaResourceLoaderTest
.\gradlew.bat :engine:test :game:test
```

Expected: both commands report `BUILD SUCCESSFUL`; the valid JAR fixture loads
without any file-protocol assumptions.

- [ ] **Step 7: Commit**

```powershell
git add game/src/main/java/com/gaia/assets game/src/test/java/com/gaia/assets
git commit -m "feat(assets): parse and validate indexed Gaia definitions"
```

---

### Task 6: Gaia Block, Material, Atlas, and Missing-Texture Resources

**Files:**
- Create: `game/src/main/resources/META-INF/gaialegacy/resource-indexes.list`
- Create: `game/src/main/resources/assets/gaia/resource-index.json`
- Create: `game/src/main/resources/assets/gaia/blocks/air.json`
- Create: `game/src/main/resources/assets/gaia/blocks/grass.json`
- Create: `game/src/main/resources/assets/gaia/blocks/dirt.json`
- Create: `game/src/main/resources/assets/gaia/blocks/stone.json`
- Create: `game/src/main/resources/assets/gaia/materials/opaque.json`
- Create: `game/src/main/resources/assets/gaia/materials/missing.json`
- Create: `game/src/main/resources/assets/gaia/atlases/blocks.json`
- Move/edit: `game/src/main/resources/textures/atlas.png` to `game/src/main/resources/assets/gaia/textures/atlas.png`
- Delete: old `game/src/main/resources/blocks/grass.json`
- Delete: old `game/src/main/resources/blocks/dirt.json`
- Test: `game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java`

**Interfaces:**
- Consumes: `GaiaResourceLoader`.
- Produces: production `GaiaAssetCatalog` with IDs 0–3 and unchanged existing
  UV regions.

- [ ] **Step 1: Write the failing production-resource test**

```java
@Test
void loadsProductionResourcesWithStableIdsAndUvs() {
    ClassLoader loader =
            GaiaProductionAssetsTest.class.getClassLoader();
    GaiaAssetCatalog catalog =
            new GaiaResourceLoader(new AssetManager(loader)).load();

    assertEquals(
            0,
            catalog.blockRegistry()
                    .require(ResourceLocation.parse("gaia:air"))
                    .id());
    assertEquals(
            1,
            catalog.blockRegistry()
                    .require(ResourceLocation.parse("gaia:grass"))
                    .id());
    assertEquals(
            2,
            catalog.blockRegistry()
                    .require(ResourceLocation.parse("gaia:dirt"))
                    .id());
    assertEquals(
            3,
            catalog.blockRegistry()
                    .require(ResourceLocation.parse("gaia:stone"))
                    .id());

    TextureAtlasMetadata atlas = catalog.blockAtlas();
    assertEquals(0,
            atlas.requireRegion(
                    ResourceLocation.parse("gaia:grass_top")).x());
    assertEquals(16,
            atlas.requireRegion(
                    ResourceLocation.parse("gaia:grass_side")).x());
    assertEquals(32,
            atlas.requireRegion(
                    ResourceLocation.parse("gaia:dirt")).x());
    assertEquals(48,
            atlas.requireRegion(
                    ResourceLocation.parse("gaia:stone")).x());
    assertEquals(80,
            atlas.requireRegion(
                    ResourceLocation.parse("gaia:missing")).x());
    assertTrue(catalog.report().errors().isEmpty());
}
```

- [ ] **Step 2: Run the production-resource test and confirm RED**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.assets.GaiaProductionAssetsTest
```

Expected: FAIL because the indexed production resources do not exist.

- [ ] **Step 3: Add the exact text resources**

The discovery list contains:

```text
assets/gaia/resource-index.json
```

Write `resource-index.json` exactly as:

```json
{
  "namespace": "gaia",
  "blocks": [
    "blocks/air.json",
    "blocks/grass.json",
    "blocks/dirt.json",
    "blocks/stone.json"
  ],
  "materials": [
    "materials/opaque.json",
    "materials/missing.json"
  ],
  "atlases": [
    "atlases/blocks.json"
  ]
}
```

Write `air.json`:

```json
{
  "id": 0,
  "name": "gaia:air",
  "material": "gaia:missing",
  "textures": {},
  "hardness": 0.0,
  "structuralIntegrity": 0.0,
  "tolerance": 0.0,
  "gravity": false,
  "flammable": false,
  "blastResistance": 0.0
}
```

Write `grass.json`:

```json
{
  "id": 1,
  "name": "gaia:grass",
  "material": "gaia:opaque",
  "textures": {
    "top": "gaia:grass_top",
    "bottom": "gaia:dirt",
    "sides": "gaia:grass_side"
  },
  "hardness": 0.6,
  "structuralIntegrity": 50.0,
  "tolerance": 3.0,
  "gravity": false,
  "flammable": false,
  "blastResistance": 1.0,
  "item": {
    "id": "gaia:grass",
    "maxStackSize": 64,
    "mouthHoldable": false,
    "twoHanded": false
  }
}
```

Write `dirt.json`:

```json
{
  "id": 2,
  "name": "gaia:dirt",
  "material": "gaia:opaque",
  "textures": {
    "all": "gaia:dirt"
  },
  "hardness": 0.5,
  "structuralIntegrity": 40.0,
  "tolerance": 2.5,
  "gravity": false,
  "flammable": false,
  "blastResistance": 1.0,
  "item": {
    "id": "gaia:dirt",
    "maxStackSize": 64,
    "mouthHoldable": false,
    "twoHanded": false
  }
}
```

Write `stone.json`:

```json
{
  "id": 3,
  "name": "gaia:stone",
  "material": "gaia:opaque",
  "textures": {
    "all": "gaia:stone"
  },
  "hardness": 1.5,
  "structuralIntegrity": 80.0,
  "tolerance": 6.0,
  "gravity": false,
  "flammable": false,
  "blastResistance": 1.0,
  "item": {
    "id": "gaia:stone",
    "maxStackSize": 64,
    "mouthHoldable": false,
    "twoHanded": false
  }
}
```

Write `opaque.json`:

```json
{
  "id": "gaia:opaque",
  "atlas": "gaia:blocks",
  "renderType": "opaque",
  "alphaCutoff": 0.5,
  "missingRegion": "gaia:missing"
}
```

Write `missing.json`:

```json
{
  "id": "gaia:missing",
  "atlas": "gaia:blocks",
  "renderType": "opaque",
  "alphaCutoff": 0.5,
  "missingRegion": "gaia:missing"
}
```

Write `blocks.json`:

```json
{
  "id": "gaia:blocks",
  "texture": "gaia:textures/atlas.png",
  "width": 128,
  "height": 64,
  "regions": {
    "gaia:grass_top": {
      "x": 0, "y": 0, "width": 16, "height": 16
    },
    "gaia:grass_side": {
      "x": 16, "y": 0, "width": 16, "height": 16
    },
    "gaia:dirt": {
      "x": 32, "y": 0, "width": 16, "height": 16
    },
    "gaia:stone": {
      "x": 48, "y": 0, "width": 16, "height": 16
    },
    "gaia:missing": {
      "x": 80, "y": 0, "width": 16, "height": 16
    }
  }
}
```

- [ ] **Step 4: Add the original missing tile without changing used pixels**

Invoke the `imagegen` skill to edit the existing atlas with this exact intent:

```text
Edit the supplied 128x64 pixel-art texture atlas. Preserve every pixel outside
the rectangle x=80..95, y=0..15 exactly. Inside only that 16x16 rectangle,
create a crisp purple-and-black checker missing-texture tile with no
anti-aliasing, no gradients, and fully opaque pixels. Keep dimensions 128x64
and output PNG.
```

Before the edit, record SHA-256 hashes over ImageIO ARGB integer values for the
four 16x16 rectangles whose top-left coordinates are `(0,0)`, `(16,0)`,
`(32,0)`, and `(48,0)`. Hash each pixel in row-major order as four bytes
`alpha, red, green, blue`. Add these expected hashes to
`GaiaProductionAssetsTest`:

```text
(0,0)  = 1c8979cf8e6b41bc6d3e67ab5b7efff6fbc2816472a120d54ca2bd2bae785a61
(16,0) = 87baa8f45b3b71e6ecf61fa23dce1047758d58ba381dbe7e8b47a858696e217f
(32,0) = 055bbfb1d895874d53eab4db98a32e290d2b27681d728ec58cc5320c112e6de3
(48,0) = 799040087946283e315d980e13372844b1ff9f1629fbba0bcf9ae24ea13be299
```

After the edit, assert all four hashes are unchanged and assert the `(80,0)`
region contains both opaque purple and opaque black pixels. If the image tool
changes any existing used pixel, reject that output and repeat the image edit
with the original atlas as the reference.

- [ ] **Step 5: Run the production resource and module tests**

Run:

```powershell
.\gradlew.bat :game:test --tests com.gaia.assets.GaiaProductionAssetsTest
.\gradlew.bat :game:test
```

Expected: both commands report `BUILD SUCCESSFUL`; production loading reports
no fatal diagnostics and grass/dirt/stone retain their existing atlas regions.

- [ ] **Step 6: Commit**

```powershell
git add game/src/main/resources game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java
git commit -m "feat(assets): add indexed Gaia block and material definitions"
```

---

### Task 7: Data-Driven Chunk Face UV Resolution

**Files:**
- Modify: `engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java`
- Modify: `engine/src/main/java/com/overlord/config/GameConfig.java`
- Test: `engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java`

**Interfaces:**
- Consumes: `BlockRenderResolver`, `BlockRenderInfo`, `BlockFace`,
  `TextureRegion`.
- Produces:

```java
public ChunkMeshBuilder(BlockRenderResolver renderResolver)
public float[] buildChunkMeshData(
        Chunk chunk, int chunkX, int chunkZ, World world)
```

- [ ] **Step 1: Write a failing six-face UV test**

Create one block in an otherwise empty `World`. Supply a resolver whose six
faces use six distinct regions in a `96x16` atlas. Assert the 180 returned
floats represent six faces, and inspect each 30-float face segment to confirm
its U bounds correspond to:

```text
NORTH=0/96..16/96
SOUTH=16/96..32/96
UP=32/96..48/96
DOWN=48/96..64/96
WEST=64/96..80/96
EAST=80/96..96/96
```

Also test that a non-renderable resolver result produces an empty mesh even for
a non-zero stored byte.

- [ ] **Step 2: Run the mesh test and confirm RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.voxel.ChunkMeshBuilderTest
```

Expected: FAIL because `ChunkMeshBuilder` still has a static hard-coded texture
switch and no injected resolver.

- [ ] **Step 3: Replace the hard-coded texture switch**

Convert `ChunkMeshBuilder` to a final instance class:

```java
public final class ChunkMeshBuilder {
    private final BlockRenderResolver renderResolver;

    public ChunkMeshBuilder(BlockRenderResolver renderResolver) {
        this.renderResolver =
                Objects.requireNonNull(
                        renderResolver, "renderResolver");
    }
}
```

For each stored block:

```java
BlockRenderInfo renderInfo =
        renderResolver.resolve(Byte.toUnsignedInt(block));
if (!renderInfo.renderable()) {
    continue;
}
```

Map existing face indices exactly:

```java
private static final BlockFace[] FACES = {
    BlockFace.NORTH,
    BlockFace.SOUTH,
    BlockFace.UP,
    BlockFace.DOWN,
    BlockFace.WEST,
    BlockFace.EAST
};
```

Pass `renderInfo.region(FACES[face])` to `addFace`.

In `getFaceVertices`, replace atlas constants with:

```java
float u = region.uMin();
float uEnd = region.uMax();
float v = region.vMin();
float vEnd = region.vMax();
```

Keep the current `flipV = face != 2` behavior and all vertex winding unchanged.
Delete `getTopTexture`, `getSideTexture`, and `getBottomTexture`.

After no callers remain, remove `TEXTURE_SIZE`, `ATLAS_WIDTH`, and
`ATLAS_HEIGHT` from `GameConfig.Rendering`.

- [ ] **Step 4: Run mesh and full engine tests**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.voxel.ChunkMeshBuilderTest
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add engine/src/main/java/com/overlord/voxel/ChunkMeshBuilder.java engine/src/main/java/com/overlord/config/GameConfig.java engine/src/test/java/com/overlord/voxel/ChunkMeshBuilderTest.java
git commit -m "refactor(rendering): resolve block UVs from atlas metadata"
```

---

### Task 8: Inject Registry IDs into World Generation and CPU Meshing

**Files:**
- Modify: `game/src/main/java/com/gaia/world/GaiaWorldGenerator.java`
- Modify: `game/src/main/java/com/gaia/world/WorldLoader.java`
- Modify: `game/src/test/java/com/gaia/world/WorldLoaderTest.java`
- Create: `game/src/test/java/com/gaia/world/GaiaWorldGeneratorTest.java`

**Interfaces:**
- Consumes: `BlockRegistry`, `ChunkMeshBuilder`.
- Produces:

```java
public GaiaWorldGenerator(BlockRegistry blocks)
public void generateChunk(World world, int chunkX, int chunkZ)

public WorldLoader(
        GaiaWorldGenerator worldGenerator,
        ChunkMeshBuilder meshBuilder,
        byte fallbackGroundId)
public WorldLoadResult load(World world)
```

- [ ] **Step 1: Write failing injection tests**

Load the production test catalog once in test setup. Construct
`GaiaWorldGenerator(catalog.blockRegistry())`, generate chunk `(0, 0)`, and
assert the top non-empty voxel is ID 1, the next three are ID 2, and a deeper
voxel is ID 3.

Define the shared test helper in each test class that uses it:

```java
private static GaiaAssetCatalog productionCatalog() {
    return new GaiaResourceLoader(
            new AssetManager(
                    WorldLoaderTest.class.getClassLoader()))
            .load();
}
```

In `GaiaWorldGeneratorTest`, replace `WorldLoaderTest.class` with
`GaiaWorldGeneratorTest.class`.

Update `WorldLoaderTest` construction:

```java
GaiaAssetCatalog catalog = productionCatalog();
WorldLoader loader =
        new WorldLoader(
                new GaiaWorldGenerator(catalog.blockRegistry()),
                new ChunkMeshBuilder(catalog.blockRegistry()),
                catalog.blockRegistry()
                        .requireStoredId(
                                ResourceLocation.parse("gaia:grass")));
```

Use that same loader for worker and cancellation tests.

- [ ] **Step 2: Run world tests and confirm RED**

Run:

```powershell
.\gradlew.bat :game:test --tests "com.gaia.world.*"
```

Expected: compilation fails because world generation and loading still use
static hard-coded callers.

- [ ] **Step 3: Refactor world generation**

`GaiaWorldGenerator` stores:

```java
private final byte grassId;
private final byte dirtId;
private final byte stoneId;
```

The constructor resolves each once:

```java
grassId = blocks.requireStoredId(
        ResourceLocation.parse("gaia:grass"));
dirtId = blocks.requireStoredId(
        ResourceLocation.parse("gaia:dirt"));
stoneId = blocks.requireStoredId(
        ResourceLocation.parse("gaia:stone"));
```

Make `generateChunk` an instance method and replace only the three former
constant accesses. Preserve noise seed, octaves, scale, persistence, height
logic, and loop boundaries byte-for-byte.

- [ ] **Step 4: Refactor world loading**

Store constructor-injected `worldGenerator`, `meshBuilder`, and
`fallbackGroundId` with null checks. Replace:

```java
GaiaWorldGenerator.generateChunk(...)
ChunkMeshBuilder.buildChunkMeshData(...)
(byte) 1
```

with their injected equivalents. Preserve cancellation points, chunk radius,
spawn calculation, mesh combination, and worker-only CPU behavior.

- [ ] **Step 5: Run world and complete game tests**

Run:

```powershell
.\gradlew.bat :game:test --tests "com.gaia.world.*"
.\gradlew.bat :game:test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add game/src/main/java/com/gaia/world game/src/test/java/com/gaia/world
git commit -m "refactor(world): inject data-driven block definitions"
```

---

### Task 9: Main-Thread Upload of Loaded CPU Texture Data

**Files:**
- Modify: `engine/src/main/java/com/overlord/renderer/Texture.java`
- Modify: `engine/src/main/java/com/overlord/renderer/Renderer.java`
- Modify: `engine/src/main/java/com/overlord/core/Engine.java`
- Modify: `engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java`
- Create: `engine/src/test/java/com/overlord/renderer/RenderAssetsTest.java`

**Interfaces:**
- Consumes: `RenderAssets`, `TextureImage`, `MainThreadGuard`.
- Produces:

```java
public Texture(MainThreadGuard guard, TextureImage image)
public Renderer(MainThreadGuard guard, RenderAssets assets)
public Engine(MainThreadGuard guard, RenderAssets assets)
```

The existing `Engine()` and `Engine(MainThreadGuard)` constructors delegate to
`RenderAssets.missing()` and remain source-compatible for the engine demo and
thread-guard tests.

- [ ] **Step 1: Write failing constructor and thread-ownership tests**

Assert via reflection that `Texture` no longer has a `(MainThreadGuard, String)`
constructor and that `Renderer` has the new `(MainThreadGuard, RenderAssets)`
constructor.

Update the worker-thread guard test:

```java
Renderer renderer =
        new Renderer(
                MainThreadGuard.captureCurrentThread(),
                RenderAssets.missing());
```

The test still calls `resizeFramebuffer` on a worker and expects
`IllegalStateException` before OpenGL.

- [ ] **Step 2: Run focused tests and confirm RED**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.renderer.RenderAssetsTest --tests com.overlord.core.thread.MainThreadGuardTest
```

Expected: compilation or assertion failure because Renderer and Texture still
own a hard-coded `"textures/atlas.png"` path.

- [ ] **Step 3: Refactor Texture and Renderer**

Delete all classloader and STB decode logic from `Texture`. Its constructor:

```java
public Texture(
        MainThreadGuard mainThreadGuard,
        TextureImage image) {
    this.mainThreadGuard =
            Objects.requireNonNull(
                    mainThreadGuard, "mainThreadGuard");
    Objects.requireNonNull(image, "image");
    this.mainThreadGuard.assertMainThread(
            "texture GPU upload");
    this.width = image.width();
    this.height = image.height();
    ByteBuffer pixels = image.rgbaPixels();

    textureId = glGenTextures();
    try {
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MIN_FILTER,
                GL_NEAREST);
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MAG_FILTER,
                GL_NEAREST);
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_S,
                GL_CLAMP_TO_EDGE);
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_T,
                GL_CLAMP_TO_EDGE);
        glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA,
                width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
    } catch (RuntimeException | Error failure) {
        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }
        throw failure;
    }
}
```

Renderer stores `RenderAssets`, requires it in its constructor, and initializes
the atlas with:

```java
textureAtlas =
        new Texture(
                mainThreadGuard,
                renderAssets.blockAtlas());
```

Keep shader source at GLSL 410 and leave all render behavior unchanged.

- [ ] **Step 4: Inject assets into Engine**

Add:

```java
private final RenderAssets renderAssets;

public Engine() {
    this(
            MainThreadGuard.captureCurrentThread(),
            RenderAssets.missing());
}

public Engine(MainThreadGuard mainThreadGuard) {
    this(mainThreadGuard, RenderAssets.missing());
}

public Engine(
        MainThreadGuard mainThreadGuard,
        RenderAssets renderAssets) {
    this.mainThreadGuard =
            Objects.requireNonNull(
                    mainThreadGuard, "mainThreadGuard");
    this.renderAssets =
            Objects.requireNonNull(renderAssets, "renderAssets");
    int maxCores = Runtime.getRuntime().availableProcessors();
    availableCores = Math.min(4, Math.max(1, maxCores));
    taskScheduler = new TaskScheduler(availableCores);
}
```

Change only Renderer construction in `init`:

```java
initializedRenderer =
        new Renderer(mainThreadGuard, renderAssets);
```

Do not register `RenderAssets` in `ServiceLocator`.

- [ ] **Step 5: Run focused and complete engine tests**

Run:

```powershell
.\gradlew.bat :engine:test --tests com.overlord.renderer.RenderAssetsTest --tests com.overlord.core.thread.MainThreadGuardTest
.\gradlew.bat :engine:test
```

Expected: both commands report `BUILD SUCCESSFUL`; no unit test invokes
OpenGL because constructor reflection and pre-OpenGL thread guards are used.

- [ ] **Step 6: Commit**

```powershell
git add engine/src/main/java/com/overlord/renderer engine/src/main/java/com/overlord/core/Engine.java engine/src/test/java/com/overlord/renderer engine/src/test/java/com/overlord/core/thread/MainThreadGuardTest.java
git commit -m "refactor(rendering): upload injected texture image data"
```

---

### Task 10: Bootstrap Composition and Packaged-JAR Verification

**Files:**
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/blocks/BlockRegistry.java`
- Delete: `game/src/main/java/com/gaia/blocks/Block.java`
- Delete: `game/src/main/java/com/gaia/blocks/BlockProperties.java`
- Modify: `game/src/test/java/com/gaia/GameBootstrapTest.java`
- Create: `game/src/test/java/com/gaia/GameBootstrapStructureTest.java`
- Modify: `game/build.gradle`

**Interfaces:**
- Consumes: `GaiaAssetCatalog`, `Engine(RenderAssets)`,
  `GaiaWorldGenerator`, `ChunkMeshBuilder`, `WorldLoader`.
- Produces: one explicit startup composition path and a
  `verifyPackagedResources` Gradle check.

- [ ] **Step 1: Write a failing Bootstrap structure test**

Read `GameBootstrap.java` and assert:

```java
assertTrue(source.contains("new AssetManager("));
assertTrue(source.contains("new GaiaResourceLoader("));
assertTrue(source.contains("catalog.renderAssets()"));
assertTrue(source.contains("new GaiaWorldGenerator("));
assertTrue(source.contains("new ChunkMeshBuilder("));
assertFalse(source.contains("BlockRegistry.init()"));
assertFalse(source.contains("BlockRegistry.loadAllFromResources()"));
assertFalse(source.contains("BlockRegistry.GRASS"));
assertFalse(source.contains("BlockRegistry.DIRT"));
assertFalse(source.contains("BlockRegistry.STONE"));
```

Retain the existing cleanup-suppression tests.

- [ ] **Step 2: Run Bootstrap tests and confirm RED**

Run:

```powershell
.\gradlew.bat :game:test --tests "com.gaia.GameBootstrap*"
```

Expected: structure assertions fail because Bootstrap still calls the old
static registry loader.

- [ ] **Step 3: Compose assets before Engine and world work**

At the start of `GameBootstrap.run`, after capturing the main thread and before
constructing Engine:

```java
ClassLoader classLoader =
        GameBootstrap.class.getClassLoader();
GaiaAssetCatalog catalog =
        new GaiaResourceLoader(
                new AssetManager(classLoader))
                .load();
logAssetReport(catalog.report());

Engine engine =
        new Engine(
                mainThreadGuard,
                catalog.renderAssets());
engine.init();
```

Construct world components once:

```java
BlockRegistry blocks = catalog.blockRegistry();
GaiaWorldGenerator generator =
        new GaiaWorldGenerator(blocks);
ChunkMeshBuilder meshBuilder =
        new ChunkMeshBuilder(blocks);
byte fallbackGroundId =
        blocks.requireStoredId(
                ResourceLocation.parse("gaia:grass"));
WorldLoader worldLoader =
        new WorldLoader(
                generator,
                meshBuilder,
                fallbackGroundId);
```

Submit:

```java
CompletableFuture.supplyAsync(
        () -> worldLoader.load(engine.getWorld()),
        worldExecutor)
```

`logAssetReport` prints one line per diagnostic containing severity, code,
source, optional resource/field, message, and optional fallback. It does not
swallow `AssetLoadException`.

Do not store the catalog in `ServiceLocator`. Do not add it to `GameContext`
because all Phase 2 consumers receive their dependencies during composition.

Remove the migration bridge from `BlockRegistry`, then delete `Block.java` and
`BlockProperties.java`. Confirm with:

```powershell
rg -n "BlockRegistry\.(AIR|GRASS|DIRT|STONE|init|loadAllFromResources)|BlockProperties|new Block\(" game/src
```

Expected: no match.

- [ ] **Step 4: Add packaged resource verification**

Add to `game/build.gradle`:

```groovy
tasks.register('verifyPackagedResources') {
    dependsOn tasks.named('jar')
    doLast {
        def archive = tasks.named('jar').get().archiveFile.get().asFile
        def required = [
            'META-INF/gaialegacy/resource-indexes.list',
            'assets/gaia/resource-index.json',
            'assets/gaia/blocks/air.json',
            'assets/gaia/blocks/grass.json',
            'assets/gaia/blocks/dirt.json',
            'assets/gaia/blocks/stone.json',
            'assets/gaia/materials/opaque.json',
            'assets/gaia/materials/missing.json',
            'assets/gaia/atlases/blocks.json',
            'assets/gaia/textures/atlas.png'
        ]
        new java.util.zip.ZipFile(archive).withCloseable { zip ->
            required.each { path ->
                if (zip.getEntry(path) == null) {
                    throw new GradleException(
                        "Packaged game JAR is missing ${path}")
                }
            }
        }
    }
}

tasks.named('check') {
    dependsOn tasks.named('verifyPackagedResources')
}
```

The dynamic-JAR loader test from Task 5 proves stream loading; this Gradle task
proves the real game artifact contains the required layout.

- [ ] **Step 5: Run Bootstrap, game, and packaged resource checks**

Run:

```powershell
.\gradlew.bat :game:test --tests "com.gaia.GameBootstrap*"
.\gradlew.bat :game:verifyPackagedResources
.\gradlew.bat :game:test :game:build
```

Expected: every command reports `BUILD SUCCESSFUL`, the JAR check lists no
missing resource, and no GLFW window opens.

- [ ] **Step 6: Commit**

```powershell
git add game/src/main/java/com/gaia/GameBootstrap.java game/src/main/java/com/gaia/blocks game/src/test/java/com/gaia/GameBootstrapTest.java game/src/test/java/com/gaia/GameBootstrapStructureTest.java game/build.gradle
git commit -m "refactor(core): compose indexed assets at bootstrap"
```

---

### Task 11: Attribution, References, Handoff, and Final Verification

**Files:**
- Create: `THIRD_PARTY_NOTICES.md`
- Create: `docs/references.md`
- Create: `docs/agent-handoffs/phase-02-handoff.md`
- Inspect: every modified source/resource/build file

**Interfaces:**
- Consumes: completed Phase 2 implementation and fresh verification output.
- Produces: provenance documentation, phase handoff, clean diff, and PR-ready
  branch without merging.

- [ ] **Step 1: Write attribution and reference documents**

`THIRD_PARTY_NOTICES.md` records dependency name, repository-pinned version,
usage, license, and official source:

```text
LWJGL 3.3.3 — BSD 3-Clause — https://github.com/LWJGL/lwjgl3
GLFW via LWJGL — zlib/libpng — https://www.glfw.org/license
stb via LWJGL — public domain or MIT — upstream stb component licenses
JOML 1.10.5 — MIT — https://central.sonatype.com/artifact/org.joml/joml
Gson 2.10.1 — Apache-2.0 — https://github.com/google/gson
JUnit 6.1.1 — EPL-2.0 — https://github.com/junit-team/junit-framework
Gradle Wrapper — Apache-2.0 — https://github.com/gradle/gradle
```

State explicitly that Phase 2 adds no copied third-party code or art and that
the purple-black missing tile is original GaiaLegacy project work.

`docs/references.md` states:

- Terasology was consulted only for public modular/data-driven architectural
  ideas; no code or assets were copied.
- Create code, models, textures, and other art were not copied.
- The actual linked libraries are those enumerated in
  `THIRD_PARTY_NOTICES.md`.

- [ ] **Step 2: Run repository hygiene checks**

Run:

```powershell
git status --short
git diff --check
git ls-files | Select-String -Pattern '(^|/)(bin/|.*\.class)'
Select-String -Path gradle.properties -Pattern 'org\.gradle\.java\.home|/Library/Java|[A-Za-z]:\\'
```

Expected:

- only intended Phase 2 files appear in status;
- `git diff --check` prints nothing;
- no tracked `bin/` or `.class` path is printed;
- no platform-specific JDK path is printed.

- [ ] **Step 3: Run clean automated verification**

Run:

```powershell
.\gradlew.bat clean test build
```

Expected: `BUILD SUCCESSFUL`; all engine/game tests and
`verifyPackagedResources` pass; no GLFW window opens.

- [ ] **Step 4: Run the interactive Windows smoke test**

Run:

```powershell
.\gradlew.bat :game
```

Verify:

- the existing world appears;
- grass top/side, dirt, and stone match the pre-Phase-2 appearance;
- movement, mouse capture toggle, resize, Retina/logical framebuffer handling,
  and Escape exit remain functional;
- logs contain indexed resource load information and no fatal diagnostics.

Exit with Escape. Record the result without treating a timed process
termination as a successful smoke test.

- [ ] **Step 5: Record macOS verification status accurately**

The handoff includes the required commands:

```bash
./gradlew clean test build
./gradlew :game
```

If a macOS host or CI run is available, record its exact command, date, and
result. From a Windows-only execution environment, write `not run locally;
requires macOS developer/CI verification` rather than claiming success.

- [ ] **Step 6: Write the Phase 2 handoff**

`docs/agent-handoffs/phase-02-handoff.md` contains these exact sections:

```text
Completed work
Incomplete work
Core architecture decisions
Modified files
Test commands and results
Known risks
Interfaces the next phase must not break
```

Stable interfaces include:

```text
ResourceLocation syntax and equality
META-INF/gaialegacy/resource-indexes.list discovery
fixed unsigned block IDs 0..255
air=0, grass=1, dirt=2, stone=3
immutable BlockRegistry and GaiaAssetCatalog
BlockRenderResolver engine/game boundary
six-face TextureRegion atlas lookup
visible missing asset fallback and diagnostics
main-thread-only OpenGL/GPU ownership
Java 17 and macOS OpenGL 4.1 / GLSL 410
Phase 1 lifecycle/input/fixed-step contracts
```

- [ ] **Step 7: Run final review and commit documentation**

Run:

```powershell
git diff --check
git diff --stat origin/main...HEAD
git status --short
```

Inspect the full diff for concrete grass/dirt/stone constants in engine:

```powershell
rg -n "grass|dirt|stone|case 1|case 2|case 3|textures/atlas\.png" engine/src/main
```

Expected: no concrete Gaia block/atlas occurrence in engine production code.

Commit:

```powershell
git add THIRD_PARTY_NOTICES.md docs/references.md docs/agent-handoffs/phase-02-handoff.md
git commit -m "docs: record Phase 2 asset provenance and handoff"
```

- [ ] **Step 8: Prepare the final report without merging**

Output:

```text
git diff --stat origin/main...HEAD
all automated and manual test results
macOS verification status
suggested commit: feat(assets): add data-driven block and material resource system
suggested PR title
suggested PR description
```

Do not merge a PR.
