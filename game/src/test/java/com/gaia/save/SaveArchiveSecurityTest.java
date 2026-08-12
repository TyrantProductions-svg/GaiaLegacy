package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveManifestCodec;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.EncodedSaveSection;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.AtomicSaveStore;
import com.gaia.save.store.FileSaveCatalog;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.save.store.SaveRepository;
import com.gaia.shell.save.SaveSummary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveArchiveSecurityTest {
    private static final Instant MODIFIED = Instant.parse("2026-08-10T12:30:00Z");

    @TempDir
    Path tempDir;

    @Test
    void missingUnknownOptionalEntryDoesNotInvalidateRequiredCanonicalSnapshot()
            throws Exception {
        SaveGameId id = Gate14CTestSupport.id(71);
        var snapshot = Gate14CTestSupport.snapshot(id, "Optional absent", 71L, 71L);
        EncodedSaveGame encoded = Gate14CTestSupport.codec().encode(snapshot, MODIFIED);
        List<SaveSectionDescriptor> descriptors = new ArrayList<>(
                encoded.manifest().sections());
        descriptors.add(new SaveSectionDescriptor(
                SaveSectionId.DISCOVERY_LORE,
                1,
                false,
                0,
                sha256(new byte[0])));
        SaveGameManifest manifest = copyWithSections(encoded.manifest(), descriptors);
        Path archive = tempDir.resolve("missing-optional.glsave");
        writeEncodedArchiveWithoutOptional(archive, manifest, encoded.sections());

        SaveArchiveReadResult result = Gate14CTestSupport.reader().read(archive);

        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        assertEquals(snapshot, result.snapshot().orElseThrow());
    }

    @Test
    void futureAndCorruptArchivesRemainDistinctClosedCatalogStates()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("classification-root"));
        SaveGameId futureId = Gate14CTestSupport.id(72);
        SaveGameId corruptId = Gate14CTestSupport.id(73);
        Path future = Files.createDirectories(root.resolve(futureId.value()))
                .resolve("current.glsave");
        Path corrupt = Files.createDirectories(root.resolve(corruptId.value()))
                .resolve("current.glsave");
        Gate14CTestSupport.writeArchive(
                future,
                Gate14CTestSupport.snapshot(futureId, "Future", 72L, 72L),
                MODIFIED);
        rewriteFormatVersion(future, 2);
        Files.write(corrupt, new byte[] {0x50, 0x4b, 0x03});

        List<SaveSummary> summaries = Gate14CTestSupport.catalog(root).summaries();
        Map<SaveGameId, SaveSummary> byId = new LinkedHashMap<>();
        summaries.forEach(summary -> byId.put(summary.id(), summary));

        assertEquals(2, summaries.size());
        assertEquals(SaveSummary.Health.UNSUPPORTED_VERSION, byId.get(futureId).health());
        assertEquals(SaveSummary.Health.CORRUPT, byId.get(corruptId).health());
        assertTrue(byId.get(futureId).formatVersion().isPresent());
        assertEquals(new SaveFormatVersion(2), byId.get(futureId).formatVersion().orElseThrow());
        assertTrue(byId.get(corruptId).formatVersion().isEmpty());
        Gate14CTestSupport.assertClosedDiagnostics(byId.get(futureId).diagnostics(), root);
        Gate14CTestSupport.assertClosedDiagnostics(byId.get(corruptId).diagnostics(), root);
    }

    @Test
    void traversalEntryAndSecretArchivePathNeverReachPublishedDiagnosticText()
            throws Exception {
        SaveGameId id = Gate14CTestSupport.id(74);
        EncodedSaveGame encoded = Gate14CTestSupport.codec().encode(
                Gate14CTestSupport.snapshot(id, "Traversal", 74L, 74L), MODIFIED);
        Path secretDirectory = Files.createDirectories(
                tempDir.resolve("secret-operator-directory"));
        Path archive = secretDirectory.resolve("private-world-name.glsave");
        List<ZipFixtureEntry> entries = new ArrayList<>();
        entries.add(new ZipFixtureEntry(
                "manifest.json", new SaveManifestCodec().encode(encoded.manifest())));
        entries.add(new ZipFixtureEntry("../../secret-user-path", new byte[] {1}));
        for (EncodedSaveSection section : encoded.sections()) {
            entries.add(new ZipFixtureEntry(entryName(section), section.bytes()));
        }
        Gate14CTestSupport.writeZip(archive, entries);

        SaveArchiveReadResult result = Gate14CTestSupport.reader().read(archive);

        assertEquals(SaveArchiveReadResult.Status.CORRUPT, result.status());
        assertTrue(result.snapshot().isEmpty());
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), secretDirectory);
        assertTrue(result.diagnostics().stream()
                .noneMatch(diagnostic -> diagnostic.message().contains("secret-user-path")));
    }

    @Test
    void directLinkedWorldIsExcludedRejectedAndNeverMutatesExternalArchives()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("linked-root"));
        Path external = Files.createDirectories(tempDir.resolve("linked-external"));
        SaveGameId id = Gate14CTestSupport.id(75);
        Path externalCurrent = external.resolve("current.glsave");
        Gate14CTestSupport.writeArchive(
                externalCurrent,
                Gate14CTestSupport.snapshot(id, "External", 75L, 75L),
                MODIFIED);
        Path sentinel = external.resolve("keep.txt");
        Files.writeString(sentinel, "outside");
        byte[] archiveBytes = Files.readAllBytes(externalCurrent);
        Path linkedWorld = root.resolve(id.value());
        assumeTrue(tryCreateDirectoryLink(linkedWorld, external),
                "symbolic link or contained Windows junction is unavailable");
        assertTrue(Files.exists(linkedWorld, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSameFile(linkedWorld, external));

        SaveRepository repository = Gate14CTestSupport.repository(
                root, new JdkSaveFileOperations());

        assertTrue(new FileSaveCatalog(repository).summaries().isEmpty());
        SaveDeleteResult delete = repository.delete(id);
        assertEquals(SaveDeleteResult.Status.UNSAFE_TARGET, delete.status());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtomicSaveStore(
                        root,
                        id,
                        Gate14CTestSupport.codec(),
                        new com.gaia.save.archive.SaveArchiveWriter(),
                        Gate14CTestSupport.reader(),
                        new JdkSaveFileOperations()));
        assertArrayEquals(archiveBytes, Files.readAllBytes(externalCurrent));
        assertEquals("outside", Files.readString(sentinel));
        Gate14CTestSupport.assertClosedDiagnostics(delete.diagnostics(), root);
    }

    private static void writeEncodedArchiveWithoutOptional(
            Path archive,
            SaveGameManifest manifest,
            List<EncodedSaveSection> sections) throws IOException {
        List<ZipFixtureEntry> entries = new ArrayList<>();
        entries.add(new ZipFixtureEntry(
                "manifest.json", new SaveManifestCodec().encode(manifest)));
        for (EncodedSaveSection section : sections) {
            entries.add(new ZipFixtureEntry(entryName(section), section.bytes()));
        }
        Gate14CTestSupport.writeZip(archive, entries);
    }

    private static String entryName(EncodedSaveSection section) {
        return switch (section.descriptor().sectionId().value()) {
            case "chunks" -> "chunks.bin";
            case "player" -> "player.json";
            case "inventory" -> "inventory.json";
            case "world-items" -> "world-items.json";
            default -> throw new IllegalArgumentException("unexpected required section");
        };
    }

    private static SaveGameManifest copyWithSections(
            SaveGameManifest source, List<SaveSectionDescriptor> sections) {
        return new SaveGameManifest(
                source.formatVersion(),
                source.gameVersion(),
                source.saveGameId(),
                source.displayName(),
                source.createdAt(),
                source.modifiedAt(),
                source.worldSeed(),
                source.generatorVersion(),
                source.generatorConfigFingerprint(),
                source.chunkRadius(),
                source.worldHeight(),
                source.fixedTick(),
                source.summary(),
                sections);
    }

    private static void rewriteFormatVersion(Path archive, int version)
            throws IOException {
        List<ZipFixtureEntry> entries = Gate14CTestSupport.readZipEntries(archive);
        List<ZipFixtureEntry> rewritten = new ArrayList<>();
        for (ZipFixtureEntry entry : entries) {
            if (!entry.name().equals("manifest.json")) {
                rewritten.add(entry);
                continue;
            }
            JsonObject manifest = JsonParser.parseString(
                    new String(entry.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            manifest.addProperty("formatVersion", version);
            rewritten.add(new ZipFixtureEntry(
                    "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8)));
        }
        Gate14CTestSupport.writeZip(archive, rewritten);
    }

    private boolean tryCreateDirectoryLink(Path link, Path target) throws Exception {
        Path controlledRoot = tempDir.toAbsolutePath().normalize();
        if (!link.toAbsolutePath().normalize().startsWith(controlledRoot)
                || !target.toAbsolutePath().normalize().startsWith(controlledRoot)) {
            throw new IllegalArgumentException("link fixture escaped the temporary root");
        }
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return false;
            }
        }

        Process process = new ProcessBuilder(
                        "cmd.exe",
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        link.toAbsolutePath().toString(),
                        target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        }
        return process.exitValue() == 0
                && Files.exists(link, LinkOption.NOFOLLOW_LINKS)
                && Files.isSameFile(link, target);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
