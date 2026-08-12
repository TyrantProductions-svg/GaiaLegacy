package com.gaia.save.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/** JDK implementation of the save transaction filesystem boundary. */
public final class JdkSaveFileOperations implements SaveFileOperations {
    private final DirectoryForcer directoryForcer;

    public JdkSaveFileOperations() {
        this(JdkSaveFileOperations::forceDirectoryWhenSupported);
    }

    JdkSaveFileOperations(DirectoryForcer directoryForcer) {
        this.directoryForcer = Objects.requireNonNull(
                directoryForcer, "directoryForcer");
    }

    @Override
    public Path createSiblingTemp(
            Path directory,
            String targetName,
            MutationGuard mutationGuard) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        return Files.createTempFile(directory, targetName + ".", ".tmp");
    }

    @Override
    public void forceFile(Path file, MutationGuard mutationGuard) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void moveAtomicReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        Path checkedSource = Objects.requireNonNull(source, "source");
        Path checkedDestination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.move(
                checkedSource,
                checkedDestination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void moveReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        Path checkedSource = Objects.requireNonNull(source, "source");
        Path checkedDestination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.move(
                checkedSource,
                checkedDestination,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void copyReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        Path checkedSource = Objects.requireNonNull(source, "source");
        Path checkedDestination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.copy(
                checkedSource,
                checkedDestination,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean deleteIfExists(Path path, MutationGuard mutationGuard)
            throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        return Files.deleteIfExists(checkedPath);
    }

    @Override
    public void forceDirectoryBestEffort(
            Path directory, MutationGuard mutationGuard) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        try {
            directoryForcer.force(directory);
        } catch (UnsupportedOperationException unsupportedByProvider) {
            // Directory force is not a portable JDK capability.
        }
    }

    private static void forceDirectoryWhenSupported(Path directory) throws IOException {
        if (System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")) {
            throw new UnsupportedOperationException(
                    "The Windows JDK provider does not expose directory channels");
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }
}
