package com.gaia.settings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes complete UTF-8 files through a same-directory temporary replacement. */
public final class AtomicFileWriter {
    private final FileMover mover;
    private final TemporaryFileDeleter temporaryFileDeleter;

    public AtomicFileWriter() {
        this(Files::move, Files::deleteIfExists);
    }

    public AtomicFileWriter(FileMover mover) {
        this(mover, Files::deleteIfExists);
    }

    AtomicFileWriter(
            FileMover mover, TemporaryFileDeleter temporaryFileDeleter) {
        this.mover = Objects.requireNonNull(mover, "mover");
        this.temporaryFileDeleter = Objects.requireNonNull(
                temporaryFileDeleter, "temporaryFileDeleter");
    }

    public void write(Path target, String contents) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");

        Path resolvedTarget = target.toAbsolutePath();
        Path parent = resolvedTarget.getParent();
        Files.createDirectories(parent);

        Path temporary = null;
        IOException primaryFailure = null;
        try {
            temporary = Files.createTempFile(
                    parent, resolvedTarget.getFileName() + ".", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8)) {
                writer.write(contents);
                writer.flush();
            }
            try {
                mover.move(
                        temporary,
                        resolvedTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
            } catch (AtomicMoveNotSupportedException unsupported) {
                mover.move(
                        temporary,
                        resolvedTarget,
                        StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
            }
        } catch (IOException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (temporary != null) {
                try {
                    temporaryFileDeleter.delete(temporary);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface FileMover {
        void move(Path source, Path destination, CopyOption... options)
                throws IOException;
    }

    @FunctionalInterface
    interface TemporaryFileDeleter {
        void delete(Path temporary) throws IOException;
    }
}
