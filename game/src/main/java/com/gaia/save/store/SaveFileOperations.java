package com.gaia.save.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fault-injectable filesystem boundary for one atomic save transaction. */
public interface SaveFileOperations {
    /** Creates one direct directory after revalidating its anchored parent. */
    default void createDirectory(
            Path directory, MutationGuard mutationGuard) throws IOException {
        Path checkedDirectory = Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.createDirectory(checkedDirectory);
    }

    Path createSiblingTemp(
            Path directory, String targetName, MutationGuard mutationGuard)
            throws IOException;

    void forceFile(Path file, MutationGuard mutationGuard) throws IOException;

    /** Writes exact bounded bytes after revalidating the owning path identity. */
    default void writeBounded(
            Path file,
            byte[] bytes,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        Path checkedFile = Objects.requireNonNull(file, "file");
        byte[] checkedBytes = Objects.requireNonNull(bytes, "bytes");
        if (maximumBytes < 0 || checkedBytes.length > maximumBytes) {
            throw new IOException("Bounded save write exceeds its maximum");
        }
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.write(
                checkedFile,
                checkedBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Creates a new bounded regular file without replacing an existing slot. */
    default void createBounded(
            Path file,
            byte[] bytes,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        Path checkedFile = Objects.requireNonNull(file, "file");
        byte[] checkedBytes = Objects.requireNonNull(bytes, "bytes");
        if (maximumBytes < 0L || checkedBytes.length > maximumBytes) {
            throw new IOException("Bounded save create exceeds its maximum");
        }
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.write(
                checkedFile,
                checkedBytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /**
     * Rewrites a pre-existing managed slot.  This deliberately omits
     * {@code CREATE}: a missing directory entry is corruption, not permission to
     * create a new authority-bearing file without a durable directory flush.
     */
    default void writeExistingBounded(
            Path file,
            byte[] bytes,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        Path checkedFile = Objects.requireNonNull(file, "file");
        byte[] checkedBytes = Objects.requireNonNull(bytes, "bytes");
        if (maximumBytes < 0L || checkedBytes.length > maximumBytes) {
            throw new IOException("Bounded managed-slot write exceeds its maximum");
        }
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        Files.write(
                checkedFile,
                checkedBytes,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Reads at most the declared number of bytes after path revalidation. */
    default byte[] readBounded(
            Path file,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        Path checkedFile = Objects.requireNonNull(file, "file");
        if (maximumBytes < 0) {
            throw new IOException("Bounded save read maximum is negative");
        }
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        try (InputStream input = Files.newInputStream(checkedFile)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(maximumBytes, 8192L));
            byte[] buffer = new byte[8192];
            long count = 0L;
            for (int read; (read = input.read(buffer)) != -1; ) {
                try {
                    count = Math.addExact(count, read);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Bounded save read exceeds its maximum", overflow);
                }
                if (count > maximumBytes) {
                    throw new IOException("Bounded save read exceeds its maximum");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

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

    /** Requires the provider to make directory-entry mutations durable. */
    default void forceDirectoryDurably(
            Path directory, MutationGuard mutationGuard) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        throw new IOException("Durable directory forcing is unsupported");
    }

    /** Reads the provider identity, if any, used to prove exact file ownership. */
    default Object readFileKey(Path path, MutationGuard mutationGuard)
            throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        return Files.readAttributes(
                        checkedPath,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS)
                .fileKey();
    }

    /**
     * Captures a complete bounded file identity and proves the file did not change
     * while it was hashed.
     */
    default Object readFileIdentity(
            Path path, long maximumBytes, MutationGuard mutationGuard)
            throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        MutationGuard checkedGuard = Objects.requireNonNull(
                mutationGuard, "mutationGuard");
        if (maximumBytes < 0L) {
            throw new IOException("Bounded identity read maximum is negative");
        }
        checkedGuard.validate();
        BasicFileAttributes before = Files.readAttributes(
                checkedPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > maximumBytes) {
            throw new IOException("Bounded file identity is unavailable");
        }
        String sha256;
        try (InputStream input = Files.newInputStream(checkedPath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0L;
            for (int read; (read = input.read(buffer)) != -1; ) {
                try {
                    count = Math.addExact(count, read);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Bounded identity read exceeds its maximum", overflow);
                }
                if (count > maximumBytes) {
                    throw new IOException("Bounded identity read exceeds its maximum");
                }
                digest.update(buffer, 0, read);
            }
            if (count != before.size()) {
                throw new IOException("File changed while its identity was read");
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
        BasicFileAttributes after = Files.readAttributes(
                checkedPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        checkedGuard.validate();
        if (!after.isRegularFile()
                || before.size() != after.size()
                || before.creationTime().toMillis() != after.creationTime().toMillis()
                || before.lastModifiedTime().toMillis()
                        != after.lastModifiedTime().toMillis()
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("File changed while its identity was read");
        }
        return new ProviderFileIdentity(
                after.fileKey(),
                after.creationTime().toMillis(),
                after.lastModifiedTime().toMillis(),
                after.size(),
                sha256);
    }

    /**
     * Captures the strong baseline required before an in-place managed-slot
     * write.  Providers that cannot expose both a stable identity and link count
     * fail closed.
     */
    default ManagedFileIdentity readManagedFileIdentity(
            Path path, long maximumBytes, MutationGuard mutationGuard)
            throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        MutationGuard checkedGuard = Objects.requireNonNull(
                mutationGuard, "mutationGuard");
        checkedGuard.validate();
        BasicFileAttributes before = Files.readAttributes(
                checkedPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null) {
            throw new IOException("Managed file provider identity is unavailable");
        }
        long links = readUnixLinkCount(checkedPath);
        if (links != 1L) {
            throw new IOException("Managed file must have exactly one link");
        }
        Object bounded = readFileIdentity(checkedPath, maximumBytes, checkedGuard);
        BasicFileAttributes after = Files.readAttributes(
                checkedPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        long linksAfter = readUnixLinkCount(checkedPath);
        checkedGuard.validate();
        if (!after.isRegularFile()
                || linksAfter != 1L
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.creationTime().equals(after.creationTime())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException("Managed file changed while its identity was read");
        }
        return new ManagedFileIdentity(
                after.fileKey(),
                new PortableFileAttributes(
                        after.creationTime(),
                        after.lastModifiedTime(),
                        after.size()),
                linksAfter,
                bounded);
    }

    private static long readUnixLinkCount(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(
                    path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Number number)) {
                throw new IOException("Managed file link count is unavailable");
            }
            return number.longValue();
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Managed file link count is unavailable", unsupported);
        }
    }

    /** Reads a directory's provider identity inside a guarded observation. */
    default Object readDirectoryKey(Path path, MutationGuard mutationGuard)
            throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        MutationGuard checkedGuard = Objects.requireNonNull(
                mutationGuard, "mutationGuard");
        checkedGuard.validate();
        BasicFileAttributes attributes = Files.readAttributes(
                checkedPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        checkedGuard.validate();
        if (!attributes.isDirectory()) {
            throw new IOException("Directory identity is unavailable");
        }
        return attributes.fileKey();
    }

    /** Revalidates anchored save paths inside one filesystem mutation boundary. */
    @FunctionalInterface
    interface MutationGuard {
        void validate() throws IOException;
    }

    /** Stable fallback identity for providers that omit {@code fileKey}. */
    record ProviderFileIdentity(
            Object providerKey,
            long creationTimeMillis,
            long lastModifiedTimeMillis,
            long size,
            String sha256) {}

    /** Provider-complete baseline for an authority-bearing fixed file. */
    record ManagedFileIdentity(
            Object providerIdentity,
            Object completeAttributes,
            long linkCount,
            Object boundedContentIdentity) {
        public ManagedFileIdentity {
            Objects.requireNonNull(providerIdentity, "providerIdentity");
            Objects.requireNonNull(completeAttributes, "completeAttributes");
            Objects.requireNonNull(boundedContentIdentity, "boundedContentIdentity");
            if (linkCount != 1L) {
                throw new IllegalArgumentException(
                        "Managed files require exactly one link");
            }
        }
    }

    record PortableFileAttributes(
            FileTime creationTime, FileTime lastModifiedTime, long size) {}
}
