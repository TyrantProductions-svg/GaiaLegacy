package com.gaia.save.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.EncodedSaveSection;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.overlord.voxel.ChunkRepositorySnapshot;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.CRC32;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveArchiveCorruptionTest {
    private static final int MAX_DIAGNOSTIC_CODE_POINTS = 280;
    private static final int MAX_RADIUS_8_CHUNKS_BYTES =
            24 + (17 * 17) * (20 + 16 * 256 * 16);

    @TempDir Path tempDir;

    @Test
    void rejectsDuplicateEntryNamesDuringZipIteration() throws Exception {
        Path archive = tempDir.resolve("duplicate.glsave");
        List<EntrySpec> entries = validEntries();
        entries.add(2, new EntrySpec("player.bin", new byte[] {9}));
        writeArchive(archive, manifestJson(1, requiredDescriptors()), entries);
        replaceAsciiInPlace(archive, "player.bin", "chunks.bin");

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.duplicate-entry");
    }

    @Test
    void rejectsTraversalAndBoundsTheUiDiagnostic() throws Exception {
        Path archive = tempDir.resolve("traversal.glsave");
        String malicious = "../" + "secret".repeat(600) + ".bin";
        writeArchive(
                archive,
                manifestJson(1, requiredDescriptors()),
                List.of(
                        new EntrySpec("manifest.json", new byte[0]),
                        new EntrySpec(malicious, new byte[] {1})));

        SaveArchiveReadResult result = assertClosed(
                archive,
                SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.invalid-entry-name");
        for (SaveDiagnostic diagnostic : result.diagnostics()) {
            assertTrue(diagnostic.message().codePointCount(
                    0, diagnostic.message().length()) <= MAX_DIAGNOSTIC_CODE_POINTS);
            assertFalse(diagnostic.message().contains(malicious));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/chunks.bin",
        "C:/chunks.bin",
        "..\\chunks.bin",
        "foo\\bar",
        "./chunks.bin",
        "a//b",
        "dir/",
        "../chunks.bin"
    })
    void rejectsEveryAbsoluteTraversalBackslashAndDirectoryEntryName(String unsafeName)
            throws Exception {
        Path archive = tempDir.resolve("unsafe-" + Math.abs(unsafeName.hashCode()) + ".glsave");
        writeArchive(
                archive,
                manifestJson(1, requiredDescriptors()),
                List.of(
                        new EntrySpec("manifest.json", new byte[0]),
                        new EntrySpec(unsafeName, new byte[] {1})));

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.invalid-entry-name");
    }

    @Test
    void rejectsDuplicateRootJsonMember() throws Exception {
        String validManifest = manifestJson(1, requiredDescriptors());
        String duplicateRoot = validManifest.replace(
                "\"worldSeed\":12345,",
                "\"worldSeed\":12345,\"worldSeed\":54321,");
        Path rootArchive = tempDir.resolve("duplicate-root-member.glsave");
        writeArchive(rootArchive, duplicateRoot, validEntries());
        assertClosed(rootArchive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.malformed-manifest");
    }

    @Test
    void rejectsDuplicateDescriptorJsonMember() throws Exception {
        String validManifest = manifestJson(1, requiredDescriptors());
        String firstHash = requiredDescriptors().get(0).sha256();
        String duplicateDescriptor = validManifest.replaceFirst(
                "\\\"sha256\\\":\\\"" + firstHash + "\\\"",
                "\"sha256\":\"" + firstHash + "\",\"sha256\":\""
                        + "0".repeat(64) + "\"");
        Path descriptorArchive = tempDir.resolve("duplicate-descriptor-member.glsave");
        writeArchive(descriptorArchive, duplicateDescriptor, validEntries());
        assertClosed(descriptorArchive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.malformed-manifest");
    }

    @Test
    void rejectsMissingRequiredEntry() throws Exception {
        Path archive = tempDir.resolve("missing.glsave");
        List<EntrySpec> entries = validEntries();
        entries.removeIf(entry -> entry.name().equals("inventory.json"));
        writeArchive(archive, manifestJson(1, requiredDescriptors()), entries);

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.missing-required-entry");
    }

    @Test
    void rejectsUnknownRequiredSectionButSkipsUnknownOptionalSection()
            throws Exception {
        byte[] extension = "future".getBytes(StandardCharsets.UTF_8);
        List<DescriptorSpec> required = new ArrayList<>(requiredDescriptors());
        required.add(new DescriptorSpec(
                "future-required", 1, true, extension.length, sha256(extension)));
        List<EntrySpec> requiredEntries = validEntries();
        requiredEntries.add(new EntrySpec("future-required.bin", extension));
        Path requiredArchive = tempDir.resolve("unknown-required.glsave");
        writeArchive(requiredArchive, manifestJson(1, required), requiredEntries);
        assertClosed(requiredArchive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.unknown-required-section");

        List<DescriptorSpec> optional = new ArrayList<>(requiredDescriptors());
        optional.add(new DescriptorSpec(
                "discovery-lore", 1, false, extension.length, sha256(extension)));
        List<EntrySpec> optionalEntries = validEntries();
        optionalEntries.add(new EntrySpec("discovery-lore.json", extension));
        Path optionalArchive = tempDir.resolve("unknown-optional.glsave");
        writeArchive(optionalArchive, manifestJson(1, optional), optionalEntries);

        SaveArchiveReadResult result = reader().read(optionalArchive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        assertTrue(result.snapshot().isPresent());
        assertEquals(
                List.of("save-archive.unknown-optional-section"),
                result.diagnostics().stream().map(SaveDiagnostic::code).toList());
    }

    @Test
    void reportsFutureFormatWithoutPublishingSnapshot() throws Exception {
        Path archive = tempDir.resolve("future.glsave");
        writeArchive(archive, manifestJson(2, requiredDescriptors()), validEntries());

        assertClosed(archive, SaveArchiveReadResult.Status.UNSUPPORTED_VERSION,
                "save-archive.unsupported-version");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 13})
    void acceptsValidV1ManifestWhenFormatVersionIsNotTheFirstRootMember(
            int rootPosition) throws Exception {
        Path archive = tempDir.resolve("v1-version-position-" + rootPosition + ".glsave");
        writeArchive(
                archive,
                manifestJsonWithVersionAt(1, rootPosition),
                validEntries());

        SaveArchiveReadResult result = reader().read(archive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        assertTrue(result.snapshot().isPresent());
    }

    @Test
    void reportsFutureFormatWhenFormatVersionIsNotTheFirstRootMember()
            throws Exception {
        Path archive = tempDir.resolve("future-version-non-first.glsave");
        writeArchive(
                archive,
                manifestJsonWithVersionAt(2, 8),
                validEntries());

        assertClosed(archive, SaveArchiveReadResult.Status.UNSUPPORTED_VERSION,
                "save-archive.unsupported-version");
    }

    @Test
    void reportsFutureFormatWhenFutureOnlyRootMemberPrecedesNonFirstVersion()
            throws Exception {
        JsonObject v2 = JsonParser.parseString(
                manifestJsonWithVersionAt(2, 8)).getAsJsonObject();
        JsonObject reordered = new JsonObject();
        JsonObject futureOnly = new JsonObject();
        futureOnly.addProperty("regionFormat", "v2");
        reordered.add("futureWorldLayout", futureOnly);
        v2.entrySet().forEach(entry -> reordered.add(entry.getKey(), entry.getValue()));
        Path archive = tempDir.resolve("future-only-root-before-version.glsave");
        writeArchive(archive, reordered.toString(), validEntries());

        assertClosed(archive, SaveArchiveReadResult.Status.UNSUPPORTED_VERSION,
                "save-archive.unsupported-version");
    }

    @Test
    void reportsFutureFormatWhenSectionsUseANonV1Shape() throws Exception {
        JsonObject v2 = JsonParser.parseString(
                manifestJsonWithVersionAt(2, 6)).getAsJsonObject();
        JsonObject futureSections = new JsonObject();
        futureSections.addProperty("layout", "region-index");
        futureSections.addProperty("codec", 2);
        v2.add("sections", futureSections);
        Path archive = tempDir.resolve("future-sections-shape.glsave");
        writeArchive(archive, v2.toString(), validEntries());

        assertClosed(archive, SaveArchiveReadResult.Status.UNSUPPORTED_VERSION,
                "save-archive.unsupported-version");
    }

    @Test
    void rejectsWrongDeclaredSizeAndWrongChecksum() throws Exception {
        List<DescriptorSpec> base = requiredDescriptors();
        DescriptorSpec chunks = base.get(0);

        List<DescriptorSpec> wrongSize = new ArrayList<>(base);
        wrongSize.set(0, new DescriptorSpec(
                chunks.id(), chunks.codecVersion(), true, chunks.size() + 1, chunks.sha256()));
        Path sizeArchive = tempDir.resolve("wrong-size.glsave");
        writeArchive(sizeArchive, manifestJson(1, wrongSize), validEntries());
        assertClosed(sizeArchive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.size-mismatch");

        List<DescriptorSpec> wrongHash = new ArrayList<>(base);
        wrongHash.set(0, new DescriptorSpec(
                chunks.id(), chunks.codecVersion(), true, chunks.size(), "0".repeat(64)));
        Path hashArchive = tempDir.resolve("wrong-hash.glsave");
        writeArchive(hashArchive, manifestJson(1, wrongHash), validEntries());
        assertClosed(hashArchive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.checksum-mismatch");
    }

    @Test
    void onePayloadByteMutationWithoutManifestUpdateFailsChecksum() throws Exception {
        List<EntrySpec> entries = validEntries();
        int chunksIndex = indexOfEntry(entries, "chunks.bin");
        byte[] mutated = entries.get(chunksIndex).bytes();
        mutated[mutated.length - 1] ^= 0x01;
        entries.set(chunksIndex, new EntrySpec("chunks.bin", mutated));
        Path archive = tempDir.resolve("payload-mutated.glsave");
        writeArchive(archive, manifestJson(1, requiredDescriptors()), entries);

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.checksum-mismatch");
    }

    @Test
    void rejectsTruncatedArchive() throws Exception {
        Path archive = tempDir.resolve("truncated.glsave");
        writeArchive(archive, manifestJson(1, requiredDescriptors()), validEntries());
        byte[] complete = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(complete, 47));

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.truncated");
    }

    @Test
    void rejectsArchiveWhoseEntireCentralDirectoryAndEocdAreMissing() throws Exception {
        Path archive = tempDir.resolve("no-central-directory.glsave");
        writeArchive(archive, manifestJson(1, requiredDescriptors()), validEntries());
        byte[] complete = Files.readAllBytes(archive);
        int centralDirectory = indexOfSignature(complete, 0x02014b50);
        assertTrue(centralDirectory > 0, "fixture must contain a central directory");
        Files.write(archive, java.util.Arrays.copyOf(complete, centralDirectory));

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.truncated");
    }

    @Test
    void rejectsStoredEntryWhosePayloadNoLongerMatchesItsRecordedCrc() throws Exception {
        Path archive = tempDir.resolve("bad-crc.glsave");
        writeStoredArchive(archive, manifestJson(1, requiredDescriptors()), validEntries());
        byte[] bytes = Files.readAllBytes(archive);
        int payloadOffset = localEntryPayloadOffset(bytes, "chunks.bin");
        bytes[payloadOffset] ^= 0x01;
        Files.write(archive, bytes);

        SaveArchiveReadResult result = reader().read(archive);
        assertEquals(SaveArchiveReadResult.Status.CORRUPT, result.status());
        assertTrue(result.snapshot().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsCentralDirectoryCompressedSizeThatDisagreesWithLocalEntry()
            throws Exception {
        Path baseline = tempDir.resolve("central-size-baseline.glsave");
        new SaveArchiveWriter().write(baseline, SaveArchiveRoundTripTest.encodedFixture());
        SaveArchiveReadResult baselineResult = reader().read(baseline);
        assertEquals(SaveArchiveReadResult.Status.VALID, baselineResult.status());
        assertTrue(baselineResult.snapshot().isPresent());

        Path mutated = tempDir.resolve("central-size-mutated.glsave");
        Files.copy(baseline, mutated);
        byte[] original = Files.readAllBytes(mutated);
        byte[] patched = original.clone();
        int fieldOffset = centralCompressedSizeOffset(patched, "chunks.bin");
        int originalSize = readLittleEndianInt(patched, fieldOffset);
        writeLittleEndianInt(patched, fieldOffset, originalSize + 1);
        int differences = 0;
        for (int index = 0; index < original.length; index++) {
            if (original[index] != patched[index]) {
                differences++;
                assertTrue(index >= fieldOffset && index < fieldOffset + Integer.BYTES);
            }
        }
        assertTrue(differences >= 1 && differences <= Integer.BYTES);
        Files.write(mutated, patched);

        assertClosed(mutated, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.truncated");
    }

    @Test
    void rejectsTrailingBytesAfterArchiveIntegrityChecks() throws Exception {
        EncodedSaveGame encoded = SaveArchiveRoundTripTest.encodedFixture();
        List<EntrySpec> entries = validEntries();
        int playerIndex = indexOfEntry(entries, "player.json");
        byte[] withTrailingByte = append(entries.get(playerIndex).bytes(), (byte) 'x');
        entries.set(playerIndex, new EntrySpec("player.json", withTrailingByte));
        List<DescriptorSpec> descriptors = requiredDescriptors();
        descriptors.set(1, new DescriptorSpec(
                "player", 1, true, withTrailingByte.length, sha256(withTrailingByte)));
        Path archive = tempDir.resolve("trailing.glsave");
        writeArchive(archive, manifestJson(1, descriptors), entries);

        assertEquals(4, encoded.sections().size());
        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.invalid-section");
    }

    @Test
    void rejectsOversizedDeclaredLengthBeforeAllocation() throws Exception {
        List<DescriptorSpec> descriptors = requiredDescriptors();
        DescriptorSpec chunks = descriptors.get(0);
        descriptors.set(0, new DescriptorSpec(
                chunks.id(), 1, true, Long.MAX_VALUE, chunks.sha256()));
        Path archive = tempDir.resolve("declared-limit.glsave");
        writeArchive(archive, manifestJson(1, descriptors), validEntries());

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.declared-size-limit");
    }

    @Test
    void rejectsDeflateExpansionBeyondTheRadiusEightStructuralBound()
            throws Exception {
        byte[] expanded = new byte[MAX_RADIUS_8_CHUNKS_BYTES + 1];
        List<DescriptorSpec> descriptors = requiredDescriptors();
        descriptors.set(0, new DescriptorSpec(
                "chunks", 1, true, MAX_RADIUS_8_CHUNKS_BYTES, sha256(new byte[0])));
        Path archive = tempDir.resolve("expansion-limit.glsave");
        writeArchive(
                archive,
                manifestJson(1, descriptors),
                List.of(
                        new EntrySpec("manifest.json", new byte[0]),
                        new EntrySpec("chunks.bin", expanded)));

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.expansion-limit");
    }

    @Test
    void rejectsExcessiveEntryCountBeforeCatalogingAllNames() throws Exception {
        List<DescriptorSpec> descriptors = new ArrayList<>(requiredDescriptors());
        List<EntrySpec> entries = validEntries();
        byte[] one = new byte[] {1};
        for (int index = 0; index < 128; index++) {
            String id = "optional-" + index;
            descriptors.add(new DescriptorSpec(id, 1, false, 1, sha256(one)));
            entries.add(new EntrySpec(id + ".bin", one));
        }
        Path archive = tempDir.resolve("entry-count.glsave");
        writeArchive(archive, manifestJson(1, descriptors), entries);

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.entry-count-limit");
    }

    @Test
    void rejectsThirtySecondDescriptorBeforeParsingItsMalformedSentinel() throws Exception {
        List<DescriptorSpec> descriptors = new ArrayList<>(requiredDescriptors());
        byte[] one = new byte[] {1};
        for (int index = 0; index < 27; index++) {
            descriptors.add(new DescriptorSpec(
                    "optional-" + index, 1, false, 1, sha256(one)));
        }
        String manifest = manifestJson(1, descriptors);
        int closingArray = manifest.lastIndexOf(']');
        String withMalformedThirtySecond = manifest.substring(0, closingArray)
                + ",{THIS_IS_NOT_A_DESCRIPTOR"
                + manifest.substring(closingArray);
        Path archive = tempDir.resolve("descriptor-sentinel.glsave");
        writeArchive(
                archive,
                withMalformedThirtySecond,
                List.of(new EntrySpec("manifest.json", new byte[0])));

        assertClosed(archive, SaveArchiveReadResult.Status.CORRUPT,
                "save-archive.entry-count-limit");
    }

    @Test
    void fatalErrorsFromDomainDecodeRemainTransparent() throws Exception {
        Path archive = tempDir.resolve("fatal.glsave");
        writeArchive(archive, manifestJson(1, requiredDescriptors()), validEntries());
        AssertionError fatal = new AssertionError("fatal decoder");
        SaveSnapshotCodec normal = SaveArchiveRoundTripTest.snapshotCodec();
        ChunkSectionCodec chunks = new ChunkSectionCodec();
        SaveSectionCodec<ChunkRepositorySnapshot> fatalChunks = new SaveSectionCodec<>() {
            @Override public SaveSectionId sectionId() { return chunks.sectionId(); }
            @Override public int codecVersion() { return chunks.codecVersion(); }
            @Override public boolean required() { return true; }
            @Override public byte[] encode(ChunkRepositorySnapshot value) {
                return chunks.encode(value);
            }
            @Override public ChunkRepositorySnapshot decode(byte[] bytes) {
                throw fatal;
            }
        };
        SaveSnapshotCodec fatalCodec = new SaveSnapshotCodec(
                fatalChunks,
                new com.gaia.save.codec.PlayerSectionCodec(),
                new com.gaia.save.codec.InventorySectionCodec(),
                new com.gaia.save.codec.WorldItemsSectionCodec());

        assertEquals(4, normal.encode(
                SaveArchiveRoundTripTest.snapshotFixture(),
                java.time.Instant.parse("2026-08-10T12:05:00Z")).sections().size());
        assertSame(fatal, assertThrows(
                AssertionError.class,
                () -> new SaveArchiveReader(fatalCodec).read(archive)));
        Files.delete(archive);
        Files.write(archive, new byte[] {7, 8, 9});
        assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(archive));
    }

    private SaveArchiveReadResult assertClosed(
            Path archive, SaveArchiveReadResult.Status status, String diagnosticCode) {
        SaveArchiveReadResult result = reader().read(archive);
        assertEquals(status, result.status());
        assertTrue(result.snapshot().isEmpty());
        assertEquals(
                diagnosticCode,
                result.diagnostics().get(0).code());
        return result;
    }

    private SaveArchiveReader reader() {
        return new SaveArchiveReader(SaveArchiveRoundTripTest.snapshotCodec());
    }

    private static List<EntrySpec> validEntries() {
        EncodedSaveGame encoded = SaveArchiveRoundTripTest.encodedFixture();
        List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec("manifest.json", new byte[0]));
        for (EncodedSaveSection section : encoded.sections()) {
            entries.add(new EntrySpec(entryName(section.descriptor().sectionId()), section.bytes()));
        }
        return entries;
    }

    private static List<DescriptorSpec> requiredDescriptors() {
        return new ArrayList<>(SaveArchiveRoundTripTest.encodedFixture().manifest().sections()
                .stream()
                .map(DescriptorSpec::from)
                .toList());
    }

    private static String entryName(SaveSectionId id) {
        return switch (id.value()) {
            case "chunks" -> "chunks.bin";
            case "player" -> "player.json";
            case "inventory" -> "inventory.json";
            case "world-items" -> "world-items.json";
            default -> id.value() + ".bin";
        };
    }

    private static String manifestJson(int formatVersion, List<DescriptorSpec> descriptors) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", formatVersion);
        root.addProperty("gameVersion", "0.2.0-alpha.1");
        root.addProperty("saveGameId", "123e4567-e89b-12d3-a456-426614174000");
        root.addProperty("displayName", "Archive World");
        root.addProperty("createdAt", "2026-08-10T12:00:00Z");
        root.addProperty("modifiedAt", "2026-08-10T12:05:00Z");
        root.addProperty("worldSeed", 12345L);
        root.addProperty("generatorVersion", "v1");
        root.addProperty("generatorConfigFingerprint", "b".repeat(64));
        root.addProperty("chunkRadius", 4);
        root.addProperty("worldHeight", 16);
        root.addProperty("fixedTick", 42L);
        root.addProperty("summary", "Archive fixture");
        JsonArray sections = new JsonArray();
        for (DescriptorSpec descriptor : descriptors) {
            JsonObject section = new JsonObject();
            section.addProperty("sectionId", descriptor.id());
            section.addProperty("codecVersion", descriptor.codecVersion());
            section.addProperty("required", descriptor.required());
            section.addProperty("uncompressedSize", descriptor.size());
            section.addProperty("sha256", descriptor.sha256());
            sections.add(section);
        }
        root.add("sections", sections);
        return root.toString();
    }

    private static String manifestJsonWithVersionAt(int formatVersion, int rootPosition) {
        JsonObject original = JsonParser.parseString(
                manifestJson(formatVersion, requiredDescriptors())).getAsJsonObject();
        JsonElement version = original.remove("formatVersion");
        JsonObject reordered = new JsonObject();
        int index = 0;
        boolean inserted = false;
        for (var entry : original.entrySet()) {
            if (index++ == rootPosition) {
                reordered.add("formatVersion", version);
                inserted = true;
            }
            reordered.add(entry.getKey(), entry.getValue());
        }
        if (!inserted) {
            reordered.add("formatVersion", version);
        }
        return reordered.toString();
    }

    private static void writeArchive(
            Path archive, String manifest, List<EntrySpec> requestedEntries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            boolean manifestWritten = false;
            for (EntrySpec requested : requestedEntries) {
                byte[] bytes = requested.name().equals("manifest.json")
                        ? manifest.getBytes(StandardCharsets.UTF_8)
                        : requested.bytes();
                ZipEntry entry = new ZipEntry(requested.name());
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
                manifestWritten |= requested.name().equals("manifest.json");
            }
            if (!manifestWritten) {
                throw new AssertionError("fixture must contain manifest.json");
            }
        }
    }

    private static void writeStoredArchive(
            Path archive, String manifest, List<EntrySpec> requestedEntries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (EntrySpec requested : requestedEntries) {
                byte[] bytes = requested.name().equals("manifest.json")
                        ? manifest.getBytes(StandardCharsets.UTF_8)
                        : requested.bytes();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(requested.name());
                entry.setTime(0L);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static void replaceAsciiInPlace(Path archive, String from, String to)
            throws Exception {
        if (from.length() != to.length()) {
            throw new AssertionError("replacement names must have equal encoded length");
        }
        byte[] bytes = Files.readAllBytes(archive);
        byte[] needle = from.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = to.getBytes(StandardCharsets.US_ASCII);
        int replacements = 0;
        for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                match &= bytes[offset + index] == needle[index];
            }
            if (match) {
                System.arraycopy(replacement, 0, bytes, offset, replacement.length);
                replacements++;
            }
        }
        assertEquals(2, replacements, "local and central ZIP names must both be patched");
        Files.write(archive, bytes);
    }

    private static int indexOfEntry(List<EntrySpec> entries, String name) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).name().equals(name)) return index;
        }
        throw new AssertionError("missing fixture entry " + name);
    }

    private static int indexOfSignature(byte[] bytes, int littleEndianSignature) {
        byte[] signature = new byte[] {
            (byte) littleEndianSignature,
            (byte) (littleEndianSignature >>> 8),
            (byte) (littleEndianSignature >>> 16),
            (byte) (littleEndianSignature >>> 24)
        };
        for (int offset = 0; offset <= bytes.length - signature.length; offset++) {
            if (bytes[offset] == signature[0]
                    && bytes[offset + 1] == signature[1]
                    && bytes[offset + 2] == signature[2]
                    && bytes[offset + 3] == signature[3]) {
                return offset;
            }
        }
        return -1;
    }

    private static int localEntryPayloadOffset(byte[] bytes, String entryName) {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= bytes.length - 30 - name.length; offset++) {
            if (readLittleEndianInt(bytes, offset) != 0x04034b50) continue;
            int nameLength = readLittleEndianShort(bytes, offset + 26);
            int extraLength = readLittleEndianShort(bytes, offset + 28);
            if (nameLength != name.length) continue;
            boolean matches = true;
            for (int index = 0; index < name.length; index++) {
                matches &= bytes[offset + 30 + index] == name[index];
            }
            if (matches) return offset + 30 + nameLength + extraLength;
        }
        throw new AssertionError("missing local ZIP entry " + entryName);
    }

    private static int centralCompressedSizeOffset(byte[] bytes, String entryName) {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= bytes.length - 46 - name.length; offset++) {
            if (readLittleEndianInt(bytes, offset) != 0x02014b50) continue;
            int nameLength = readLittleEndianShort(bytes, offset + 28);
            if (nameLength != name.length) continue;
            boolean matches = true;
            for (int index = 0; index < name.length; index++) {
                matches &= bytes[offset + 46 + index] == name[index];
            }
            if (matches) return offset + 20;
        }
        throw new AssertionError("missing central ZIP entry " + entryName);
    }

    private static int readLittleEndianShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return readLittleEndianShort(bytes, offset)
                | readLittleEndianShort(bytes, offset + 2) << 16;
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] append(byte[] source, byte value) {
        byte[] result = java.util.Arrays.copyOf(source, source.length + 1);
        result[source.length] = value;
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record EntrySpec(String name, byte[] bytes) {
        private EntrySpec {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record DescriptorSpec(
            String id, int codecVersion, boolean required, long size, String sha256) {
        static DescriptorSpec from(SaveSectionDescriptor descriptor) {
            return new DescriptorSpec(
                    descriptor.sectionId().value(),
                    descriptor.codecVersion(),
                    descriptor.required(),
                    descriptor.uncompressedSize(),
                    descriptor.sha256());
        }
    }
}
