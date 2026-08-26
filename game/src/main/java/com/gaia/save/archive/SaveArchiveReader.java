package com.gaia.save.archive;

import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.streaming.Phase14MigrationResult;
import com.gaia.save.streaming.Phase14SaveMigrator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Bounded, validation-first JDK ZIP reader for one save archive. */
public final class SaveArchiveReader {
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_ENTRY_NAME_CODE_POINTS = 128;
    private static final int EOCD_MIN_BYTES = 22;
    private static final int MAX_ZIP_COMMENT_BYTES = 65_535;
    private static final Set<SaveSectionId> REQUIRED_V1 = Set.of(
            SaveSectionId.CHUNKS,
            SaveSectionId.PLAYER,
            SaveSectionId.INVENTORY,
            SaveSectionId.WORLD_ITEMS);

    private final SaveSnapshotCodec snapshotCodec;
    private final SaveManifestCodec manifestCodec;
    private final SaveArchiveLimits limits;

    public SaveArchiveReader(SaveSnapshotCodec snapshotCodec) {
        this(snapshotCodec, new SaveManifestCodec(), new SaveArchiveLimits());
    }

    SaveArchiveReader(
            SaveSnapshotCodec snapshotCodec,
            SaveManifestCodec manifestCodec,
            SaveArchiveLimits limits) {
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.manifestCodec = Objects.requireNonNull(manifestCodec, "manifestCodec");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public SaveArchiveReadResult read(Path archive) {
        Objects.requireNonNull(archive, "archive");
        Phase14SaveMigrator.PublicationObservation migrated =
                readPublishedMigration(archive);
        if (migrated.status()
                == Phase14SaveMigrator.PublicationStatus.PUBLISHED_VALID) {
            return SaveArchiveReadResult.valid(
                    migrated.migration().snapshot(), List.of());
        }
        if (migrated.status()
                == Phase14SaveMigrator.PublicationStatus.PUBLISHED_INVALID) {
            return SaveArchiveReadResult.corrupt(migrated.diagnostic());
        }
        return readPhase14(archive);
    }

    /** Reads the exact Phase 14 archive without consulting adjacent v2 authority. */
    public SaveArchiveReadResult readPhase14(Path archive) {
        Objects.requireNonNull(archive, "archive");
        try {
            return readValidated(archive);
        } catch (ArchiveFailure failure) {
            return failure.unsupported
                    ? SaveArchiveReadResult.unsupported(failure.diagnostic())
                    : SaveArchiveReadResult.corrupt(failure.diagnostic());
        } catch (IOException failure) {
            return SaveArchiveReadResult.corrupt(diagnostic(
                    "save-archive.truncated",
                    "The save archive is truncated or unreadable",
                    failure));
        }
    }

    private Phase14SaveMigrator.PublicationObservation
            readPublishedMigration(Path archive) {
        try {
            Path normalized = archive.toAbsolutePath().normalize();
            if (normalized.getFileName() == null
                    || !normalized.getFileName().toString().equals("current.glsave")) {
                return Phase14SaveMigrator.PublicationObservation.absent();
            }
            Path world = normalized.getParent();
            Path root = world == null ? null : world.getParent();
            if (root == null || world.getFileName() == null) {
                return Phase14SaveMigrator.PublicationObservation.absent();
            }
            SaveGameId id = SaveGameId.parse(world.getFileName().toString());
            return Phase14SaveMigrator.observePublished(
                    root, id, this, new JdkSaveFileOperations());
        } catch (RuntimeException invalidOrUnpublished) {
            return Phase14SaveMigrator.PublicationObservation.absent();
        }
    }

    private SaveArchiveReadResult readValidated(Path archive) throws IOException {
        List<CentralEntry> centralEntries = inspectCentralDirectory(archive);
        try (InputStream file = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(file)) {
            Set<String> observedNames = new HashSet<>();
            ZipEntry manifestEntry = zip.getNextEntry();
            if (manifestEntry == null) {
                throw corrupt(
                        "save-archive.truncated", "The save archive has no manifest");
            }
            String manifestName = validateEntry(manifestEntry);
            verifyCentralEntry(manifestEntry, centralEntries, 0, false);
            if (!observedNames.add(manifestName)) {
                throw corrupt(
                        "save-archive.duplicate-entry", "The save archive repeats an entry");
            }
            if (!"manifest.json".equals(manifestName)) {
                throw corrupt(
                        "save-archive.manifest-first", "The manifest must be the first entry");
            }
            byte[] manifestBytes = readBounded(zip, limits.maxBytesFor(manifestName));
            verifyCentralEntry(manifestEntry, centralEntries, 0, true);
            int formatVersion;
            try {
                formatVersion = manifestCodec.formatVersion(manifestBytes);
            } catch (SaveManifestCodec.EntryCountLimitException failure) {
                throw corrupt(
                        "save-archive.entry-count-limit",
                        "The save archive contains too many entries",
                        failure);
            } catch (RuntimeException failure) {
                throw corrupt(
                        "save-archive.malformed-manifest",
                        "The save manifest is malformed",
                        failure);
            }
            if (formatVersion > SaveFormatVersion.CURRENT.value()) {
                throw unsupported(
                        "save-archive.unsupported-version",
                        "The save uses a newer unsupported format");
            }
            SaveGameManifest manifest;
            try {
                manifest = manifestCodec.decode(manifestBytes);
            } catch (SaveManifestCodec.EntryCountLimitException failure) {
                throw corrupt(
                        "save-archive.entry-count-limit",
                        "The save archive contains too many entries",
                        failure);
            } catch (RuntimeException failure) {
                throw corrupt(
                        "save-archive.malformed-manifest",
                        "The save manifest is malformed",
                        failure);
            }

            if (manifest.sections().size() + 1 > SaveArchiveLimits.MAX_ENTRY_COUNT) {
                throw corrupt(
                        "save-archive.entry-count-limit",
                        "The save archive contains too many entries");
            }
            Map<String, SaveSectionDescriptor> descriptors =
                    validateDescriptors(manifest);
            Map<SaveSectionId, byte[]> payloads = new LinkedHashMap<>();
            List<SaveDiagnostic> diagnostics = new ArrayList<>();
            long totalBytes = manifestBytes.length;
            int entryCount = 1;

            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entryCount++;
                if (entryCount > SaveArchiveLimits.MAX_ENTRY_COUNT) {
                    throw corrupt(
                            "save-archive.entry-count-limit",
                            "The save archive contains too many entries");
                }
                String name = validateEntry(entry);
                verifyCentralEntry(entry, centralEntries, entryCount - 1, false);
                if (!observedNames.add(name)) {
                    throw corrupt(
                            "save-archive.duplicate-entry",
                            "The save archive repeats an entry");
                }
                SaveSectionDescriptor descriptor = descriptors.get(name);
                if (descriptor == null) {
                    throw corrupt(
                            "save-archive.unexpected-entry",
                            "The save archive contains an undescribed entry");
                }
                byte[] bytes = readBounded(zip, limits.maxBytesFor(name));
                verifyCentralEntry(entry, centralEntries, entryCount - 1, true);
                try {
                    totalBytes = Math.addExact(totalBytes, bytes.length);
                } catch (ArithmeticException failure) {
                    throw corrupt(
                            "save-archive.expansion-limit",
                            "The save archive exceeds its expansion bound",
                            failure);
                }
                if (totalBytes > SaveArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw corrupt(
                            "save-archive.expansion-limit",
                            "The save archive exceeds its expansion bound");
                }
                if (descriptor.uncompressedSize() != bytes.length) {
                    throw corrupt(
                            "save-archive.size-mismatch",
                            "A save section does not match its declared size");
                }
                if (!descriptor.sha256().equals(sha256(bytes))) {
                    throw corrupt(
                            "save-archive.checksum-mismatch",
                            "A save section checksum does not match");
                }
                payloads.put(descriptor.sectionId(), bytes);
                if (!REQUIRED_V1.contains(descriptor.sectionId())) {
                    diagnostics.add(diagnostic(
                            "save-archive.unknown-optional-section",
                            "An optional save section was safely skipped"));
                }
            }
            if (entryCount != centralEntries.size()) {
                throw corrupt(
                        "save-archive.truncated",
                        "The save archive directory does not match its entries");
            }

            for (SaveSectionId required : REQUIRED_V1) {
                if (!payloads.containsKey(required)) {
                    throw corrupt(
                            "save-archive.missing-required-entry",
                            "The save archive is missing a required section");
                }
            }
            SaveGameSnapshot snapshot;
            try {
                snapshot = snapshotCodec.decode(manifest, payloads);
            } catch (RuntimeException failure) {
                throw corrupt(
                        "save-archive.invalid-section",
                        "A save section contains invalid canonical data",
                        failure);
            }
            return SaveArchiveReadResult.valid(snapshot, diagnostics);
        }
    }

