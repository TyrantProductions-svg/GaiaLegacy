package com.gaia.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {
    @Test
    void writesUtf8ContentThroughUniqueSiblingTemporaryFile(@TempDir Path root)
            throws IOException {
        Path target = root.resolve("nested").resolve("settings.json");
        String content = "Gaia Legacy ✓";

        new AtomicFileWriter().write(target, content);

        assertEquals(content, Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(content, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        try (Stream<Path> entries = Files.list(target.getParent())) {
            assertEquals(List.of(target), entries.sorted().toList());
        }
    }

    @Test
    void requestsAtomicReplaceBeforeAnyFilesystemMoveCapabilityIsUsed(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        List<List<CopyOption>> moves = new ArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    moves.add(List.of(options));
                    Files.move(
                            source,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                });

        writer.write(target, "new settings");

        assertEquals(
                List.of(
                        List.of(
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING)),
                moves);
        assertEquals("new settings", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void createsUniqueTemporarySourcesAsSiblingsOfTheTargetAcrossWrites(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("nested").resolve("settings.json");
        List<Path> sources = new ArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    sources.add(source);
                    Files.move(
                            source,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                });

        writer.write(target, "first settings");
        writer.write(target, "second settings");

        assertEquals(2, sources.size());
        assertEquals(target.getParent(), sources.get(0).getParent());
        assertEquals(target.getParent(), sources.get(1).getParent());
        assertTrue(Files.notExists(sources.get(0)));
        assertTrue(Files.notExists(sources.get(1)));
        assertTrue(!sources.get(0).equals(sources.get(1)));
    }

    @Test
    void successfulRealMoveDoesNotInvokeTemporaryCleanupAfterward(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(
                Files::move,
                temporary -> {
                    cleanupCalls.incrementAndGet();
                    throw new IOException("cleanup must not run after a successful move");
                });

        writer.write(target, "replacement settings");

        assertEquals(
                "replacement settings", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(0, cleanupCalls.get());
    }

    @Test
    void retriesNonAtomicReplaceOnlyWhenAtomicMoveIsUnsupported(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        List<List<CopyOption>> moves = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    moves.add(List.of(options));
                    if (attempts.getAndIncrement() == 0) {
                        throw new AtomicMoveNotSupportedException(
                                source.toString(),
                                destination.toString(),
                                "injected unsupported atomic move");
                    }
                    Files.move(source, destination, options);
                });

        writer.write(target, "fallback settings");

        assertEquals(
                List.of(
                        List.of(
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING),
                        List.of(StandardCopyOption.REPLACE_EXISTING)),
                moves);
        assertEquals(
                "fallback settings", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void nonAtomicMoveFailurePreservesTargetAndCleansTemporaryFile(
            @TempDir Path root) throws IOException {
        Path target = root.resolve("settings.json");
        Files.writeString(target, "previous settings", StandardCharsets.UTF_8);
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    throw new IOException("injected move failure");
                });

        assertThrows(IOException.class, () -> writer.write(target, "new settings"));

        assertEquals(
                "previous settings", Files.readString(target, StandardCharsets.UTF_8));
        try (Stream<Path> entries = Files.list(root)) {
            assertEquals(List.of(target), entries.sorted().toList());
        }
    }

    @Test
    void doesNotRetryOrdinaryMoveFailureWithoutAtomicFallback(
            @TempDir Path root) {
        AtomicInteger calls = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    calls.incrementAndGet();
                    throw new IOException("injected move failure");
                });

        assertThrows(
                IOException.class,
                () -> writer.write(root.resolve("settings.json"), "settings"));

        assertEquals(1, calls.get());
        assertTrue(Files.notExists(root.resolve("settings.json")));
    }

    @Test
    void preservesPrimaryMoveFailureWhenTemporaryCleanupAlsoFails(
            @TempDir Path root) {
        AtomicFileWriter writer = new AtomicFileWriter(
                (source, destination, options) -> {
                    throw new IOException("primary move failure");
                },
                temporary -> {
                    throw new IOException("cleanup failure");
                });

        IOException failure = assertThrows(
                IOException.class,
                () -> writer.write(root.resolve("settings.json"), "settings"));

        assertEquals("primary move failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("cleanup failure", failure.getSuppressed()[0].getMessage());
    }
}
