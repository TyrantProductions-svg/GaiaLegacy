# Phase 17.75A — dependency admission provenance

Inspected 2026-09-02. Tooling only; no engine/game/runtime distribution dependency.
Actual artifact bytes are checked by `DependencyAdmissionTest` against the receipt
in `tools/src/modelInspector/resources/model-inspector/dependencies.properties`.
These SHA-256 values identify bytes, NOT Git blob IDs. No third-party model,
texture, Blender addon or scene was imported.

## Exact dependency graph

```text
modelInspectorImplementation
 + de.javagl:jgltf-model:3.0.1
 | + jackson-databind:2.22.1
 | | + jackson-annotations:2.22
 | | + jackson-core:2.22.1
 | | + jackson-bom:2.22.1 (constraints)
 | + jackson-core:2.18.8 -> 2.22.1 (resolved convergence)
 | + de.javagl:jgltf-impl-v1:3.0.1
 | + de.javagl:jgltf-impl-v2:3.0.1
 + com.fasterxml.jackson.core:jackson-core:2.22.1 (direct preflight API)
 + com.fasterxml.jackson.core:jackson-databind:2.22.1 (direct strict binding API)
```

No JglTF viewer/extensions/builder, Gson, JNI, LWJGL, engine or game dependency.
The v1 DTO jar is an upstream transitive dependency, NOT permission to admit
glTF 1.0; preflight rejects non-2.0 before any mapping. Test-only dependencies use
the existing repository JUnit Jupiter 6.1.1/platform launcher convention. They
are not modelInspector runtime dependencies. No global Jackson resolution rule.

## Source identity and maintenance evidence

JglTF project: https://github.com/javagl/JglTF

Release tag `jgltf-parent-3.0.1`; annotated Git tag object
`39f47f21b40ea5a6c16fce7c9c9b78ac8deb7ff4`; peeled source commit
`fa46b160d386cedb3fdab9b02d71711440456375`.
Maven Central release exists; parent POM targets Java 8, while Gaia compiles the
new source sets with `--release 17`. Real admission tests run on Windows JDK21.
This is a recent 3.x API, not a promise of long-term maintenance or security.
Future upgrades require renewed boundary tests/hash review; no dynamic version.

Jackson sources:

| Project | Release tag | Source Git commit |
|---|---|---|
| https://github.com/FasterXML/jackson-core | jackson-core-2.22.1 | 2451603e1931dac6f28ebdbcf308eb24b0f84b26 |
| https://github.com/FasterXML/jackson-databind | jackson-databind-2.22.1 | 446f4ce26371980a5634cf47fedc376b5e411ac6 |
| https://github.com/FasterXML/jackson-annotations | jackson-annotations-2.22 | c638c81e78c0857d8d70edf7636066c2abe5a863 |

## Artifact URLs and SHA-256

JglTF base URL: `https://repo.maven.apache.org/maven2/de/javagl/`.
Append `<artifact>/3.0.1/<artifact>-3.0.1.jar` for each runtime artifact below.
Jackson base URL: `https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/`.
Append `<artifact>/<version>/<artifact>-<version>.jar`.

| Exact runtime artifact | SHA-256 |
|---|---|
| jgltf-model-3.0.1.jar | A531A09E29A737A328BE984B2625890519F46245A729EA254FC8A887A5EEA23D |
| jgltf-impl-v1-3.0.1.jar | E5118DEB681B2DC1320CB03A485C433850F3B8A94931C8450900A6A90C3B8F92 |
| jgltf-impl-v2-3.0.1.jar | 9C6E8E29183CF6AB69A2ECC6094CED11D3F7245C56D9064A5ACF367E8749EE70 |
| jackson-core-2.22.1.jar | 941FF029BCDB93E83D209CE516C1A7FB8BBAC07D0A2FA122F5BF194B2CD7B4F4 |
| jackson-databind-2.22.1.jar | 7DCD7E53BEC1F56C7AD278BD1CA0840BEBCC595D61CE44D6A8439ABB75B965B2 |
| jackson-annotations-2.22.jar | 21DDB598807D3A51A876704EB979D9296E1C6A6F47AB1826FF88C6D6A127A2D0 |

Upstream source archives were hashed in memory, not vendored or executed.
Use the same JglTF artifact URLs with `-sources.jar`:

| Archive | Bytes | SHA-256 |
|---|---:|---|
| jgltf-model-3.0.1-sources.jar | 415669 | F6A6E5A43C9EE5CB12D3463E2D2C1458A32D913D6E5DD9AF713DA13C10620881 |
| jgltf-impl-v1-3.0.1-sources.jar | 41299 | 6FAB0FB683FA7C1E66F937AA5BFA6939F728C69C9F4EB1399E5E83A19311FEBA |
| jgltf-impl-v2-3.0.1-sources.jar | 39336 | E02820142B0C6CB679390132801C3939C764578F78390BA06E8E6E4B3634809E |

## Complete license / notice retention

JglTF: MIT, copyright 2016 Marco Hutter. Exact source:
https://raw.githubusercontent.com/javagl/JglTF/fa46b160d386cedb3fdab9b02d71711440456375/LICENSE

1,079 bytes; SHA-256
`53628709BBC440617513F9D4F0DDE16B286034EBA56AD8B41B58A1B6F26A0D2B`.
The resolved JglTF jars omit this top-level notice, so it is included verbatim as
`tools/src/modelInspector/resources/META-INF/licenses/jgltf-LICENSE.txt`.
One path-specific `.gitattributes` rule preserves the actual upstream bytes.

Jackson is Apache-2.0; each unmodified resolved Jackson jar retains its complete
`META-INF/LICENSE` and `META-INF/NOTICE`. In addition jackson-core retains:

- `META-INF/FastDoubleParser-LICENSE`: MIT, Werner Randelshofer, with credits;
- `META-INF/FastDoubleParser-ThirdParty-LICENSE`: complete Boost, fast_float MIT,
  and bigint BSD-2-Clause notices;
- `META-INF/Schubfach-LICENSE`: MIT, Raffaello Giulietti.

No shading, stripping or repackaging occurs. Tests verify these entries and the
entire jar hashes. Any future inspector distribution must preserve these jars
and the explicit JglTF notice; there is no standalone inspector distribution yet.
This is an engineering admission review, not a formal legal opinion.

## Reader admission limitation discovered by the tests

`GltfAssetReader.setJsonErrorConsumer` does not reliably intercept property
binding errors in 3.0.1. `GltfReader` constructs its ObjectMapper with a logging
consumer; reconfiguration registers an identically identified Jackson module,
leaving the original property wrappers active. A malformed `buffers` string
logged source text and returned a partially mapped asset. Evidence is retained
in the Gate A notes. No upstream code was modified.

Production therefore uses strict Jackson binding to PUBLIC JglTF v2 DTOs and
`GltfAssetV2` construction after preflight. No private JacksonUtils, reflection,
global logger manipulation or tolerant external-reference reader. The trusted
triangle test exercises `GltfModels.create` only on project-owned test bytes.
Full semantic model expansion on untrusted assets remains Gate B work.
