package com.gaia.save.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WinBase;

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
    public void createDirectory(
            Path directory, MutationGuard mutationGuard) throws IOException {
        SaveFileOperations.super.createDirectory(directory, mutationGuard);
    }

    @Override
    public void writeBounded(
            Path file,
            byte[] bytes,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        SaveFileOperations.super.writeBounded(
                file, bytes, maximumBytes, mutationGuard);
    }

    @Override
    public void writeExistingBounded(
            Path file,
            byte[] bytes,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        SaveFileOperations.super.writeExistingBounded(
                file, bytes, maximumBytes, mutationGuard);
    }

    @Override
    public byte[] readBounded(
            Path file,
            long maximumBytes,
            MutationGuard mutationGuard) throws IOException {
        return SaveFileOperations.super.readBounded(
                file, maximumBytes, mutationGuard);
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
        } catch (IOException unsupportedOrFailed) {
            if (!isUnsupportedDirectoryForce(unsupportedOrFailed)) {
                throw unsupportedOrFailed;
            }
        } catch (UnsupportedOperationException unsupportedByProvider) {
            // Directory force is not a portable JDK capability.
        }
    }

    @Override
    public void forceDirectoryDurably(
            Path directory, MutationGuard mutationGuard) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(mutationGuard, "mutationGuard").validate();
        directoryForcer.force(directory);
    }

    @Override
    public Object readFileKey(Path path, MutationGuard mutationGuard)
            throws IOException {
        return SaveFileOperations.super.readFileKey(path, mutationGuard);
    }

    @Override
    public ManagedFileIdentity readManagedFileIdentity(
            Path path, long maximumBytes, MutationGuard mutationGuard)
            throws IOException {
        if (!isWindows()) {
            return SaveFileOperations.super.readManagedFileIdentity(
                    path, maximumBytes, mutationGuard);
        }
        Path checked = Objects.requireNonNull(path, "path");
        MutationGuard guard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        guard.validate();
        WindowsFileInformation before = WindowsNative.readInformation(checked);
        if (before.directory() || before.reparsePoint() || before.linkCount() != 1L) {
            throw new IOException("Managed file must be a single-link regular file");
        }
        Object content = SaveFileOperations.super.readFileIdentity(
                checked, maximumBytes, guard);
        WindowsFileInformation after = WindowsNative.readInformation(checked);
        guard.validate();
        if (!before.sameManagedState(after)
                || after.directory()
                || after.reparsePoint()
                || after.linkCount() != 1L) {
            throw new IOException("Managed file changed while its identity was read");
        }
        return new ManagedFileIdentity(
                after.stableIdentity(),
                after.managedAttributes(),
                after.linkCount(),
                content);
    }

    @Override
    public Object readDirectoryKey(Path path, MutationGuard mutationGuard)
            throws IOException {
        if (!isWindows()) {
            Object key = SaveFileOperations.super.readDirectoryKey(
                    path, mutationGuard);
            if (key == null) {
                throw new IOException("Directory provider identity is unavailable");
            }
            return key;
        }
        Path checked = Objects.requireNonNull(path, "path");
        MutationGuard guard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        guard.validate();
        WindowsFileInformation before = WindowsNative.readInformation(checked);
        guard.validate();
        WindowsFileInformation after = WindowsNative.readInformation(checked);
        if (!before.sameDirectoryState(after)
                || !after.directory()
                || after.reparsePoint()) {
            throw new IOException("Directory identity is unavailable");
        }
        return after.directoryIdentity();
    }

    private static void forceDirectoryWhenSupported(Path directory) throws IOException {
        if (isWindows()) {
            WindowsNative.forceDirectory(directory);
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static boolean isUnsupportedDirectoryForce(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnsupportedOperationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record WindowsStableIdentity(long volumeSerial, long fileIndex) {}

    private record WindowsDirectoryIdentity(
            WindowsStableIdentity stableIdentity,
            int attributes,
            long creationTime) {}

    private record WindowsManagedAttributes(
            int attributes,
            long creationTime,
            long lastWriteTime,
            long volumeSerial,
            long fileSize,
            long linkCount,
            long fileIndex) {}

    /** Exact BY_HANDLE_FILE_INFORMATION snapshot. */
    private record WindowsFileInformation(
            int attributes,
            long creationTime,
            long lastAccessTime,
            long lastWriteTime,
            long volumeSerial,
            long fileSize,
            long linkCount,
            long fileIndex) {
        private boolean directory() {
            return (attributes & WindowsNative.FILE_ATTRIBUTE_DIRECTORY) != 0;
        }

        private boolean reparsePoint() {
            return (attributes & WindowsNative.FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        }

        private WindowsStableIdentity stableIdentity() {
            return new WindowsStableIdentity(volumeSerial, fileIndex);
        }

        private WindowsDirectoryIdentity directoryIdentity() {
            return new WindowsDirectoryIdentity(
                    stableIdentity(), attributes, creationTime);
        }

        private WindowsManagedAttributes managedAttributes() {
            return new WindowsManagedAttributes(
                    attributes,
                    creationTime,
                    lastWriteTime,
                    volumeSerial,
                    fileSize,
                    linkCount,
                    fileIndex);
        }

        private boolean sameManagedState(WindowsFileInformation other) {
            return attributes == other.attributes
                    && creationTime == other.creationTime
                    && lastWriteTime == other.lastWriteTime
                    && volumeSerial == other.volumeSerial
                    && fileSize == other.fileSize
                    && linkCount == other.linkCount
                    && fileIndex == other.fileIndex;
        }

        private boolean sameDirectoryState(WindowsFileInformation other) {
            return attributes == other.attributes
                    && creationTime == other.creationTime
                    && volumeSerial == other.volumeSerial
                    && fileIndex == other.fileIndex;
        }
    }

    /** Isolated non-graphics Win32 binding implemented with the existing LWJGL runtime. */
    private static final class WindowsNative {
        private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
        private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
        private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
        private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
        private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
        private static final int GENERIC_WRITE = 0x40000000;
        private static final int FILE_SHARE_READ = 0x1;
        private static final int FILE_SHARE_WRITE = 0x2;
        private static final int FILE_SHARE_DELETE = 0x4;
        private static final int OPEN_EXISTING = 3;
        private static final long INVALID_HANDLE_VALUE = -1L;
        private static final long CREATE_FILE_2 = function("CreateFile2");
        private static final long FLUSH_FILE_BUFFERS = function("FlushFileBuffers");
        private static final long GET_FILE_INFORMATION =
                function("GetFileInformationByHandle");
        private static final long CLOSE_HANDLE = function("CloseHandle");

        private WindowsNative() {}

        private static void forceDirectory(Path directory) throws IOException {
            long handle = open(directory, GENERIC_WRITE);
            IOException failure = null;
            try {
                if (JNI.callPI(handle, FLUSH_FILE_BUFFERS) == 0) {
                    failure = error("Windows directory flush failed");
                }
            } finally {
                try {
                    close(handle);
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static WindowsFileInformation readInformation(Path path)
                throws IOException {
            long handle = open(path, 0);
            IOException failure = null;
            WindowsFileInformation information = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bytes = stack.calloc(4, 52).order(ByteOrder.nativeOrder());
                if (JNI.callPPI(
                                handle,
                                MemoryUtil.memAddress(bytes),
                                GET_FILE_INFORMATION)
                        == 0) {
                    failure = error("Windows managed-file identity failed");
                } else {
                    information = new WindowsFileInformation(
                            bytes.getInt(0),
                            unsignedPair(bytes.getInt(8), bytes.getInt(4)),
                            unsignedPair(bytes.getInt(16), bytes.getInt(12)),
                            unsignedPair(bytes.getInt(24), bytes.getInt(20)),
                            Integer.toUnsignedLong(bytes.getInt(28)),
                            unsignedPair(bytes.getInt(32), bytes.getInt(36)),
                            Integer.toUnsignedLong(bytes.getInt(40)),
                            unsignedPair(bytes.getInt(44), bytes.getInt(48)));
                }
            } finally {
                try {
                    close(handle);
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return information;
        }

        private static long open(Path path, int desiredAccess) throws IOException {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer name = stack.UTF16(nativePath(path), true);
                int parameterBytes = Pointer.POINTER_SIZE == Long.BYTES ? 32 : 24;
                ByteBuffer parameters = stack.calloc(
                                Pointer.POINTER_SIZE, parameterBytes)
                        .order(ByteOrder.nativeOrder());
                parameters.putInt(0, parameterBytes);
                parameters.putInt(4, FILE_ATTRIBUTE_NORMAL);
                parameters.putInt(
                        8,
                        FILE_FLAG_BACKUP_SEMANTICS
                                | FILE_FLAG_OPEN_REPARSE_POINT);
                long handle = JNI.callPPPPP(
                        MemoryUtil.memAddress(name),
                        desiredAccess,
                        (long) (FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE),
                        (long) OPEN_EXISTING,
                        MemoryUtil.memAddress(parameters),
                        CREATE_FILE_2);
                if (handle == INVALID_HANDLE_VALUE) {
                    throw error("Windows path handle could not be opened");
                }
                return handle;
            }
        }

        private static void close(long handle) throws IOException {
            if (JNI.callPI(handle, CLOSE_HANDLE) == 0) {
                throw error("Windows path handle could not be closed");
            }
        }

        private static long unsignedPair(int high, int low) {
            return (Integer.toUnsignedLong(high) << 32)
                    | Integer.toUnsignedLong(low);
        }

        private static String nativePath(Path path) {
            String absolute = path.toAbsolutePath().normalize().toString();
            if (absolute.startsWith("\\\\?\\")) {
                return absolute;
            }
            if (absolute.startsWith("\\\\")) {
                return "\\\\?\\UNC\\" + absolute.substring(2);
            }
            return "\\\\?\\" + absolute;
        }

        private static long function(String name) {
            long address = Kernel32.getLibrary().getFunctionAddress(name);
            if (address == MemoryUtil.NULL) {
                throw new IllegalStateException("Required Windows function is unavailable");
            }
            return address;
        }

        private static IOException error(String message) {
            return new IOException(message + " (error " + WinBase.getLastError() + ")");
        }
    }
}
