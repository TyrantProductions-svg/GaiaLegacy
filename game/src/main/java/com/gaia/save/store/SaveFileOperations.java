package com.gaia.save.store;

import java.io.IOException;
import java.nio.file.Path;

/** Fault-injectable filesystem boundary for one atomic save transaction. */
public interface SaveFileOperations {
    Path createSiblingTemp(
            Path directory, String targetName, MutationGuard mutationGuard)
            throws IOException;

    void forceFile(Path file, MutationGuard mutationGuard) throws IOException;

    void moveAtomicReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException;

    void moveReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException;

    void copyReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException;

    boolean deleteIfExists(Path path, MutationGuard mutationGuard) throws IOException;

    void forceDirectoryBestEffort(
            Path directory, MutationGuard mutationGuard) throws IOException;

    /** Revalidates anchored save paths inside one filesystem mutation boundary. */
    @FunctionalInterface
    interface MutationGuard {
        void validate() throws IOException;
    }
}