    private Map<String, SaveSectionDescriptor> validateDescriptors(
            SaveGameManifest manifest) {
        Map<String, SaveSectionDescriptor> descriptors = new LinkedHashMap<>();
        for (SaveSectionDescriptor descriptor : manifest.sections()) {
            SaveSectionId id = descriptor.sectionId();
            if (descriptor.required()
                    && (!REQUIRED_V1.contains(id) || descriptor.codecVersion() != 1)) {
                throw corrupt(
                        "save-archive.unknown-required-section",
                        "The save requires an unsupported section codec");
            }
            String entryName = SaveArchiveWriter.entryName(id);
            if (descriptor.uncompressedSize() > limits.maxBytesFor(entryName)) {
                throw corrupt(
                        "save-archive.declared-size-limit",
                        "A declared section size exceeds its structural bound");
            }
            if (descriptors.putIfAbsent(entryName, descriptor) != null) {
                throw corrupt(
                        "save-archive.duplicate-entry",
                        "The save manifest maps multiple sections to one entry");
            }
        }
        return descriptors;
    }

    private static String validateEntry(ZipEntry entry) {
        String name = entry.getName();
        if (entry.isDirectory()
                || name == null
                || name.isEmpty()
                || name.endsWith("/")
                || name.indexOf('\\') >= 0
                || name.startsWith("/")
                || (name.length() >= 2
                        && Character.isLetter(name.charAt(0))
                        && name.charAt(1) == ':')
                || name.codePointCount(0, name.length()) > MAX_ENTRY_NAME_CODE_POINTS) {
            throw corrupt(
                    "save-archive.invalid-entry-name",
                    "The save archive contains an unsafe entry name");
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw corrupt(
                        "save-archive.invalid-entry-name",
                        "The save archive contains an unsafe entry name");
            }
        }
        return name;
    }

    private static List<CentralEntry> inspectCentralDirectory(Path archive)
            throws IOException {
        long fileSize = Files.size(archive);
        if (fileSize < EOCD_MIN_BYTES) {
            throw corrupt(
                    "save-archive.truncated", "The save archive has no end directory");
        }
        if (fileSize > SaveArchiveLimits.MAX_ARCHIVE_FILE_BYTES) {
            throw corrupt(
                    "save-archive.expansion-limit",
                    "The save archive exceeds its bounded file size");
        }
        int tailLength = (int) Math.min(
                fileSize, (long) EOCD_MIN_BYTES + MAX_ZIP_COMMENT_BYTES);
        byte[] tail = new byte[tailLength];
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            channel.position(fileSize - tailLength);
            ByteBuffer buffer = ByteBuffer.wrap(tail);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw corrupt(
                            "save-archive.truncated",
                            "The save archive end directory is truncated");
                }
            }
        }
        int eocd = findEocd(tail, fileSize - tailLength, fileSize);
        if (eocd < 0) {
            throw corrupt(
                    "save-archive.truncated", "The save archive has no valid end directory");
        }
        int disk = unsignedShort(tail, eocd + 4);
        int centralDisk = unsignedShort(tail, eocd + 6);
        int entriesOnDisk = unsignedShort(tail, eocd + 8);
        int entryCount = unsignedShort(tail, eocd + 10);
        long centralSize = unsignedInt(tail, eocd + 12);
        long centralOffset = unsignedInt(tail, eocd + 16);
        long absoluteEocd = fileSize - tailLength + eocd;
        if (disk != 0
                || centralDisk != 0
                || entriesOnDisk != entryCount
                || entryCount == 0xffff
                || centralSize == 0xffff_ffffL
                || centralOffset == 0xffff_ffffL
                || centralOffset + centralSize != absoluteEocd) {
            throw corrupt(
                    "save-archive.truncated",
                    "The save archive end directory is inconsistent");
        }
        if (entryCount > SaveArchiveLimits.MAX_ENTRY_COUNT) {
            throw corrupt(
                    "save-archive.entry-count-limit",
                    "The save archive contains too many entries");
        }

        List<CentralEntry> entries = new ArrayList<>(entryCount);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() != entryCount) {
                throw corrupt(
                        "save-archive.truncated",
                        "The save archive directory count is inconsistent");
            }
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entries.size() >= SaveArchiveLimits.MAX_ENTRY_COUNT) {
                    throw corrupt(
                            "save-archive.entry-count-limit",
                            "The save archive contains too many entries");
                }
                entries.add(new CentralEntry(
                        entry.getName(),
                        entry.getMethod(),
                        entry.getSize(),
                        entry.getCompressedSize(),
                        entry.getCrc()));
            }
        }
        return List.copyOf(entries);
    }

    private static int findEocd(byte[] tail, long tailOffset, long fileSize) {
        for (int offset = tail.length - EOCD_MIN_BYTES; offset >= 0; offset--) {
            if (unsignedInt(tail, offset) != 0x0605_4b50L) {
                continue;
            }
            int commentLength = unsignedShort(tail, offset + 20);
            if (tailOffset + offset + EOCD_MIN_BYTES + commentLength == fileSize) {
                return offset;
            }
        }
        return -1;
    }

    private static void verifyCentralEntry(
            ZipEntry local,
            List<CentralEntry> centralEntries,
            int index,
            boolean afterPayload) {
        if (index < 0 || index >= centralEntries.size()) {
            throw corrupt(
                    "save-archive.truncated",
                    "The save archive has an entry absent from its directory");
        }
        CentralEntry central = centralEntries.get(index);
        if (!Objects.equals(local.getName(), central.name)
                || local.getMethod() != central.method) {
            throw corrupt(
                    "save-archive.truncated",
                    "The save archive directory does not match a local entry");
        }
        if (afterPayload
                && ((local.getSize() >= 0 && local.getSize() != central.size)
                        || (local.getCompressedSize() >= 0
                                && local.getCompressedSize() != central.compressedSize)
                        || (local.getCrc() >= 0 && local.getCrc() != central.crc))) {
            throw corrupt(
                    "save-archive.truncated",
                    "The save archive directory metadata is inconsistent");
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
                unsignedShort(bytes, offset)
                        | unsignedShort(bytes, offset + 2) << 16);
    }

    private static byte[] readBounded(ZipInputStream input, long maximum)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximum, BUFFER_BYTES));
        byte[] buffer = new byte[BUFFER_BYTES];
        long count = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            try {
                count = Math.addExact(count, read);
            } catch (ArithmeticException failure) {
                throw corrupt(
                        "save-archive.expansion-limit",
                        "The save archive exceeds its expansion bound",
                        failure);
            }
            if (count > maximum) {
                throw corrupt(
                        "save-archive.expansion-limit",
                        "The save archive exceeds its expansion bound");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static SaveDiagnostic diagnostic(String code, String message) {
        return SaveDiagnostic.of(code, message);
    }

    private static SaveDiagnostic diagnostic(
            String code, String message, Throwable cause) {
        return SaveDiagnostic.of(code, message, cause);
    }

    private static ArchiveFailure corrupt(String code, String message) {
        return new ArchiveFailure(false, diagnostic(code, message));
    }

    private static ArchiveFailure corrupt(
            String code, String message, Throwable cause) {
        return new ArchiveFailure(false, diagnostic(code, message, cause));
    }

    private static ArchiveFailure unsupported(String code, String message) {
        return new ArchiveFailure(true, diagnostic(code, message));
    }

    private static final class ArchiveFailure extends RuntimeException {
        private final boolean unsupported;
        private final SaveDiagnostic diagnostic;

        private ArchiveFailure(boolean unsupported, SaveDiagnostic diagnostic) {
            super(diagnostic.message(), diagnostic.cause().orElse(null));
            this.unsupported = unsupported;
            this.diagnostic = diagnostic;
        }

        private SaveDiagnostic diagnostic() {
            return diagnostic;
        }
    }

    private record CentralEntry(
            String name, int method, long size, long compressedSize, long crc) {}
}
