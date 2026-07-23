# Third-Party Notices

This document records the third-party software declared or resolved by the
GaiaLegacy Gradle build. Direct versions are pinned by the repository build
files; transitive versions were confirmed from freshly resolved compile and
runtime classpaths on 2026-07-23.

## Runtime dependencies

### LWJGL 3.3.3

- **Artifacts:** `org.lwjgl:lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, and
  `lwjgl-stb`, including the selected platform native classifiers
- **Use:** Java/native access to GLFW, OpenGL, and stb image decoding
- **License:** BSD 3-Clause for LWJGL
- **Official source and license:**
  [LWJGL repository](https://github.com/LWJGL/lwjgl3) and
  [LWJGL license](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md)

The LWJGL native modules used by GaiaLegacy include these upstream components:

- **GLFW (via `lwjgl-glfw`):** window creation, OpenGL context ownership, and
  input; zlib/libpng license;
  [official GLFW license](https://www.glfw.org/license)
- **stb image (via `lwjgl-stb`):** CPU-side image decoding; dual-licensed as
  public domain or MIT;
  [upstream stb license](https://github.com/nothings/stb/blob/master/LICENSE)

### JOML 1.10.5

- **Artifact:** `org.joml:joml:1.10.5`
- **Use:** vector, matrix, and rendering math
- **License:** MIT
- **Official package metadata and source:**
  [Maven Central](https://central.sonatype.com/artifact/org.joml/joml/1.10.5)
  and [JOML repository](https://github.com/JOML-CI/JOML)

### Gson 2.10.1

- **Artifact:** `com.google.code.gson:gson:2.10.1`
- **Use:** strict parsing of Gaia block, material, atlas, and resource-index
  JSON
- **License:** Apache License 2.0
- **Official source and license:**
  [Gson 2.10.1 source](https://github.com/google/gson/tree/gson-parent-2.10.1)
  and
  [Gson license](https://github.com/google/gson/blob/gson-parent-2.10.1/LICENSE)

## Build and test dependencies

### JUnit 6.1.1

- **Artifacts:** JUnit Jupiter and JUnit Platform modules resolved through
  `org.junit.jupiter:junit-jupiter:6.1.1` and
  `org.junit.platform:junit-platform-launcher:6.1.1`
- **Use:** automated engine and game tests only
- **License:** Eclipse Public License 2.0
- **Official source and license:**
  [JUnit framework repository](https://github.com/junit-team/junit-framework)
  and
  [JUnit license](https://github.com/junit-team/junit-framework/blob/main/LICENSE.md)

### OpenTest4J 1.3.0

- **Artifact:** `org.opentest4j:opentest4j:1.3.0`
- **Use:** transitive JUnit test-runtime assertion/exception API
- **License:** Apache License 2.0
- **Official package metadata and source:**
  [Maven Central](https://central.sonatype.com/artifact/org.opentest4j/opentest4j/1.3.0)
  and
  [OpenTest4J repository](https://github.com/ota4j-team/opentest4j)

### API Guardian 1.1.2

- **Artifact:** `org.apiguardian:apiguardian-api:1.1.2`
- **Use:** transitive JUnit compile-time API-status annotations
- **License:** Apache License 2.0
- **Official package metadata and source:**
  [Maven Central](https://central.sonatype.com/artifact/org.apiguardian/apiguardian-api/1.1.2)
  and
  [API Guardian repository](https://github.com/apiguardian-team/apiguardian)

### JSpecify 1.0.0

- **Artifact:** `org.jspecify:jspecify:1.0.0`
- **Use:** transitive JUnit compile-time nullness annotations
- **License:** Apache License 2.0
- **Official package metadata and source:**
  [Maven Central](https://central.sonatype.com/artifact/org.jspecify/jspecify/1.0.0)
  and
  [JSpecify repository](https://github.com/jspecify/jspecify)

### Gradle Wrapper 8.5

- **Repository files:** `gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`, and
  `gradle/wrapper/gradle-wrapper.properties`
- **Use:** reproducible build-tool acquisition and execution
- **License:** Apache License 2.0
- **Official source, license, and version documentation:**
  [Gradle repository](https://github.com/gradle/gradle),
  [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE), and
  [Gradle 8.5 release notes](https://docs.gradle.org/8.5/release-notes.html)

## Phase 2 provenance statement

Phase 2 introduced no copied third-party source code and no copied third-party
art. Terasology and Create code, models, textures, and other assets were not
copied into GaiaLegacy.

The purple-and-black missing-texture tile added to
`game/src/main/resources/assets/gaia/textures/atlas.png` is original
GaiaLegacy project work. It was produced by a deterministic project-local
pixel edit; it is not derived from third-party art.
