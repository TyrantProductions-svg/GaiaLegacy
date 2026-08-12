package com.gaia.save.archive;

import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.EncodedSaveSection;
import com.gaia.save.format.SaveSectionId;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic JDK ZIP/Deflate container writer for one encoded v1 save. */
public final class SaveArchiveWriter {
    private static final LocalDateTime NORMALIZED_ZIP_TIME =
            LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final List<SaveSectionId> REQUIRED_ORDER = List.of(
            SaveSectionId.CHUNKS,
            SaveSectionId.PLAYER,
            SaveSectionId.INVENTORY,
            SaveSectionId.WORLD_ITEMS);

    private final SaveManifestCodec manifestCodec;
    private final SaveArchiveLimits limits;
    private final ArchiveOutputFactory outputFactory;

    public SaveArchiveWriter() {
        this(new SaveManifestCodec(), new SaveArchiveLimits(),
                SaveArchiveWriter::openDefaultOutput);
    }

    SaveArchiveWriter(ArchiveOutputFactory outputFactory) {
        this(new SaveManifestCodec(), new SaveArchiveLimits(), outputFactory);
    }

    SaveArchiveWriter(SaveManifestCodec manifestCodec, SaveArchiveLimits limits) {
        this(manifestCodec, limits, SaveArchiveWriter::openDefaultOutput);
    }

    private SaveArchiveWriter(
            SaveManifestCodec manifestCodec,
            SaveArchiveLimits limits,
            ArchiveOutputFactory outputFactory) {
        this.manifestCodec = Objects.requireNonNull(manifestCodec, "manifestCodec");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
    }

    public void write(Path archive, EncodedSaveGame encoded) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(encoded, "encoded");
        byte[] manifest = manifestCodec.encode(encoded.manifest());
        requireWithinLimit("manifest.json", manifest.length);
        for (int index = 0; index < REQUIRED_ORDER.size(); index++) {
            EncodedSaveSection section = encoded.sections().get(index);
            if (!section.descriptor().sectionId().equals(REQUIRED_ORDER.get(index))) {
                throw new IllegalArgumentException("Encoded sections are not canonical");
            }
            requireWithinLimit(entryName(REQUIRED_ORDER.get(index)), section.bytes().length);
        }

        try (OutputStream file = outputFactory.open(archive);
                ZipOutputStream output = new ZipOutputStream(file)) {
            output.setLevel(Deflater.DEFAULT_COMPRESSION);
            writeEntry(output, "manifest.json", manifest);
            for (EncodedSaveSection section : encoded.sections()) {
                writeEntry(
                        output,
                        entryName(section.descriptor().sectionId()),
                        section.bytes());
            }
        }
    }

    private static OutputStream openDefaultOutput(Path archive) throws IOException {
        return Files.newOutputStream(
                archive,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    static String entryName(SaveSectionId sectionId) {
        return switch (sectionId.value()) {
            case "chunks" -> "chunks.bin";
            case "player" -> "player.json";
            case "inventory" -> "inventory.json";
            case "world-items" -> "world-items.json";
            case "discovery-lore" -> "discovery-lore.json";
            case "detail-blocks" -> "detail-blocks.bin";
            default -> sectionId.value() + ".bin";
        };
    }

    private void requireWithinLimit(String entryName, long size) {
        if (size < 0 || size > limits.maxBytesFor(entryName)) {
            throw new IllegalArgumentException("Archive entry exceeds its structural bound");
        }
    }

    private static void writeEntry(
            ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(NORMALIZED_ZIP_TIME
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli());
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setComment(null);
        entry.setExtra(null);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    @FunctionalInterface
    interface ArchiveOutputFactory {
        OutputStream open(Path archive) throws IOException;
    }
}
